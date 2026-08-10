# my-xhs-order + payment 已知故障与陷阱

## 一、测试陷阱

### 分库分表跨库限制
- **现象**: 跨用户JOIN查询失败
- **根因**: ShardingSphere-JDBC 分片键=user_id，不同user_id路由到不同库
- **应对**: 总是使用 user_id 作为查询条件。非分片键查询(orderNo)走映射表

### 延时消息delayLevel陷阱
- **现象**: 延时关单不生效或太快触发
- **根因**: RocketMQ delayLevel:
  - level=5: 1分钟（测试环境，快速验证）
  - level=16: 30分钟（生产环境）
- **应对**: 测试时检查配置文件 `rocketmq.message.delay-level`

### Mock 90%成功率导致支付失败
- **现象**: `POST /api/order/pay/create` 有时返回400
- **根因**: mock.pay.success-rate=0.9，10%失败 → 自动取消订单
- **验证**: 失败后查订单状态 = 4(CANCELLED)

## 二、代码陷阱

### 事务消息回查
- **现象**: 订单创建后几分钟才显示状态
- **根因**: sendMessageInTransaction 半消息发送成功 → 本地事务完成 → 回查延迟
- **应对**: 正常行为——RocketMQ 事务消息保障最终一致性

### 库存幂等 pseudoOrderId
- **现象**: 同一订单库存操作重复执行无影响
- **根因**: inventory 模块根据 pseudoOrderId 幂等
- **应对**: 设计如此——防消息重复投递

## 三、依赖故障

### INTERNAL_TOKEN 不匹配
- **现象**: pay-success/pay-fail/refund-success 回调返回403
- **根因**: order 和 payment 的 `myxhs.internal.token` 配置不一致
- **验证**: 两边 `grep "myxhs.internal.token" application.yml`
- **修复**: 确保两个服务同 token

### Payment 服务不可用(remote 模式)
- **现象**: 支付报错"PaymentFeignClient 调用失败"
- **根因**: pay.type=remote 但 my-xhs-payment 未启动
- **应对**: 默认 pay.type=mock，测试不需要 payment 服务

## 四、并发陷阱

### 并发取消订单与支付成功竞态
- **现象**: 用户点取消同时支付回调到达
- **根因**: 乐观锁 `UPDATE WHERE status=X`——先到先执行
- **预期**: 取消了→支付回调执行status!=0跳过；支付了→取消执行被乐观锁拒绝

## 五、代码级缺陷（9 P0 + 8 P1，链5深审 2026-08-09 产出）

---

### P0-5 ✅ 延时关单 delayLevel=5 硬编码（已修复）
- **位置**: `OrderService.java:700`
- **现象**: 订单1分钟即被关闭，用户来不及支付
- **根因**: 硬编码 `sendCloseDelayMessage(..., 3000, 5)`，注释"测试用，生产改回 16=30min"
- **修复**: 配置化 `${order.close.delay-level:16}`，@Value注入 `orderCloseDelayLevel`
- **影响**: 直接导致 P0-1 竞态放大（用户支付途中订单已关闭）

### P0-8 ✅ refund 水平越权（已修复）
- **位置**: `PaymentService.java:395-401`
- **现象**: 任意用户传他人 `paymentId` 可发起退款 → 资金损失
- **根因**: `refund()` 只查支付单存在性（`findById`），不校验 `userId` 归属
- **修复**: 加 `payment.getUserId().equals(userId)` 归属校验，不匹配抛 403
- **关联**: `PaymentController.refund` 端点无 `X-Internal-Call` 校验，仅靠 `X-User-Id` header

