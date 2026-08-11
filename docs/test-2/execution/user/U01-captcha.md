# U01-captcha — GET /api/user/auth/captcha
> 2026-08-10 | 链1 user | 公开端点

## L1 HTTP
- GET /api/user/auth/captcha → code=200, 返回 captchaKey + captchaImage(base64 PNG)

## L2 数据
- Redis `myxhs:user:captcha:{key}` 存在 (验证码)

## 结果: ✅ 通过
