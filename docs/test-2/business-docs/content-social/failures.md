# my-xhs-content-social 已知故障与陷阱

## 一、SSE跨实例channel不一致bug

- **现象**: 通知推送跨实例不到达 —— 发布笔记→通知粉丝, 但某些实例的SSE客户端收不到
- **根因**: 写操作使用 `myxhs:notification:sse:channel:{userId}`, 读操作使用 `notify:sse:channel:{userId}` — Redis key不一致导致跨实例消息投递失败
- **修复**: 统一为 `myxhs:notification:sse:channel:{userId}`
- **涉及**: content-social发布笔记→MQ FEED_TOPIC→FeedConsumer→SSE推送→notification服务channel

## 二、RateLimit超限

- **现象**: POST 返回 429 Too Many Requests / "操作过于频繁"
- **根因**: @RateLimit 注解窗口内超频 —— Redis Lua脚本 INCR+TTL 原子窗口
- **受影响端点**: NC01(5/60s), NC09(20/60s), NC10(10/60s), CM01(10/60s), FA01(30/min), FA02(30/min), LK01(30/min), LK02(30/min), FW01(20/min), FW02(20/min)
- **应对**: 客户端退避1s+重试, 批量操作间隔>2s

## 三、analytics缺management.admin-token

- **现象**: NC01 publishNote 调用 analytics 服务(DFA审核/统计) → 返回403
- **根因**: analytics 服务 `@Value("${management.admin-token}")` 占位符未配置(非标准 `myxhs.admin.token`)
- **修复**: JVM参数添加 `-Dmanagement.admin-token=my-xhs-admin-token-2026`
- **验证**: `ps aux | grep content-social | grep management.admin-token`

## 四、SOCIAL_TOPIC双消费者

- **现象**: 点赞后 like_count +2 (计数翻倍) / 取关后 follower_count 异常
- **根因**: CounterEventConsumer + FeedConsumer 同时监听 SOCIAL_TOPIC → 双重 UPDATE like_count
- **应对**:
  - CounterEventConsumer → 独占计数(like/comment/favorite/collect/follow 的 +1/-1)
  - FeedConsumer → 仅做 Feed 递送(收发件箱构建, 不操作count字段)
- **去重**: Redis SETNX `msg:{msgId}` EX 86400 处理消息重复投递

## 五、游标分页 vs offset

- **现象**: 评论列表翻页 ← 第2页重复出现第1页刚刷新出来的评论 → 用户看到重复内容
- **根因**: offset 分页: 第1页 `LIMIT 0,10` → 新增1条 → 第2页 `LIMIT 10,10` → 原第1页最后一条变成了第2页第1条
- **修复**: CM03采用游标分页(cursor=last_comment_id) → `WHERE id < cursor ORDER BY id DESC LIMIT size` → 锚定id防漂移
- **CM04 楼中楼同样游标**: `WHERE parent_id=? AND id < cursor ORDER BY id DESC LIMIT size`

## 六、收藏/点赞取消对账

- **现象**: 取消收藏(FA02)时 `DELETE FROM t_favorite WHERE user_id=? AND note_id=?` 影响0行 → 抛异常 "收藏不存在"
- **根因**: 未做幂等处理 —— @Idempotent 只防5s内重复, 但5s后实际已取消时再次调用会报错
- **修复**: FA02 unfavorite 改为 `DELETE WHERE ... AND EXISTS(...)` → 影响0行也返回 ok(幂等)
- **同理**: LK02 unlike 取消点赞时找不到记录 → 幂等返回 ok 不报错
- **FW02 unfollow**: 取消关注时找不到关系 → 幂等返回 ok 不报错

## 代码级缺陷（9 P0 + 16 P1，链6深审 2026-08-09 产出）

### P0-1 ES 索引版本冲突 → Canal增量同步永久失效
- **位置**: `NoteIndexSyncConsumer.java:129-132,252-258` + `IncrementalIndexSyncJob.java:69,253`
- **现象**: Canal用`es`小整数做版本，补偿用毫秒时间戳(~1.7e12)。一旦写入过大版本号，后续Canal消息`12345 < 1.7e12`被ES拒绝。三种写入路径混用两种版本号。
- **修复**: 统一版本号来源——全部用Canal `es`或全部用`ts`毫秒

### P0-2 Like/Favorite消费者版本先写——重试静默丢弃
- **位置**: `LikeUnlikeConsumer.java:69-88` + `FavoriteUnlikeConsumer.java:69-88`
- **现象**: DB insert失败→重试→versionCheck命中→return→数据永久丢失
- **修复**: 先执行业务(DB唯一索引幂等)，成功后写版本号

### P0-3 Feed游标`lastScore-0.001`跳过同分记录
- **位置**: `FeedService.java:82,257`
- **修复**: 用开区间`(lastScore`或`lastScore+noteId`做tie-break

### P0-4 SSE跨实例channel不一致
- **位置**: `SseEmitterManager:60`←`myxhs:...:sse:channel` vs `SseCrossInstanceSubscriber:41`←`notify:sse:channel`
- **修复**: 统一为`myxhs:notification:sse:channel`

### P0-5 FollowService双计数key体系不同步
- **位置**: `FollowService.java:82,99,434`
- **现象**: analytics和counter维护两套独立key，仅每小时对账同步
- **修复**: 统一为单计数key体系

### P0-6 Like/Favorite syncSend超时回滚→Redis与MySQL不一致
- **位置**: `LikeService.java:96-104` + `FavoriteService.java:73-79`
- **现象**: MQ超时判失败回滚Redis→Broker可能已消费落库→Redis无DB有

### P0-7 Gateway信任X-Forwarded-For→热搜反作弊可绕过
- **位置**: `RateLimiterConfig:33` + `TrafficColoringFilter:152` + `HotSearchService:156`
- **修复**: Gateway set()覆盖XFF，同X-User-Id处理方式

### P0-8 Feed大V发件箱只取前500关注
- **位置**: `FeedService.java:298`（`reverseRange(followingKey, 0, 499)`)
- **修复**: 改游标分批取完

### P0-9 NOTE_DELETE无人消费→删除笔记残留Feed
- **位置**: `NoteService.java:294`→`SOCIAL_TOPIC:NOTE_DELETE`无消费者
- **修复**: 新增消费者ZREM清理inbox/outbox

### P1重要问题(12项)
| # | 服务 | 位置 | 问题 |
|:--:|------|------|------|
| 1 | content | NoteService:100 | businessMetrics在事务内，回滚误记 |
| 2 | content | NoteService:334 | VIEW事件逐请求无聚合 |
| 3 | analytics | FollowService:330 | ZINTER全量交集回客户端 |
| 4 | analytics | FollowService:101 | Step B失败不补偿 |
| 5 | counter | CounterController:58 | batch-get无上限 |
| 6 | counter | CounterBuffer:219 | 刷盘重试3次后丢弃 |
| 7 | home | HomeController:51-137 | 全部接口无@RateLimit |
| 8 | home | UserProfileAggService:140 | 计数恒为0 |
| 9 | home | FeedCleanupJob:59 | count在循环体内声明限流失效 |
| 10 | search | IndexRebuildJob:331-367 | ES doc字段全0或空 |
| 11 | search | RecommendService:388 | getUserPreferenceScore key错误 |
| 12 | search | SearchController全覆盖 | 无@RateLimit |
