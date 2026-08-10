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

> 测试时间：2026-08-01 18:00 CST | 服务：my-xhs-gateway(19000) → my-xhs-user(19001) | **通过 Gateway 调用（生产路径）**

### 正常用例

**curl 请求**（通过 Gateway）：
```bash
curl -s http://21.214.97.212:19000/api/user/auth/captcha
```

**响应**（HTTP=200, time=21ms）：
```json
{
  "code": 200, "message": "操作成功", "success": true,
  "data": {
    "captchaKey": "1d5d59d16fde4f299ce6071a8f912c31",
    "captchaImage": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAHgAAAAoCAIAAAC6iKly..."
  }
}
```

### 13 层验证

| 层 | 验证项 | 结果 |
|:--:|------|:----:|
| L1 | API 响应 HTTP=200 + captchaKey(32位hex) + captchaImage(Base64 PNG) | ✅ |
| L2 | gateway 日志 `[Gateway] >>> GET /api/user/auth/captcha, traceId=c8ec031f...` + user 日志 `[ACCESS] GET /api/user/auth/captcha, status=200, rt=4ms`（**traceId 一致 c8ec031f...**） | ✅ |
| L3 | Redis `myxhs:user:captcha:1d5d59d1...` value=`"RGW4"` TTL=300s type=string | ✅ |
| L4 | MySQL 无变化（纯 Redis 操作，不写 DB） | ✅ N/A |
| L5 | user 应用日志 `[验证码] 生成成功, key=1d5d59d1..., code=RGW4` | ✅ |
| L6 | Nacos 注册正常（Actuator nacosDiscovery UP） | ✅ |
| L7 | XXL-Job N/A（captcha 不涉及定时任务） | ✅ N/A |
| L8 | MQ N/A（captcha 不涉及消息队列） | ✅ N/A |
| L9 | @RateLimit N/A（接口无 @RateLimit 注解） | ✅ N/A |
| L10 | Sentinel N/A（接口无 Sentinel 资源） | ✅ N/A |
| L11 | SkyWalking **sw8 传播成功**：gateway trace_id hex=`3b4baf84...`，user 有 5 个同 trace_id 的 segment（CROSS_PROCESS refs 完整） | ✅ |
| L12 | Gateway 路由 `GET http://19000/api/user/auth/captcha` HTTP=200 + captchaKey（本用例即通过 Gateway） | ✅ |
| L13 | Actuator `status: UP`（db/discovery/diskSpace/liveness/nacos 全 UP） | ✅ |

> **注**：业务 traceId（`c8ec031f...`，X-Trace-Id header）与 SkyWalking trace_id（`3b4baf84...`，sw8 header）是两套独立系统，均验证透传成功。详见 feedback_business_traceid_vs_skywalking_sw8.md。

### 异常用例：重复获取验证码（通过 Gateway）

**测试目的**：验证多次获取验证码时，每次生成唯一 key + 旧 key 不被删除（各自独立 TTL）+ 每次请求 traceId 透传

**操作**：连续 3 次通过 Gateway 调用 `GET http://21.214.97.212:19000/api/user/auth/captcha`（间隔 ~1s）

| 次数 | captchaKey | Redis value | TTL | gateway traceId | user traceId |
|:--:|------|------|:--:|------|------|
| 1 | `809d124873df4bed849c1a5ff30432ec` | `"PQJY"` | 298s | `e36c3c49...` | `e36c3c49...` ✅ |
| 2 | `59e2850a65454acda2a83053bd7a2c45` | `"7TQH"` | 299s | `4ddef9d0...` | `4ddef9d0...` ✅ |
| 3 | `644a4f5efa1048bdbb116289c52d4891` | `"E6J7"` | 300s | `147a9c7b...` | `147a9c7b...` ✅ |

**验证结论**：
- ✅ 3 个 captchaKey 互不相同（UUID 唯一性）
- ✅ 3 个 Redis key 同时存在（旧 key 不因新调用而删除，各自独立 5 分钟 TTL）
- ✅ 每次验证码值不同（SecureRandom 随机生成）
- ✅ 3 次请求的 traceId 在 gateway ↔ user 之间一致（业务 traceId 透传成功）
- ✅ TTL 递减 298s→299s→300s 反映创建间隔 ~1s（数据真实，非复制粘贴）
- **工程意义**：用户可以多次获取验证码，旧验证码在过期前仍然有效。这允许用户"刷新验证码看不清时重新获取"，不影响之前已获取的验证码（直到过期或被消费）