### P0-3 本地消息表补发机制完全失效（待修复）
- **位置**: `OrderTransactionService.java:100-101`（插入不设 nextRetryTime）+ `LocalMessageRetryJob.java:94-99`（查询 `next_retry_time <= NOW()`）
- **现象**: 事务消息投递失败 → 本地消息永不补发 → 库存预扣消息永久丢失
- **根因**: 新插入的 `LocalMessage.next_retry_time` 为 **NULL**。SQL `NULL <= NOW()` 结果为 NULL → 不匹配 → 消息永远不被扫描。`selectPendingMessages`（带60秒窗口）已定义但未被 `doRetry` 调用
- **影响**: 注释声称"Broker 整体宕机也不丢消息、定时补发"——实际完全失效。库存预扣消息丢失 → 超卖风险
- **修复方向**: 插入时设 `nextRetryTime=NOW()`；`doRetry` 改用 `selectPendingMessages` 查询；加 `ORDER BY next_retry_time ASC` 防饿死（对应 P1-5）

### P0-4 事务消息 COMMIT 后异常误删幂等键 → 重复下单（待修复）
- **位置**: `OrderService.java:240-244`（COMMIT 后无 try-catch 的 selectOne）+ `251-254`（catch(Exception) 中 `delete(idempotentKey)`）
- **现象**: 订单本地事务 COMMIT 成功、已落库后 → 若后面任一步（如 line 240 查询）抛异常 → 进入 `catch(Exception)` → **删除幂等键** → 用户重试 → **创建重复订单**
- **根因**: `catch` 块过于宽泛，涵盖了 COMMIT 后异常但不应删除幂等键的场景
- **修复方向**: COMMIT 后不得删除幂等键；用 `context.getOrderId() != null` 作为"已创建"标记；异常时保留幂等键并提示查询已有订单

### P0-1 支付成功 vs 延时关单并发竞态 → 钱付了订单没了（待修复）
- **位置**: `OrderEventService.java:105-112` + `OrderService.java:581-589` + `PaymentService.java:320-323`
- **现象**: 支付回调与延时关单同时触发 → 两者先查 `status=0` 再写事件 → 乐观锁 `UPDATE WHERE status=0` 只有一个能赢。若**关单先赢**(status 0→4)，支付回调 `appendEvent` 抛 `IllegalStateException` → `onPaymentSuccess` 中断 → `setPaidAt` 不执行 → 支付单已标记 status=1（钱已扣），订单 status=4（已取消）
- **后果**: 用户付款成功但订单已取消——**无自动退款机制**。`PaymentNotifyCompensateJob` 也救不了（见 P0-6）
- **修复方向**: `onPaymentSuccess` 捕获并发冲突异常后 → 调支付服务发起**自动退款**

### P0-10 补偿消费者忽略 action → 取消后库存泄漏（待修复）
- **位置**: `OrderCompensationConsumer.java:114`（所有 action 都调 `closeTimeoutOrder`）+ `OrderService.java:639`（`status != 0 → return`）
- **现象**: 取消订单 → `appendEvent`(status→4) → `releaseInventory` Feign 失败 → 发 `RELEASE_STOCK` 补偿消息 → 消费者收到 → 调 `closeTimeoutOrder` → **订单 status=4 ≠ 0 → return** → **库存永久泄漏**
- **根因**: 补偿消费者不区分 action——所有补偿消息都走同一个 `closeTimeoutOrder`。但 `closeTimeoutOrder` 只处理待支付订单（status=0）。取消/退款路径需要的是 `releaseInventory` / `returnCouponIfUsed`
- **修复方向**: 按 action 分发——`RELEASE_STOCK` 直接调 `releaseInventory`、`RETURN_COUPON` 直接调 `returnCouponIfUsed`、`CLOSE_ORDER` 才走 `closeTimeoutOrder`
- **关联**: 与 P0-3（本地消息补发失效）互为因果——补发生效后此缺陷将被激活

### P0-6 补偿任务无限循环（待修复）
- **位置**: `PaymentNotifyCompensateJob.java:87-93` + `RefundNotifyCompensateJob.java:82-91` + `OrderService.java:989-1003`
- **现象**: 两补偿任务每2-3分钟对**全量历史**支付/退款成功记录重复调用订单服务
- **根因**: ①扫描条件（`paid_at < now-5min` / `success_at < now-5min`）**无"已通知"标记**，每次都命中全部；②判断依据 `getOrderPayAmount` 只查订单是否存在（不判状态），对已支付/已取消订单**同样返回金额>0** → 永远判定"待支付" → 无限循环
- **修复方向**: 订单服务提供真正状态查询接口；补偿任务持久化"已通知"标记

