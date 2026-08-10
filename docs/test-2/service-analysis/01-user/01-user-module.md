# my-xhs-user 用户模块

## 模块概览

| 项目 | 内容 |
|------|------|
| 模块路径 | `my-xhs-user/` |
| 端口 | 19001 |
| 服务名 | `my-xhs-user`（注册到 Nacos） |
| 数据库 | `my_xhs_user`（MySQL 13306） |
| 启动类 | `UserApplication.java` |
| 扫描包 | `com.myxhs.user`, `com.myxhs.common` |

**职责边界**：用户注册/登录/注销（认证）、个人信息 CRUD（含敏感信息脱敏）、收货地址管理、用户屏蔽。

---

## 1. 数据模型

### 1.1 数据库结构

数据库 `my_xhs_user` 使用 MySQL 实例 13306，含 2 张业务表。

**`t_user` — 用户表**

```sql
id          BIGINT PRIMARY KEY    -- 号段模式生成（起始 10001，步长 1000）
username    VARCHAR(64) UNIQUE    -- 用户名（字母数字下划线，4-32 位）
password    VARCHAR(128)          -- BCrypt 加密
nickname    VARCHAR(64)           -- 昵称（默认 = username）
avatar      VARCHAR(512)          -- 头像 URL
gender      TINYINT               -- 0=未知 1=男 2=女
birthday    DATE                  -- 生日
phone       VARCHAR(20) UNIQUE    -- 手机号
email       VARCHAR(128)          -- 邮箱
signature   VARCHAR(256)          -- 个性签名
status      TINYINT DEFAULT 1     -- 0=禁用 1=正常
deleted     TINYINT DEFAULT 0     -- 逻辑删除（@TableLogic）
created_at  DATETIME
updated_at  DATETIME
```

索引：PK(id)、UK(username)、UK(phone)、INDEX(created_at)

**`t_user_address` — 收货地址表**

```sql
id              BIGINT PRIMARY KEY
user_id         BIGINT              -- 用户 ID
receiver_name   VARCHAR(64)         -- 收货人姓名
receiver_phone  VARCHAR(20)         -- 联系电话
province        VARCHAR(64)         -- 省
city            VARCHAR(64)         -- 市
district        VARCHAR(64)         -- 区
detail_address  VARCHAR(256)        -- 详细地址
is_default      TINYINT DEFAULT 0   -- 0=否 1=是（唯一默认）
deleted         TINYINT DEFAULT 0   -- 逻辑删除
created_at      DATETIME
updated_at      DATETIME
```

索引：PK(id)、INDEX(user_id)

### 1.2 ID 生成策略

使用号段模式（Leaf-like），`t_id_segment` 表管理：

| biz_tag | 起始 ID | 步长 | 说明 |
|---------|---------|------|------|
| `user` | 10001 | 1000 | 用户 ID |
| `address` | 0 | 1000 | 地址 ID |

---

## 2. 接口清单（16 个 REST 端点）

### 2.1 认证接口（5 个）— `/api/user/auth`

**`GET /api/user/auth/captcha`** — 获取图形验证码
- Gateway 白名单：是
- 请求：无参数
- 返回：`R<CaptchaResponse>` — `captchaKey`（UUID）+ `captchaImage`（Base64 PNG）

**`POST /api/user/auth/register`** — 用户注册
- Gateway 白名单：是
- 请求：`RegisterRequest` — `username`（4-32 位字母数字下划线）、`password`（6-64 位）、`phone`（可选）、`captchaKey`、`captchaCode`
- 返回：`R<Void>`

**`POST /api/user/auth/login`** — 用户登录
- Gateway 白名单：是
- 请求：`LoginRequest` — `username`、`password`、`captchaKey`、`captchaCode`
- 返回：`R<TokenResponse>` — `accessToken`（JWT 30min）+ `refreshToken`（JWT 7d）

**`POST /api/user/auth/refresh`** — 刷新 Token
- Gateway 白名单：是
- 请求：`?refreshToken=xxx`
- 返回：`R<TokenResponse>` — 新 Token 对，旧 Token 入黑名单

**`POST /api/user/auth/logout`** — 退出登录
- Gateway 白名单：是
- 请求：`Authorization` Header + `?refreshToken=xxx`
- 返回：`R<Void>`

### 2.2 用户信息接口（5 个）— `/api/user`

> 注意：此组接口需要 HMAC 签名（不在 HMAC 白名单中）

**`GET /api/user/me`** — 获取当前用户信息（完整版）
- 请求：`X-User-Id` Header（Gateway 注入）
- 返回：`R<UserInfoResponse>` — 含 id/username/nickname/avatar/gender/birthday/phone(脱敏)/email(脱敏)/signature/status/createdAt

**`GET /api/user/{userId}/info`** — 获取公开用户信息（精简版）
- Gateway 白名单：是（`/api/user/*/info` 在 auth + hmac 双白名单）
- 请求：路径参数 `userId`
- 返回：`R<UserPublicInfoResponse>` — 只含 id/username/nickname/avatar/gender/signature/createdAt（不含手机/邮箱/状态）

**`PUT /api/user/me`** — 更新个人信息
- 请求：`X-User-Id` + `UpdateUserRequest`（所有字段可选，只更新非 null 字段）
- 返回：`R<UserInfoResponse>`

