# U11: 地址列表 — GET /api/user/address/list

## § 源码分析

- **Controller**: `UserAddressController.java:31` → `@GetMapping("/list")`, 参数 `@RequestHeader("X-User-Id")`
- **Service**: `UserAddressService.java:241` → `listAddresses()`
  - MySQL `SELECT * FROM t_user_address WHERE user_id=? AND deleted=0 ORDER BY is_default DESC, created_at DESC`
- **下游**: MySQL `t_user_address` SELECT（无缓存，数据量小）

## § 业务逻辑

Gateway注入X-User-Id → 直接查MySQL `t_user_address` → 按默认优先+时间倒序排序 → 返回地址列表（无分页，地址上限20条）

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl GET /api/user/address/list` | 200, 地址数组 |
| MySQL | `SELECT COUNT(*) FROM t_user_address WHERE user_id=? AND deleted=0` | 与返回一致 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 无缓存，地址上限20条够快 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s http://localhost:19000/api/user/address/list \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图

```
curl GET /api/user/address/list + Authorization
  → Gateway → my-xhs-user:19001 UserAddressController.listAddresses(X-User-Id)
    → UserAddressService.listAddresses(userId)
      → MySQL: SELECT * FROM t_user_address WHERE user_id=? AND deleted=0 ORDER BY is_default DESC, created_at DESC
      → 返回地址列表
```