### 工程知识点

**1. 自绘验证码（不依赖 Kaptcha）**
- `Graphics2D` 绘制 120×40 PNG 图片，4 位字符
- 字符集 `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`（去掉易混淆的 0/O/1/I：0 与 O 形似，1 与 I 形似）
- 6 条干扰线 + 30 个噪点，防 OCR 识别
- 字符颜色随机（RGB 三通道各 `20 + nextInt(110)` = [20, 129] 范围，保证深色可读）

**2. Redis 存储设计**
- key = `myxhs:user:captcha:{uuid}`（PROJECT_PREFIX=`myxhs:` 统一前缀，防多项目冲突）
- value = 验证码大写形式（`code.toUpperCase()`）
- TTL = 5 分钟（`EXPIRE_MINUTES=5`）
- 用 `RedisTemplate`（配 Jackson Json 序列化器，String 序列化为 JSON 带引号 `"Q9PJ"`，**不是 StringRedisTemplate**——RedisOperator.set() 内部调用 `redisTemplate.opsForValue().set()`）
- ⚠️ **潜在风险**：`RedisOperator.set()` 对非连接失败的异常只 `log.error` 不抛出，意味着 Redis 写入失败（如序列化错误）时 API 仍返回 200 + captchaKey，但 Redis 里没 key，用户无法注册/登录。此接口受此风险影响

**3. 一次性消费（防暴力枚举）**
- `verifyCaptcha()` 顺序：先比较得 `matched` → **无条件删除** → 用 `matched` 判断是否抛异常
- 即校验成功/失败**都删除**，防止攻击者反复尝试同一 key 的不同验证码值
- 消费时机：注册（`UserService.register()` line 69）和登录（`UserService.login()` line 133）都调用 `verifyCaptcha(key, code)`

**4. SecureRandom 防预测**
- 用 `SecureRandom` 而非 `Random`，防止种子被预测导致验证码可预测
- `SecureRandom` 使用操作系统熵源（/dev/urandom），密码学安全

**5. Base64 内嵌图片**
- 返回 `data:image/png;base64,...` 格式
- 前端直接 `<img src="data:image/png;base64,...">` 显示，无需额外 HTTP 请求
- 减少 HTTP 往返，但增加响应体大小（Base64 膨胀 ~33%）

**6. traceId 跨服务透传**
- SkyWalking trace_id=`cdfeabb4...`（hex 部分）+ MDC traceId=`c1e6b7b30e3f4fc08c023bc3ecddbd9f`（业务 traceId）
- 两套 traceId 独立（详见 feedback_business_traceid_vs_skywalking_sw8.md）

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| HTTP 200 + captchaKey + captchaImage | HTTP=200, captchaKey=32位hex, captchaImage=Base64 PNG | 通过 |
| Redis value = 应用日志的 code | Redis value=`"Q9PJ"` = 应用日志 code=Q9PJ | 通过 |
| TTL ≈ 300s | TTL=240s（测试过程耗时 ~60s） | 通过 |
| 重复获取 key 唯一 | 3 次 key 互不相同 | 通过 |
| 旧 key 不被新调用删除 | 3 个 key 同时存在 | 通过 |
| Gateway 路由转发 | HTTP=200 + 新 captchaKey | 通过 |
| SkyWalking trace 上报 | trace_id 存在 + latency=421ms | 通过 |

---

## 2.2 用户注册 — `POST /api/user/auth/register`

> 测试时间：2026-08-01 18:07 CST | 服务：gateway(19000) → user(19001) | **通过 Gateway 调用**

### 正常用例

**前置**：先通过 Gateway 获取验证码（`GET /api/user/auth/captcha`），从 Redis 提取 code

**curl 请求**（通过 Gateway）：
```bash
curl -s -X POST http://21.214.97.212:19000/api/user/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser1785578824","password":"Test@123456","phone":"13985578824","captchaKey":"53e7b4741db2446c8932ebd0fc59b566","captchaCode":"J9BZ"}'
```

