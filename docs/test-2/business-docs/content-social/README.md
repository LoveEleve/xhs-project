# my-xhs-content-social 内容社交服务

> 5 Controller | 34端点 | X-User-Id Header | 10个@RateLimit | 2个@Idempotent | RocketMQ SOCIAL_TOPIC | CounterEventConsumer 10种事件

---

## 架构概览

```
端口: 19012
JVM:  -Xms256m -Xmx256m, -Dmanagement.admin-token=xxx
Nacos: namespace=my-xhs, server-addr=21.130.247.89:18848
SkyWalking: my-xhs-content-social → OAP 21.130.247.89:11800

Controller:
  NoteController    → /api/note/* (10端点)  L41-L154
  CommentController → /api/comment/* (6端点) L38-L112
  FavoriteController→ /api/social/favorite/* (4端点) L38-L81
  LikeController   → /api/social/like/* (5端点) L42-L110
  FollowController → /api/social/* (9端点) L36-L147

MQ 生产者: SOCIAL_TOPIC + FEED_TOPIC → RocketMQ
MQ 消费者: CounterEventConsumer(10种事件异步计数) | FeedConsumer(粉丝收件箱递送)
```

## 端点清单

### NoteController /api/note (NC01-NC10)

| ID | 方法 | 路径 | Line | RateLimit | 说明 |
|------|------|------|:--:|------|------|
| NC01 | POST | `/api/note/publish` | L41 | 60s/5次 | 发布笔记→MQ FEED_TOPIC |
| NC02 | POST | `/api/note/draft` | L57 | — | 保存草稿 |
| NC03 | PUT | `/api/note/{id}` | L68 | — | 编辑笔记 |
| NC04 | DELETE | `/api/note/{id}` | L80 | — | 删除笔记(软删除) |
| NC05 | GET | `/api/note/detail/{id}` | L91 | — | 笔记详情(公开) |
| NC06 | GET | `/api/note/user/{userId}` | L99 | — | 用户笔记列表(公开) |
| NC07 | GET | `/api/note/my` | L110 | — | 我的笔记(含草稿) |
| NC08 | POST | `/api/note/{id}/publish` | L122 | — | 发布草稿 |
| NC09 | POST | `/api/note/upload/image` | L137 | 60s/20次 | 上传图片 |
| NC10 | POST | `/api/note/{id}/share` | L154 | 60s/10次 | 分享笔记 |

### CommentController /api/comment (CM01-CM06)

| ID | 方法 | 路径 | Line | RateLimit | 说明 |
|------|------|------|:--:|------|------|
| CM01 | POST | `/api/comment` | L38 | 60s/10次 | 发表评论 |
| CM02 | DELETE | `/api/comment/{id}` | L55 | — | 删除评论 |
| CM03 | GET | `/api/comment/list/{noteId}` | L74 | — | 评论列表(游标分页) |
| CM04 | GET | `/api/comment/children/{parentId}` | L92 | — | 子评论(楼中楼) |
| CM05 | GET | `/api/comment/count/{noteId}` | L103 | — | 评论数(公开) |
| CM06 | GET | `/api/comment/page/{noteId}` | L112 | — | 评论分页(备用) |

### FavoriteController /api/social/favorite (FA01-FA04)

| ID | 方法 | 路径 | Line | 注解 | 说明 |
|------|------|------|:--:|------|------|
| FA01 | POST | `/api/social/favorite` | L38 | RateLimit 30/min + Idempotent 5s | 收藏笔记 |
| FA02 | DELETE | `/api/social/favorite` | L53 | RateLimit 30/min | 取消收藏 |
| FA03 | GET | `/api/social/favorite/status` | L68 | — | 收藏状态 |
| FA04 | GET | `/api/social/favorite/list` | L81 | — | 收藏列表 |

### LikeController /api/social/like (LK01-LK05)

