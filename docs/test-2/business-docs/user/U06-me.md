# U06: 当前用户信息 — GET /api/user/me

## § 源码分析

- **Controller**: `UserController.java:32` → `@GetMapping("/me")`, 参数 `@RequestHeader("X-User-Id") Long userId`（Gateway注入）
- **Service**: `UserService.java:220` → `getUserInfo()`
  - CacheAside: Redis `myxhs:user:info:{userId}` (TTL=30min)
  - 命中 → 反序列化JSON返回
  - 未命中 → MySQL `SELECT * FROM t_user WHERE id=? AND deleted=0` (排除password字段) → 回写Redis
- **下游**: Redis `USER_INFO + userId` + MySQL `t_user`

## § 业务逻辑

Gateway解析JWT → 注入X-User-Id → 读Redis缓存(USER_INFO+userId, 30min TTL) → 命中直接返回 → 未命中查MySQL `t_user`(排除password) → 回写Redis(30min) → 返回完整用户信息

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` → `curl /api/user/me` | 401 |
| Redis缓存 | `r.exists('myxhs:user:info:{userId}')` | 首次访问需查DB |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/user/me -H "Authorization: Bearer $TOKEN"` | 200, username/nickname/avatar |
| Redis | `r.ttl('myxhs:user:info:{userId}')` | ≤1800 |
| MySQL | `SELECT id,username FROM t_user WHERE id=?` | 一行, deleted=0 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | CacheAside命中率 > 90%? | ✅ (30min TTL) |
| 安全 | 不返回password字段 | ✅ (SELECT排除) |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s http://localhost:19000/api/user/me \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# 提取userId
USER_ID=$(curl -s http://localhost:19000/api/user/me \
  -H "Authorization: Bearer $TOKEN" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['id'])")
echo "userId: $USER_ID"
```

## § ASCII流转图

```
curl GET /api/user/me + Authorization: Bearer {token}
  → Gateway(AuthFilter解析JWT→注入X-User-Id)
    → my-xhs-user:19001 UserController.getCurrentUser(X-User-Id)
      → UserService.getUserInfo(userId)
        → Redis GET myxhs:user:info:{userId}
          → 命中 → 反序列化 → 返回
          → 未命中 → MySQL: SELECT id,username,nickname,avatar,... FROM t_user WHERE id=? AND deleted=0
                    → Redis SET myxhs:user:info:{userId} TTL=1800
                    → 返回
```
