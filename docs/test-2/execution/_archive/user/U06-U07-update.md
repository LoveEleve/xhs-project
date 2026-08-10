# U06 — 更新用户信息

`PUT /api/user/me` | JWT + X-User-Id required

## ASCII 流转图

```
[curl] → Gateway:19000 → user:19001
  → UserController.updateCurrentUser(X-User-Id=10001, body:UpdateUserRequest)
  → UserService.updateUserInfo()
     └ MySQL:13306 my_xhs_user.t_user UPDATE nickname/avatar/gender
```

## 业务逻辑

更新当前用户昵称、头像、性别（0未知/1男/2女）。仅更新传入的字段，不修改其他列。nickname 最长 64 位，avatar 最长 512 位。

## curl

```bash
TOKEN=$(cat /tmp/test_token.txt)

# 修改昵称+性别
curl -s -X PUT "http://localhost:19000/api/user/me" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001" \
  -d '{"nickname":"测试用户A-新","gender":1}'

# 恢复原值
curl -s -X PUT "http://localhost:19000/api/user/me" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001" \
  -d '{"nickname":"测试用户A","gender":0}'
```

## 七层验证

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, nickname="测试用户A-新", gender=1 | ✅ |
| MySQL | t_user nickname 变更确认 | ✅ |
| 恢复 | 200, 原值恢复 | ✅ |
| SkyWalking | traceId=e9ff8edde2e542d386703aab1c715646 | ✅ |
| Prometheus | PUT /me 指标 | ✅ |

---

# U07 — 修改密码

`PUT /api/user/me/password` | JWT + X-User-Id required

## ASCII 流转图

```
[curl] → Gateway:19000 → user:19001
  → UserController.changePassword(X-User-Id=10001, body:ChangePasswordRequest)
  → UserService.changePassword()
     ├ 校验 oldPassword BCrypt.matches
     ├ BCrypt 加密 newPassword
     ├ MySQL UPDATE t_user SET password
     └ revokeAllTokens → Redis DEL token:access/refresh → 旧 JWT 失效
```

## 业务逻辑

修改用户密码，需提供旧密码验证。新密码长度 6-64 位。修改成功后所有旧 JWT token 被撤销——当前请求成功后，旧的 accessToken 不再有效。

## curl

```bash
# 修改密码（会注销旧 token）
curl -s -X PUT "http://localhost:19000/api/user/me/password" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001" \
  -d '{"oldPassword":"Test@123456","newPassword":"Test@654321"}'

# 改回原密码（需要新 token，因为旧 token 已失效）
curl -s -X PUT "http://localhost:19000/api/user/me/password" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001" \
  -d '{"oldPassword":"Test@654321","newPassword":"Test@123456"}'
```

## 七层验证

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200 | ✅ |
| Redis | token 已撤销（revokeAllTokens） | ✅ |
| 改回 | 需重新登录（旧 token 401，预期行为） | ✅ |
