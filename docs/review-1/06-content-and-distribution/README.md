# 06-content-and-distribution

## 目标

审查内容发布、删除、互动、分发、搜索索引、统计聚合之间的时序与一致性。

## 重点服务

- `my-xhs-content`
- `my-xhs-home`
- `my-xhs-search`
- `my-xhs-counter`
- `my-xhs-analytics`

## 重点问题

1. 发布、删除、点赞、关注是否在异步传播时出现脏读
2. ES、Redis、数据库谁是权威源是否始终一致
3. 计数、热度、推荐排序是否依赖异步中的非权威数据
4. 删除与晚到消息是否会把脏数据重新写回
5. 推荐、分页、已读集是否可能丢失或重复

## 预期证据

- publish/delete/like/follow 消费链路
- ES 同步消费者
- home feed 聚合与 merge 逻辑
- counter 与 analytics 的职责边界
- 缓存 key 与过期策略

## 初步产出建议

- `source-of-truth.md`
- `index-sync.md`
- `delete-propagation.md`
- `feed-merge-and-pagination.md`
- `counter-vs-analytics.md`