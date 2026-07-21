# 用户模块 curl 逐条测试记录

> 每条测试包含：curl 请求、实际返回、DB 验证、Redis 验证、业务讲解。

| 编号 | 接口 | 状态 |
|------|------|:----:|
| 2.1 | GET /api/user/auth/captcha | 通过 |
| 2.2 | POST /api/user/auth/register | 通过 |
| 2.3 | POST /api/user/auth/login | 通过 |
| 2.4 | GET /api/user/me | 待测试 |
| 2.5 | GET /api/user/{userId}/info | 待测试 |
| 2.6 | PUT /api/user/me | 待测试 |
| 2.7 | PUT /api/user/me/password | 待测试 |
| 2.8 | POST /api/user/auth/refresh | 待测试 |
| 2.9 | POST /api/user/auth/logout | 待测试 |
| 2.10 | POST /api/user/block/{id} | 待测试 |
| 2.11 | GET /api/user/block/list | 待测试 |
| 2.12 | POST /api/user/address | 待测试 |
| 2.13 | GET /api/user/address/list | 待测试 |
| 2.14 | GET /api/user/address/default | 待测试 |
| 2.15 | PUT /api/user/address/{id} | 待测试 |
| 2.16 | DELETE /api/user/address/{id} | 待测试 |

## 测试环境

- MySQL: `mysql -h 21.130.247.89 -P 13306 -u root -p'Xhs@2026#MySQL' my_xhs_user`
- Redis: `redis-cli -h 21.130.247.89 -p 16379 -a 'Xhs@2026#Redis'`

---

## 2.1 获取图形验证码 — `GET /api/user/auth/captcha`

### curl 请求

```bash
curl -s http://localhost:19000/api/user/auth/captcha | jq .
```

### 实际返回

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "captchaKey": "2bdbe07399d845d28bde3f91ad333e56",
    "captchaImage": "data:image/png;base64,iVBORw0KGgo..."
  },
  "timestamp": 1784281000000,
  "success": true
}
```

### Redis 验证

```bash
# Key 格式：myxhs:user:captcha:{captchaKey}
GET myxhs:user:captcha:2bdbe07399d845d28bde3f91ad333e56
```

| 字段 | 值 |
|------|------|
| Value | `PEST` |
| TTL | 300s（5 分钟） |

### MySQL 验证

| 验证项 | 结果 |
|--------|------|
| t_user 记录数 | 无变化（纯 Redis 操作，不写 DB） |

### 业务讲解

- `captchaKey` = `UUID.randomUUID().toString().replace("-", "")`，32 位纯十六进制
- `captchaImage` = 自绘 120×40 PNG 的 Base64（含干扰线、噪点），字符集为 `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`（去掉易混淆的 0O1lI）
- 验证码原文存 Redis `myxhs:user:captcha:{key}`，TTL 5 分钟
- **一次性消费**：`verifyCaptcha()` 中无论校验成功还是失败，都执行 `redisOperator.delete(key)`，防止暴力枚举

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| Redis 中有对应验证码值 | `PEST` | 通过 |
| TTL = 300s | 300s | 通过 |
| MySQL 无变化 | 无变化 | 通过 |

---

## 2.2 用户注册 — `POST /api/user/auth/register`

### curl 请求

```bash
curl -s -X POST http://localhost:19000/api/user/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"newuser","password":"Test@123456","captchaKey":"015969203e614c4fbe3fcb335c020f74","captchaCode":"XNTK"}'
```

### 实际返回

```json
{ "code": 200, "message": "操作成功", "success": true }
```

### MySQL 验证

```sql
SELECT id, username, nickname, gender, status,
       SUBSTRING(password, 1, 20) AS pwd_prefix, deleted
FROM t_user WHERE username='newuser';
```

| 字段 | 值 | 说明 |
|------|------|------|
| id | `2078387513547841537` | 号段模式生成 |
| username | `newuser` | 传入的用户名 |
| nickname | `newuser` | 默认 = username |
| gender | `0` | 未知 |
| status | `1` | 正常 |
| pwd_prefix | `$2a$10$z/sTD372KydmZ` | BCrypt 加密 |
| deleted | `0` | 逻辑删除标记 |

### Redis 验证

| 验证项 | 结果 | 说明 |
|--------|------|------|
| 验证码 Key 是否存在 | 不存在（已删除） | 一次性消费：校验后无论成功失败都 delete |

### 业务讲解

注册流程（对应 `UserService.register()`）：

```
1. captchaService.verifyCaptcha(key, code)
   → 查 Redis → 取出 "XNTK" → 对比成功 → delete key
