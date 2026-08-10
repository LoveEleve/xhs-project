# FW09: 修复计数器 — POST /api/social/internal/repair-counter/{userId}

## § 源码分析

- **Controller**: `FollowController.java:147` → `@PostMapping("/internal/repair-counter/{userId}")`
- **鉴权**: `FollowController.java:161-167` → `X-Admin-Call` + `management.admin-token` 校验 → 不匹配返回403
  - `String adminToken = request.getHeader("X-Admin-Call")` (L161)
  - `if (!adminToken.equals(managementAdminToken)) throw new ForbiddenException()` (L163-164)
- **Service**: 按MySQL t_follow实际计数修复Redis计数器 → `SET myxhs:follower:count:{userId}` = actual
- **下游**: MySQL t_follow + Redis

## § 业务逻辑

管理接口 → AdminToken校验 → 以MySQL实际计数为准重新修复Redis计数器

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Admin Token | X-Admin-Call | 403 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST + X-Admin-Call` | 200 |
| Redis | `r.get('myxhs:following:count:{userId}')` | = MySQL COUNT |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | AdminToken | ✅ |

## § curl

```bash
curl -s -X POST "http://localhost:19000/api/social/internal/repair-counter/123456" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Admin-Call: my-xhs-admin-token-2026"
```

## § ASCII流转图

```
POST /api/social/internal/repair-counter/{userId} + X-Admin-Call
  → FollowController.repairCounter()
    → AdminToken校验
    → SELECT COUNT(*) FROM t_follow WHERE follower_id=? 以MySQL为准
    → Redis SET myxhs:following:count:{userId} = actual
    → 返回 ok
```