**`PUT /api/user/me/password`** — 修改密码
- 请求：`X-User-Id` + `ChangePasswordRequest`（`oldPassword` + `newPassword`）
- 返回：`R<Void>`
- 副作用：注销所有活跃 Token（防止改密后旧 Token 继续生效）

### 2.3 屏蔽管理（3 个）— `/api/user/block`

**`POST /api/user/block/{targetUserId}`** — 屏蔽用户
- 请求：`X-User-Id` + 路径参数
- 返回：`R<Void>`
- 校验：不能屏蔽自己

**`DELETE /api/user/block/{targetUserId}`** — 取消屏蔽
- 请求：`X-User-Id` + 路径参数
- 返回：`R<Void>`

**`GET /api/user/block/list`** — 获取屏蔽列表
- 请求：`X-User-Id`
- 返回：`R<Set<Object>>` — 被屏蔽用户 ID 集合

### 2.4 收货地址接口（7 个）— `/api/user/address`

> 注意：此组接口需要 HMAC 签名

**`GET /api/user/address/list`** — 地址列表
- 返回：`R<List<AddressVO>>` — 按 is_default DESC + created_at DESC 排序，手机号脱敏

**`GET /api/user/address/default`** — 获取默认地址（带缓存）
- 返回：`R<AddressVO>` — Redis 缓存 addressId → DB 查详情，TTL 30 分钟

**`GET /api/user/address/{id}`** — 地址详情
- 返回：`R<AddressVO>`

**`POST /api/user/address`** — 新增地址
- 请求：`AddressCreateRequest` — receiverName/receiverPhone/province/city/district/detailAddress/isDefault
- 上限 20 条，第一条自动设默认，Redisson 分布式锁防并发超限

**`PUT /api/user/address/{id}`** — 更新地址
- 请求：`AddressUpdateRequest`（全部可选）
- 双重校验防越权（WHERE id=xxx AND user_id=xxx）

**`DELETE /api/user/address/{id}`** — 删除地址（逻辑删除）
- 删除默认地址时自动将第一条设为新默认

**`PUT /api/user/address/{id}/default`** — 设置默认地址
- 取消旧默认 → 设置新默认 → 更新缓存

---

## 3. 内部架构

### 3.1 核心组件图

```
Controller 层
├── AuthController       ─┬─ UserService
├── UserController       ─┤
└── UserAddressController ─┴─ UserAddressService
                                │
Service 层                      │
├── UserService          ──────┤
│   ├── CaptchaService         │
│   ├── TokenService           │
│   ├── CacheHelper            │→ Redis (Cache + 分布式锁 + 黑名单)
│   ├── RedisOperator          │
│   └── UserMapper             │→ MySQL
├── UserAddressService  ──────┤
│   ├── RedisOperator          │→ Redis (默认地址缓存)
│   ├── UserAddressMapper      │→ MySQL
│   └── RedissonClient         │→ Redisson Lock
├── TokenService         ──────┘
│   ├── JwtUtil (common)
│   ├── RedisOperator          │→ Redis (Token 映射 + 黑名单)
│   └── RedissonClient         │→ Redisson Lock (刷新时防并发)

MQ Consumer
└── CacheEvictConsumer   ──────   订阅 CACHE_EVICT_TOPIC（用户缓存 L3 兜底删除）
```

### 3.2 Redis Key 清单

| Key 模式 | 类型 | TTL | 用途 |
|---------|------|------|------|
| `myxhs:user:captcha:{uuid}` | String | 5min | 验证码原文 |
| `myxhs:user:token:access:{userId}` | String | 30min | Access Token 映射（单设备登录） |
| `myxhs:user:token:refresh:{userId}` | String | 7d | Refresh Token 映射 |
| `myxhs:user:token:blacklist:{jti}` | String | Token 剩余有效期 | 注销 Token 黑名单 |
| `myxhs:user:login:fail:{username}` | Counter | 30min（首次后） | 登录失败计数 |
| `myxhs:user:login:lock:{username}` | String | 15min | 账号锁定标志 |
| `myxhs:user:register:lock:{username}` | RLock | 10s wait / 3s lease | 注册防并发锁 |
| `myxhs:user:info:{userId}` | Object | 30min | 用户信息缓存 |
| `myxhs:user:address:default:{userId}` | String (addressId) | 30min | 默认地址 ID 缓存 |
| `myxhs:user:address:lock:{userId}` | RLock | 10s wait / 3s lease | 地址创建防并发锁 |
| `myxhs:user:block:{userId}` | Set | 持久 | 屏蔽用户 ID 集合 |
| `myxhs:token:refresh:lock:{jti}` | RLock | 10s wait / 3s lease | Token 刷新防并发锁 |

---

## 4. 关键设计详解

### 4.1 认证体系

**密码**：BCrypt 加密，通过 `PasswordEncoderConfig` 注册为 Spring Bean。

**JWT 双 Token**：
- Access Token：JWT（`type=access`），30 分钟有效。携带业务接口。
- Refresh Token：JWT（`type=refresh`），7 天有效。仅用于刷新 Access Token。
- 签名密钥：`gateway.auth.secret`（`MyXhs@2026#JwtSecretKey!ForTokenSign`），Gateway 与 User 服务共用。

**单设备登录**：Redis 中按 userId 存储 Token（不按设备 ID），后登录覆盖前一个。后登录设备的 Refresh Token 可以刷新，前一个设备的 Refresh Token 因 Redis 中的值不匹配而失效。

