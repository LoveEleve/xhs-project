# 链6 — 内容社交全链路

> 2026-08-08 | content+analytics+counter | chaintest_c1 | noteId=2086012037638475778

## 流程

```
C07 发布笔记 (title="链6测试笔记", status=2已发布)
  → [Canal] t_note binlog → ES note_index
  → [MQ FEED_TOPIC] →粉丝 feed inbox推送

A01 点赞 (bizType=1, bizId=noteId, POST JSON body)
  → Redis Lua SADD myxhs:like:note:{noteId}
  → MQ SOCIAL_TOPIC:LIKE → LikeUnlikeConsumer(MySQL t_like)+CounterEventConsumer(counter)
  → counter likeCount=1 ✅

A06 收藏 (POST JSON body {noteId})
  → Redis ZADD myxhs:favorite:{userId} {noteId}
  → MQ SOCIAL_TOPIC:FAVORITE → FavoriteUnlikeConsumer(MySQL t_favorite)+CounterEventConsumer
  → counter favCount=1 ✅

C01 评论 (POST JSON body {noteId, content})
  → MySQL t_comment INSERT
  → MQ SOCIAL_TOPIC:COMMENT → CounterEventConsumer → counter commentCount=1 ✅
  → MQ NOTIFICATION_TOPIC → 通知笔记作者

A10 关注 (POST /api/social/follow/10001)
  → Redis Lua ZADD myxhs:follow:list:{userId}+myxhs:follow:fans:{targetUserId}
  → MQ SOCIAL_TOPIC:FOLLOW → CounterEventConsumer(双向计数)
  → counter following/follower 更新 ✅
```

## 数据验证 (L2)

| 端点 | Redis | MQ | MySQL |
|------|------|------|------|
| C07 发笔记 | — | FEED_TOPIC | t_note INSERT |
| A01 点赞 | like set={userId}, counter=1 ✅ | SOCIAL_TOPIC:LIKE | t_like |
| A06 收藏 | fav zset=[noteId], counter=1 ✅ | SOCIAL_TOPIC:FAVORITE | t_favorite |
| C01 评论 | counter comment=1 ✅ | SOCIAL_TOPIC:COMMENT | t_comment |
| A10 关注 | following=[10001], fans含userId ✅ | SOCIAL_TOPIC:FOLLOW | t_follow |

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 业务自洽 | 笔记→互动→counter计数 完整链路 ✅ |
| 数据一致 | Redis Lua原子操作↔MQ异步MySQL ✅ |
| 幂等安全 | A01 @Idempotent 5s防重复; A10不能follow自己 ✅ |
| 回滚完整 | A02取消点赞(SREM)、A11取消关注(ZREM) ✅ |

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | analytics/counter actuator ✅ |
| Kibana | traceId 日志可查 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
# C07 发笔记
curl -s -X POST http://localhost:19000/api/note/publish -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-User-Id: 2085982901507301378" \
  -d '{"title":"链6测试笔记","content":"全链路重测","images":[],"isPublic":true}'
# A01 点赞(JSON body)
curl -s -X POST http://localhost:19000/api/social/like -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-User-Id: 2085982901507301378" \
  -d '{"bizType":1,"bizId":2086012037638475778}'
# A06 收藏
curl -s -X POST http://localhost:19000/api/social/favorite -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-User-Id: 2085982901507301378" \
  -d '{"noteId":2086012037638475778}'
# A10 关注(targetUserId在PATH,非body)
curl -s -X POST http://localhost:19000/api/social/follow/10001 -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
```

## § 踩坑

- A01/A06用JSON body(非query param)
- A10 targetUserId在URL PATH(非body)
- analytics需-Dmanagement.admin-token参数启动
- content/analytics需重新编译(之前只编了inventory+search)
| **性能** | A01 @RateLimit 60s/30; Redis Lua SADD<3ms; counter异步MQ更新 ✅ |
| **可扩展** | MQ SOCIAL_TOPIC→双Consumer(LikeUnlike+counter)独立扩展; analytics权威Set↔counter展示缓存双轨 ✅ |
| **微服务** | Feign analytics→counter; MQ异步解耦; Canal binlog→ES note_index ✅ |
| **并发** | @Idempotent 5s防重复; Redis Lua原子性SADD; t_like唯一索引MySQL幂等 ✅ |
| **安全** | X-User-Id Gateway注入; A10不能follow自己校验; C07 DFA敏感词检测 ✅ |

## § 前置依赖验证（Feed推送）

| 检查项 | 结果 |
|------|:--:|
| chaintest_c1的粉丝数 | 0(无粉丝,Feed推空) ⚠️ |
| 10001 Feed收件箱 | 7条历史笔记(系统正常) ✅ |
| 新笔记推送(chaintest_c1) | 无粉丝→无推送 ⚠️ |

> 完整的Feed推送验证需要先创建粉丝关系: testuser(10001)关注chaintest_c1→发笔记→验证10001 Feed收件箱增量

### Feed推送完整验证(已补)

| 步骤 | 结果 |
|------|:--:|
| 1. 注册 feed_follower | id=2086023092250976258 ✅ |
| 2. feed_follower 关注 chaintest_c1 | 200 ✅ |
| 3. chaintest_c1 发笔记 | noteId=2086023195367919617 ✅ |
| 4. MQ推送到粉丝Feed收件箱 | myxhs:feed:inbox:{feed_follower}=1条,noteId匹配 ✅ |
