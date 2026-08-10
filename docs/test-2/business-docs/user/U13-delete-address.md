# U13: 删除地址 — DELETE /api/user/address/{id}

## § 源码分析

- **Controller**: `UserAddressController.java:78` → `@DeleteMapping("/{id}")`, 参数 `@RequestHeader("X-User-Id")` + `@PathVariable id`
- **Service**: `UserAddressService.java:197` → `deleteAddress()`
  - MySQL `SELECT` 校验归属(`user_id=? AND id=? AND deleted=0`)
  - **逻辑删除**: `UPDATE t_user_address SET deleted=1 WHERE id=?`
  - 若删的是默认地址 → 查找首条剩余地址 → 自动升级为新默认
  - 删除Redis缓存 `USER_ADDRESS_DEFAULT + userId`
- **下游**: MySQL UPDATE (逻辑删除) + 默认升级 + Redis DEL

## § 业务逻辑

提交地址ID → 校验归属(非本人报403) → 逻辑删除(deleted=1) → 若删了默认地址则自动升级首条为新默认 → 清除Redis默认地址缓存 → 返回success

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |
| 地址归属本人 | `mysql -e "SELECT user_id FROM t_user_address WHERE id=?"` | 403 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X DELETE /api/user/address/{id}` | 200 |
| MySQL | `SELECT deleted FROM t_user_address WHERE id=?` | 1 (逻辑删除) |
| MySQL | 若删默认 → `SELECT COUNT(*) FROM t_user_address WHERE user_id=? AND is_default=1` | ≥1 (自动升级) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | 归属校验 + 逻辑删除(非物理) | ✅ |
| 一致性 | 默认地址自动升级(删默认不丢失默认) | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
ADDR_ID=1  # 替换为实际地址ID
curl -s -i -X DELETE "http://localhost:19000/api/user/address/$ADDR_ID" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
curl DELETE /api/user/address/{id} + Authorization
  → Gateway → my-xhs-user:19001 UserAddressController.deleteAddress()
    → UserAddressService.deleteAddress()
      → MySQL: SELECT * FROM t_user_address WHERE user_id=? AND id=? (归属校验)
      → MySQL: UPDATE t_user_address SET deleted=1 WHERE id=? (逻辑删除)
      → 若是默认 → MySQL: SELECT首条剩余 WHERE user_id=? AND deleted=0 ORDER BY created_at
                  → MySQL: UPDATE SET is_default=1 WHERE id=? (自动升级)
      → Redis DEL myxhs:user:address:default:{userId}
      → 返回 success
```
