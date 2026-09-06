# my-xhs-order 源码深度分析

## 1. 状态机与事件

```text
createOrder -> 0
pay-success  -> 0→1
cancel/close/pay-fail -> 0→4
deliver      -> 1→2
confirmReceive -> 2→3
refund-success -> 1→5
```

所有状态变更经 `OrderEventService.appendEvent`：事件落库 + `OrderMapper.updateStatusWithLock`（WHERE status=current 乐观锁），并发冲突抛异常。事件用 `uk_order_event_seq` 唯一索引 + INSERT IGNORE 幂等；重复事件但状态不一致时用乐观锁收敛，避免事件在但状态回退。

## 2. 下单链路（事务消息）

```text
① Redis 幂等键(SETNX 24h) → ② 用户 10s 分布式锁
③ Feign: product 批量SKU + inventory 逐SKU 预校验
④ 真实地址快照 → ⑤ 计算金额/优惠
⑥ sendMessageInTransaction 半消息
   -> OrderTransactionService.executeLocalTransaction @Transactional
      INSERT t_order + t_order_item + t_local_message（同事务）
⑦ 优惠券 useCoupon → 延时关单消息
⑧ 创建事件 + 快照 + 订单号映射表
```

半消息回查 `checkLocalTransaction` 查本地消息表：有记录=COMMIT，无=ROLLBACK，异常=UNKNOWN 重查。库存预扣由 inventory 服务消费 ORDER_TRANSACTION_TOPIC 完成。

## 3. 取消/关单/退款补偿

- 取消/关单/支付失败：释放库存 + 退券，Feign 失败 → 发 ORDER_COMPENSATION_TOPIC，MQ 也失败 → Redis set 兜底，Job 每分钟重放
- 释放库存幂等键从 orderNo 派生 pseudoOrderId（SHA-256 前 8 字节），与 inventory 预扣消费者同算法
- 退款：releaseInventory（confirm 后无预扣记录=no-op）+ restoreStockOnRefund 逐明细回补

## 4. 中间件与分片

- Redis：幂等键、用户锁、序号、补偿 pending set
- MySQL 分片：t_order/order_item/local_message/snapshot/event × 4库×4表
- MySQL 公共库：t_order_no_mapping（非分片键反查）
- MySQL 独立库：t_payment
- MQ：ORDER_TRANSACTION_TOPIC、ORDER_CLOSE_TOPIC（30min 延时）、ORDER_COMPENSATION_TOPIC

## 5. 风险分类

### 代码 Bug
- C1【测试编译失败】OrderControllerTest 未同步 AccessTokenGuard 构造器，3 参→需 4 参；且 internalToken 字段已删
- C2【断言错误】getOrderPayAmount_notFound 断言抛异常，实际映射缺失返回 null
- C3【死代码】OrderMapper.markPaid/markCompleted/markRefunded、LocalMessageMapper.selectPendingMessages/markFailed/markDead 无调用
- C4【注释失真】"异步写入映射表"实为同步调用

### 业务逻辑
- B1【券折扣静默降级】calculateCouponDiscount 失败返回 0，但 couponId 仍写入订单并核销 → 券被用但订单全价
- B2【地址快照手拼 JSON】只转义斜杠和引号，姓名含换行/控制符产出非法 JSON
- B3【Mock 支付非原子】先 onPaymentSuccess 再插 t_payment，跨数据源无事务

### 分布式
- D1【券失败取消 vs 库存预扣竞态】cancelOrder 紧跟事务消息 COMMIT，inventory 预扣可能未完成 → releaseStock no-op → 预扣后无人释放（依赖对账兜底）
- D2【10s 锁与耗时】多 Feign 串行下锁可能过期，同用户并发两单
- D3【支付重复回调】依赖 payment 侧去重

### 微服务
- M1【补偿 DLQ 无重放】ORDER_COMPENSATION_TOPIC 进 DLQ 后无消费者，Redis set 只覆盖发送失败

### 性能
- P1【映射修复全表扫描】OrderMappingRepairJob 每 5 分钟扫全量订单，线性增长
- P2【关单游标与索引】selectTimeoutOrders status+created_at 过滤按 id 排序，与 idx_status_created 不完全匹配
- P3【列表无分页】getUserOrders 全量返回

### 工程
- E1【映射写失败回调不可达窗口】saveOrderNoMapping 失败仅日志
- E2【测试覆盖缺口】createOrder 成功路径、回查、OrderCloseConsumer、LocalMessageRetryJob、OrderCloseJob 无单测

### 可观测性
- O1 确认 recordOrderCreateLatency Timer 名与 yml 匹配
- O2 待运行验证 actuator/prometheus、SkyWalking、bucket

## 6. 补充遗漏（终审新增）

### 分布式
- N-1【P1 退款双重回补】退款路径同时 releaseInventory（releaseStock，用 pseudoOrderId）+ restoreStockOnRefund（refundRestore，用真实 orderId）。confirmInventoryDeduct 异步 fire-and-forget 失败且无重试时，预扣记录仍存活 → releaseStock 回补一次 + refundRestore 再回补一次 = 库存虚增 qty。需两通道共享幂等键或 refund 前置校验预扣记录已清理
- N-2【P2 本地消息表重复投递】事务提交时本地消息从不标记已投递；LocalMessageRetryJob 每 30s 扫 status IN(0,2) 把每单 ORDER_CREATED 再投递一次 → 每单事务消息投递两次，仅靠 inventory 幂等兜住
- N-4【P2 状态更新与时间戳非原子】setPaidAt 等时间戳更新在事件后单独执行，catch 不含 SQL DataAccessException；瞬时失败时状态已变但 paid_at 为空，回调 500，payment 侧可能重试/退款已支付订单
- N-8【P2 LocalMessageRetryJob 广播 LIMIT pushdown】无 user_id 全分片 selectList LIMIT 50，ShardingSphere 广播下 per-shard 截断可能使重试批序失真

