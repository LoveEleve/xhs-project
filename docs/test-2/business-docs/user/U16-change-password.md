# U16: 修改密码 — PUT /api/user/me/password

## § 源码分析

- **Controller**: `UserController.java:62` → `@PutMapping("/me/password")`, 参数 `@RequestHeader("X-User-Id")` + `@Valid @RequestBody ChangePasswordRequest`(oldPassword, newPassword)
- **Service**: `UserService.java:287` → `changePassword()`
  - 查MySQL `t_user` by userId
  - BCrypt验证旧密码 → 不匹配直接抛异常
  - BCrypt加密新密码 → LambdaUpdate写入
  - `tokenService.revokeAllTokens(userId)` — 注销该用户所有活跃Token
- **下游**: MySQL `t_user` UPDATE + Redis Token全清

## § 业务逻辑

提交旧密码+新密码 → 查用户 → BCrypt校验旧密码 → LambdaUpdate写新密码 → revokeAllTokens(所有活跃Token入黑名单+删Redis Token键) → 返回success。改密后旧Token全部失效，所有设备需重新登录。

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |
| 知道旧密码 | correct password | "原密码错误" |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X PUT /api/user/me/password -d '{"oldPassword":"xxx","newPassword":"yyy"}'` | 200 |
| MySQL | `SELECT password FROM t_user WHERE id=?` | BCrypt值变化 |
| 反验证 | 旧Token调用 `/api/user/me` | 401 (所有Token已被revoke) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | BCrypt+旧密码验证+Token全清 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i -X PUT http://localhost:19000/api/user/me/password \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"oldPassword":"Test@123456","newPassword":"NewPass@123"}'

# 验证旧Token失效
curl -s -o /dev/null -w "%{http_code}" http://localhost:19000/api/user/me \
  -H "Authorization: Bearer $TOKEN"
# 预期: 401
```

## § ASCII流转图

```
curl PUT /api/user/me/password + Authorization + {oldPassword, newPassword}
  → Gateway → my-xhs-user:19001 UserController.changePassword()
    → UserService.changePassword()
      → MySQL: SELECT password FROM t_user WHERE id=?
      → BCrypt.matches(oldPassword, hash)
      → BCrypt.encode(newPassword)
      → MySQL: UPDATE t_user SET password=? WHERE id=?
      → TokenService.revokeAllTokens(userId)
        → Redis: 遍历活跃Token → 写入blacklist
        → Redis: DEL 所有Token键(access/refresh/hmac)
      → 返回 success
```