**注销流程**：

```
logout(accessToken, refreshToken)
  ├─ 1. 解析 accessToken 获取 jti、exp
  ├─ 2. (jti, "1") 写入黑名单，TTL = 剩余有效期
  ├─ 3. 解析 refreshToken，同上入黑名单
  └─ 4. 删除 Redis 中 userId → Token 映射
```

**Token 刷新流程**（最具安全性的链路）：

```
refreshToken(oldRefreshToken)
  ├─ 1. 解析 oldRefreshToken，校验 type=refresh
  ├─ 2. 查黑名单：jti 是否已拉黑？（首次检查）
  ├─ 3. 获取分布式锁 lock(myxhs:token:refresh:lock:{jti})  ← 防并发刷新
  ├─ 4. 二次查黑名单（另一个线程可能已经刷新并拉黑了）
  ├─ 5. 校验 Redis 中的 RefreshToken 是否一致  ← 单设备登录保障
  │    如果 != oldRefreshToken → 说明被其他设备覆盖 → 拒绝
  ├─ 6. oldRefreshToken 入黑名单
  ├─ 7. generateTokenPair() → Redis 覆盖 → 返回新 Token 对
  └─ 8. 释放锁
```

**改密后全注销**：`changePassword()` 调用 `revokeAllTokens(userId)` → 取出 Redis 中当前活跃的 access/refresh Token → 全入黑名单 → 删除映射。确保改密后旧 Token 不生效。

### 4.2 登录安全

**防暴力破解**：
1. 登录失败 → Redis INCR `myxhs:user:login:fail:{username}`，首次失败设 TTL 30min
2. 达到 5 次 → 写 `myxhs:user:login:lock:{username}` = "1"，TTL 15min → 删除失败计数
3. 登录成功 → 删除失败计数
4. 锁定期间返回 `ACCOUNT_LOCKED`

**防并发注册**：Redisson 分布式锁（粒度 = 用户名），尝试等 3s，持有 10s。锁内：检查用户名唯一性 → 检查手机号唯一性 → insert DB。

### 4.3 缓存策略

**用户信息**：Cache Aside 模式，`CacheHelper.getWithCacheAside`（来自 common 模块）。

```
getUserInfo(userId)
  ├─ 1. 查 Redis: myxhs:user:info:{userId}
  │    命中 → 返回
  └─ 2. 未命中 → 查 DB（排除 password 字段）
        └─ 3. 回填 Redis，TTL 30min
```

`getUserInfo()` 和 `getUserPublicInfo()` 共享同一份缓存——从缓存拿到完整 User 对象后，各自提取不同字段。`select()` 使用了 `.select()` 方法排除 `password`，避免敏感信息落入 Redis。

**缓存一致性**：三级保障（见架构文档 `06-cache-strategy.md`）：

```
updateUserInfo()
  ├─ L1: 先更新 DB
  ├─ L2: CacheHelper.delayDoubleDelete(cacheKey) → 延迟双删
  └─ L3: CacheEvictConsumer 订阅 CACHE_EVICT_TOPIC → MQ 兜底删除
```

**默认地址缓存**：只缓存默认地址 ID（不缓存完整 Address 对象，减少数据不一致风险）。

### 4.4 数据脱敏

| 字段 | 脱敏规则 | 示例 |
|------|---------|------|
| 手机号 | 前 3 + `****` + 后 4 | `138****1234` |
| 邮箱 | `u***@example.com` | `test***@qq.com` |

脱敏通过响应 DTO 的静态方法实现（`UserInfoResponse.maskPhone/maskEmail`），在 `toUserInfoResponse()` 转换时调用。API 返回给客户端的永远是脱敏后的数据，DB 中存储的是原始值。

### 4.5 地址上限与唯一默认

- 每用户最多 20 条地址（`app.address.limit` 可配置）
- 创建时 Redisson 分布式锁防并发超限
- 唯一默认：设新默认时 `cancelDefaultAddress()` 取消旧默认（`UPDATE SET is_default=0 WHERE user_id=? AND is_default=1`）
- 删除默认地址时自动降级：取最新一条非默认地址设为新默认

---

## 5. 业务代码详解

### 5.1 注册 — `UserService.register()`

**源码**：`my-xhs-user/src/main/java/com/myxhs/user/service/UserService.java:67-117`

```
register(RegisterRequest)
│
├─ 1. captchaService.verifyCaptcha(key, code)
│      ├─ redisOperator.get("myxhs:user:captcha:" + key)
│      ├─ 对比（忽略大小写）
│      └─ redisOperator.delete(key)  ← 无论匹配与否都删除，防暴力枚举
│
├─ 2. redissonClient.getLock("myxhs:user:register:lock:" + username)
│      ├─ tryLock(3, 10, SECONDS)  ← 等 3 秒，持锁 10 秒
│      ├─ 锁内：userMapper.selectCount(eq(username)) → 用户名唯一性
│      ├─ 锁内：userMapper.selectCount(eq(phone)) → 手机号唯一性
│      └─ finally: if (lock.isHeldByCurrentThread()) unlock()
│
├─ 3. new User()
│      ├─ password = passwordEncoder.encode(rawPassword)  ← BCrypt
│      ├─ nickname = username                             ← 默认昵称
│      ├─ gender = 0, status = 1
│      └─ userMapper.insert(user)                         ← 号段模式生成 ID
│
└─ 4. 返回（无 Token，注册后需登录）
```