### 微服务
- N-5【P2 硬编码内部令牌默认值】InternalCallFeignConfig 默认 my-xhs-internal-token-2026 常量，而校验侧默认空；一旦某环境 internal.token 设为该公开常量即被任意伪造 X-Internal-Call

### 性能
- N-3【P2 事件查询全分片广播】OrderEventMapper findByOrderId/findLastByOrderId 无 user_id，每次 appendEvent 对 16 张分表全路由
- N-6【P2 列表 VO items 恒空】getUserOrders 走 buildOrderVO 不含明细，items=null，前端需二次查详情

### 业务逻辑
- N-7【P3 useCoupon 返回折扣被丢弃】useCoupon 只校验 isSuccess，未用返回折扣重算 payAmount；订单金额以 getCouponDiscount 为唯一权威，跨模板变更时应付与券侧实际折扣不一致

## 7. 本轮修复与验证

### 代码/契约修复
- C3 死代码删除：`OrderMapper.markPaid/markCompleted/markRefunded`、`LocalMessageMapper.selectPendingMessages/markFailed/markDead` 全部删除（`markSuccess` 保留，被 LocalMessageRetryJob 使用）
- C4 注释修正：`OrderService.java:309` "异步写入映射表" 改为 "写入映射表（同步）"；`:697` 过时注释 "markPaid" 改为 "状态更新"
- N-5 内部令牌默认值修复：`InternalCallFeignConfig` 删除硬编码公开常量 `my-xhs-internal-token-2026`，改为 `${myxhs.internal.token:}` 空默认 + `@PostConstruct` fail-closed 拒绝启动（与 cart/payment 一致），消除任意伪造 X-Internal-Call 风险

### 资损/一致性修复（终审后追加）
- B1 券折扣静默降级：`calculateCouponDiscount` 携带 couponId 时，Feign 失败/返回失败改为抛 `BizException` 拒绝下单，避免“券被核销但按全价付款”
- N-1 退款双重回补：`onRefundSuccess` 去掉 `releaseInventory`，退款前同步 `confirmInventoryDeductSync` 清理预扣记录，再只走幂等 `refundRestore`，消除 confirm 失败时 Redis/MySQL 双回补导致库存虚增
- M1 补偿 DLQ 无重放：`OrderCompensationConsumer` 重试接近上限（reconsumeTimes>=2）时写入 Redis 兜底集合 `myxhs:order:compensation:pending`，由 OrderCloseJob 每分钟重放，避免进 DLQ 后库存/券永久泄漏
- N-4 支付回调时间戳非原子：`onPaymentSuccess` 增加 `DataAccessException` 捕获；若状态已更新为已支付则返回 true 并记录（避免 payment 侧误判失败自动退款已支付订单），paid_at 缺失由对账兜底

### 第二批复核修复（结合上下游评估）
- N-2 本地消息重复投递：事务消息 COMMIT 后调用 `LocalMessageMapper.markSuccessByTransactionId(orderNo,userId)` 标记已投递（带 user_id 精确路由），避免 `LocalMessageRetryJob` 30s 后再补发一次导致每单双投
- N-3 事件全分片广播：`OrderEventService.appendEvent` 改用 `findLastByOrderIdAndUserId` 精确路由分片，避免每次状态变更对 16 张分表全路由
- N-7 useCoupon 折扣丢弃：核销返回实际折扣与订单 discountAmount 不一致时记录告警（订单金额仍以 getCouponDiscount 为权威，模板变更窗口极小的可观测项）

### 评估为有兜底/设计权衡，暂不修（记录）
- D1 预扣竞态：cancelOrder 释放时预扣可能未完成导致 releaseStock no-op；inventory `PreDeductTimeoutJob` 每 1 分钟扫描恢复，30min 内自动释放，非永久泄漏；修复需跨服务改动成本高，暂保留
- 列表无分页（P3）：接口语义变更需前端配合，暂保留
- N-8 LocalMessageRetryJob 广播 LIMIT pushdown：补发是低频低量的“至少一次”兜底，影响是延迟而非丢失，暂保留

### 验证结果
- order 58 个测试全部通过；common 53、coupon 13 个通过
- order 已重新打包并启动，19011 health 为 UP
- RocketMQ broker 运行中，可支撑后续运行级验证

## 8. 测试状态

- 已修复 C1：OrderControllerTest 构造器补 AccessTokenGuard，删除 internalToken 字段 setField，mock isInternalCall=true；22 个用例通过
- 已修复 C2：getOrderPayAmount_notFound 断言改为返回 null（与 getOrderStatus 语义一致），不抛异常
- 本轮 order 58 个测试全部通过；common 53 个、coupon 13 个通过
- RocketMQ broker 当前运行中；order 服务已启动，分布式行为待运行级联调确认
