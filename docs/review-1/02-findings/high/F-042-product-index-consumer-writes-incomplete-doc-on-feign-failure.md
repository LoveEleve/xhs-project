# F-042 商品索引消费者在 product Feign 失败时仍写入不完整 ES 文档

## 严重度

High

## 涉及文件

- `my-xhs-search/src/main/java/com/myxhs/search/consumer/ProductIndexSyncConsumer.java:219-256`
- `my-xhs-search/src/main/java/com/myxhs/search/consumer/ProductIndexSyncConsumer.java:258-279`

## 现象

当 `ProductIndexSyncConsumer` 处理 Canal 的 SPU 变更时，会通过 Feign 向 product 服务补齐 `categoryName / price / image` 等字段。

但如果 Feign 调用失败，它不是抛异常重试，而是记录 warn 后继续把不完整文档写入 ES，并记录“索引成功”。

## 证据

1. Feign 获取补全字段的异常在 `indexProductFromCanal()` 内被捕获并吞掉：`ProductIndexSyncConsumer.java:219-256`。
2. catch 后继续构造文档，`categoryName`、`price`、`image` 可能为 `null`：`:258-268`。
3. 随后仍然执行 `esClient.index(...)` 并记录 `Canal 索引成功`：`:270-279`。

## 影响

1. 商品 ES 文档会被写成语义错误的“成功快照”，而不是进入重试/补偿。
2. 搜索结果可能缺失价格、分类名、主图等关键字段。
3. 因为消息被正常消费，后续不会自动重放；错误文档会一直保留到下一次成功增量或全量重建。
4. 这比 F-023（增量补偿写不完整文档）更糟，因为它发生在正常增量链路，而不是补偿分支。

## 修复建议

1. 对关键补全字段获取失败应抛异常，让消息重试，而不是继续写半成品文档。
2. 或者改造 Canal 索引路径，直接从单一权威视图/宽表构建文档，避免跨服务补全。
3. 如果必须容忍部分字段缺失，应给 ES 文档写入“incomplete”标记，并把 spuId 加入补偿集合，而不是记录成功。
4. 将 ProductIndexSyncConsumer 与 F-023 的增量补偿逻辑统一，确保失败文档总能被完整重建。

## 是否需要补充验证

需要在 product 服务不可用时触发一次 SPU UPDATE，观察 ES 中对应 product 文档是否被写入 `price=null/categoryName=null/image=null`。