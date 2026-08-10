# U-B3: 屏蔽列表 — GET /api/user/block/list

## § 源码分析

- **Controller**: `UserController.java:97` → `@GetMapping("/block/list")`, 参数 `@RequestHeader("X-User-Id") Long userId`
- **Service**: `UserService.java:415` → `getBlockList(userId)`
  - `SMEMBERS myxhs:user:block:{userId}` → Set<Long>
  - 查MySQL: `SELECT id, username, nickname, avatar FROM t_user WHERE id IN(blockedIds) AND deleted=0`
- **下游**: Redis Set + MySQL `t_user`

## § 业务逻辑

SMEMBERS取屏蔽Set → WHERE id IN(...)查用户基本信息 → 返回屏蔽用户列表(不含password等敏感字段)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |

## § ASCII流转图

```
curl GET /api/user/block/list + Authorization
  → UserController.getBlockList(X-User-Id)
    → Redis SMEMBERS myxhs:user:block:{userId} → Set of Long
    → MySQL: SELECT id,username,nickname,avatar FROM t_user WHERE id IN(...)
    → 返回屏蔽用户列表
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl http://localhost:19000/api/user/block/list -H "Authorization: Bearer $TOKEN"` | 200, list |
| Redis | `r.scard('myxhs:user:block:{userId}')` | ≥0 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | SMEMBERS O(N) + MySQL IN查询 | ✅ |
| 安全 | 不返回敏感字段(无password) | ✅ |
| 并发 | 只读操作，无锁 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/user/block/list" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```
