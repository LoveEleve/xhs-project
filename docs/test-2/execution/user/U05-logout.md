# U05-logout — POST /api/user/auth/logout
> 2026-08-10 | 链1 user | 认证端点

## L1 正常: logout → 200; 原token再访问me → 401 "Token已被注销"

## L2 数据: Redis token黑名单生效 ✅

## 结果: ✅ 通过
