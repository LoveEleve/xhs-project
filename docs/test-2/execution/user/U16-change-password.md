# U16-change-password — PUT /api/user/me/password
> 2026-08-10 | 链1 user | 需JWT+HMAC

## L1 正常: 改 Test@123456→Temp@123456 → 200; 改回 → 200
- 异常: 旧密码错误 → 40108 "旧密码错误"

## L2 数据: MySQL t_user.password hash 更新 ✅

## 发现的问题
- 改密码会轮换 HMAC 会话密钥 → 之后需重新登录(安全特性,非bug)

## 结果: ✅ 通过
