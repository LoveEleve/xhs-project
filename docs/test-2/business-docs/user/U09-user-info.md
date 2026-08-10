# U09: 查看他人公开信息 — GET /api/user/{userId}/info

## § 源码分析

- **Controller**: `UserController.java:44` → `@GetMapping("/{userId}/info")`, 参数 `@PathVariable Long userId`
- **Service**: `UserService.java:212` → `getUserPublicInfo(userId)`
  - 读Redis缓存 `USER_INFO + userId` (30min TTL, CacheAside)
  - 命中 → 过滤敏感字段(phone/email/password)后返回
  - 未命中 → MySQL `SELECT * FROM t_user WHERE id=? AND deleted=0` → 回写Redis → 过滤返回
- **下游**: Redis `USER_INFO` + MySQL `t_user`

## § 业务逻辑

无需认证 → 读Redis缓存(USER_INFO+userId) → 命中过滤敏感字段 → 未命中查MySQL → 回写Redis → 仅返回公开字段(nickname/avatar/description等)，不返回phone/email/password

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 目标用户存在 | `mysql -e "SELECT id FROM t_user WHERE id=?"` | 404 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl GET /api/user/{userId}/info` | 200, 公开字段(无phone/email) |
| MySQL | `SELECT * FROM t_user WHERE id=?` | deleted=0 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | CacheAside 30min TTL | ✅ |
| 安全 | 不返回phone/email/password | ✅ (手动过滤敏感字段) |
| 安全 | 无需JWT公开接口 | ✅ |

## § curl

```bash
# 用已知userId测试
curl -s http://localhost:19000/api/user/2085982901507301378/info | python3 -m json.tool
# 确认: 有nickname/avatar，无phone/email/password
```

## § ASCII流转图

```
curl GET /api/user/{userId}/info (无需认证)
  → Gateway (无需JWT)
    → my-xhs-user:19001 UserController.getUserPublicInfo(userId)
      → UserService.getUserPublicInfo(userId)
        → Redis GET myxhs:user:info:{userId}
          → 命中 → 过滤敏感字段 → 返回
          → 未命中 → MySQL: SELECT * FROM t_user WHERE id=? AND deleted=0
                    → Redis SET myxhs:user:info:{userId} TTL=1800
                    → 过滤敏感字段 → 返回
```
