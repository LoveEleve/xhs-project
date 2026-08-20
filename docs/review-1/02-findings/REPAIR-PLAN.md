# review-1 修复计划

## 1. 修复原则

1. 先修确定性业务错误，再修补偿基础设施。
2. 每批只改一个业务边界，避免跨批次状态不可控。
3. 每个修复必须同时补单测、集成验证和最终一致性断言。
4. 不修改安全/凭据/网络附录项，除非它们直接阻塞业务修复验证。
5. 不修改 AI 团队代码；AI 侧问题单独移交。
6. 每批发布前保留旧 jar、数据库变更脚本和 Redis/MQ 回滚说明。

## 2. 批次总览

| 批次 | 目标 | 问题 | 风险 |
|---|---|---|---|
| Batch 0 | 修复基础测试与可观测性基线 | 测试补洞、验证工具 | 低 |
| Batch 1 | 止住确定性业务错误 | F-007、F-010、F-016、F-039 | 中 |
| Batch 2 | 修复补偿与映射闭环 | F-006、F-008、F-036、F-037 | 中高 |
| Batch 3 | 修复搜索/推荐产物错误 | F-021、F-023、F-024、F-041、F-042、F-043 | 中 |
| Batch 4 | 修复库存 Outbox 与状态模型 | F-004、F-005、F-012、F-015、F-025、F-038、F-040 | 中高 |
| Batch 5 | AI 侧移交验证 | F-013 | 依赖 AI 团队 |

## 3. Batch 0：测试与验证基线

### 范围

1. 建立独立 `review-1` 测试数据 ID 段。
2. 增加 DB/Redis/MQ/ES 快照工具，不修改业务逻辑。
3. 将现有 curl 从“直连服务端口”拆成 gateway 测试与内部接口测试两套。
4. 建立最终一致性等待与断言模板。

### 必须产出

- 多 SKU 退款测试数据构造器
- mapping 故障/缺失验证器
- coupon remain_count/Redis stock 对账器
- ES 文档关键字段校验器
- MQ/Outbox/DLQ 查询脚本

### 通过标准

未完成 Batch 0，不进入大批量业务修复，避免修了问题却无法证明已收敛。

## 4. Batch 1：确定性业务错误

### 4.1 F-007 多 SKU 退款回补

状态：**已完成代码修复与单测验证**

已实施：

1. inventory 退款回补幂等键已改为 `orderId + skuId`。
2. 增加多 SKU 回补测试，锁定每个 SKU 的独立幂等语义。
3. 保留后续检查项：Outbox/consumer 的 action 粒度仍建议在 Batch 2/4 联动复核。

验证：

- `InventoryServiceTest` 已新增双 SKU `refundRestore` 独立幂等键测试
- `mvn -q -pl my-xhs-inventory -am test -Dtest=InventoryServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

剩余建议：

- 在隔离环境做一次真实双 SKU 退款回归，核对 Redis total/bucket、MySQL available 与 Outbox。

### 4.2 F-010 退款回调错误传递

状态：**已完成代码修复与单测验证**

已实施：

1. `onRefundSuccess()` 已返回明确 boolean 结果。
2. `notifyRefundSuccess()` 已区分已幂等完成、状态未收敛和正常成功，并在失败时返回 `ORDER_STATUS_ERROR`。
3. 已退款（status=5）保留幂等成功语义。

验证：

- `OrderServiceTest` 已覆盖 mapping 缺失、状态=0、状态=5、正常退款
- `OrderControllerTest` 已覆盖 refund-success 成功语义路径
- `mvn -q -pl my-xhs-order -am test -Dtest=OrderControllerTest,OrderServiceTest,OrderTransactionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

剩余建议：

- 在隔离环境联动 payment compensation，确认非成功响应会触发继续补偿。

### 4.3 F-016 领券库存结果

状态：**已完成代码修复与单测验证**

已实施：