**关键设计点**：

1. **验证码一次性消费**：`verifyCaptcha()` 中是先校验再删除，但**校验失败也删除**——这是防暴力枚举的关键。如果失败只做 compare 不 delete，攻击者可以无数次重试同一个 captchaKey。

2. **锁粒度 = 用户名**：用 `username` 而不是笼统的 `register` 做锁 key。如果两个不同用户同时注册，它们应该并行；只有同一用户名并发注册时才互斥。

3. **锁内做唯一性检查**：如果 `selectCount` 在锁外做，两个并发请求可能都看到 count=0，都 insert，造成唯一键冲突。锁内检查+插入保证原子性。

4. **`isHeldByCurrentThread()` 判断**：Redisson RLock 在 finally 解锁时必须判断。如果 `tryLock` 超时返回 false，当前线程不持锁，此时 `unlock()` 会抛异常或者误删其他线程持有的锁。

---

### 5.2 登录 — `UserService.login()`

**源码**：`my-xhs-user/src/main/java/com/myxhs/user/service/UserService.java:131-175`

```
login(LoginRequest)
│
├─ 1. captchaService.verifyCaptcha(key, code)
│
├─ 2. redisOperator.get("myxhs:user:login:lock:" + username)
│      → 不为 null → throw ACCOUNT_LOCKED
│
├─ 3. userMapper.selectOne(eq(username))  ← @TableLogic 自动加 deleted=0
│      → null → incrementLoginFail → throw PASSWORD_ERROR（不暴露用户是否存在）
│
├─ 4. if (user.status == 0) → throw ACCOUNT_DISABLED
│
├─ 5. passwordEncoder.matches(rawPassword, bcryptHash)
│      → false → incrementLoginFail → throw PASSWORD_ERROR
│
├─ 6. clearLoginFail(username)  ← 登录成功清除失败计数
│
└─ 7. tokenService.generateTokenPair(userId)
       ├─ JwtUtil.generateToken(userId, "access", 30min, secret)
       ├─ JwtUtil.generateToken(userId, "refresh", 7d, secret)
       ├─ redisOperator.set("myxhs:user:token:access:" + userId, accessToken, 30min)
       └─ redisOperator.set("myxhs:user:token:refresh:" + userId, refreshToken, 7d)
```

**关键设计点**：

1. **用户名不存在也返回 "用户名或密码错误"**：不区分"用户不存在"和"密码错误"，防止攻击者通过错误信息枚举已注册用户名。

2. **登录失败计数机制**：
   ```
   incrementLoginFail(username)
     ├─ redisOperator.incr("myxhs:user:login:fail:" + username)
     ├─ 首次失败 → expire(key, 30min)       ← 过期窗口
     ├─ count >= 5 → 
     │    ├─ redisOperator.set("myxhs:user:login:lock:" + username, "1", 15min)
     │    └─ redisOperator.delete(failKey)   ← 锁定后清除计数
   ```
   计数器有 30 分钟过期窗口，意味着 30 分钟内连续输错 5 次才锁定。30 分钟后计数自动清零。

3. **用户不存在也计入失败计数**：即使查不到用户，仍然 `incrementLoginFail(username)`。不然攻击者可以遍历用户名，发现某个 username 不触发计数 → 这个 username 不存在。

4. **`@TableLogic` 自动过滤**：查询时不需要手动加 `deleted=0`，MyBatis-Plus 会自动在 WHERE 条件中追加。但如果用户被逻辑删除，查出来是 null → 走用户不存在的分支（同样不暴露"已删除"状态）。

---

### 5.3 Token 刷新 — `TokenService.refreshToken()`

**源码**：`my-xhs-user/src/main/java/com/myxhs/user/service/TokenService.java:89-154`

整个模块中最复杂的业务链路，6 层安全校验。

```
refreshToken(oldRefreshToken)
│
├─ 1. JwtUtil.parseToken(oldRefreshToken, secret) → Claims
│      ├─ catch 解析异常 → throw TOKEN_INVALID
│      └─ 校验 claims.type != "refresh" → throw "Token 类型错误"
│
├─ 2. isBlacklisted(claims.jti)                   ← 首次检查
│      └─ redisOperator.get("myxhs:user:token:blacklist:" + jti)
│         不为 null → throw TOKEN_REVOKED
│
├─ 3. redissonClient.getLock("myxhs:token:refresh:lock:" + jti)
│      ├─ tryLock(3, 10, SECONDS)                 ← 防并发刷新
│      └─ 失败 → throw "Token 正在刷新中，请稍后重试"
│
├─ 4. isBlacklisted(claims.jti)                   ← 二次检查（Double Check）
│      └─ 不为 null → throw "Token 已被刷新，请使用新 Token"
│
├─ 5. redisOperator.get("myxhs:user:token:refresh:" + userId)
│      ├─ 与 oldRefreshToken 比较
│      └─ 不一致 → throw "Token 已被其他设备覆盖，请重新登录"
│
├─ 6. blacklistByClaims(claims)                   ← 旧 Token 入黑名单
│      ├─ 计算剩余有效期 = exp - now
│      └─ redisOperator.set("myxhs:user:token:blacklist:" + jti, "1", remaining/1000+1)
│
└─ 7. generateTokenPair(userId)                   ← 生成新 Token 对，Redis 覆盖
```

