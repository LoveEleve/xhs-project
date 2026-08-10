# my-xhs-content-social 业务逻辑分析

## 一、笔记审核状态机

```
saveDraft(NC02) → INSERT t_note(status=0 草稿, title/content可空)
  ├─→ publishNote(NC01) → DFA敏感词检测 → INSERT t_note(status=1 已发布) + MQ FEED_TOPIC
  └─→ publishDraft(NC08) → SELECT t_note WHERE id=? AND user_id=? AND status=0
        → 补充完整字段 → UPDATE t_note SET status=1, title=?, content=?
        → status!=1 → 抛异常 "已发布不能通过草稿发布"

发布: DFA敏感词检测(title/content/images) → 命中 → BizException "内容包含敏感词: [word1, word2]"
公开可见: status=1 + is_deleted=0 → 查询接口(NC05/NC06)仅返回已发布+未删除

状态流转:
  草稿(0) → 发布 → 已发布(1) → 逻辑删除(2)
```

## 二、游标分页

```
评论列表(CM03-comment-list)用游标非offset:

请求: GET /api/comment/list/{noteId}?cursor=999&size=10
  → 参数: cursor(上页最后一条评论ID) + size(默认10)
  → SQL: SELECT * FROM t_comment WHERE note_id=? AND parent_id IS NULL
          AND id < #{cursor} ORDER BY id DESC LIMIT #{size}
  → nextCursor: 结果最后一条的id

首次请求: cursor传当前时间戳(System.currentTimeMillis()) → 拉最新评论

游标 vs offset:
  offset问题: 翻页时新增数据→重复出现(第1页item#11变成第2页item#1)
  游标解决: 用id作为锚点, 新增数据id>cursor不干扰已翻过的页

楼中楼(CM04): GET /api/comment/children/{parentId}?cursor=999&size=10
  → SQL: SELECT * FROM t_comment WHERE parent_id=? AND id < #{cursor}
          ORDER BY id DESC LIMIT #{size}
```

## 三、@RateLimit 全注解分布

| 端点 | 限制 | key | 方法 | 路径 |
|------|:----:|------|------|------|
| NC01 publishNote | 60s/5次 | note:publish | POST | /api/note/publish |
| NC09 uploadImage | 60s/20次 | note:upload | POST | /api/note/upload/image |
| NC10 shareNote | 60s/10次 | note:share | POST | /api/note/{id}/share |
| CM01 createComment | 60s/10次 | comment:create | POST | /api/comment |
| FA01 favorite | 30/min | favorite:create | POST | /api/social/favorite |
| FA02 unfavorite | 30/min | favorite:delete | DELETE | /api/social/favorite |
| LK01 like | 30/min | like:create | POST | /api/social/like |
| LK02 unlike | 30/min | like:delete | DELETE | /api/social/like |
| FW01 follow | 20/min | follow:create | POST | /api/social/follow/{targetUserId} |
| FW02 unfollow | 20/min | follow:delete | DELETE | /api/social/follow/{targetUserId} |

实现: 基于Redis Lua脚本 `INCR + TTL` 原子窗口, 超限 → 429 "操作过于频繁"

## 四、@Idempotent 分布

| 端点 | 窗口 | 说明 |
|------|:----:|------|
| FA01 favorite | 5s | 防重复收藏: Redis SETNX `idempotent:favorite:{userId}:{noteId}` ttl=5s |
| LK01 like | 5s | 防重复点赞: Redis SETNX `idempotent:like:{userId}:{targetType}:{targetId}` ttl=5s |

窗口内相同参数重放 → 直接返回成功(不重复执行DB操作)。@Idempotent通过AOP切面实现, 方法注解指定keySpEL表达式。

## 五、MQ SOCIAL_TOPIC 消费者

```
CounterEventConsumer 处理10种事件:

生产者 → RocketMQ SOCIAL_TOPIC → CounterEventConsumer
  NOTE_PUBLISH     → Feed更新 (INSERT t_user_feed_inbox)
  NOTE_DELETE      → 清理Feed (DELETE t_user_feed_inbox WHERE note_id=?)
  COMMENT_CREATE   → UPDATE t_note SET comment_count=comment_count+1 WHERE id=?
  COMMENT_DELETE   → UPDATE t_note SET comment_count=comment_count-1 WHERE id=?
  LIKE_NOTE        → UPDATE t_note SET like_count=like_count+1 WHERE id=?
  UNLIKE_NOTE      → UPDATE t_note SET like_count=like_count-1 WHERE id=?
  LIKE_COMMENT     → UPDATE t_comment SET like_count=like_count+1 WHERE id=?
  UNLIKE_COMMENT   → UPDATE t_comment SET like_count=like_count-1 WHERE id=?
  FAVORITE         → UPDATE t_note SET collect_count=collect_count+1 WHERE id=?
  UNFAVORITE       → UPDATE t_note SET collect_count=collect_count-1 WHERE id=?

幂等: Redis SETNX msg:{msgId} EX 86400 → 已处理过的MQ消息跳过不重复执行

双消费者注意: 计数与Feed递送分离
  CounterEventConsumer → 独占计数(like/comment/favorite/collect)
  FeedConsumer → 仅做Feed递送(收发件箱构建)
```

## 六、DFA敏感词检测

```
发布/评论前:
  → DFAWordFilter.search(content) → List<String> 命中敏感词
  → 非空 → BizException "内容包含敏感词: [word1, word2]"
  → 空 → 继续流程

publishNote(NC01): @Valid → title/content/images均校验
saveDraft(NC02): 无@Valid → 允许不完整, 不做DFA(草稿可含敏感词)
```

## 七、Feed收件箱构建

```
发布时(同步):
  → 扫描粉丝列表: SELECT follower_id FROM t_follow WHERE user_id=?
  → 批处理: INSERT INTO t_user_feed_inbox(user_id, note_id, push_time) VALUES ...

关注时:
  → 扫描被关注者已发布笔记 → ZADD feed:timeline:{followerId} pushTime noteId

取关时:
  → ZREM feed:timeline:{formerFollowerId} <noteIds>

收件箱读取: Redis ZSET feed:timeline:{userId} + 断点续推(cursor)
```
