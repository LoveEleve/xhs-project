# FW04: 粉丝列表 — GET /api/social/follower/{userId}

## § 源码分析

- **Controller**: `FollowController.java:86` → `GET /follower/{userId}`, 参数 `@PathVariable Long userId`
- **下游**: MySQL t_follow

## § 业务逻辑

查询用户的粉丝列表 → LEFT JOIN t_user获取粉丝用户信息

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 公开接口 | 无需登录 | 无需 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "/api/social/follower/{id}?page=1&size=20"` | 200, {total,list} |
| Redis | `r.scard('myxhs:follower:{userId}')` | = total |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 分页, size≤20 | ✅ |
| 安全 | 公开接口, 无鉴权 | ✅ |
| 并发 | 只读操作, 无锁 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/social/follower/123456" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
GET /api/social/follower/{userId}?page=1&size=20
  → FollowController.getFollowerList(userId, page, size)
  → Redis Set SCAN + MySQL补充用户信息
  → SCARD → total → 返回 {total, list}
```