**关键设计点**：

1. **Double Check Locking**：第 2 步和第 4 步是两次黑名单检查。为什么需要两次？
   - 第 2 步：快速过滤已明确被拉黑的 Token，避免不必要的锁竞争。
   - 第 4 步：在获取锁之后、执行实际操作之前。另一个线程可能在当前线程等待锁时已经刷新了同一 Token 并拉黑了它。不加二次检查会允许重复刷新。

2. **Redis 中的 Token 一致性检查**（第 5 步）：这是**单设备登录**的核心校验。`generateTokenPair()` 会覆盖 Redis 中的 Token 映射，所以如果用户在设备 B 登录，设备 A 持有的 oldRefreshToken 虽然 JWT 签名仍有效，但 Redis 中的值已经被设备 B 的 Token 覆盖了，设备 A 尝试刷新时会在此步被拒绝。

3. **黑名单 TTL = Token 剩余有效期的精确值**：`remainingMs / 1000 + 1`。这样可以确保黑名单中的 Token 到期后自动释放内存，不需要手动清理。

4. **锁粒度 = jti**：每个 Refresh Token 有唯一 jti，所以不同 Token 的刷新互不影响。不应该用 userId 粒度锁，否则同一用户的多设备（假设未来支持）会互相阻塞。

---

### 5.4 更新用户信息 — `UserService.updateUserInfo()`

**源码**：`my-xhs-user/src/main/java/com/myxhs/user/service/UserService.java:220-259`

```
updateUserInfo(userId, UpdateUserRequest)   ← UpdateUserRequest 所有字段可选（全部 @Nullable）
│
├─ 1. userMapper.selectById(userId) → User
│      → null → throw USER_NOT_FOUND
│
├─ 2. 手机号唯一性校验（如果提供了新手机号且与旧不同）
│      count = userMapper.selectCount(eq(phone).ne(id, userId))
│      count > 0 → throw PHONE_EXISTS
│
├─ 3. LambdaUpdateWrapper 动态构建
│      updateWrapper.eq(User::getId, userId)
│      if (request.getNickname() != null)  updateWrapper.set(User::getNickname, ...)
│      if (request.getAvatar() != null)    updateWrapper.set(User::getAvatar, ...)
│      if (request.getGender() != null)    updateWrapper.set(User::getGender, ...)
│      if (request.getBirthday() != null)  updateWrapper.set(User::getBirthday, ...)
│      if (request.getPhone() != null)     updateWrapper.set(User::getPhone, ...)
│      if (request.getEmail() != null)     updateWrapper.set(User::getEmail, ...)
│      if (request.getSignature() != null) updateWrapper.set(User::getSignature, ...)
│      userMapper.update(null, updateWrapper)   ← 只更新非 null 字段
│
├─ 4. cacheHelper.delayDoubleDelete("myxhs:user:info:" + userId)
│      └─ 延迟双删：立即删 → sleep 100ms → 再删
│
└─ 5. getUserInfoDirect(userId) → 直接查 DB（不走缓存）返回最新数据
```

**关键设计点**：

1. **只更新非 null 字段**：`UpdateUserRequest` 的所有字段都是可选的。前端只传要修改的字段，`null` 的字段不更新。避免了"忘记传 avatar 导致头像被清空"的问题。

2. **手机号唯一性使用 `.ne(id, userId)`**：`selectCount(eq(phone).ne(id, userId))` —— 排除自己，允许不修改手机号时不报错，也允许"把手机号改成自己已经在用的那个号"（虽然前端一般不会这样做）。

3. **先更新 DB → 再删缓存**：正确的顺序。如果反过来（先删缓存 → 再更新 DB），在删缓存和更新 DB 之间有窗口期，并发的读请求会查到旧数据并回填缓存。

4. **返回直接查 DB 不查缓存**：因为刚删了缓存，查缓存必然 miss，不如直接查 DB。而且 DB 中是最新数据，不会受缓存延迟影响。

---

### 5.5 修改密码 — `UserService.changePassword()`

**源码**：`my-xhs-user/src/main/java/com/myxhs/user/service/UserService.java:268-290`

```
changePassword(userId, ChangePasswordRequest)
│
├─ 1. userMapper.selectById(userId)
│      → null → throw USER_NOT_FOUND
│
├─ 2. passwordEncoder.matches(oldPassword, user.password)
│      → false → throw PASSWORD_ERROR("旧密码错误")
│
├─ 3. userMapper.updateById(new User(id, passwordEncoder.encode(newPassword)))
│
└─ 4. tokenService.revokeAllTokens(userId)
       ├─ redisOperator.get("myxhs:user:token:access:" + userId) → accessToken
       ├─ redisOperator.get("myxhs:user:token:refresh:" + userId) → refreshToken
       ├─ blacklistToken(accessToken)   ← JWT jti 入黑名单，TTL = 剩余有效期
       ├─ blacklistToken(refreshToken)  ← 同上
       └─ redisOperator.delete(...token...)  ← 清除 Redis 映射
```

**关键设计点**：

1. **`revokeAllTokens`** 是整个流程最关键的一步。如果不注销旧 Token，用户改密后，之前签发但尚未过期的 accessToken（30min）和 refreshToken（7天）仍可继续使用——攻击者拿到的旧密码泄露时的 Token 仍然有效。

2. **不区分旧密码错误和用户不存在**：与登录逻辑一致，防止信息泄露。

