# F-036 订单映射补偿只扫描最近 1 小时，持续故障后历史缺失映射永久遗漏

## 严重度

Medium

## 涉及文件

- `my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java:1027-1037`
- `my-xhs-order/src/main/java/com/myxhs/order/job/OrderMappingRepairJob.java:56-63`

## 现象

下单时映射写入失败只记录日志并继续主流程；补偿任务每次只扫描最近 1 小时的订单。

如果 mapping 库故障、配置错误或写入异常持续超过 1 小时，早于窗口的订单将不再被扫描。

## 证据

1. `saveOrderNoMapping()` 捕获异常后只记录日志，不创建持久补偿记录：`OrderService.java:1027-1037`。
2. `OrderMappingRepairJob` 使用 `LocalDateTime.now().minusHours(1)`：`OrderMappingRepairJob.java:56-58`。
3. 任务只从该时间窗口内的订单中寻找缺失映射：`OrderMappingRepairJob.java:61-75`。
4. 支付、退款、订单号查询等回调依赖 mapping 反查分片路由。

## 触发条件

1. mapping 数据源持续不可用超过 1 小时
2. 订单主库仍可创建订单，但公共 mapping 库写入失败
3. 故障恢复后没有额外全量扫描/对账任务

## 影响

1. 老订单永久缺少 orderId→userId 路由映射。
2. 支付/退款回调可能无法定位分片，触发 F-006/F-010 类状态不收敛。
3. 订单号查询、补偿任务和客服查询都会长期失败。

## 修复建议

1. 不要使用固定 1 小时窗口作为唯一补偿边界；应扫描所有缺失 mapping 的订单。
2. 或将 mapping 写失败记录写入独立 durable outbox/补偿表。
3. 增加全量 mapping 对账任务，并对缺失数量告警。
4. 回调在 mapping 暂缺时应返回可重试失败，不要把问题静默视为成功。

## 是否需要补充验证

需要模拟 mapping 数据源不可用超过 1 小时，再恢复服务，确认旧订单是否会被补录。