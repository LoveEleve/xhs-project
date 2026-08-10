# my-xhs-content-social 架构分析

## 一、服务拓扑

```
端口: 19012
JVM:  -Xms256m -Xmx256m, -Dmanagement.admin-token=xxx
日志: /tmp/r_content_social.log
SkyWalking: my-xhs-content-social → OAP 21.130.247.89:11800
Nacos:     namespace=my-xhs, server-addr=21.130.247.89:18848
MySQL:     my_xhs库 (t_note/t_comment/t_favorite/t_like/t_follow)
Redis:     缓存热门笔记/Feed收件箱(localhost:6379 + Sentinel)
RocketMQ:  producer→SOCIAL_TOPIC→CounterEventConsumer(异步计数)
```

## 二、调用链路

```
写操作(NC01 发布笔记):
  POST /api/note → NoteController.createNote()
    → NoteService.createNote(userId, dto)
      → INSERT t_note (title, content, images, tags, category)
      → RocketMQTemplate.send("SOCIAL_TOPIC", {type:"note_publish", noteId, userId})
        → CounterEventConsumer → Redis INCR note:{id}:views
        → Feed服务(异步) → INSERT t_user_feed_inbox → 粉丝收件箱

读操作(NC02 笔记详情):
  GET /api/note/{id} → NoteController.getNote()
    → NoteService.getNoteDetail(noteId)
      → SELECT * FROM t_note WHERE id=? AND is_deleted=0
      → Redis GET cache:note:{id} → 缓存命中直接返回
      → JOIN t_user → 作者信息
      → 返回 NoteDetailVO + like/collect/comment counts
```

## 三、MQ消息流

```
生产者: NoteController/LikeController/CommentController/FavoriteController/FollowController
  → RocketMQTemplate.convertAndSend("SOCIAL_TOPIC", EventMessage)

消费者: CounterEventConsumer
  ↓
  UPDATE t_note SET like_count=like_count+1 / -1  (点赞/取消)
  UPDATE t_note SET collect_count=collect_count+1 / -1 (收藏/取消)
  UPDATE t_note SET comment_count=comment_count+1 / -1 (评论/删除)
  UPDATE t_comment SET like_count=like_count+1 / -1 (评论点赞)
  UPDATE t_user SET following_count / follower_count (关注/取关)

幂等: MQ messageId去重 → Redis SETNX msg:{msgId} 24h
```

## 四、Feed推送架构

```
发布笔记 → SOCIAL_TOPIC:{type:"note_publish", noteId, userId}
  → FeedConsumer(异步)
    → SELECT follower_id FROM t_follow WHERE user_id=note_author
    → 批处理: INSERT INTO t_user_feed_inbox (user_id, note_id, push_time) VALUES ...
    → Redis ZADD feed:timeline:{follower_id} score=pushTime noteId

拉取Feed:
  GET /api/feed → FeedController(或NoteController.hot)
    → Redis ZREVRANGE feed:timeline:{userId} 0 19 → 最近20条noteId
    → MySQL SELECT * FROM t_note WHERE id IN (...)
    → 按时间倒序返回
```

## 五、Redis Key

```
缓存:
  cache:note:{id}              → NoteDetailVO (1h TTL)
  cache:user:{userId}          → UserVO (30min TTL)
  hot:notes:list               → 热门笔记ID列表 (5min TTL)

Feed:
  feed:timeline:{userId}       → ZSET (noteId → pushTime)
  feed:cursor:{userId}         → 上次拉取位置
```

## 六、限流注解

```
NoteController: @RateLimit(key="note_create", rate=3, window=60)     // NC01 3次/60秒
NoteController: @RateLimit(key="note_search", rate=10, window=60)    // NC08 10次/60秒
CommentController: @RateLimit(key="comment_create", rate=5, window=60)
LikeController: @RateLimit(key="like_note", rate=10, window=60)
FollowController: @RateLimit(key="follow", rate=5, window=60)
```
