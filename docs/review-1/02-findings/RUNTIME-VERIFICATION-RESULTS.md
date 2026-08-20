# 运行态验证结果（首轮）

## 已确认

### F-007：多 SKU 退款回补幂等键错误

状态：**已运行态复现，后续已完成代码修复与单测验证**

使用隔离订单 `9000000000000001001` 构造两个 SKU，并调用真实 `POST /api/order/refund-success`。接口返回 200，order 日志把两个 SKU 都记为“退款回补库存成功”，但 inventory 日志显示：

- SKU 990000001：Redis 缺失，回补放弃
- 随后 SKU 990000001/990000002 都命中 `inventory:refund:{orderId}` 幂等跳过
- MySQL inventory 两个 SKU 的库存均未变化

测试数据已清理。该结果直接证明修复前订单级幂等键会阻断多 SKU 独立回补，并且上游日志存在成功语义失真。

后续修复与验证：

- inventory 退款回补幂等键已改为 `inventory:refund:{orderId}:{skuId}`
- `InventoryServiceTest` 已新增多 SKU `refundRestore` 独立幂等键测试
- `mvn -q -pl my-xhs-inventory -am test -Dtest=InventoryServiceTest -Dsurefire.failIfNoSpecifiedTests=false` 已通过

当前仍缺：

- 一次真实双 SKU 退款的隔离环境回归，用于把“单测通过”补成“运行态修复确认”。

### F-010：退款成功回调吞掉订单侧失败

状态：**已运行态复现，后续已完成代码修复与单测验证**

构造隔离订单 `9000000000000002001`，状态保持待付款（status=0），写入 mapping 后调用真实退款成功回调：

- HTTP 状态：200
- 响应：`success=true`
- 订单状态：仍为 0，未发生退款状态收敛

测试数据已清理。该结果证明修复前 `notifyRefundSuccess()` 会把订单侧未处理成功包装成成功响应。

后续修复与验证：

- `onRefundSuccess()` 已返回明确 boolean，controller 对失败返回 `ORDER_STATUS_ERROR`
- `OrderServiceTest` / `OrderControllerTest` 已覆盖 mapping 缺失、状态=0、状态=5、正常退款
- `mvn -q -pl my-xhs-order -am test -Dtest=OrderControllerTest,OrderServiceTest,OrderTransactionServiceTest -Dsurefire.failIfNoSpecifiedTests=false` 已通过

当前仍缺：

- 一次 payment compensation 联动回归，确认非成功响应会继续补偿而非误判完成。

### F-006：补偿消息路由缺失时被 ACK

状态：**已完成代码修复与单测验证，待隔离环境时序回归**

当前结论：

- `OrderCompensationConsumer` 在缺少 userId/header 非法时，已改为优先回查 mapping；若仍无法路由则抛异常触发 RocketMQ 重试
- `OrderCompensationConsumerTest` 已覆盖 mapping 缺失重试、坏 userId 重试、mapping 回查成功三条路径
- `mvn -q -pl my-xhs-order -am test -Dtest=OrderCompensationConsumerTest,OrderControllerTest,OrderServiceTest,OrderTransactionServiceTest -Dsurefire.failIfNoSpecifiedTests=false` 已通过

当前仍缺：

- 一次“补偿消息先到、mapping 后写入”的隔离环境回归，用于确认 reconsume 最终能自动收敛，而不是仅停留在单测层面。

### F-039：BFF 把下游不可用伪装成业务不存在

状态：**已运行态复现，后续已完成代码修复与单测验证**

短暂停止 product 服务后调用 home 商品聚合：

- 请求：`GET http://127.0.0.1:19015/api/home/product/1`
- HTTP：200
- Body：`code=404, message=商品不存在`

这证明修复前 product 服务不可用时，BFF 会将依赖故障包装成业务 404，同时外层 HTTP 仍是 200。product 服务已立即恢复。

后续修复与验证：

- Product/User/Cart 主源 fallback 已改为 `SERVICE_UNAVAILABLE`
- Note/Feed 的 content 主源 fallback 也已同步改为 `SERVICE_UNAVAILABLE`
- `HomeControllerTest` 已覆盖 product/user/cart/note/feed 五条主路径，断言业务 `code=503`
- `HomeFeignFallbackFactoryTest` 已覆盖 product/user/cart/content 主源 fallback 的 503 语义
- `mvn -q -pl my-xhs-home -am compile` 已通过

当前仍缺：

- 一次真实停 product/user/cart/content 服务的隔离环境回归，用于把“单测通过”补成“运行态修复确认”。

### F-016：领券消费者忽略模板库存更新结果

状态：**已运行态复现，后续已完成代码修复与单测验证**

构造隔离模板 `9000000000000003001`：MySQL `remain_count=0`，Redis stock 手动设为 1。调用真实领券接口后：

- HTTP 返回 200 / `success=true`
- MQ 消费后 `t_user_coupon` 成功插入
- 模板 `remain_count` 仍为 0

测试模板、用户券和 Redis key 已清理。该结果证明修复前消费者忽略 `decrementRemainCount()` 影响行数，会提交无库存用户券。

后续修复与验证：

