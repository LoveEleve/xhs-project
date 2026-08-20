# 最终确认主线 Bug 清单

> 范围：只保留业务逻辑、工程质量、分布式一致性、微服务边界问题。
> 安全配置、凭据、网络、部署加固统一放在附录，不进入本清单。

## P0：必须优先处理

### F-007：多 SKU 退款只回补第一个 SKU

- **确认级别**：已修复并补测试
- **根因**：修复前 order 按 `OrderItem` 逐 SKU 调用 `refundRestore`；inventory 幂等键只有 `inventory:refund:{orderId}`。
- **后果**：同订单第二个及之后 SKU 直接跳过，库存确定性少账。
- **修复结果**：inventory 退款回补幂等键已改为 `inventory:refund:{orderId}:{skuId}`，测试已覆盖多 SKU 独立幂等键。
- **验证**：`InventoryServiceTest` 已新增多 SKU `refundRestore` 用例；order 相关测试也已通过退款路径回归。

### F-010：退款回调吞掉订单侧失败

- **确认级别**：已修复并补测试
- **根因**：修复前 `notifyRefundSuccess()` 无条件 `R.ok()`；`onRefundSuccess()` 的 mapping 缺失/订单不存在/状态异常路径只 return。
- **后果**：payment 补偿误判成功，不再重试；钱已退但订单、库存、优惠券可能不收敛。
- **修复结果**：`onRefundSuccess()` 已返回明确 boolean；controller 对未收敛状态返回 `ORDER_STATUS_ERROR`，并保留已退款幂等成功语义。
- **验证**：`OrderServiceTest` / `OrderControllerTest` 已覆盖 mapping 缺失、状态=0、状态=5、正常退款等分支。

### F-006：补偿消息路由缺失时被 ACK

- **确认级别**：已修复并补测试
- **根因**：修复前 `OrderCompensationConsumer` 无法获得 userId/mapping 时直接 return。
- **后果**：库存释放/退券补偿消息不进重试、不进 DLQ，静默丢失。
- **修复结果**：补偿消费者在 userId 缺失或非法且 mapping 缺失时改为抛异常，触发 RocketMQ 重试；header 缺失时允许通过 mapping 反查路由继续处理。
- **验证**：`OrderCompensationConsumerTest` 已覆盖 mapping 缺失重试、坏 userId 重试、mapping 回查成功三条路径。

### F-016：领券消费者忽略库存更新结果

- **确认级别**：已修复并补测试
- **根因**：修复前插入用户券后调用 `decrementRemainCount()`，不检查 affected rows。
- **后果**：remain_count=0 时仍可能提交用户券，用户券与模板库存不一致。
- **修复结果**：`decrementRemainCount()` 返回 0 时改为抛异常，触发事务回滚与 MQ 重试。
- **验证**：`CouponClaimConsumerTest` 已覆盖 affected rows=0 抛异常；`CouponServiceTest` 与模块测试已回归通过。

### F-036：订单 mapping 补偿窗口不足

- **确认级别**：代码级确定
- **根因**：写 mapping 失败只记日志；修复任务只扫描最近 1 小时。
- **后果**：mapping 故障超过 1 小时后，旧订单永久无法路由，支付/退款回调受影响。
- **最小验证**：模拟 mapping 库故障超过窗口后恢复，检查旧订单是否补录。

## P1：高优先级业务/微服务问题

### F-013：AI 会话归属与 run 取消权限缺失

- **确认级别**：代码级确定
- **根因**：conversation list/detail 未统一使用可信 header 和 owner 校验；cancel 未做角色边界。
- **后果**：跨用户读取会话、低权限取消他人任务。
- **最小验证**：两个用户交叉访问 convId/list，OPERATOR 调用 DELETE run。

### F-043：目标校验异常时点赞/收藏继续放行

- **确认级别**：代码级确定
- **根因**：content Feign 校验异常时 `validateTarget/validateNote` 返回 true。
- **后果**：幽灵互动写入 Redis、MQ、计数和通知链。
- **最小验证**：content 不可用时发 like/favorite，检查 HTTP 结果和所有副作用。

### F-039：BFF 把依赖失败伪装成业务不存在/空数据