3. **不需要修改用户名**：`ChangePasswordRequest` 只有 `oldPassword` 和 `newPassword` 两个字段。修改用户名走 `updateUserInfo` 接口。

---

### 5.6 地址新增 — `UserAddressService.createAddress()`

**源码**：`my-xhs-user/src/main/java/com/myxhs/user/service/UserAddressService.java:57-117`

```
createAddress(userId, AddressCreateRequest)
│
├─ 1. redissonClient.getLock("myxhs:user:address:lock:" + userId)
│      ├─ tryLock(3, 10, SECONDS)
│      └─ 锁内：count = selectCount(eq(userId))
│           count >= 20 → throw ADDRESS_LIMIT_EXCEEDED
│
├─ 2. 构建 UserAddress
│      ├─ 各字段从 request 赋值
│      └─ 判断是否默认：
│           if (count == 0) → setDefault = true  ← 第一条自动默认
│           if (request.isDefault == true) → setDefault = true
│
├─ 3. if setDefault → cancelDefaultAddress(userId)  ← 取消旧默认
│      └─ UPDATE t_user_address SET is_default=0 WHERE user_id=? AND is_default=1
│
├─ 4. userAddressMapper.insert(address)
│
└─ 5. if setDefault → updateDefaultAddressCache(userId, addressId)
       └─ redisOperator.set("myxhs:user:address:default:" + userId, addressId, 30min)
```

**关键设计点**：

1. **锁粒度 = userId**：防止同一用户并发创建导致超过 20 条上限。锁不是全局的，不同用户之间的创建互不阻塞。

2. **count == 0 → 强制默认**：第一条地址自动设为默认，即使前端没有传 `isDefault=true`。这保证用户永远有一个默认地址。

3. **`cancelDefaultAddress` 用 `WHERE is_default=1` 而不是 `WHERE id=xxx`**：因为不知道旧默认是哪个地址（前端可能直接传新地址为默认），用条件更新直接取消所有默认标记。

4. **缓存只存 addressId**：不缓存完整 Address 对象。这样在地址更新时不需要同步更新缓存（因为缓存的只是 ID，具体内容每次查 DB），减少了数据不一致的风险。

---

### 5.7 地址删除 — `UserAddressService.deleteAddress()`

**源码**：`my-xhs-user/src/main/java/com/myxhs/user/service/UserAddressService.java:180-217`

```
deleteAddress(userId, addressId)   ← @Transactional
│
├─ 1. getAndVerifyOwnership(userId, addressId)
│      ├─ selectById → null → throw ADDRESS_NOT_FOUND
│      └─ address.userId != userId → throw FORBIDDEN("无权操作此地址")
│
├─ 2. boolean wasDefault = (address.isDefault == 1)
│
├─ 3. userAddressMapper.deleteById(addressId)           ← 逻辑删除（@TableLogic）
│
├─ 4. redisOperator.delete("myxhs:user:address:default:" + userId)  ← 清除缓存
│
└─ 5. if wasDefault → 自动设置新默认
       ├─ selectOne(eq(userId).eq(isDefault, 0).orderByDesc(createdAt).last("LIMIT 1"))
       │      → null → 无其他地址，不需要设置
       │      → firstAddress
       └─ update(null, eq(id, firstAddressId).eq(userId).eq(isDefault, 0).set(isDefault, 1))
            └─ rows > 0 → updateDefaultAddressCache(userId, firstAddressId)
```

**关键设计点**：

1. **双重校验防越权**：`getAndVerifyOwnership` 先查出地址，再判断 `address.userId != userId`。即使前端修改了 addressId（比如传了别人的 addressId），也能拦截。

2. **更新时也加 `eq(userId) + eq(isDefault, 0)`**（第 5 步）：`update(...).eq(id).eq(userId).eq(isDefault, 0).set(isDefault, 1)`。这个三层条件防止了并发问题——如果另一个线程同时也在设置默认地址（比如前端两个 tab 同时操作），只有第一个成功的线程会生效。`eq(isDefault, 0)` 是额外的乐观条件：如果第一个线程已经把该地址设为默认（isDefault=1），第二个线程的更新不会覆盖。

3. **逻辑删除**：`deleteById` 实际执行的是 `UPDATE SET deleted=1`，不是物理删除。用户的历史订单仍能关联到已删除的地址记录。

---

### 5.8 Shield Pattern — `CacheHelper.getWithCacheAside()`

**源码**：`my-xhs-user/src/main/java/com/myxhs/user/service/UserService.java:332-351`

```java
private User getUserFromCache(Long userId) {
    User user = cacheHelper.getWithCacheAside(
        RedisKeyConstants.USER_INFO + userId,       // cacheKey
        () -> userMapper.selectOne(                  // dbLoader (Lambda)
            new LambdaQueryWrapper<User>()
                .eq(User::getId, userId)
                .select(/* 排除 password */)          // ← 关键：不查 password
        ),
        30, TimeUnit.MINUTES                        // TTL
    );
    if (user == null) {
        throw new BizException(ResultCode.USER_NOT_FOUND);
    }
    return user;
}
```

**关键设计点**：

1. **`.select()` 排除 `password` 字段**：DB 查询时就不返回 `password`。因为查出来的 User 对象会被序列化存入 Redis，如果不排除 `password`，BCrypt 哈希值会暴露在 Redis 中。即使 Redis 在内部网络，这条规则也应该遵守。

