# 06-content-and-distribution / 搜索推荐补充审查

## 已确认

### F-012：NoteIndexSyncConsumer 仍读 counter 旧源

Canal 全量索引读 `myxhs:counter:*`，而点赞权威源是 analytics Set，导致短暂覆盖权威 likeCount。

### F-021：推荐行为消费者吞掉写库异常

`BehaviorReportConsumer` 写 `t_user_behavior` 失败时只记日志不重试，推荐/热门池行为样本静默丢失。

### F-022：增量索引补偿不是原子弹出

`IncrementalIndexSyncJob` 注释写 SPOP，但实际是 `distinctRandomMembers + remove`，多实例下可能重复补偿同一 docId。

## 已复核为暂不升级 finding

1. `FeedPushConsumer` 的已删标记、防误删、断点续推设计基本完整。
2. `SseEmitterManager` 的跨实例推送、心跳续期和连接清理目前没发现新的确定性缺陷。
3. `CounterBuffer` 的双 buffer 和跨代写保护实现相对严谨。

## 继续核查

1. home feed 聚合时计数、用户信息、笔记详情三路降级是否会造成长期脏读。
2. 评论多级嵌套删除是否真的只允许两级结构，避免孤儿评论。
3. 推荐特征提取是否继续读过时的 `t_counter` 作为互动特征源。