- **确认级别**：已修复并补测试
- **根因**：修复前 Product/User/Cart/Note/Feed 主源依赖的 Feign fallback 返回 `R.ok(empty)`，聚合层继续映射为 404、空购物车或空流。
- **后果**：调用方误判业务状态，监控看不到依赖故障。
- **修复结果**：主源 fallback 已改为 `SERVICE_UNAVAILABLE`；`HomeController` 对 product/user/cart/note/feed 主路径统一返回 `code=503`，附属字段（库存、计数、优惠券）仍保留字段级降级。
- **验证**：`HomeFeignFallbackFactoryTest` 与 `HomeControllerTest` 已覆盖 product/user/cart/note/feed 五条主路径的 503 语义。

### F-023 + F-042：商品索引失败后写入不完整文档

- **确认级别**：已修复
- **根因**：增量补偿直接写 price=0/brand/category 空值；正常 Canal 消费在 product Feign 失败时也继续写半成品。
- **修复结果**：新增统一 `ProductIndexDocumentBuilder`，Canal、增量补偿、全量重建共用同一商品文档构建逻辑；缺少分类名或价格时直接抛异常，触发 MQ 重试或保留失败 ID，禁止写占位值污染 ES。
- **验证**：`ProductIndexDocumentBuilderTest` 已覆盖权威字段构建、最低 SKU 价格选择、缺分类/价格时拒绝生成文档 3 个场景。

### F-024：全量笔记重建清零互动计数

- **确认级别**：已修复
- **根因**：`IndexRebuildJob.buildNoteDocument()` 直接把三个计数字段写 0。
- **修复结果**：全量重建改为按批从 `my_xhs_counter.t_counter` 读取 note 的 like/collect/comment 权威计数，避免重建污染 ES 热度与展示字段；同时去掉 N+1 查询，按批次一次性加载计数。
- **验证**：已完成编译验证；重建逻辑仅在拿到权威计数后写入文档，部分失败改为直接失败，不再把任务标记为成功。

### F-021 + F-041：推荐数据/任务失败被静默或假成功

- **确认级别**：已修复
- **根因**：行为消费者吞写库异常；推荐任务内层吞异常，外层仍向 XXL-Job 报成功。
- **修复结果**：`BehaviorReportConsumer` 对坏消息和写库异常统一抛出异常，触发 RocketMQ 重试/DLQ；`RecommendComputeJob` 的 ItemCF/特征/热门池路径在源表缺失、读写失败、计数补充失败时向外抛异常，避免 XXL-Job 假成功；`RecommendService` 在 MQ 发送失败且同步写库也失败时直接报错给调用方。
- **验证**：`BehaviorReportConsumerTest` 已覆盖写库失败重试与坏消息拒绝 ACK 两个场景。

### F-008：退券 afterCommit 无可靠补偿

- **确认级别**：已修复并补测试
- **根因**：修复前 MySQL 事务提交后才执行 Redis 回退；afterCommit 异常只记日志，不写补偿记录。
- **后果**：用户券/MySQL 已恢复，但 Redis 库存和领取计数未恢复。
- **修复结果**：coupon 在 afterCommit 的 Redis 退券失败时会发送 `COUPON_RETURN_REDIS_REPAIR_TOPIC` 补偿消息，并由专用 consumer 重放 Redis 修复。
- **验证**：`CouponServiceTest` 已覆盖 afterCommit Redis 失败补偿消息发送；`CouponReturnRedisRepairConsumerTest` 已覆盖补偿消息重放 Redis 修复。

## P2：建议修复的分布式/工程问题

### F-037：库存补偿固定回 bucket0

- **确认级别**：已修复，待隔离环境运行态回归
- **后果**：total 可恢复，但实际分桶失真。
- **修复结果**：`InventoryCompensationJob.doRetryCompensations()` 从 prededuct hash 读取实际来源桶号，不再固定 bucket 0；与 `InventoryService.releaseStock()` 的桶号读取逻辑一致。
- **验证**：`InventoryCompensationJobTest` 覆盖 bucket=2 补偿恢复到正确桶、bucket 字段缺失时回退 bucket 0、release.lua 返回 0 时增加重试 3 个场景；运行态仍需在非 0 bucket 场景下核对 Redis 各桶与 total。

