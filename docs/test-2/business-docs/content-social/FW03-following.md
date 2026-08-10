# FW03: 关注列表 — GET /api/social/following/{userId}

## § 源码分析

- **Controller**: `FollowController.java:69` → `GET /following/{userId}`, 参数 `@PathVariable Long userId`
- **Service**: `SELECT f.following_id FROM t_follow WHERE follower_id=? AND deleted=0` → LEFT JOIN t_user获取用户信息

## § 业务逻辑

查询关注的人的列表 → 返回List<UserVO>(userId/username/avatar)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 公开接口 | 无需登录 | 无需 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "/api/social/following/{id}?page=1&size=20"` | 200, {total,list} |
| Redis | `r.scard('myxhs:following:{userId}')` | = total |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 分页, size≤20 | ✅ |
| 安全 | 公开接口, 无鉴权 | ✅ |
| 并发 | 只读操作, 无锁 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/social/following/123456" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
GET /api/social/following/{userId}?page=1&size=20
  → FollowController.getFollowing(userId)
  → Redis ZREVRANGE myxhs:following:{userId} → 关注列表
  → MySQL LEFT JOIN t_user 补充用户信息
  → 返回 List<UserVO>
```
