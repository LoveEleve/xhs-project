# H01: Feed流 — GET /api/home/feed
## § 源码分析
- **Controller**: `HomeController.java:51` → `@GetMapping("/feed")`, X-User-Id
- **Service**: `FeedService.java:69` → `getFollowFeed()`
  - 大V判定: SISMEMBER `myxhs:user:bigv:{fid}`
  - 小V: ZREVRANGE `myxhs:feed:inbox:{uid}` 按score倒序
  - 大V: 遍历关注列表→ZREVRANGE各 `myxhs:feed:outbox:{fid}` → 混合排序
  - Feign: content(getNoteDetail)/user(getUserPublicInfo)/counter(batchGetCounts)
- **下游**: Redis inbox/outbox + Feign×3

## § 业务逻辑
取关注列表→大V实时拉取outbox+小V预推inbox→混合排序→并行Feign取笔记详情+用户信息+计数→返回Feed列表(分页lastScore)

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Token已登录 | `cat /tmp/test_token.txt` | 401 |
| 关注列表非空 | `redis SMEMBERS myxhs:follow:list:{uid}` | Feed为空正常 |

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/home/feed?lastScore=&size=20` | 200, noteList |
| Redis | `ZCARD myxhs:feed:inbox:{uid}` | 收件箱大小 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 大V拉取vs小V推送双模式 | ✅ |
| 弹性 | Feign failback降级 | ✅ |

## § curl
```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/home/feed?size=10" -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图
```
GET /home/feed → Feign analytics(following list)
  → Redis: SMEMBERS myxhs:user:bigv 判定大V
  → 小V: ZREVRANGE inbox → 直接取
  → 大V: ZREVRANGE outbox of each bigV → 混合
  → 并行Feign: content(noteDetail) + user(authorInfo) + counter(counts)
  → 排序返回
```