1. `decrementRemainCount()` affected rows 已被强校验。
2. affected=0 时抛异常，触发事务回滚/MQ 重试。
3. 已补充消费者测试，锁定“无库存不得提交用户券”。

验证：

- `CouponClaimConsumerTest` 已覆盖 affected rows=0 抛异常
- `mvn -q -pl my-xhs-coupon -am test -Dtest=CouponServiceTest,CouponClaimConsumerTest -Dsurefire.failIfNoSpecifiedTests=false`

剩余建议：

- 在隔离环境重放真实 claim 消息，确认 MQ 重试后最终只生成一张券。

### 4.4 F-039 BFF 错误语义

状态：**已完成代码修复与单测验证**

已实施：

1. Product/User/Cart 主实体 fallback 改为 `SERVICE_UNAVAILABLE`，不再返回 `R.ok(empty)`。
2. Content 主实体路径（note/feed 的 note 主源）同步改为 `SERVICE_UNAVAILABLE`，避免同类伪成功残留。
3. `HomeController` 对 product/user/cart/note/feed 主路径统一返回 `code=503`；附属字段（计数、库存、优惠券）继续保留字段级降级。
4. 新增 `DownstreamUnavailableException`，把“主源依赖失败”和“业务对象不存在”语义拆开。

验证：

- `HomeFeignFallbackFactoryTest`：product/user/cart/content 主源 fallback 返回 503
- `HomeControllerTest`：product/user/cart/note/feed 主路径异步响应返回 `code=503`
- `mvn -q -pl my-xhs-home -am compile`

剩余建议：

- 在隔离环境做一次真实停服务回归，确认 gateway / 前端联动语义符合预期。

## 5. Batch 2：补偿与映射闭环

### F-006

状态：**已完成代码修复与单测验证**

已实施：

1. mapping 缺失时补偿消费者不再 ACK，而是抛异常触发 RocketMQ 重试。
2. userId header 非法时会降级回查 mapping；mapping 仍缺失则继续抛错重试。
3. 保留 DLQ 作为上限兜底，不再静默吞消息。

验证：

- `OrderCompensationConsumerTest` 已覆盖 mapping 缺失重试、坏 userId 重试、mapping 回查成功
- `mvn -q -pl my-xhs-order -am test -Dtest=OrderCompensationConsumerTest,OrderControllerTest,OrderServiceTest,OrderTransactionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

剩余建议：

- 在隔离环境模拟“补偿消息先到、mapping 后写入”的时序，确认最终会在重试窗口内收敛。

### F-008

状态：**已完成代码修复与单测验证**

已实施：

1. coupon 退券 afterCommit 的 Redis 失败不再只记日志，而是发送 `COUPON_RETURN_REDIS_REPAIR_TOPIC` 补偿消息。
2. 新增专用 consumer 重放 Redis 退券修复，并清模板缓存。
3. 保持 MySQL 事务与 Redis 修复解耦，避免 afterCommit 成为唯一执行机会。

验证：

- `CouponServiceTest` 已覆盖 afterCommit Redis 失败时发送补偿消息
- `CouponReturnRedisRepairConsumerTest` 已覆盖补偿消息重放 Redis 修复
- `mvn -q -pl my-xhs-coupon -am test -Dtest=CouponServiceTest,CouponClaimConsumerTest,CouponReturnRedisRepairConsumerTest -Dsurefire.failIfNoSpecifiedTests=false`

剩余建议：

- 在隔离环境注入真实 Redis 失败，确认 MySQL 已提交后补偿消息可被消费并把 Redis 状态修回。

### F-036

1. ~~mapping repair 改为扫描所有缺失映射，或建立失败 outbox。~~ 已完成：删除 `repairLookbackDays` 时间窗口，改为全表 `id > lastId` 游标分页，消除"故障超过 N 天后永久遗漏"的尾巴。
2. 增加历史缺失数量指标和告警。
3. mapping 恢复后主动重放受影响回调/补偿。

验证：

- `OrderMappingRepairJobTest` 覆盖 120 天前订单仍被补录、已有映射不重复补录。
- `mvn -q -pl my-xhs-order -am test -Dtest=OrderMappingRepairJobTest -Dsurefire.failIfNoSpecifiedTests=false`

### F-037

1. ~~compensation 记录保存 bucketNo，或从 prededuct hash 读取。~~ 已完成：`InventoryCompensationJob.doRetryCompensations()` 从 prededuct hash 读取 `${skuId}:bucket` 字段获取实际来源桶号。
2. ~~统一复用正常 release 逻辑。~~ 已完成：补偿路径与 `InventoryService.releaseStock()` 使用相同的 bucket 号读取逻辑。
3. 校验 bucket sum 与 total。

验证：

- `InventoryCompensationJobTest` 覆盖 bucket=2 恢复到正确桶、bucket 字段缺失时回退 bucket 0、release.lua 返回 0 时增加重试。
- `mvn -q -pl my-xhs-inventory -am test -Dtest=InventoryCompensationJobTest,InventoryServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

