# U03-login — POST /api/user/auth/login
> 2026-08-10 | 链1 user | 认证端点

## L1 正常
- chaintest_u1/Test@123456 + 验证码 → 200, accessToken(len225)+refreshToken(len227)+hmacSecret

## L2 数据
- Redis 存 access/refresh token, TTL≈1800/604800 ✅

## 结果: ✅ 通过