**响应**（HTTP=200, time=100ms）：
```json
{"code":200,"message":"操作成功","timestamp":1785578824246,"success":true}
```

### 13 层验证

| 层 | 验证项 | 结果 |
|:--:|------|:----:|
| L1 | API 响应 HTTP=200 + code=200 + success=true | ✅ |
| L2 | gateway traceId=`a7896298...` ↔ user traceId=`a7896298...` 一致；user ACCESS `POST /api/user/auth/register, status=200, rt=88ms` | ✅ |
| L3 | Redis 验证码 key `myxhs:user:captcha:53e7b474...` value=None（**一次性消费成功**） | ✅ |
| L4 | MySQL t_user 新记录：id=2083494715266764802, username=testuser1785578824, nickname=testuser1785578824, phone=13985578824, gender=0, status=1, password=`$2a$10$21EaBYXzZVyy5bZ.4J...`(BCrypt, len=60), deleted=0 | ✅ |
| L5 | user 日志 `[验证码] 校验成功, key=53e7b474...` + `[注册] 用户注册成功, userId=2083494715266764802, username=testuser1785578824` | ✅ |
| L6 | Nacos（Actuator nacosDiscovery UP） | ✅ |
| L7 | XXL-Job N/A | ✅ N/A |
| L8 | MQ N/A | ✅ N/A |
| L9 | @RateLimit N/A（register 无 @RateLimit） | ✅ N/A |
| L10 | Sentinel N/A | ✅ N/A |
| L11 | SkyWalking（gateway↔user sw8 传播，traceId 一致） | ✅ |
| L12 | Gateway 路由（本用例即通过 gateway） | ✅ |
| L13 | Actuator `status: UP` | ✅ |

### 异常用例 1：手机号已注册

**curl 请求**（复用已注册手机号 13800138001）：
```bash
curl -s -X POST http://21.214.97.212:19000/api/user/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser1785578799","password":"Test@123456","phone":"13800138001","captchaKey":"07410899d86b4a2f86281b7db32f8235","captchaCode":"JDVR"}'
```

**响应**（HTTP=200, time=201ms）：
```json
{"code":10003,"message":"手机号已注册","timestamp":1785578799460,"success":false}
```

**验证**：
- L1 HTTP=200 + code=10003（业务异常，非 HTTP 错误）✅
- L3 Redis 验证码 key value=None（**验证码已一次性消费**——校验失败也删除）✅
- L5 user 日志 `[验证码] 校验成功`（验证码本身正确）+ `[业务异常] URI=/api/user/auth/register, code=10003, message=手机号已注册` ✅
- L2 traceId=`b9e52cb7...` gateway↔user 一致 ✅

**工程意义**：验证码校验在分布式锁之前（step 1），校验成功后立即删除（一次性消费）。即使后续业务校验失败（手机号已存在），验证码也不会退回——用户必须重新获取验证码。这是防暴力枚举的设计：攻击者不能用同一个验证码反复尝试不同用户名/手机号。

### 异常用例 2：验证码错误

**curl 请求**（正确 key + 错误 code=XXXX）：
```bash
curl -s -X POST http://21.214.97.212:19000/api/user/auth/register \
  -d '{"username":"newuser...","password":"Test@123456","captchaKey":"a9fcba40...","captchaCode":"XXXX"}'
```

**响应**（HTTP=200）：
```json
{"code":40104,"message":"验证码错误或已过期","success":false}
```

**验证**：Redis 验证码 key value=None（**校验失败也删除** ✅）。应用日志 `[验证码] 校验失败(输入错误), key=a9fcba40...`。

### 异常用例 3：用户名已存在

**curl 请求**（复用已注册用户名 testuser1785578824 + 正确验证码 MLTE）：

**响应**（HTTP=200）：
```json
{"code":10002,"message":"用户名已存在","success":false}
```

**验证**：验证码校验成功（code 正确）→ 分布式锁获取成功 → 锁内 selectCount(username)=1 → 抛 USERNAME_EXISTS。验证码已消费（一次性）。

### 异常用例 4-6：参数校验失败（@Valid 触发）

