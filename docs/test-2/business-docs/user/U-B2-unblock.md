# U-B2: 取消屏蔽 — DELETE /api/user/block/{targetUserId}

## § 源码分析

- **Controller**: `UserController.java:86` → `@DeleteMapping("/block/{targetUserId}")`, `X-User-Id` + `@PathVariable`
- **Service**: `UserService.java:406` → `unblockUser(userId, targetUserId)`
  - `redisTemplate.opsForSet().remove("myxhs:user:block:" + userId, targetUserId.toString())`
- **下游**: Redis Set `myxhs:user:block:{userId}`

## § 业务逻辑

SREM从屏蔽列表移除targetUserId → 取消屏蔽 → 该用户内容重新可见

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |
| 已屏蔽该用户 | `r.sismember('myxhs:user:block:{userId}','{targetUserId}')` | 幂等返回ok |

## § ASCII流转图

```
curl DELETE /api/user/block/{targetUserId}
  → UserService.unblockUser(userId, targetUserId)
    → Redis SREM myxhs:user:block:{userId} targetUserId
    → 返回 ok（幂等）
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X DELETE /api/user/block/{id} -H "Authorization: Bearer $TOKEN"` | 200 |
| Redis | `r.sismember('myxhs:user:block:{userId}','{id}')` | False |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | SREM O(1) | ✅ |
| 并发 | Redis单线程原子 | ✅ |
| 安全 | 不能取消未屏蔽用户 | ✅ 幂等 |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X DELETE "http://localhost:19000/api/user/block/2085927845755986221" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```