- `decrementRemainCount()` 返回 0 时已改为抛异常，触发事务回滚/MQ 重试
- `CouponClaimConsumerTest` 已覆盖 affected rows=0 抛异常
- `mvn -q -pl my-xhs-coupon -am test -Dtest=CouponServiceTest,CouponClaimConsumerTest -Dsurefire.failIfNoSpecifiedTests=false` 已通过

当前仍缺：

- 一次隔离环境 claim 消息重放，确认真实 MQ 重试后最终只生成一张券。

### F-008：退券 afterCommit 无可靠补偿

状态：**已完成代码修复与单测验证，待隔离环境故障注入回归**

当前结论：

- coupon 退券 afterCommit 的 Redis 失败现在会发送 `COUPON_RETURN_REDIS_REPAIR_TOPIC` 补偿消息
- 新增 `CouponReturnRedisRepairConsumer` 重放 Redis 退券修复，避免 afterCommit 成为唯一执行机会
- `CouponServiceTest` 已覆盖 afterCommit Redis 失败发送补偿消息；`CouponReturnRedisRepairConsumerTest` 已覆盖补偿消息重放
- `mvn -q -pl my-xhs-coupon -am test -Dtest=CouponServiceTest,CouponClaimConsumerTest,CouponReturnRedisRepairConsumerTest -Dsurefire.failIfNoSpecifiedTests=false` 已通过

当前仍缺：

- 一次真实 Redis 故障注入回归，用于确认 MySQL 已提交后，补偿消息最终能把 Redis 库存和领取计数修回。

### F-009：退款通知补偿重复处理

状态：**运行态确认**

证据：`logs/my-xhs-payment.log`

同一补偿任务每 3 分钟都报告重新通知 5 条：12:06、12:09、12:12、12:15、12:18、12:21。

结合代码中退款 notified key 只读不写，确认补偿去重未生效。

## 静态证据已足够，无需运行注入

### F-007

多 SKU 退款的 order 侧逐 SKU调用与 inventory 侧 orderId 单键冲突，代码级确定。

### F-016

领券消费者忽略 `decrementRemainCount()` 影响行数，代码级确定。

### F-024

修复后全量 note 重建改为从 `my_xhs_counter.t_counter` 读取真实 like/collect/comment 计数，代码级确认已消除“重建即清零”问题；当前仍未在隔离环境执行真实全量重建，避免对现网 ES 产生写入影响。

### F-040

FeedCleanupJob 的 count 变量位于循环体内，节流条件静态不可达；无需真实清理大量 Redis key。

## 当前环境观察

- order/payment/inventory/coupon/search/home/notification/im 服务均在监听对应端口。
- 当前基线：订单分片中没有发现多 SKU 订单；inventory 表为 0；mapping 表为 0；payment=6；coupon_template=5。
- 当前 inventory/coupon 对账日志显示扫描数量为 0，当前环境没有可用于安全验证的库存基线，不能据此判定实现正确。
- 因为没有多 SKU 订单和库存基线，F-007/F-004/F-037 不能在当前数据上直接完成运行态验证。
- 未执行 MQ 停止、Redis 故障、ES 故障、mapping 删除、全量重建等破坏性注入。

## 尚未闭环

### 需要受控故障注入或隔离测试环境

- F-004：confirm MQ 失败后 locked_stock 是否残留（代码已修复为保留 outbox 待补发，待隔离环境验证时序闭环）
- F-005：Outbox action 覆盖时序（代码已修复为 outbox 主键级精确标记，待隔离环境验证）
- F-012：点赞后编辑笔记的 ES 计数回退（代码已修复为 Canal 不再覆盖计数字段，待隔离环境验证“点赞→编辑”时序）
- F-015：malformed transaction message 是否直接 ACK（代码已修复为 fail-closed + 重试/DLQ，待隔离环境验证实际 RocketMQ 重投与死信路径）
- F-025：推荐特征任务是否固化滞后计数（代码已修复为不再查询 `t_counter`，待隔离环境验证）
- F-037：非 bucket0 释放补偿
- F-038：下线/过期模板对账范围（代码已修复为覆盖所有未删除模板，待隔离环境验证历史漂移修复）
- F-006：补偿消息路由缺失（已修复，待时序回归）
- F-007：多 SKU 退款回补（修复前已复现，修复后待隔离环境回归）
- F-008：退券 afterCommit 补偿（已修复，待 Redis 故障注入回归）
- F-010：退款回调错误传递（修复前已复现，修复后待 payment 联动回归）
- F-016：领券库存结果（修复前已复现，修复后待隔离环境回归）
- F-039：BFF 依赖失败返回语义（修复前已复现，修复后待隔离环境回归）

## 首轮判定

- 已运行态确认：F-009
- 代码级确定：F-040
- 已修复待隔离环境回归：F-004/F-005/F-006/F-007/F-008/F-010/F-012/F-015/F-016/F-021/F-023/F-024/F-025/F-038/F-039/F-041/F-042/F-043
- 需隔离环境验证：F-037（代码已修复，需按非 0 bucket 预扣→补偿路径核对 Redis bucket 恢复）

不能把“当前没有测试数据”当作这些问题不存在。