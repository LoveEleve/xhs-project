# FW05: 共同关注 — GET /api/social/common/{targetUserId}

## § 源码分析

- **Controller**: `FollowController.java:101` → `@GetMapping("/common/{targetUserId}")`, 参数 `X-User-Id` + `@PathVariable Long targetUserId`
- **Service**: 取当前用户关注列表 + 目标用户关注列表 → 交集 `INTERSECT`

## § 业务逻辑

查询两个人共同关注的人 → 返回交集列表

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "/api/social/common/{targetUserId}"` | 200, [userIds] |
| Redis | `r.sinter('myxhs:following:{id}','myxhs:following:{targetId}')` | 交集 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | SINTER O(N*M) | ✅ |
| 安全 | 已登录X-User-Id | ✅ |
| 并发 | 只读操作, 无锁 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/social/common/123456" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
GET /api/social/common/{targetUserId}
  → FollowController.getCommonFollowing(currentUserId, targetUserId)
  → Redis SINTER myxhs:following:{currentUserId} myxhs:following:{targetUserId}
  → MySQL LEFT JOIN t_user 补充用户信息
  → 返回 List<UserVO>(共同关注列表)
```


