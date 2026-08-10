# U12: 修改地址 — PUT /api/user/address/{id}

## § 源码分析

- **Controller**: `UserAddressController.java:67` → `@PutMapping("/{id}")`, 参数 `@RequestHeader("X-User-Id")` + `@PathVariable id` + `@Valid @RequestBody AddressUpdateRequest`
- **Service**: `UserAddressService.java:130` → `updateAddress()`
  - MySQL `SELECT` 校验归属(`user_id=? AND id=? AND deleted=0`)
  - Redisson锁 `USER_ADDRESS_LOCK + userId`
  - `LambdaUpdate` 更新字段(双重校验: WHERE user_id=? AND id=? 防越权)
  - 设为默认 → 取消旧默认 + 设新默认
  - 更新Redis缓存 `USER_ADDRESS_DEFAULT + userId`
- **下游**: MySQL UPDATE + 旧默认清除 + Redis

## § 业务逻辑

提交地址字段 → 校验归属(非本人报403) → 分布式锁 → LambdaUpdate更新(双重校验防越权) → 若设为默认则切换旧默认→新默认 → 更新Redis默认地址缓存 → 查最新数据返回

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |
| 地址归属本人 | `mysql -e "SELECT user_id FROM t_user_address WHERE id=?"` | 403 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X PUT /api/user/address/{id} -d '{...}'` | 200, 更新后数据 |
| MySQL | `SELECT * FROM t_user_address WHERE id=?` | 字段已更新 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 并发 | Redisson锁 + LambdaUpdate双重校验 | ✅ |
| 安全 | 归属校验(两次)防越权 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
ADDR_ID=1  # 替换为实际地址ID
curl -s -i -X PUT "http://localhost:19000/api/user/address/$ADDR_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"receiverName":"新名字","isDefault":true}'
```

## § ASCII流转图

```
curl PUT /api/user/address/{id} + Authorization + Body
  → Gateway → my-xhs-user:19001 UserAddressController.updateAddress()
    → UserAddressService.updateAddress()
      → MySQL: SELECT * FROM t_user_address WHERE user_id=? AND id=? (归属校验)
      → Redisson RLock USER_ADDRESS_LOCK+{userId}
      → MySQL: UPDATE t_user_address SET ... WHERE user_id=? AND id=? (双重校验)
      → 设为默认 → MySQL: UPDATE旧默认 SET is_default=0 → UPDATE新默认 SET is_default=1
      → Redis SET myxhs:user:address:default:{userId}
      → MySQL: SELECT最新数据 → 返回
```