| ID | 方法 | 路径 | Line | 注解 | 说明 |
|------|------|------|:--:|------|------|
| LK01 | POST | `/api/social/like` | L42 | RateLimit 30/min + Idempotent 5s | 点赞 |
| LK02 | DELETE | `/api/social/like` | L57 | RateLimit 30/min | 取消点赞 |
| LK03 | GET | `/api/social/like/status` | L73 | — | 点赞状态 |
| LK04 | GET | `/api/social/like/batch-status` | L90 | — | 批量点赞状态(Pipeline) |
| LK05 | GET | `/api/social/like/count` | L110 | — | 点赞数(公开) |

### FollowController /api/social (FW01-FW09)

| ID | 方法 | 路径 | Line | 注解 | 说明 |
|------|------|------|:--:|------|------|
| FW01 | POST | `/api/social/follow/{targetUserId}` | L36 | RateLimit 20/min | 关注用户 |
| FW02 | DELETE | `/api/social/follow/{targetUserId}` | L52 | RateLimit 20/min | 取消关注 |
| FW03 | GET | `/api/social/following/{userId}` | L69 | — | 关注列表(公开) |
| FW04 | GET | `/api/social/follower/{userId}` | L86 | — | 粉丝列表(公开) |
| FW05 | GET | `/api/social/common/{targetUserId}` | L101 | — | 共同关注 |
| FW06 | GET | `/api/social/relation/{targetUserId}` | L113 | — | 关注关系 |
| FW07 | GET | `/api/social/follower/count/{userId}` | L131 | — | 粉丝数(公开) |
| FW08 | GET | `/api/social/following/count/{userId}` | L139 | — | 关注数(公开) |
| FW09 | POST | `/api/social/internal/repair-counter/{userId}` | L147 | Admin-Call | 对账修复计数 |

## MySQL表(全部在my_xhs库)

| 表 | 说明 | 关键字段 |
|------|------|------|
| t_note | 笔记 | id, user_id, title, content, images, tags, category, status(DRAFT/PUBLISHED), is_deleted, like_count, collect_count, comment_count, share_count |
| t_comment | 评论 | id, note_id, user_id, parent_id, root_id, content, is_deleted, like_count |
| t_favorite | 收藏 | id, user_id, note_id, created_at |
| t_like | 点赞 | id, user_id, target_type(NOTE/COMMENT), target_id |
| t_follow | 关注 | user_id(关注者), follower_id(被关注者) |
| t_user | 用户 | id, nickname, avatar, following_count, follower_count |

## MQ

```
生产者: NoteController/LikeController/CommentController/FavoriteController/FollowController
  → RocketMQTemplate.send("SOCIAL_TOPIC", EventMessage{type, noteId, userId, targetUserId})

消费者: CounterEventConsumer → 处理10种事件:
  NOTE_PUBLISH → Feed更新 | NOTE_DELETE → Feed清理
  COMMENT_CREATE → comment_count+1 | COMMENT_DELETE → comment_count-1
  LIKE_NOTE → like_count+1 | UNLIKE_NOTE → like_count-1
  LIKE_COMMENT → t_comment.like_count | UNLIKE_COMMENT → t_comment.like_count
  FAVORITE → collect_count+1 | UNFAVORITE → collect_count-1

幂等: Redis SETNX msg:{msgId} 24h去重

FEED_TOPIC (NC01/NC08):
  → FeedConsumer → SELECT follower_id FROM t_follow → INSERT t_user_feed_inbox → Redis ZADD feed:timeline
```

## @RateLimit分布

| 端点 | key | 窗口 | 次数 |
|------|------|------|:--:|
| NC01 publish | note:publish | 60s | 5 |
| NC09 upload | note:upload | 60s | 20 |
| NC10 share | note:share | 60s | 10 |
| CM01 create | comment:create | 60s | 10 |
| FA01 favorite | favorite:create | 60s | 30 |
| FA02 unfavorite | favorite:delete | 60s | 30 |
| LK01 like | like:create | 60s | 30 |
| LK02 unlike | like:delete | 60s | 30 |
| FW01 follow | follow:create | 60s | 20 |
| FW02 unfollow | follow:delete | 60s | 20 |

## @Idempotent分布

| 端点 | 窗口 |
|------|:--:|
| FA01 favorite | 5s |
| LK01 like | 5s |
