# F-012 笔记索引同步仍读 counter 旧计数源，会覆盖点赞权威计数

## 严重度

Medium

## 涉及文件

- `my-xhs-search/src/main/java/com/myxhs/search/consumer/NoteIndexSyncConsumer.java:171-179`
- `my-xhs-search/src/main/java/com/myxhs/search/consumer/NoteIndexSyncConsumer.java:234-236`
- `my-xhs-search/src/main/java/com/myxhs/search/consumer/LikeCountSyncConsumer.java:58-60`

## 现象

点赞计数的权威源已经被明确为 analytics 的 Set `myxhs:like:note:{noteId}`（SCARD，同步写入、无消费时序竞争）。但 `NoteIndexSyncConsumer` 在 Canal 触发的全量索引时，仍然从旧的 counter key `myxhs:counter:1:{noteId}:1` 读取 likeCount 写入 ES。

两条路径读不同的源，会在笔记内容变更时用旧计数覆盖权威计数。

## 证据

1. `LikeCountSyncConsumer.java:54-60`（T-092 修正）读 analytics 权威 Set：
   ```java
   String likeSetKey = "myxhs:like:note:" + bizId;
   Long likeSetSize = stringRedisTemplate.opsForSet().size(likeSetKey);
   ```
2. `NoteIndexSyncConsumer.getCounterValue()` 构造的是 counter key：`NoteIndexSyncConsumer.java:174`。
3. `indexNoteFromCanal()` 用 `getCounterValue(1, noteId, 1)` 读 likeCount 并全量写 ES：`NoteIndexSyncConsumer.java:234`。
4. `LikeCountSyncConsumer` 的注释明确说明旧 counter key 存在"search 与 counter 并行消费同一 LIKE 消息，counter 写入前 search 读到旧值（实测 likeCount 更新为 0）"的竞争：`LikeCountSyncConsumer.java:54-57`。

## 触发条件

1. 用户点赞，analytics 同步 SADD `myxhs:like:note:{id}`，`LikeCountSyncConsumer` 正确 partial update ES likeCount。
2. 之后笔记标题/内容等字段发生变更，触发 Canal → `NOTE_INDEX_TOPIC`。
3. `NoteIndexSyncConsumer` 用 counter key 的滞后值重写整篇 ES 文档，覆盖权威 likeCount。

## 影响

1. ES 的 likeCount 可能被 counter key 的滞后值短暂覆盖，`sort=hot` 热榜排序在窗口内退化。
2. 由于 counter 服务有 `reconcileLikeFromAnalytics()`（`CounterService.java:346`）以 analytics 权威 Set 修正 counter key，且 T-113 同步 DB，counter key 最终收敛到权威值，覆盖通常是短暂滞后而非永久。
3. 两套计数源并存，collectCount/commentCount 也存在同样的源选择不一致风险。

## 修复建议

1. `NoteIndexSyncConsumer` 的 likeCount 统一改读 `myxhs:like:note:{noteId}` SCARD，与 `LikeCountSyncConsumer` 对齐。
2. 明确 collect/comment 的权威源，避免 canal 全量索引与 partial update 使用不同源。
3. 若 canal 索引必须带计数，建议只写内容字段，计数由专门的计数消费者负责，或索引前以同一权威源为准。
4. 补充回归：点赞后编辑笔记，断言 ES likeCount 不被 canal 索引回退。

## 残余风险

即使统一了读源，Canal 全量索引与 partial update 之间的写覆盖仍需要版本/字段粒度控制；当前 full index 会重写整个文档，仍可能覆盖其他消费者的 partial 字段。

## 是否需要补充验证

需要构造"点赞 → 编辑笔记"顺序，观察 ES likeCount 是否被 `NoteIndexSyncConsumer` 用 counter 旧值回退。