2. redissonClient.getLock("myxhs:user:register:lock:newuser")
   → tryLock(3s wait, 10s hold)
3. 锁内：selectCount(eq("username","newuser")) → 0（不重复）
4. 锁内：selectCount(eq("phone",null)) → 跳过（未填手机号）
5. passwordEncoder.encode("Test@123456") → BCrypt $2a$10$...
6. userMapper.insert(user) → id 由号段模式生成
```

**关键点**：
- **分布式锁粒度 = 用户名**：不同用户的注册并行，同一用户名互斥
- **锁内做唯一性检查**：防止两个并发请求同时看到 count=0 都 insert
- **验证码一次性消费**：Redis key 已被删除，攻击者不能重复使用

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| DB 新增一条用户记录 | id=2078387513547841537, status=1 | 通过 |
| 密码 BCrypt 加密 | `$2a$10$z/sTD372KydmZ...` | 通过 |
| 昵称默认 = 用户名 | `newuser` | 通过 |
| 验证码被消费（Redis 删除） | key 不存在 | 通过 |

---

## 2.3 用户登录 — `POST /api/user/auth/login`

### curl 请求

```bash
curl -s -X POST http://localhost:19000/api/user/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"newuser","password":"Test@123456","captchaKey":"6b28d3bc...","captchaCode":"VC95"}'
```

### 实际返回

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
  }
}
```

### JWT 解码

```json
// AccessToken payload（base64解码中间那一段）
{
  "jti": "35593b4ac37b438b91f21977914f4588",   // Token 唯一 ID
  "sub": "2078387513547841537",                  // userId
  "type": "access",                              // 类型
  "iat": 1784362618,                             // 签发时间
  "exp": 1784364418                              // 过期时间（iat + 1800s = 30min）
}
```

### Redis 验证

| Key | 值 | TTL |
|------|------|------|
| `myxhs:user:captcha:6b28...` | 不存在（已消费） | — |
| `myxhs:user:token:access:2078387513547841537` | JWT 原文 | 1788s（≈30min） |
| `myxhs:user:token:refresh:2078387513547841537` | JWT 原文 | 604788s（=7天） |

### MySQL 验证

| 验证项 | 结果 |
|--------|------|
| t_user 记录 | 无变化（登录只读，不写 DB） |

### 业务讲解

登录流程（对应 `UserService.login()`）：

```
1. captchaService.verifyCaptcha(key, code)
   → Redis GET → "VC95" → 匹配 → DELETE key（一次性消费）

2. 检查账号锁定
   → Redis GET myxhs:user:login:lock:newuser → null → 未锁定

3. 查用户
   → userMapper.selectOne(eq("username","newuser"))
   → @TableLogic 自动加 deleted=0 条件
   → 查到 User(id=2078387513547841537, status=1)

4. 检查状态
   → status=1 → 正常（status=0 会抛 ACCOUNT_DISABLED）

5. 校验密码
   → passwordEncoder.matches("Test@123456", "$2a$10$...")
   → BCrypt 匹配成功
   → 匹配失败会走 incrementLoginFail（5次锁定15min）

6. 清除失败计数
   → Redis DEL myxhs:user:login:fail:newuser

7. 生成 Token 对
   → JwtUtil.generateToken(userId, "access", 30min, secret)
   → JwtUtil.generateToken(userId, "refresh", 7d, secret)
   → Redis SET myxhs:user:token:access:2078387513547841537 = accessToken
   → Redis SET myxhs:user:token:refresh:2078387513547841537 = refreshToken
```

**Token 存储到 Redis 的目的**：
- 单设备登录：下次登录会覆盖 Redis 中的值，旧设备的 Refresh Token 在刷新时会被拒绝
- 改密全注销：`revokeAllTokens` 需要从 Redis 取出当前 Token 加入黑名单
- 登出清理：`logout` 从 Redis 删除映射

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| code=200，返回 accessToken + refreshToken | 两端 JWT | 通过 |
| JWT.sub = userId | `2078387513547841537` | 通过 |
| JWT.type = "access" | `access` | 通过 |
| AccessToken TTL ≈ 30min | 1788s | 通过 |
| RefreshToken TTL = 7天 | 604788s | 通过 |
| 验证码被消费 | Redis key 不存在 | 通过 |