### F-038：优惠券对账跳过下线/过期模板

- **确认级别**：已修复
- **根因**：`CouponReconcileJob` 之前只扫描 `status=1 && valid_end>now && deleted=0` 模板，把下线/过期但仍有 Redis/MySQL 漂移的历史模板直接排除在外。
- **修复结果**：对账范围改为覆盖所有 `deleted=0` 模板；状态/有效期只影响业务可领取性，不再影响一致性修复。顺手将 MySQL 修复语句简化为 `updateById`，避免 LambdaUpdateWrapper 在任务/测试场景下的额外不稳定性。
- **验证**：`CouponReconcileJobTest` 已覆盖下线模板 Redis/MySQL 差异修复、过期模板 Redis 缺失补全两个场景。

### F-004：库存 confirm 失败导致 locked_stock 虚高

- **确认级别**：已修复
- **根因**：`confirmDeduct()` 删除 Redis 预扣后，`CONFIRM` MQ 发送失败只打日志；outbox 还会被取消，导致 MySQL `locked_stock` 无后续落账机会。
- **修复结果**：`CONFIRM` 发送失败时保留 outbox 事件，由 `InventoryOutboxSenderJob` 后续精确补发；不再把确认失败吞成永久脏账。
- **验证**：`InventoryServiceTest` 已覆盖 confirm MQ 失败时保留 outbox、不取消记录。

### F-005：库存 Outbox action 粒度不足

- **确认级别**：已修复
- **根因**：outbox 之前按 `orderId+skuId` 维度标记/删除，同一订单同 SKU 的 `PRE_DEDUCT/CONFIRM/RELEASE` 会互相覆盖或误标记。
- **修复结果**：`t_inventory_outbox` 改为按主键 `id/outboxId` 精确写入、标记、取消；补发消息携带同一 `outboxId`，不同 action 不再相互污染。
- **验证**：`InventoryOutboxSenderJobTest` 已覆盖按 `eventId` 精确标记发送成功。 

### F-012 + F-025：衍生计数源进入索引/推荐产物

- **确认级别**：已修复
- **根因**：Canal 笔记索引会把 `myxhs:counter:*` 的滞后计数写回 ES；推荐特征任务直接读 `t_counter` 计算 `quality_score`，把衍生源误差固化到离线结果。
- **修复结果**：`NoteIndexSyncConsumer` 的 Canal 路径不再写 `likeCount/collectCount/commentCount`，避免内容变更覆盖独立计数消费者维护的字段；`RecommendComputeJob` 改为点赞读 analytics 权威 Set、评论读 `t_comment`、收藏读 `my_xhs_analytics.t_favorite`，不再依赖 `t_counter`。
- **验证**：`RecommendComputeJobTest` 已覆盖特征补充过程不再查询 `t_counter`；`my-xhs-search` 定向测试与编译均通过。

### F-015：坏事务消息被直接确认

- **确认级别**：已修复
- **根因**：`OrderTransactionConsumer` 在缺少 `orderNo/userId` 的情况下直接 `return`，RocketMQ 视为成功消费；`skuItems` 缺失或字段异常也没有统一 fail-closed。
- **修复结果**：`OrderTransactionConsumer` 对 `orderNo/userId/skuItems` 以及单个 `skuId/quantity` 字段做严格校验，异常统一抛出进入重试/DLQ，并移除幂等标记，避免坏消息被直接 ACK。
- **验证**：`OrderTransactionConsumerTest` 已覆盖缺少 `userId`、`skuItems` 单项缺字段两类 malformed 消息都会进入失败路径且不触发预扣。

### F-040：FeedCleanup 节流失效

- **确认级别**：代码级确定
- **后果**：大量 key 清理时 Redis 压力高于设计预期。

## 最终判断

### 已确认真 bug

F-006、F-007、F-008、F-010、F-013、F-016、F-021、F-023、F-024、F-036、F-039、F-041、F-042、F-043。

### 保留但需运行态量化

F-004、F-005、F-012、F-015、F-025、F-037、F-038、F-040。

### 不进入主线

F-001/002/003/014/017/018/019/020/026/027/028/029/030/031/032/033/034/035：安全、凭据、网络、部署加固或运维附录项。