### P0-9 支付超时检查 Lua 通配符失效（待修复）
- **位置**: `PaymentService.java:582-584` + `PaymentConfig.java:31-41`
- **现象**: 支付超时检查形同虚设——所有超时支付单永不处理
- **根因**: `KEYS[1]` 是字面字符串 `"myxhs:payment:status:*"`，Redis `GET` 这个字面 key → nil → 永远返回 0
- **修复方向**: 用 SCAN 枚举所有 key 逐个 Lua；或 Lua 内 `scan`

### P0-2 重复支付漏洞（待修复）
- **位置**: `PaymentService.java:160-167,306` + `OrderController.java:126-136`
- **现象**: `statusKey` 30分钟 TTL 过期后 → 同一订单可再次发起支付 → 产生多条"已支付"流水
- **根因**: 支付成功后 `statusKey` 设 TTL=30min，过期后锁后检查通过
- **修复方向**: 支付成功后不设 TTL（直到订单退款才清除）；或 pay 时 Feign 校验订单状态

### P0-7 对账游标字段错误（待修复）
- **位置**: `PaymentService.java:706-714,733-734`
- **现象**: 对账按 `payment.id` 查询但游标用 `orderId` → lastId 回退 → 死循环/漏数据
- **根因**: `lastId = max(orderId)` 而非 `max(id)`；当前 `reconcile()` 无定时调用（死代码中 P0）
- **修复方向**: `lastId = max(id)`；接入定时调度

---

### P1-1 refund 锁先于事务提交释放
- **位置**: `PaymentService.java:462-467`（finally 释放锁）+ `@Transactional`（line 366）
- **问题**: `finally` 在事务提交前执行锁释放 → 另一线程获取锁后读到未提交数据
- **修复**: 用 `TransactionSynchronizationManager.afterCommit()` 释放

### P1-2 退款成功更新无乐观锁条件
- **位置**: `PaymentService.java:523-526`
- **问题**: `UPDATE SET status=3 WHERE id=? AND deleted=0` 无 `status=1` 条件
- **修复**: 加 `AND status=1`

### P1-3 补偿消费者幂等 Key 设计错误
- **位置**: `OrderCompensationConsumer.java:85-90`
- **问题**: `consumedKey = orderId + reconsumeTimes` → 重试次数变则Key变 → 重复执行
- **修复**: 用 `msg.getMsgId()`

### P1-4 ORDER_CREATED 事件从未落库
- **位置**: `OrderEventService.java:59-63`（targetStatus==currentStatus→return）
- **问题**: 新建订单 status=0，目标状态也是 0 → 幂等跳过 → 事件流无创建记录
- **修复**: 为创建事件单独处理

### P1-5 本地消息补发无排序 → 饿死
- **位置**: `LocalMessageRetryJob.java:94-99`
- **问题**: `LIMIT 50` 无 `ORDER BY` → 失败消息占满前50 → 其他到期消息饿死
- **修复**: 加 `ORDER BY next_retry_time ASC`

### P1-7 PayResultConsumer / RefundResultConsumer 空消费者
- **位置**: `PayResultConsumer.java:36-54` + `RefundResultConsumer.java:31-49`
- **问题**: 只打日志无补偿逻辑 → 订单模块未订阅 → 消息堆积
- **修复**: 移除或实现真实补偿

### P1-13 ShardingSphere worker-id 同机冲突
- **位置**: `ShardingSphereDataSourceConfig.java:108-116`
- **问题**: worker-id 由本机IP计算 → Docker同机多实例IP相同 → 雪花ID冲突
- **修复**: 强制注入 `WORKER_ID` 环境变量

### P1-14 OrderMappingRepairJob 只补最近1小时
- **位置**: `OrderMappingRepairJob.java:57`
- **问题**: `since = now-1h` → 超过1小时的映射丢失永久无法补录
- **修复**: 放宽窗口或按失败标记补录