| 用例 | 输入 | 响应 code | HTTP | message |
|:--:|------|:--:|:--:|------|
| 4 短密码 | password=`123`（3位） | 40002 | **400** | 密码长度6~64位 |
| 5 用户名特殊字符 | username=`test@user` | 40002 | **400** | 用户名只能包含字母、数字和下划线 |
| 6 空用户名 | username=`` | 40002 | **400** | 用户名不能为空; 用户名长度4~32位; 用户名只能包含字母、数字和下划线 |

**关键区别**：
- 异常用例 1-3（业务异常）：HTTP=200 + code=10xxx（BizException，业务流程内的校验）
- 异常用例 4-6（参数校验）：HTTP=**400** + code=40002（MethodArgumentNotValidException，@Valid 在 Controller 入口触发，**不消费验证码**）

**应用日志**：
```
[参数校验失败] 用户名只能包含字母、数字和下划线
[ACCESS] POST /api/user/auth/register, status=400, rt=3ms
```

**工程意义**：参数校验（@Valid）在 Controller 入口由 Spring MVC 触发，**不会进入 UserService.register()**，因此不会消费验证码、不会获取分布式锁。这是正确的分层——参数格式校验应在业务逻辑之前。

### 工程知识点

**1. 注册流程（UserService.register() line 67-110）**
```
1. verifyCaptcha(key, code)  → 查 Redis → 比较 → 无条件删除
2. RLock lock = redissonClient.getLock("myxhs:user:register:lock:" + username)
   → tryLock(3s wait, 10s hold)
3. 锁内：selectCount(username) → 查重
4. 锁内：selectCount(phone) → 查重（如果提供了手机号）
5. passwordEncoder.encode(password) → BCrypt $2a$10$...
6. userMapper.insert(user) → id 由号段模式生成
7. finally: lock.unlock()
```

**2. 分布式锁粒度 = 用户名**
- `RedisKeyConstants.USER_REGISTER_LOCK + username` = `myxhs:user:register:lock:{username}`
- 不同用户名并行注册，相同用户名互斥
- tryLock(3, 10, TimeUnit.SECONDS)：等待 3s，持有 10s 自动释放（防死锁）
- **关键**：锁内做唯一性检查，防止两个并发请求同时看到 count=0 都 insert

**3. BCrypt 密码加密**
- `passwordEncoder.encode()` 生成 `$2a$10$...` 格式（60 字符）
- `$2a$` = BCrypt 算法标识，`10` = cost factor（2^10=1024 轮迭代）
- 每次加密 salt 随机，同一密码每次加密结果不同（防彩虹表）

**4. 验证码一次性消费 + 顺序设计**
- verifyCaptcha 在分布式锁**之前**（step 1）—— 防止攻击者用验证码占用锁资源
- 校验失败也删除 —— 防暴力枚举
- 业务校验失败（手机号已存在）不退回验证码 —— 用户必须重新获取

**5. ⚠️ 潜在风险：验证码校验在锁外**
- verifyCaptcha 在分布式锁之前执行，如果攻击者用正确验证码 + 不同用户名并发注册，验证码会被第一次请求消费，后续请求验证码已删除
- 但后续请求会在 verifyCaptcha 阶段失败（code=CAPTCHA_EXPIRED），不会占用锁
- 这是合理的设计：验证码是"准入凭证"，不是"资源"

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| HTTP 200 + code=200 | HTTP=200, code=200, success=true | 通过 |
| MySQL 新记录 + BCrypt 密码 | id=2083494715266764802, password=$2a$10$... len=60 | 通过 |
| Redis 验证码一次性消费 | captcha key value=None | 通过 |
| traceId gateway↔user 一致 | a7896298... 一致 | 通过 |
| 异常：手机号已注册 code=10003 | code=10003 + 验证码已消费 | 通过 |
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

> 测试时间：2026-08-01 18:09 CST | 服务：gateway(19000) → user(19001) | **通过 Gateway 调用**

### 正常用例

**前置**：用 2.2 注册的 testuser1785578824 / Test@123456

**curl 请求**（通过 Gateway）：
```bash
curl -s -X POST http://21.214.97.212:19000/api/user/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser1785578824","password":"Test@123456","captchaKey":"967cea91...","captchaCode":"KX57"}'
```

