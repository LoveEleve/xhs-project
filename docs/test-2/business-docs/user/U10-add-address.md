# U10: 新增地址 — POST /api/user/address

## § 源码分析

- **Controller**: `UserAddressController.java:57` → `@PostMapping("")`, 参数 `@RequestHeader("X-User-Id")` + `@Valid @RequestBody AddressCreateRequest`
- **Service**: `UserAddressService.java:57` → `createAddress()`
  - Redisson锁 `USER_ADDRESS_LOCK + userId`
  - MySQL `SELECT COUNT(*) FROM t_user_address WHERE user_id=? AND deleted=0` — 上限20条
  - 首次添加自动设为默认 `is_default=1`
  - `insert` t_user_address
  - 更新Redis缓存 `USER_ADDRESS_DEFAULT + userId` (TTL=30min)
- **下游**: MySQL `t_user_address` INSERT + Redis `ADDRESS_DEFAULT`

## § 业务逻辑

提交地址信息(姓名/手机/地区/详细地址) → Redisson锁防并发 → 查数量上限20 → 首条自动默认 → insert MySQL → 更新Redis默认地址缓存 → 返回地址对象

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |
| 地址未超20条 | `mysql -e "SELECT COUNT(*) FROM t_user_address WHERE user_id=? AND deleted=0"` | "达到最大数量" |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/user/address -d '{...}'` | 200, addressId |
| MySQL | `SELECT * FROM t_user_address WHERE user_id=? AND receiver_name=?` | 1行 |
| Redis | `r.get('myxhs:user:address:default:{userId}')` | 首条时有值 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 并发 | Redisson USER_ADDRESS_LOCK | ✅ |
| 可扩展 | 上限20条 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i -X POST http://localhost:19000/api/user/address \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"receiverName":"测试","receiverPhone":"13800138000","province":"广东","city":"深圳","district":"南山","detailAddress":"科技园1号"}'
```

## § ASCII流转图

```
curl POST /api/user/address + Authorization + Body
  → Gateway → my-xhs-user:19001 UserAddressController.createAddress()
    → UserAddressService.createAddress()
      → Redisson RLock USER_ADDRESS_LOCK+{userId}
      → MySQL: SELECT COUNT(*) FROM t_user_address WHERE user_id=? AND deleted=0
      → 首条 → is_default=1
      → MySQL: INSERT INTO t_user_address(...)
      → Redis SET myxhs:user:address:default:{userId} (首条时)
      → 返回 addressId
```