## 6. Batch 3：搜索、推荐与互动产物

### F-021

~~行为写库异常必须抛出重试；坏消息进入 DLQ，不得静默成功。~~ 已完成：`BehaviorReportConsumer` 对写库失败和缺字段坏消息统一抛异常，不再静默 ACK。

验证：

- `BehaviorReportConsumerTest` 覆盖 DB 写失败触发重试、坏消息拒绝 ACK 两个场景。
- `mvn -q -pl my-xhs-search -am test -Dtest=BehaviorReportConsumerTest -Dsurefire.failIfNoSpecifiedTests=false`

### F-023/F-042

~~统一商品索引构建器：Canal、增量补偿、全量重建共用完整文档构建逻辑；补不全就失败，不写占位值。~~ 已完成：新增 `ProductIndexDocumentBuilder`，Canal/增量补偿/全量重建共用；缺分类名或价格时直接失败，禁止写 price=0、空分类等半成品文档。

验证：

- `ProductIndexDocumentBuilderTest` 覆盖权威字段构建、最低价格选择、缺关键字段时拒绝构建。
- `mvn -q -pl my-xhs-search -am test -Dtest=ProductIndexDocumentBuilderTest -Dsurefire.failIfNoSpecifiedTests=false`

### F-024

~~全量 note 重建不能写零计数：~~ 已完成：`IndexRebuildJob` 按批从 `my_xhs_counter.t_counter` 读取 note 的 like/collect/comment 权威计数后写入 ES，不再清零互动字段。

验证：

- 已完成 `my-xhs-search` 编译验证；按批量 `IN (...)` 读取计数，避免全量重建 N+1。

### F-041

~~推荐任务内层异常必须向 XXL-Job 传播；“无数据”与“执行失败”分开。~~ 已完成：`RecommendComputeJob` 的 ItemCF/特征/热门池路径在依赖异常时统一抛出失败；仅“无交互/无热门”等业务空数据场景才正常返回。

### F-043

~~互动目标校验异常改为 fail-closed 或进入待确认状态，不得直接写 Redis/MQ。~~ 已完成：`LikeService.validateTarget()` 与 `FavoriteService.validateNote()` 在 content 服务异常时抛 `SERVICE_UNAVAILABLE`，不再降级放行。
## 7. Batch 4：库存与计数基础模型

### F-004

~~confirm 失败必须保留可重试事件，并增加 locked_stock 对账修复。~~ 已完成第一阶段深修：`CONFIRM` MQ 发送失败不再取消 outbox，而是保留待 `InventoryOutboxSenderJob` 补发，避免 `locked_stock` 永久虚高。

验证：

- `InventoryServiceTest` 覆盖 confirm MQ 失败时保留 outbox、preDeduct 失败时取消 outbox。
- `mvn -q -pl my-xhs-inventory -am test -Dtest=InventoryServiceTest,InventoryCompensationJobTest,InventoryOutboxSenderJobTest -Dsurefire.failIfNoSpecifiedTests=false`