2. **`getUserInfo()` 和 `getUserPublicInfo()` 共享缓存**：两者都调 `getUserFromCache()`，拿到同一个 User 对象后各自提取不同字段。`getUserPublicInfo` 只返回 `id/username/nickname/avatar/gender/signature/createdAt`，不返回 `phone/email/status`。

3. **Cache Aside 内部实现**（common 模块 `CacheHelper`）：
   ```
   getWithCacheAside(key, dbLoader, ttl)
     ├─ redisOperator.get(key)
     │    命中 → 返回缓存数据
     └─ 未命中
          ├─ dbLoader.get() → DB 查询
          └─ redisOperator.set(key, data, ttl) → 回填缓存
   ```

---

## 6. Gateway 交互

### 6.1 白名单情况

| 接口 | auth 白名单 | hmac 白名单 | 是否需要 JWT | 是否需要 HMAC |
|------|:----------:|:----------:|:----------:|:----------:|
| `/api/user/auth/captcha` | 是 | 是 | 否 | 否 |
| `/api/user/auth/register` | 是 | 是 | 否 | 否 |
| `/api/user/auth/login` | 是 | 是 | 否 | 否 |
| `/api/user/auth/refresh` | 是 | 是 | 否 | 否 |
| `/api/user/auth/logout` | 是 | 是 | 否 | 否 |
| `/api/user/*/info` | 是 | 是 | 否 | 否 |
| 其他 `/api/user/**` | 否 | 否 | 是 | 是 |

### 6.2 `X-User-Id` Header

非公开接口使用 `@RequestHeader("X-User-Id")` 获取用户 ID。此 Header 由 Gateway 的 `GatewayAuthFilter` 解析 JWT 后自动注入——Controller 层不解析 JWT，只信任 Gateway 注入的值（信任链模型）。

### 6.3 Gateway 路由规则

```yaml
- id: user-service
  uri: lb://my-xhs-user        # Nacos 负载均衡
  predicates: Path=/api/user/**
  metadata:
    response-timeout: 5000ms   # 5s
    connect-timeout: 1000ms    # 1s
  filters:
    - RequestRateLimiter         # IP 维度限流 50 QPS
```

---

## 7. 被下游调用

### 7.1 被 home BFF 通过 Feign 调用

```java
// my-xhs-home/.../feign/UserFeignClient.java
@FeignClient(name = "my-xhs-user", fallbackFactory = UserFeignFallbackFactory.class)
public interface UserFeignClient {
    @GetMapping("/api/user/{userId}/info")
    R<Map<String, Object>> getUserInfo(@PathVariable("userId") Long userId);
}
```

home 模块在聚合 Feed 流时，每篇笔记需要附带作者信息，通过此 Feign 接口调用 user 服务的公开信息端点。

---

## 8. 测试数据

`sql/test-data-init.sql` 预置 3 个测试用户：

| 用户 | userId | 密码 |
|------|--------|------|
| testuser | 10001 | Test@123456（BCrypt） |
| testuser2 | 10002 | Test@123456（BCrypt） |
| testuser3 | 10003 | Test@123456（BCrypt） |

预置 4 条地址记录分属不同用户。

---

## 9. 单元测试覆盖

| 测试类 | 测试方法数 | 测试内容 |
|--------|:--------:|---------|
| `AuthServiceTest` | 3+ | login（成功/密码错误/用户不存在）、CaptchaService（生成/校验） |
| `UserServiceTest` | 3+ | updateUserInfo、getUserInfo、blockUser（含屏蔽自己异常） |
| `AddressServiceTest` | 4+ | listAddresses、createAddress、deleteAddress、setDefaultAddress |

所有测试使用 Mockito 纯 Mock 模式。

---

---

## 10. 资源配置

### 10.1 application.yml 核心配置结构

| 配置组 | 关键项 | 值 |
|--------|--------|------|
| Server | port | 19001 |
| Server | shutdown | graceful |
| Server | Tomcat threads | max=150, min-spare=15 |
| Nacos | discovery.config.server-addr | 21.130.247.89:18848 |
| Nacos | namespace | my-xhs |
| Sentinel | dashboard | 21.130.247.89:8858 |
| Sentinel | port | 8721 |
| Sentinel | eager | true（启动时初始化） |
| OpenFeign | connect-timeout | 500ms |
| OpenFeign | read-timeout | 2000ms |
| OpenFeign | max-connections | 200 |
| Redis | sentinel.master | mymaster |
| Redis | sentinel.nodes | 26379/26380/26381 |
| Redis | cache.port | 16380（allkeys-lru） |
| Redis | business.port | 16381（noeviction） |
| Lettuce | pool.max-active | 15 |
| JWT | secret | MyXhs@2026#JwtSecretKey!ForTokenSign |
| JWT | accessTokenExpire | 1800000ms（30min） |
| JWT | refreshTokenExpire | 604800000ms（7d） |
| RocketMQ | name-server | 21.130.247.89:9876;9877 |
| Actuator | health.probes.enabled | true |
| Actuator | health.redis.enabled | false（手动通过 CacheRedisHealthIndicator 控制） |

### 10.2 读写分离数据源

`application-datasource.properties` 配置了 `ReadWriteRoutingDataSource`（common 模块提供）：

