# U14: 注册 — POST /api/user/auth/register

## § 源码分析

- **Controller**: `AuthController.java:36` → `@PostMapping("/register")`, `@Valid @RequestBody RegisterRequest`
- **Service**: `UserService.java:68` → `register()`
  - 校验验证码 (`CaptchaService.verifyCaptcha`)
  - Redisson锁 `USER_REGISTER_LOCK+username`
  - 查用户名/手机号唯一性(`SELECT FROM t_user WHERE username=? OR phone=?`)
  - BCrypt加密密码
  - `userMapper.insert(user)` → MySQL
  - `cacheHelper.delayDoubleDelete` 清除可能缓存
- **下游**: MySQL `t_user` INSERT + Redis 验证码消费

## § 业务逻辑

提交用户名+密码+手机号+验证码 → 验证码校验(一次性) → 分布式锁防并发注册 → 唯一性检查(username/phone) → BCrypt密码加密 → insert t_user → 延迟双删缓存 → 返回userId

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 用户名未注册 | `mysql -e "SELECT id FROM t_user WHERE username='test_xxx'"` → 空 | "用户名已存在" |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl POST /api/user/auth/register` | 200, userId |
| MySQL | `SELECT id,username FROM t_user WHERE username='test_xxx'` | 1行, BCrypt密码 |
| Redis | 验证码key不存在 | DEL(一次性消费) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | RT < 500ms? | ✅ |
| 并发 | Redisson USER_REGISTER_LOCK | ✅ |
| 安全 | BCrypt+验证码+唯一性校验 | ✅ |

## § curl

```bash
# 1. 获取验证码
curl -s http://localhost:19000/api/user/auth/captcha > /tmp/cap.json
KEY=$(python3 -c "import json;print(json.load(open('/tmp/cap.json'))['data']['captchaKey'])")
CODE=$(python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis'); print(r.get('myxhs:user:captcha:$KEY').decode())")

# 2. 注册
curl -s -i -X POST http://localhost:19000/api/user/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"testnewuser\",\"password\":\"Test@123456\",\"phone\":\"13900001111\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}"
```

## § ASCII流转图

```
curl POST /auth/register
  → Gateway → my-xhs-user:19001 AuthController.register()
    → CaptchaService.verifyCaptcha() → Redis DEL captcha:{key}
    → Redisson USER_REGISTER_LOCK+{username}
    → MySQL: SELECT COUNT(*) FROM t_user WHERE username=? OR phone=?
    → BCrypt.encode(password)
    → MySQL: INSERT INTO t_user(username,password,phone,nickname,...)
    → CacheHelper.delayDoubleDelete() → MQ CACHE_EVICT_TOPIC
    → 返回 {userId}
```
