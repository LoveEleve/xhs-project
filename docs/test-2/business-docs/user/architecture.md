# my-xhs-user 架构分析

## 一、服务拓扑

```
端口: 19001
JVM:  -Xms512m -Xmx512m (BASE)
日志: /tmp/r_user.log
SkyWalking: my-xhs-user → OAP 21.130.247.89:11800
Nacos:     namespace=my-xhs, server-addr=21.130.247.89:18848
```

## 二、依赖图

```
my-xhs-user
├── MySQL   my_xhs_user.t_user / t_user_address (3306读写 / 3307只读)
├── Redis   Standalone 6379 (via Sentinel 26379 → 21.130.247.89:6379)
├── MQ      CACHE_EVICT_TOPIC → CacheEvictConsumer (消费端)
└── 无Feign调用 (user服务不依赖其他微服务)
```

## 三、三层 Controller

| Controller | 路径前缀 | 端点数 | 认证 |
|------|------|:--:|------|
| AuthController | `/api/user/auth` | 5 | 无需JWT(Gateway白名单 `/api/user/auth/**`) |
| UserController | `/api/user` | 4 | 需JWT + X-User-Id (Gateway注入) |
| UserAddressController | `/api/user/address` | 7 | 需JWT + X-User-Id |
| UserController (block) | `/api/user/block` | 3 | 需JWT + X-User-Id |

## 四、数据流

### 登录流程
```
Gateway(RequestLogFilter生成traceId)
  → AuthController.login()
    → CaptchaService.verifyCaptcha() → Redis GET+DEL USER_CAPTCHA:{key}
    → Redisson RLock USER_LOGIN_LOCK+{username}
    → MySQL SELECT t_user WHERE username=?
    → BCrypt.matches()
    → MySQL UPDATE t_user SET last_login_time=NOW()
    → TokenService.generateTokenPair()
      → Redis SET USER_TOKEN_ACCESS:{userId} TTL=1800
      → Redis SET USER_TOKEN_REFRESH:{userId} TTL=604800
      → Redis SET USER_HMAC_SECRET:{userId}
    → 返回 {accessToken, refreshToken, expiresIn}
```

### Token刷新流程
```
AuthController.refreshToken()
  → TokenService.refreshToken()
    → JWT解析refresh → 查黑名单 USER_TOKEN_BLACKLIST:{jti}
    → Redisson RLock TOKEN_REFRESH_LOCK+{jti}
    → 二次查黑名单(双重校验)
    → 旧Token入黑名单(Redis SET ttl=剩余有效期)
    → 重新生成Token对 → 写入Redis
    → 返回新Token
```

### 缓存策略（CacheAside）
```
读: Redis GET USER_INFO:{userId} → 命中返回 → 未命中查DB → 回写Redis(30min)
写: DB UPDATE first → delayDoubleDelete(延迟双删Redis) → MQ CACHE_EVICT_TOPIC兜底
```

### 屏蔽流程
```
POST /api/user/block/{targetUserId}
  → UserService.blockUser(userId, targetUserId)
    → 校验 userId != targetUserId (不能屏蔽自己)
    → Redis SADD myxhs:user:block:{userId} targetUserId
    → EXPIRE myxhs:user:block:{userId} 365d

GET /api/user/block/list
  → Redis SMEMBERS myxhs:user:block:{userId} → Set<Long>
  → MySQL: SELECT id,username,nickname,avatar FROM t_user WHERE id IN(...)
  → 返回屏蔽用户列表(不含password)
```

## 五、安全机制

| 层 | 机制 |
|------|------|
| 传输 | JWT (access 30min + refresh 7d) |
| 密码 | BCrypt 加密存储 |
| 防暴力 | Redis 失败计数5次 → 锁定15分钟 |
| Token防护 | 登出入黑名单、刷新时黑名单双重校验、改密全部注销 |
| 验证码 | 一次性消费(校验后立即DELETE)，5分钟过期 |
| 并发 | Redisson分布式锁(注册/登录/Token刷新/地址修改) |

## 六、Redis Key 完整清单

| Key | 类型 | TTL | 数量 |
|------|------|:--:|--|
| USER_CAPTCHA:{key} | String | 5min | 1 per request |
| USER_TOKEN_ACCESS:{userId} | String | 30min | 1 per user |
| USER_TOKEN_REFRESH:{userId} | String | 7d | 1 per user |
| USER_TOKEN_BLACKLIST:{jti} | String | Token剩余 | 按登出/刷Token次数 |
| USER_HMAC_SECRET:{userId} | String | 30min | 1 per user |
| USER_INFO:{userId} | String | 30min | 1 per user |
| USER_REGISTER_LOCK:{username} | String | — | 并发注册时 |
| USER_LOGIN_FAIL:{username} | String | — | 失败5次前 |
| USER_LOGIN_LOCK:{username} | String | 15min | 锁定期间 |
| USER_BLOCK:{userId} | Set | 365d | 屏蔽用户数 |
| USER_ADDRESS_DEFAULT:{userId} | String | 30min | 1 per user |
| USER_ADDRESS_LOCK:{userId} | String | — | 修改时 |
| TOKEN_REFRESH_LOCK:{jti} | String | — | 刷新时 |