| 角色 | 端口 | 数据库 |
|------|------|--------|
| Master（写） | 13306 | my_xhs_user |
| Slave（读） | 13310 | my_xhs_user |

通过 `@ReadOnly` 注解（common 模块的 `ReadOnlyInterceptor`）自动路由读操作到 Slave：
- 未标注 `@ReadOnly` → 默认走 Master
- 标注 `@ReadOnly` → 走 Slave
- `@Transactional(readOnly = true)` → 走 Slave

### 10.3 日志配置

`logback-spring.xml` 配置了 5 个 Appender：

| Appender | 目标 | 特性 |
|----------|------|------|
| CONSOLE | 控制台 | 彩色输出，含 TraceId |
| FILE_INFO | `logs/my-xhs-user/info.log` | 滚动（按天 + 100MB），保留 30 天，3GB 上限 |
| FILE_ERROR | `logs/my-xhs-user/error.log` | ERROR 级别，滚动保留 |
| JSON_FILE | `/logs/my-xhs-user.json` | LogstashEncoder 结构化输出，含 traceId/spanId/userId |
| LOGSTASH | TCP 推送到 21.130.247.89:15044 | 远程日志中心采集 |

FILE_INFO 和 FILE_ERROR 使用 `AsyncAppender` 包装（队列 1024/512），不阻塞业务线程。

---

## 11. Dockerfile

```dockerfile
# my-xhs-user/Dockerfile
FROM my-xhs-base:latest
COPY target/my-xhs-user.jar /app/app.jar
```

所有服务共享 `my-xhs-base` 基础镜像，只替换 jar 包。

---

## 12. 依赖分析

### 12.1 未使用/死依赖（已清理）

以下依赖在代码中无任何引用，已从 pom.xml 移除：

| 依赖 | 移除原因 |
|------|---------|
| `kaptcha-spring-boot-starter` | CaptchaService 自行用 Java AWT 绘制验证码，完全未使用 Kaptcha |
| `aliyun-sdk-oss` | 模块内无 OSS 上传代码 |
| `spring-cloud-starter-openfeign` | 模块内无 `@FeignClient` 定义 |
| `spring-cloud-starter-loadbalancer` | ~~已删除~~ **保留** — common 模块 `ZoneLoadBalancerConfiguration` 依赖 `ServiceInstanceListSupplier`，不能删除 |
| `my-xhs-user-api` | **此模块从未创建**，无任何 `import com.myxhs.user.api.*` 引用 |

> 上述 5 个依赖已移除，`mvn compile` 通过。

### 12.2 生效的核心依赖

| 依赖 | 调用位置 |
|------|---------|
| `my-xhs-common` | RedissonClient、RedisOperator、CacheHelper、JwtUtil、BizException、BaseEntity、ReadWriteRoutingDataSource |
| `spring-security-crypto` | BCryptPasswordEncoder |
| `jjwt-api/impl/jackson` | JwtUtil（common 模块） |
| `rocketmq-spring-boot-starter` | CacheEvictConsumer |
| `mybatis-plus-spring-boot3-starter` | UserMapper、UserAddressMapper |

---

## 13. SQL 迁移脚本

| 文件 | 说明 |
|------|------|
| `sql/mysql-user-init.sql` | 建库建表（my_xhs_user + analytics/notification/im 相关表） |
| `sql/migration/user/V1__init_user.sql` | Flyway 迁移脚本（内容同 init） |
| `sql/init-replication-user.sql` | MySQL 主从复制 Slave 配置 |
| `sql/init-replication-user-master.sql` | MySQL 复制用户创建（Master） |

---

## 14. 模块文件清单

| 类别 | 数量 | 文件 |
|------|:----:|------|
| 启动类 | 1 | `UserApplication.java` |
| 配置类 | 2 | `JwtProperties.java`, `PasswordEncoderConfig.java` |
| Controller | 3 | `AuthController`, `UserController`, `UserAddressController` |
| Service | 4 | `UserService`, `TokenService`, `CaptchaService`, `UserAddressService` |
| Entity | 2 | `User`, `UserAddress` |
| Mapper | 2 | `UserMapper`, `UserAddressMapper` |
| DTO-Request | 6 | `RegisterRequest`, `LoginRequest`, `UpdateUserRequest`, `ChangePasswordRequest`, `AddressCreateRequest`, `AddressUpdateRequest` |
| DTO-Response | 5 | `TokenResponse`, `CaptchaResponse`, `UserInfoResponse`, `UserPublicInfoResponse`, `AddressVO` |
| Consumer | 1 | `CacheEvictConsumer` |
| 资源文件 | 3 | `application.yml`, `application-datasource.properties`, `logback-spring.xml` |
| 测试类 | 3 | `AuthServiceTest`, `UserServiceTest`, `AddressServiceTest` |
| Dockerfile | 1 | `Dockerfile` |
| **总计 Java** | **22** | |
| **总计全部** | **33** | |

---

## 关联文档

- `/data/workspace/my-xhs/docs/arch/04-aop-three-layer-defense.md` — @DistributedLock/@Idempotent AOP
- `/data/workspace/my-xhs/docs/arch/06-cache-strategy.md` — Cache Aside + 延迟双删 + MQ 兜底
- `/data/workspace/my-xhs/docs/arch/13-gateway-security.md` — Gateway 7 层过滤器链
- `/data/workspace/my-xhs/docs/arch/34-security-deep-dive.md` — 安全体系深度分析
