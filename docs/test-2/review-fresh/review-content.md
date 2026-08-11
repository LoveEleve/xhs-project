# Content 模块 Review

## 分布式 / 消息
1. **[中] publishNote/publishDraft 双投递机制** (NoteService.java:102-153, 417-458)
   同时使用"本地消息表(M2)" + "直接 asyncSend FEED_TOPIC"。直接发送成功但 onSuccess 未 markSent
   （进程在回调前崩溃）时，FeedMessageRetryJob 会补发 → 同一事件投递 2 次。
   **缓解**：Feed ZADD 幂等（FeedMessageRetryJob 注释明确），重复推送无副作用。
   结论：安全但机制冗余；建议未来收敛为单一可靠性通道（本地表 or 事务消息）。

2. **[中] 补偿任务依赖"分布式锁"是 Redis SETNX 自实现** (FeedMessageRetryJob.java:60-62)
   非 Redisson，锁 TTL=55s，任务执行若超过 55s 锁自动过期 → 多实例可能并发补发。
   依赖下游幂等兜底。可接受，但需知悉。

3. **[低] getNoteDetail 缓存命中时仍每请求发 VIEW MQ** (NoteService.java:334)
   高 QPS 下 VIEW 事件量巨大；属设计取舍（精确计数），但需评估 MQ 容量。

## 缓存
4. **[中] NOTE_LIST_USER 只删不填（缓存死键）** (NoteService.java:128, 258, 289; getUserNotes:344)
   getUserNotes/getMyNotes 读路径不做 CacheAside 回填，仅各处 delayDoubleDelete 删除该 key。
   该缓存键实际从不命中，延迟双删是无效开销。要么补读回填，要么移除缓存键。

## 业务逻辑
5. **[低] DFA detect 为 O(n²)** (DFAFilter.java:227-244) 且同步执行于发布路径；
   短文本可接受，长文/大词库需评估。preprocess 去空格/全角转半角/小写，防绕过到位。
6. **[低] getNoteDetail 对非 PUBLISHED 笔记缓存空值 30min** (NoteService.java:318-322)
   靠 publishDraft 显式 delayDoubleDelete 兜底（已有处理，OK）。

## 工程 / 文件上传
7. **[低] verifyMagicBytes 用 file.getBytes() 全量读 5MB** (LocalFileStorageService.java:106)
   仅需前 12 字节；5MB 上限下每请求内存峰值 5MB，高并发上传需关注 GC。建议改用 InputStream 读前 12 字节。
8. 上传防护良好：content-type 白名单 + 魔数校验 + UUID 文件名 + 固定目录(note) 无路径穿越 + 大小上限。点赞。

## 监控
9. BusinessMetrics.recordFeedPush 统计 publish/cache_hit/cache_miss/json_deser_fail —— 有基础监控埋点。
