# U03: 登录 — POST /api/user/auth/login

## § 源码分析

- **Controller**: `AuthController.java:45` → `@PostMapping("/login")`, 参数 `@Valid @RequestBody LoginRequest`
- **Service**: `UserService.java:141` → `login()`
  - 验证码校验 (`CaptchaService.verifyCaptcha`)
  - Redisson锁 `USER_LOGIN_LOCK+username`
  - 查MySQL `t_user` by username
  - BCrypt密码校验 → 失败5次锁15分钟
  - `TokenService.generateTokenPair()` 生成access+refresh
- **下游**: Redis `USER_TOKEN_ACCESS/REFRESH+userId` + MySQL `last_login_time`更新

## § 业务逻辑

用户提交用户名+密码+验证码 → 校验验证码(一次性消费) → 查用户 → BCrypt密码比对 → 失败计数(Redis `USER_LOGIN_FAIL+username`, 5次锁15分钟) → 成功清计数、更新 `last_login_time` → 生成access token(30min) + refresh token(7d) → 写Redis → 返回JWT

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| user服务 Nacos注册 | `curl Nacos .../my-xhs-user` | Gateway 503 |
| MySQL t_user有数据 | `mysql -P 3306 -e "SELECT id FROM my_xhs_user.t_user LIMIT 1"` | 登录返回"用户不存在" |
| Redis可连 | `python3 -c "r.ping()"` | Token无法缓存 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -i POST /api/user/auth/login` | 200, `accessToken`, `refreshToken`, `X-Trace-Id` |
| JWT | `base64 decode payload` | `sub=userId`, `type=access`, `exp=+30min` |
| Redis | `r.get('myxhs:user:token:access:{userId}')` | Token值, TTL≈1800 |
| Redis | `r.get('myxhs:user:token:refresh:{userId}')` | Refresh存在, TTL≈604800 |
| MySQL | `SELECT last_login_time FROM t_user WHERE id=xxx` | 时间戳更新到当前 |
| MQ | 无 | 登录不产生MQ |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | RT < 500ms? | ✅ (无复杂查询) |
| 可扩展 | 无分片 | ✅ |
| 微服务 | 无Feign | ✅ |
| 并发 | Redisson锁防并发登录 | ✅ |
| 安全 | BCrypt+JWT+失败锁定+验证码一次性 | ✅ |

## § curl

```bash
# 1. 获取验证码
curl -s http://localhost:19000/api/user/auth/captcha > /tmp/cap.json
KEY=$(python3 -c "import json;print(json.load(open('/tmp/cap.json'))['data']['captchaKey'])")
CODE=$(grep "$KEY" /tmp/r_user.log | tail -1 | grep -oP 'code=\K\w+')

# 2. 登录
curl -s -i http://localhost:19000/api/user/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"chaintest_c1\",\"password\":\"Test@123456\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}"

# 3. 保存token
TOKEN=$(上述响应 | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])")
echo "$TOKEN" > /tmp/test_token.txt
```

## § ASCII流转图

```
curl POST /auth/login
  → Gateway (JWT not required for /auth/**)
    → my-xhs-user:19001 AuthController.login()
      → CaptchaService.verifyCaptcha()
        → Redis GET myxhs:user:captcha:{key} → DEL(一次性)
      → Redisson RLock USER_LOGIN_LOCK+{username}
      → MySQL: SELECT * FROM t_user WHERE username=?
      → BCrypt.matches(password, hash)
      → MySQL: UPDATE t_user SET last_login_time=NOW()
      → TokenService.generateTokenPair()
        → Redis SET myxhs:user:token:access:{userId} TTL=1800
        → Redis SET myxhs:user:token:refresh:{userId} TTL=604800
      → 返回 {accessToken, refreshToken, expiresIn}
```
