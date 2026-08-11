# U01: 获取验证码 — GET /api/user/auth/captcha

## § 源码分析

- **Controller**: `AuthController.java:28` → `@GetMapping("/captcha")`, 无参数
- **Service**: `CaptchaService.java:48` → `generateCaptcha()`
  - 生成4位随机数字验证码
  - `redisOperator.set(USER_CAPTCHA + key, code, 300)` — TTL 5min
  - 自绘PNG图片 → Base64编码
  - 返回 `{captchaKey, captchaImage}` (图片内嵌base64，无独立captcha-image端点)
- **下游**: Redis `myxhs:user:captcha:{key}` — SET with 5min TTL

## § 业务逻辑

生成随机key + 4位验证码 → 验证码写Redis(5min TTL) → 自绘PNG验证码图片 → Base64编码 → 返回captchaKey(后续login/register用) + captchaImage(可直接用`<img src="data:image/png;base64,...">`渲染)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| user服务运行 | `curl Nacos .../my-xhs-user` | Gateway 503 |
| Redis可写 | `python3 -c "r.ping()"` | 验证码无法存储，login/register会失败 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -s GET /api/user/auth/captcha` | 200, `captchaKey`, `captchaImage`(base64) |
| Redis | `r.get('myxhs:user:captcha:{key}')` | 4位验证码, TTL≈300 |
| Redis | `r.ttl('myxhs:user:captcha:{key}')` | ≤300 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | RT < 100ms? | ✅ (仅Redis写+图片生成) |
| 可扩展 | 无分片 | ✅ |
| 微服务 | 无Feign | ✅ |
| 并发 | 无锁(幂等key随机) | ✅ |
| 安全 | 验证码无需认证 | ✅ |

## § curl

```bash
cap_resp=$(curl -s http://localhost:19000/api/user/auth/captcha)
KEY=$(echo "$cap_resp" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['captchaKey'])")
IMAGE_BASE64=$(echo "$cap_resp" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['captchaImage'])")
echo "captchaKey: $KEY"
echo "image: ${IMAGE_BASE64:0:50}..."

# 验证码日志
CODE=$(python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis'); print(r.get('myxhs:user:captcha:$KEY').decode())")
echo "code: $CODE"
```

## § ASCII流转图

```
curl GET /auth/captcha
  → Gateway (无需JWT)
    → my-xhs-user:19001 AuthController.getCaptcha()
      → CaptchaService.generateCaptcha()
        → 生成随机key + 4位验证码
        → Redis SET myxhs:user:captcha:{key} 300s
        → 自绘PNG → Base64编码
      → 返回 {captchaKey, captchaImage}
```