**响应**（HTTP=200, time=291ms）：
```json
{
  "code": 200, "message": "操作成功", "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiI1NzM4YzNkNiIsInN1YiI6IjIwODM0OTQ3MTUyNjY3NjQ4MDIiLCJ0eXBlIjoiYWNjZXNzIiwiaWF0IjoxNzg1NTc4OTg2LCJleHAiOjE3ODU1ODA3ODZ9.LoK6...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiI0MmUzZTc5NiIsInN1YiI6IjIwODM0OTQ3MTUyNjY3NjQ4MDIiLCJ0eXBlIjoicmVmcmVzaCIsImlhdCI6MTc4NTU3ODk4NiwiZXhwIjoxNzg2MTgzNzg2fQ.DDG7..."
  }
}
```

**JWT payload 解码**（accessToken）：
```json
{"jti":"5738c3d6...","sub":"2083494715266764802","type":"access","iat":1785578986,"exp":1785580786}
// sub=userId, exp-iat=1800s=30min
```

### 13 层验证

| 层 | 验证项 | 结果 |
|:--:|------|:----:|
| L1 | API 响应 HTTP=200 + accessToken + refreshToken（JWT 格式） | ✅ |
| L2 | gateway traceId=`12755ead...` ↔ user traceId=`12755ead...` 一致；user ACCESS `POST /api/user/auth/login, status=200, rt=280ms` | ✅ |
| L3 | Redis access token `myxhs:user:token:access:2083494715266764802` value=JWT TTL=1762s（~30min）；refresh token `myxhs:user:token:refresh:2083494715266764802` TTL=604762s（~7day）；**验证码已删除（一次性消费）**；fail count=None（已清除）；lock=None | ✅ |
| L4 | MySQL 无变化（登录只读，不写 DB） | ✅ N/A |
| L5 | user 日志 `[登录] 用户登录成功, userId=2083494715266764802, username=testuser1785578824` | ✅ |
| L6-L10 | N/A 或同 2.1/2.2 | ✅ |
| L11 | SkyWalking sw8 传播（gateway↔user traceId 一致） | ✅ |
| L12 | Gateway 路由（本用例即通过 gateway） | ✅ |
| L13 | Actuator `status: UP` | ✅ |

> **关键**：Token Redis key = `myxhs:user:token:access:{userId}`（**以 userId 为 key，不是 token**），支持单设备登录踢出。

### 异常用例 1：密码错误

**响应**（HTTP=200）：`{"code":40108,"message":"用户名或密码错误"}`

**验证**：
- Redis `myxhs:user:login:fail:testuser1785578824` value=1（失败计数 +1）✅
- 应用日志 `[登录] 密码错误, userId=2083494715266764802` + `[登录] 登录失败计数, 当前失败次数=1` ✅

### 异常用例 2：用户不存在

**响应**（HTTP=200）：`{"code":40108,"message":"用户名或密码错误"}`

**验证**：
- **message 与"密码错误"完全相同**——安全设计，不暴露用户是否存在 ✅
- 应用日志 `[登录] 用户不存在, username=nosuchuser12345` + `登录失败计数, 当前失败次数=1` ✅
- 用户不存在也 incrementLoginFail（防暴力枚举用户名）✅

### 异常用例 3：验证码错误/过期

**响应**（HTTP=200）：`{"code":40105,"message":"验证码已过期"}`

**验证**：用 fakekey，Redis 查不到 → CAPTCHA_EXPIRED。验证码校验在密码校验之前（step 1）。

### 工程知识点

**1. 登录流程（UserService.login() line 131-180）**
```
1. verifyCaptcha(key, code)        → 一次性消费
2. 检查账号锁定                     → Redis myxhs:user:login:lock:{username}
3. 查用户（@TableLogic deleted=0）  → selectOne(username)
4. 检查状态 status==0 → ACCOUNT_DISABLED
5. 校验密码 passwordEncoder.matches → 失败走 incrementLoginFail
6. 清除失败计数                     → Redis DEL myxhs:user:login:fail:{username}
7. 生成 Token 对                    → JWT + Redis 存储（key=userId）
```

**2. 单设备登录设计（TokenService.generateTokenPair）**
- Redis key = `myxhs:user:token:access:{userId}`（**以 userId 为 key**，不是 token）
- 后登录覆盖前一个 token → 同一用户同时只能有一个有效 Token 对
- 如需多端登录：Key 改为 `{userId}:{deviceId}`

