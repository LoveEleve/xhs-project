# H04 — 用户主页聚合 (GET /api/home/user/{targetUserId})

> 2026-08-08 | 阶段14-11 | home服务 | testuser(10001)

## ASCII 流转图

```
curl → Gateway:19000 (JWT→HMAC→路由)
       → my-xhs-home:19015 (GET /api/home/user/{targetUserId})
         → CompletableFuture.supplyAsync (aggregatorPool)
           → userProfileAggService.getUserProfile(targetUserId, userId)
             ├── Feign: user服务 → 用户信息(nickname/avatar/bio)
             ├── Feign: counter服务 → following/follower/likeCount/noteCount
             ├── Feign: social服务 → isFollowing/isFollowBack/isMutual
             └── Feign: content服务 → notes列表(最新)
```

## 业务逻辑

聚合用户主页：基本信息 + 社交计数 + 当前用户的社交关系(关注/互关) + 该用户最新笔记列表。未登录用户社交关系全 false。

## 七层验证

| 层 | 状态 | 验证内容 |
|------|:--:|------|
| HTTP | ✅ | 200 OK, X-Trace-Id captured |
| 用户 | ✅ | userId=10001, nickname="测试用户A" |
| 社交 | ✅ | isFollowing=false (mytestuser→testuser 未关注) |
| 计数 | ✅ | followingCount=0, followerCount=0 |

## 执行命令

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i "http://localhost:19000/api/home/user/10001" \
  -H "Authorization: Bearer $TOKEN"
```

## 响应

```json
{"code":200,"data":{
  "userId":10001,"nickname":"测试用户A",
  "followingCount":0,"followerCount":0,"noteCount":0,
  "isFollowing":false,"isFollowBack":false,"isMutual":false,
  "notes":[]
}}
```
