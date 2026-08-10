# FW08: 关注数 — GET /api/social/following/count/{userId}

## § 源码分析

- **Controller**: `FollowController.java:139` → `GET /following/count/{userId}`, 内部接口
- **Service**: `SELECT COUNT(*) FROM t_follow WHERE follower_id=? AND deleted=0`

## § 业务逻辑

内部服务查询用户关注总数

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 内部接口 | Feign/内部调用 | 公开也可访问 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "/api/social/following/count/{userId}"` | 200, count |
| Redis | `r.scard('myxhs:following:{userId}')` | = count |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | SCARD O(1) | ✅ |
| 安全 | 公开接口, 无鉴权 | ✅ |
| 并发 | 只读操作, 无锁 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/social/following/count/123456"
```

## § ASCII流转图

```
GET /api/social/following/count/{userId}
  → FollowController.getFollowingCount(userId)
  → Redis SCARD myxhs:following:{userId}
  → 返回 {count: N}
```