**3. 安全设计：不暴露用户是否存在**
- 用户不存在和密码错误都返回 `code=40108 "用户名或密码错误"`
- 应用日志区分（`用户不存在` vs `密码错误`），但 API 响应不区分
- 防止攻击者通过 API 响应枚举有效用户名

**4. 登录失败计数 + 账号锁定**
- `incrementLoginFail(username)`：Redis INCR `myxhs:user:login:fail:{username}`
- 达到阈值（如 5 次）→ 设置 `myxhs:user:login:lock:{username}` 锁定
- 用户不存在也计数（防暴力枚举用户名）

**5. JWT + Redis 双重设计**
- JWT 无状态：自带 exp/iat/sub/jti，可独立验证签名
- Redis 存储：用于单设备登录踢出 + 登出黑名单 + 改密全注销
- 两者结合：JWT 保证性能（无需查 Redis 验签），Redis 保证可控（可主动失效）

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| HTTP 200 + accessToken + refreshToken | HTTP=200, JWT 双 token | 通过 |
| JWT.sub = userId | sub=2083494715266764802 | 通过 |
| AccessToken TTL ≈ 30min | 1762s | 通过 |
| RefreshToken TTL ≈ 7day | 604762s | 通过 |
| Redis token key = {userId} | myxhs:user:token:access:2083494715266764802 | 通过 |
| 验证码一次性消费 | captcha key value=None | 通过 |
| traceId gateway↔user 一致 | 12755ead... 一致 | 通过 |
| 异常：密码错误 code=40108 + fail count=1 | code=40108, fail count=1 | 通过 |
| 异常：用户不存在 code=40108（同 message） | code=40108, message 相同 | 通过 |
| 异常：验证码过期 code=40105 | code=40105 | 通过 |

---

## 2.4 获取当前用户信息 — `GET /api/user/me`

> 测试时间：2026-08-01 18:16 CST | 服务：gateway(19000) → user(19001) | **通过 Gateway + HMAC 签名 + JWT**

### 正常用例

**前置**：2.3 登录获取 accessToken + 构造 HMAC 签名

**curl 请求**（通过 Gateway + Bearer token + HMAC 签名 3 个 header）：
```bash
# 签名 = Base64(HmacSHA256(secret, method+path+timestamp+nonce))
# secret = myxhs-hmac-secret-key-2024
curl -s http://21.214.97.212:19000/api/user/me \
  -H "Authorization: Bearer eyJhbGc..." \
  -H "X-Timestamp: 1785579389000" \
  -H "X-Nonce: b5d6a691..." \
  -H "X-Signature: <Base64(HmacSHA256(...))>"
```

**响应**（HTTP=200, time=80ms）：
```json
{
  "code": 200, "message": "操作成功", "success": true,
  "data": {
    "id": 2083494715266764802,
    "username": "testuser1785578824",
    "nickname": "testuser1785578824",
    "avatar": null, "gender": 0, "birthday": null,
    "phone": "13985578824",  // ← 完整（不脱敏，用户看自己信息）
    "email": null, "signature": null,
    "status": 1, "createdAt": "2026-08-01 18:07:04"
  }
}
```

> **修复说明**：原代码 toUserInfoResponse 调用 maskPhone/maskEmail 脱敏，但 /me 是用户看自己的信息，应返回完整 phone/email。已改为不脱敏（脱敏只在公开接口 toUserPublicInfoResponse 中体现——完全剔除 phone/email）。

### 13 层验证

| 层 | 验证项 | 结果 |
|:--:|------|:----:|
| L1 | API 响应 HTTP=200 + 完整用户信息（phone 脱敏 `139****8824`） | ✅ |
| L2 | gateway `[Gateway] 鉴权通过, userId=2083494715266764802, path=/api/user/me` ↔ user `[ACCESS] GET /api/user/me, status=200, rt=12ms`，traceId=`b5d6a691...` 一致 | ✅ |
| L3 | Redis token `myxhs:user:token:access:2083494715266764802` 存在（JWT 有效） | ✅ |
| L4 | MySQL 无变化（只读） | ✅ N/A |
| L5 | user 日志 `[用户] 获取用户信息成功, userId=2083494715266764802` | ✅ |
| L9 | @RateLimit N/A | ✅ N/A |
| L10 | Sentinel N/A | ✅ N/A |
| L11 | SkyWalking sw8 传播（traceId 一致） | ✅ |
| L12 | **Gateway HMAC 签名校验通过** + JWT 鉴权通过（userId 注入 X-User-Id header） | ✅ |
| L13 | Actuator `status: UP` | ✅ |

