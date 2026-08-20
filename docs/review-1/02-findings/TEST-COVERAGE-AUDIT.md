# 测试覆盖审计（运行态验证后）

## 已直接证实的遗漏

### F-007 多 SKU 退款

现有测试有 `OrderTransactionServiceTest.executeLocalTransaction_multipleSkus()`，只验证多 SKU 订单创建时插入两条明细；没有验证退款回调逐 SKU回补库存。

`OrderControllerTest.notifyRefundSuccess()` 只 mock `orderService.onRefundSuccess()` 为 `doNothing()`，只断言 HTTP 200，不验证订单状态、库存、Redis 或多 SKU行为。

### F-010 退款回调失败语义

现有 `OrderControllerTest.notifyRefundSuccess()` 对 service 使用 `doNothing()`，没有覆盖 service 内部失败、mapping 缺失、订单状态不符，直接把无条件 200 固化成了测试期望。

### F-016 领券库存扣减

当前搜索到的测试未覆盖“remain_count=0 但 Redis stock 可领”的漂移场景，也未断言 `decrementRemainCount()` 影响行数。

### F-039 BFF 依赖失败

现有 home 聚合测试未发现对 product/user/cart Feign 超时或失败语义的覆盖；主要测试正常返回结构。

## 直接原因

1. Controller 测试 mock 了 Service，绕过了真正的业务状态机。
2. 多服务联调只断言 HTTP 返回，不做跨库/Redis/MQ/ES 对账。
3. 异常路径没有被作为一等测试对象。
4. 多 SKU、多消息、补偿任务和故障注入没有进入回归矩阵。

## 结论

“多轮回归全绿”不能覆盖这些问题，因为当前测试证明的是“正常入口和正常依赖可用”，不是“异常后系统仍能收敛”。