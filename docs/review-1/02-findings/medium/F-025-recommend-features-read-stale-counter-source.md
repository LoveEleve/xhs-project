# F-025 推荐特征提取仍读 t_counter 作为互动特征源，可能放大滞后计数误差

## 严重度

Medium

## 涉及文件

- `my-xhs-search/src/main/java/com/myxhs/search/job/RecommendComputeJob.java:332-357`
- `my-xhs-counter/src/main/java/com/myxhs/counter/service/CounterService.java:341-388`

## 现象

推荐特征提取在计算 `qualityScore` 时，直接从 `t_counter` 读取 `like/comment/collection` 三类计数。

其中点赞计数已知的权威源是 analytics 的 Set（`myxhs:like:note:{noteId}`），`t_counter` 需要依赖 counter 服务异步消费与后续对账修正。特征提取若在对账前执行，会把滞后计数固化到 `t_item_feature.quality_score`。

## 证据

1. `RecommendComputeJob.enrichEngagementCounts()` 查询 `t_counter`：`RecommendComputeJob.java:337-340`。
2. 质量分直接使用这些计数：`RecommendComputeJob.java:363-368`。
3. counter 服务明确说明 analytics 的 like Set 才是权威源，counter 漂移时以 analytics 为准并通过 `reconcileLikeFromAnalytics()` 修正：`CounterService.java:341-388`。

## 触发条件

1. 点赞/取消点赞后，counter 异步消费或对账尚未收敛。
2. 此时 `recommendFeatureJob` 执行特征提取或更新。

## 影响

1. `qualityScore` 会以滞后互动数据计算，推荐排序与召回质量偏差。
2. 由于特征表是离线结果，误差会持续到下一次重算，而不是像 ES 那样很快被增量修回。
3. 与 F-012 一样，本质是“权威源和衍生源混用”的问题，只是落点从搜索索引变成推荐特征。

## 修复建议

1. 推荐特征提取应对点赞计数统一读权威源（analytics Set / 对账后的稳定表），避免直接信任可能滞后的 `t_counter`。
2. 或在特征任务运行前先显式执行/等待计数对账，再抽取特征。
3. 把 `qualityScore` 的计算拆成可重放逻辑，支持在计数收敛后局部重算。

## 是否需要补充验证

需要构造“点赞后立即跑特征任务”的场景，对比 `t_counter`、analytics Set 和 `t_item_feature.quality_score` 的结果差异。