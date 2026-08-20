# F-041 推荐离线任务在内部失败时仍向 XXL-Job 报成功

## 严重度

Medium

## 涉及文件

- `my-xhs-search/src/main/java/com/myxhs/search/job/RecommendComputeJob.java:175-183`
- `my-xhs-search/src/main/java/com/myxhs/search/job/RecommendComputeJob.java:186-243`
- `my-xhs-search/src/main/java/com/myxhs/search/job/RecommendComputeJob.java:407-415`
- `my-xhs-search/src/main/java/com/myxhs/search/job/RecommendComputeJob.java:418-467`

## 现象

`recommendFeatureJob` 和 `recommendHotPoolJob` 的外层 `@XxlJob` 包装会在 `doExtractFeatures()` / `doRefreshHotPool()` 返回后直接 `handleSuccess(...)`。但这两个内部方法自己吞掉了核心逻辑异常，只写日志不抛出，导致 XXL-Job 平台看到“执行成功”，实际产物未更新。

## 证据

1. `extractFeatures()` 外层直接在 `doExtractFeatures()` 返回后 `handleSuccess`：`RecommendComputeJob.java:175-183`。
2. `doExtractFeatures()` 内部大块逻辑被 `try/catch` 包住，异常只 `log.error` 不 rethrow：`RecommendComputeJob.java:202-243`。
3. `refreshHotPool()` 外层同样在 `doRefreshHotPool()` 返回后 `handleSuccess`：`RecommendComputeJob.java:407-415`。
4. `doRefreshHotPool()` 内部 `catch` 只记录日志：`RecommendComputeJob.java:429-467`。

## 影响

1. 推荐特征和热门池更新失败时，调度平台仍显示成功，误导运营和排障。
2. 离线推荐产物可能长期停留在旧版本，而无告警升级。
3. 这类“假成功”会降低对数据质量问题的感知，直到前端效果明显异常才暴露。

## 修复建议

1. 内部逻辑异常应抛出到外层，让 XXL-Job 明确标记失败。
2. 若需要部分容错，至少区分“无数据可算”与“执行失败”。
3. 对最终产物（Redis 热门池、t_item_feature）增加成功后的校验和结果指标，而不是只依赖 try/catch。

## 是否需要补充验证

需要模拟 SQL 异常或 Redis 写入失败，确认当前 XXL-Job 控制台是否仍显示“成功”。