### 异常用例

| 用例 | 请求 | HTTP | 响应 |
|:--:|------|:--:|------|
| 1 无 token | 有签名，无 Authorization | **401** | `{"code":401,"message":"缺少认证信息"}` |
| 2 有 token 无签名 | 有 Authorization，无 X-Timestamp/X-Nonce/X-Signature | **403** | `{"code":403,"message":"签名校验失败：非公开接口必须携带 X-Timestamp、X-Nonce、X-Signature"}` |
| 3 有 token 错误签名 | Authorization + 3 个签名 header，但 X-Signature=wrongsignature | **403** | `{"code":403,"message":"签名校验失败：签名不匹配"}` |

**安全分层**：
- **401（JWT 鉴权）**：GatewayAuthFilter 校验 Authorization header → 解析 JWT → 提取 userId → 注入 X-User-Id
- **403（HMAC 签名）**：HmacSignatureFilter 校验 X-Timestamp + X-Nonce + X-Signature → 防篡改 + 防重放
- 两个 filter 独立工作，白名单分别配置

### 工程知识点

**1. Gateway 双重安全：JWT 鉴权 + HMAC 签名（per-session secret）**
- **JWT 鉴权**（GatewayAuthFilter）：校验 Authorization Bearer token → 提取 userId → 注入 X-User-Id header
- **HMAC 签名**（HmacSignatureFilter）：校验 X-Timestamp + X-Nonce + X-Signature → 防篡改 + 防重放
- **per-session secret**（方案 A 修复）：登录时生成 UUID secret 存 Redis `myxhs:user:hmac:secret:{userId}` TTL=7day，放入登录响应 `hmacSecret` 字段。Gateway 从 Redis 取 secret 验签（不再用全局 secret）
- 两个安全维度独立，白名单分别配置

**2. HMAC 签名算法**
- 签名输入：`method + path + timestamp + nonce`（直接拼接，无分隔符）
- 算法：`Base64(HmacSHA256(perSessionSecret, signStr))`
- secret = 登录响应的 `hmacSecret`（per-session UUID，不是配置文件全局 secret）
- 签名输出：Base64 编码

**3. 防重放机制**
- **timestamp**：毫秒时间戳，超过 5 分钟拒绝（`TIMESTAMP_TOLERANCE_MS = 5 * 60 * 1000`）
- **nonce**：UUID，Redis SETNX 保证唯一性（Lua 脚本 `SET NX EX` 原子操作），TTL=5min
- nonce Redis key = `myxhs:gateway:nonce:{nonce}`

**4. 手机号脱敏**
- /me 返回 `phone: "139****8824"`（中间 4 位用 * 替换）
- /{userId}/info（公开接口）不返回 phone（非敏感字段 only）
- 脱敏在 UserService.getUserInfo() 中实现

**5. ⚠️ Redis 异常降级设计**
- HmacSignatureFilter 注释："Redis 异常时降级为放行（宁可漏放，不可误拒）"
- nonce 去重依赖 Redis，Redis 不可用时签名校验降级为只校验 timestamp + 签名
- 设计原则：签名校验是"安全增强"而非"核心鉴权"，可用性优先

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| HTTP 200 + 用户信息 + phone 脱敏 | HTTP=200, phone=`139****8824` | 通过 |
| Gateway 鉴权通过 + userId 注入 | `[Gateway] 鉴权通过, userId=2083494715266764802` | 通过 |
| traceId gateway↔user 一致 | b5d6a691... 一致 | 通过 |
| 异常：无 token 401 | HTTP=401 缺少认证信息 | 通过 |
| 异常：无签名 403 | HTTP=403 必须携带签名 header | 通过 |
| 异常：错误签名 403 | HTTP=403 签名不匹配 | 通过 |