### F-005

~~Outbox 使用事件 ID/`orderId+skuId+action` 粒度；mark/delete 按主键定位。~~ 已完成：outbox 写入、标记、取消统一按主键 `id/outboxId` 精确定位，`PRE_DEDUCT/CONFIRM/RELEASE` 不再互相覆盖。

验证：

- `InventoryOutboxSenderJobTest` 覆盖按 outbox 主键标记成功发送。

Outbox 使用事件 ID/`orderId+skuId+action` 粒度；mark/delete 按主键定位。

### F-012/F-025

~~索引与推荐统一使用权威计数源，或明确先对账再读取衍生源。~~ 已完成：`NoteIndexSyncConsumer` Canal 路径不再写互动计数字段；`RecommendComputeJob` 的互动特征改为点赞读 analytics Set、评论读 `t_comment`、收藏读 `my_xhs_analytics.t_favorite`，不再查询 `t_counter`。

验证：

- `RecommendComputeJobTest` 覆盖互动特征补充过程不再查询 `t_counter`。
- `mvn -q -pl my-xhs-search -am test -Dtest=BehaviorReportConsumerTest,ProductIndexDocumentBuilderTest,RecommendComputeJobTest -Dsurefire.failIfNoSpecifiedTests=false`

### F-015

~~订单事务消息字段缺失不得直接 ACK；进入重试/DLQ，并提供订单库存补偿。~~ 已完成：`OrderTransactionConsumer` 对 `orderNo/userId/skuItems` 与单个 SKU 字段做严格 schema 校验，坏消息统一抛异常进入重试/DLQ，不再静默确认。

验证：

- `OrderTransactionConsumerTest` 覆盖缺 `userId`、坏 `skuItems` 两类 malformed 消息的失败路径。
- `mvn -q -pl my-xhs-inventory -am test -Dtest=OrderTransactionConsumerTest,InventoryServiceTest,InventoryCompensationJobTest,InventoryOutboxSenderJobTest -Dsurefire.failIfNoSpecifiedTests=false`

### F-038

~~对账覆盖所有未删除模板，不按 status/valid_end 筛掉历史数据。~~ 已完成：`CouponReconcileJob` 改为扫描全部 `deleted=0` 模板，过期/下线模板也参与 Redis/MySQL 对账修复。

验证：

- `CouponReconcileJobTest` 覆盖下线模板数量修复、过期模板 Redis 回填。
- `mvn -q -pl my-xhs-coupon -am test -Dtest=CouponServiceTest,CouponClaimConsumerTest,CouponReturnRedisRepairConsumerTest,CouponReconcileJobTest -Dsurefire.failIfNoSpecifiedTests=false`

### F-040

修正 cleanup 计数器作用域，增加大 key 数量下的性能测试。

## 8. Batch 5：AI 侧移交

F-013 不由本修复批次直接改动。交付 AI 团队：

1. conversation list 使用可信用户 header
2. conversation detail 校验 owner
3. run cancel 增加 TECH 角色边界
4. AI 团队完成后由主系统做 gateway 联调验收

## 9. 发布顺序

1. Batch 0 测试基线
2. Batch 1 业务止血
3. Batch 2 补偿闭环
4. Batch 3 搜索/推荐
5. Batch 4 基础模型
6. Batch 5 AI 团队交付后联调

## 10. 每批统一验收

1. 单元测试
2. 服务集成测试
3. 正常路径
4. 重复/重放路径
5. 超时/失败路径
6. DB/Redis/MQ/ES 对账
7. 日志与监控确认
8. 回滚演练

## 11. 第一批实际实施边界

下一步只实施 Batch 0 + Batch 1，不同时修改库存 Outbox、推荐、AI 和安全配置。

Batch 1 完成后暂停，先跑完整回归与最终一致性验证，再决定是否进入 Batch 2。