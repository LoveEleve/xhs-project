# F-022 ES 增量补偿用 distinctRandomMembers + remove，失败集合不是原子弹出

## 严重度

Medium

## 涉及文件

- `my-xhs-search/src/main/java/com/myxhs/search/job/IncrementalIndexSyncJob.java:96-106`
- `my-xhs-search/src/main/java/com/myxhs/search/job/IncrementalIndexSyncJob.java:149-158`

## 现象

增量索引补偿任务声称“使用 SPOP 原子弹出”，但实现实际使用的是：

1. `distinctRandomMembers(key, MAX_BATCH)` 随机取样
2. 再循环 `remove(key, id)`

这不是原子弹出，多实例并发时可能取到相同 docId，或在 remove 前后被其他实例重复处理。

## 证据

1. `compensateFailedNotes()`：`IncrementalIndexSyncJob.java:97-105`
2. `compensateFailedProducts()`：`IncrementalIndexSyncJob.java:150-157`
3. 代码注释写的是“SPOP 原子弹出”，与实际实现不符：`IncrementalIndexSyncJob.java:91-93`

## 影响

1. 多实例并发时同一失败 docId 可能被重复补偿，造成额外 ES bulk 开销。
2. 文档索引是幂等的，所以通常不会产生错误结果，但会放大补偿噪音和日志量。
3. 注释与实现不一致，会误导后续维护者对并发安全的判断。

## 修复建议

1. 改为真正的原子弹出（Lua + SPOP 批量，或单实例锁保护）。
2. 若继续使用随机取样，应在注释里明确这是“近似一次”的实现，不是原子 SPOP。
3. 多实例部署时增加分布式锁或按 hash 分片补偿。

## 是否需要补充验证

需要确认搜索服务是否多实例部署；单实例时影响主要是注释失真，多实例时才有重复补偿窗口。