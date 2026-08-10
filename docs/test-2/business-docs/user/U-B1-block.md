# U-B1: 屏蔽用户 — POST /api/user/block/{targetUserId}

## § 源码分析

- **Controller**: `UserController.java:75` → `@PostMapping("/block/{targetUserId}")`, 参数 `@RequestHeader("X-User-Id") Long userId` + `@PathVariable Long targetUserId`
- **Service**: `UserService.java:393` → `blockUser(userId, targetUserId)`
  - 校验不能屏蔽自己
  - `SADD myxhs:user:block:{userId} targetUserId` (Set, TTL=365天)
- **下游**: Redis Set `myxhs:user:block:{userId}`

## § 业务逻辑

Gateway注入X-User-Id → 校验targetUserId != userId(不能自己屏蔽自己) → Redis SADD写入屏蔽列表(Set, 365天TTL, 永久屏蔽)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |
| targetUserId 有效 | `mysql -P 3306 -e "SELECT id FROM my_xhs_user.t_user WHERE id={targetUserId}"` | "用户不存在" |
| 不屏蔽自己 | 使用不同用户 | "不能屏蔽自己" |

## § ASCII流转图

```
curl POST /api/user/block/{targetUserId} + Authorization
  → Gateway → UserController.blockUser(X-User-Id, targetUserId)
    → UserService.blockUser(userId, targetUserId)
      → 校验 userId != targetUserId
      → Redis SADD myxhs:user:block:{userId} targetUserId
      → EXPIRE myxhs:user:block:{userId} 365天
      → 返回 ok
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/user/block/{targetUserId} -H "Authorization: Bearer $TOKEN"` | 200 |
| Redis | `r.smembers('myxhs:user:block:{userId}')` | 含 targetUserId |
| Redis TTL | `r.ttl('myxhs:user:block:{userId}')` | ≈31536000(365天) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | 不能屏蔽自己 | ✅ |
| 并发 | Redis SADD原子 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST "http://localhost:19000/api/user/block/2085927845755986221" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# 预期: {"code":200,"success":true}
```
