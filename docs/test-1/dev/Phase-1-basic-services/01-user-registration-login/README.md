# 用户注册登录

> 所属服务：my-xhs-user (9001) | 开发阶段：Phase-1 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

用户通过手机号/邮箱+密码注册账号，登录后获取 JWT 双 Token（Access + Refresh），支持图形验证码防刷、邮箱验证码验证、Gateway 统一鉴权。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 手机号/邮箱注册 | ✅ | 唯一性校验（分布式锁+DB唯一索引） |
| 密码登录 | ✅ | BCrypt 慢哈希加密 |
| JWT 双 Token | ✅ | Access 30min + Refresh 7d，无感刷新 |
| 图形验证码 | ✅ | Kaptcha 生成 + Redis 存储 5 分钟 |
| 邮箱验证码 | ✅ | JavaMail + Redis 存储 5 分钟（开发环境 Mock） |
| Token 注销 | ✅ | Redis 黑名单，退出/改密码时拉黑 |
| @RateLimit 防暴力破解 | ✅ | 注册/登录/验证码分别限频 |
| Gateway 统一鉴权 | ✅ | GlobalFilter 校验 Token，白名单放行 |
| 手机验证码登录 | ❌ | 需对接 SMS SDK，本项目不涉及真实短信 |
| 第三方登录（微信/QQ） | ❌ | 需对接 OAuth2，不在 MVP 范围 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 用户总量 | 1000 万 | 中型社交电商平台 |
| 日活用户 | 100 万 | 活跃率 10% |
| 注册峰值 QPS | 500 | 运营活动期间新用户涌入 |
| 登录峰值 QPS | 3000 | 早高峰+晚高峰集中登录 |
| Token 校验 QPS | 10000 | 每次请求都经过 Gateway 鉴权 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Nginx → Gateway(9000) → my-xhs-user(9001) → MySQL + Redis
                    │
                    ├── AuthGlobalFilter（JWT 校验 + 黑名单检查）
                    ├── RateLimitFilter（Sentinel 限流）
                    └── 白名单放行（/api/user/register, /api/user/login, /api/user/captcha）
```

### 2.2 模块交互

| 调用方 | 被调用方 | 方式 | 场景 |
|--------|---------|------|------|
| Gateway | Redis | 直接读取 | Token 黑名单检查 |
| Gateway | my-xhs-user | HTTP 转发 | 注册/登录/刷新 Token |
| my-xhs-user | MySQL | MyBatis-Plus | 用户 CRUD |
| my-xhs-user | Redis | RedisOperator | 验证码/Token/缓存/分布式锁 |

### 2.3 核心流程时序图

**用户注册流程：**

```
1. Client → Gateway: POST /api/user/register（白名单放行）
2. Gateway → UserService: 转发请求
3. UserService → Redis: 校验图形验证码（GET + DEL，一次性消费）
4. UserService → Redis: 获取分布式锁 user:register:lock:{phone}（10s TTL）
5. UserService → MySQL: 查询手机号/用户名是否已存在
6. UserService → MySQL: INSERT t_user（BCrypt 加密密码）
7. UserService → Redis: 释放分布式锁
8. UserService → Client: 返回注册成功
```

**用户登录流程：**

```
1. Client → Gateway: POST /api/user/login（白名单放行）
2. Gateway → UserService: 转发请求
3. UserService → Redis: 校验图形验证码
4. UserService → Redis: 检查账号是否被锁定（5分钟内失败5次→锁定15分钟）
5. UserService → MySQL: 查询用户（按 username/phone/email）
6. UserService: BCrypt.matches(rawPassword, encodedPassword)
7. UserService → Redis: 生成 Access Token + Refresh Token，存入 Redis
8. UserService → MySQL: 更新 last_login_time
9. UserService → Client: 返回双 Token
```

**Token 无感刷新流程：**

```
1. Client: Access Token 过期（前端拦截 401）
2. Client → Gateway: POST /api/user/refresh-token（携带 Refresh Token）
3. Gateway → UserService: 转发（白名单放行）
4. UserService → Redis: 校验 Refresh Token 有效性
5. UserService: 生成新 Access Token + 新 Refresh Token
6. UserService → Redis: 旧 Refresh Token 加入黑名单（防重用）
7. UserService → Redis: 存储新 Token 对
8. UserService → Client: 返回新双 Token
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
CREATE DATABASE IF NOT EXISTS my_xhs_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_user;

-- 用户表
CREATE TABLE IF NOT EXISTS t_user (
    id           BIGINT       NOT NULL COMMENT 'ID（号段模式，8~10位）',
    username     VARCHAR(64)  NOT NULL COMMENT '用户名',
    password     VARCHAR(128) NOT NULL COMMENT '密码（BCrypt）',
    nickname     VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
    avatar       VARCHAR(512) DEFAULT NULL COMMENT '头像URL',
    gender       TINYINT      DEFAULT 0 COMMENT '性别：0-未知 1-男 2-女',
    birthday     DATE         DEFAULT NULL COMMENT '生日',
    phone        VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    email        VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    signature    VARCHAR(256) DEFAULT NULL COMMENT '个性签名',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-正常',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_username (username),
    UNIQUE INDEX uk_phone (phone),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
```

### 3.2 索引设计

| 索引名 | 字段 | 类型 | 使用场景 |
|--------|------|------|----------|
| `uk_username` | username | UNIQUE | 登录时按用户名查询；注册时唯一性校验兜底 |
| `uk_phone` | phone | UNIQUE | 登录时按手机号查询；注册时唯一性校验兜底 |
| `idx_created_at` | created_at | INDEX | 后台管理按注册时间排序/筛选 |

> **为什么 email 没有唯一索引？** 实际 SQL 中 email 允许 NULL 且未加唯一索引，因为邮箱注册是可选的，部分用户只用手机号注册。如果业务要求邮箱也唯一，可后续加 `UNIQUE INDEX uk_email (email)`。

### 3.3 字段设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| ID 生成 | 号段模式（8~10位） | 短 ID 适合 URL 展示（如用户主页 `/user/10086`），vs 雪花 ID 18位太长 |
| 密码长度 | VARCHAR(128) | BCrypt 输出固定 60 字符，预留空间 |
| 逻辑删除 | deleted 字段 | 用户注销后数据保留 30 天，满足合规要求 |
| 无 points/balance/vip | 实际 SQL 未包含 | 这些字段在架构文档中规划，实际 DDL 中精简了，后续按需加 |

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `user:info:{userId}` | Hash | 30min | 用户基本信息缓存（nickname/avatar/gender等） |
| `user:token:access:{userId}` | String | 30min | Access Token 值 |
| `user:token:refresh:{userId}` | String | 7d | Refresh Token 值 |
| `user:token:blacklist:{jti}` | String | 与 Token 剩余 TTL 一致 | Token 黑名单（注销/改密码时加入） |
| `user:captcha:{key}` | String | 5min | 图形验证码答案（key=UUID） |
| `user:email:code:{email}` | String | 5min | 邮箱验证码 |
| `user:register:lock:{phone}` | String | 10s | 注册分布式锁（Redisson） |
| `user:login:fail:{username}` | String(int) | 5min | 登录失败次数计数 |
| `user:login:lock:{username}` | String | 15min | 账号锁定标记 |

### 4.2 缓存更新策略

| 操作 | 策略 | 说明 |
|------|------|------|
| 读用户信息 | Cache Aside | 先查 Redis → Miss → 查 DB → 写 Redis（30min TTL） |
| 写用户信息 | 延迟双删 | 先更新 DB → 删 Redis → 延迟 500ms 再删 Redis |
| Token 校验 | Redis 直读 | Gateway 每次请求校验 Token，走 Redis 不查 DB，毫秒级 |

### 4.3 缓存异常处理

| 问题 | 解决方案 |
|------|----------|
| 缓存穿透 | 缓存空值（TTL=2min）+ 布隆过滤器（用户 ID 预加载） |
| 缓存击穿 | 用户信息非热点 Key，使用互斥锁（`@DistributedLock`）重建缓存 |
| 缓存雪崩 | TTL 加随机偏移（30min ± 5min）+ Redis Sentinel 高可用 |

---

## 📡 五、接口设计

### 5.1 接口列表

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/user/captcha` | 获取图形验证码 | ❌ |
| POST | `/api/user/register` | 用户注册 | ❌ |
| POST | `/api/user/login` | 用户登录 | ❌ |
| POST | `/api/user/refresh-token` | 刷新 Access Token | ❌（需 Refresh Token） |
| POST | `/api/user/logout` | 退出登录 | ✅ |
| GET | `/api/user/profile` | 获取当前用户信息 | ✅ |
| PUT | `/api/user/profile` | 更新用户信息 | ✅ |

### 5.2 请求/响应示例

**获取图形验证码**

```http
GET /api/user/captcha
```

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "captchaKey": "a1b2c3d4-uuid",
    "captchaImage": "data:image/png;base64,iVBORw0KGgo..."
  }
}
```

**用户注册**

```http
POST /api/user/register
Content-Type: application/json

{
  "username": "zhangsan",
  "password": "Abc@123456",
  "phone": "13800138000",
  "email": "zhangsan@gmail.com",
  "captchaKey": "a1b2c3d4-uuid",
  "captchaCode": "x7k9"
}
```

```json
{
  "code": 200,
  "msg": "注册成功",
  "data": {
    "userId": 10086
  }
}
```

**用户登录**

```http
POST /api/user/login
Content-Type: application/json

{
  "username": "zhangsan",
  "password": "Abc@123456",
  "captchaKey": "a1b2c3d4-uuid",
  "captchaCode": "x7k9"
}
```

```json
{
  "code": 200,
  "msg": "登录成功",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 1800
  }
}
```

**刷新 Token**

```http
POST /api/user/refresh-token
Content-Type: application/json

{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

```json
{
  "code": 200,
  "msg": "刷新成功",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...(新)",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...(新)",
    "expiresIn": 1800
  }
}
```

---

## 💻 六、核心代码实现

### 6.1 BCrypt 密码加密

```java
/**
 * 安全配置 — 注入 BCryptPasswordEncoder
 * BCrypt 是慢哈希算法，每秒只能计算几千次，暴力破解成本极高
 * vs MD5/SHA256：太快了，每秒数十亿次，暴力破解成本极低
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 默认 strength=10，即 2^10=1024 轮哈希
        return new BCryptPasswordEncoder();
    }
}
```

### 6.2 用户注册核心逻辑

```java
/**
 * 用户注册
 * 关键点：验证码校验 → 分布式锁防并发 → DB唯一索引兜底
 */
@Service
public class UserServiceImpl implements UserService {

    @Override
    public Long register(UserRegisterRequest request) {
        // 1. 校验图形验证码（一次性消费：GET + DEL 原子操作）
        String captchaKey = "user:captcha:" + request.getCaptchaKey();
        String savedCode = redisOperator.get(captchaKey);
        redisOperator.delete(captchaKey); // 无论对错都删除，防重用
        if (savedCode == null || !savedCode.equalsIgnoreCase(request.getCaptchaCode())) {
            throw new BizException(BizErrorCode.CAPTCHA_ERROR);
        }

        // 2. 分布式锁防并发注册（同一手机号10秒内只能注册1次）
        String lockKey = "user:register:lock:" + request.getPhone();
        return distributedLockHelper.executeWithLock(lockKey, 10, TimeUnit.SECONDS, () -> {
            // 3. 唯一性校验（DB查询）
            if (userMapper.selectByUsername(request.getUsername()) != null) {
                throw new BizException(BizErrorCode.USERNAME_EXISTS);
            }
            if (userMapper.selectByPhone(request.getPhone()) != null) {
                throw new BizException(BizErrorCode.PHONE_EXISTS);
            }

            // 4. 创建用户
            User user = new User();
            user.setId(idGenerator.nextId()); // 号段模式生成ID
            user.setUsername(request.getUsername());
            user.setPassword(passwordEncoder.encode(request.getPassword())); // BCrypt加密
            user.setPhone(request.getPhone());
            user.setEmail(request.getEmail());
            user.setNickname(request.getUsername()); // 默认昵称=用户名
            user.setStatus(1);

            // 5. 入库（DB唯一索引兜底，极端并发下分布式锁失效时由DB保证唯一性）
            try {
                userMapper.insert(user);
            } catch (DuplicateKeyException e) {
                throw new BizException(BizErrorCode.USERNAME_OR_PHONE_EXISTS);
            }

            return user.getId();
        });
    }
}
```

### 6.3 JWT 双 Token 生成与刷新

```java
/**
 * Token 服务
 * 关键点：Access Token 短期（30分钟）+ Refresh Token 长期（7天）
 * jti（JWT ID）用于黑名单机制，每个 Token 有唯一标识
 */
@Service
public class TokenService {

    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * 登录成功后生成双 Token
     */
    public TokenPair generateTokenPair(Long userId) {
        String jti = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        // Access Token（30分钟）
        String accessToken = Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .claim("type", "access")
                .issuedAt(new Date(now))
                .expiration(new Date(now + 30 * 60 * 1000)) // 30min
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes()))
                .compact();

        // Refresh Token（7天）
        String refreshJti = UUID.randomUUID().toString();
        String refreshToken = Jwts.builder()
                .id(refreshJti)
                .subject(String.valueOf(userId))
                .claim("type", "refresh")
                .issuedAt(new Date(now))
                .expiration(new Date(now + 7 * 24 * 60 * 60 * 1000)) // 7d
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes()))
                .compact();

        // 存入 Redis（用于主动失效场景）
        redisOperator.set("user:token:access:" + userId, accessToken, 30, TimeUnit.MINUTES);
        redisOperator.set("user:token:refresh:" + userId, refreshToken, 7, TimeUnit.DAYS);

        return new TokenPair(accessToken, refreshToken, 1800);
    }

    /**
     * 刷新 Token
     * 关键点：旧 Refresh Token 加入黑名单（防重用攻击）
     */
    public TokenPair refreshToken(String refreshToken) {
        // 1. 解析 Refresh Token
        Claims claims = parseToken(refreshToken);
        if (!"refresh".equals(claims.get("type"))) {
            throw new BizException(BizErrorCode.INVALID_TOKEN);
        }

        // 2. 检查黑名单
        String jti = claims.getId();
        if (redisOperator.hasKey("user:token:blacklist:" + jti)) {
            throw new BizException(BizErrorCode.TOKEN_REVOKED);
        }

        // 3. 旧 Refresh Token 加入黑名单（TTL = 剩余有效期）
        long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (remainingMs > 0) {
            redisOperator.set("user:token:blacklist:" + jti, "1",
                    remainingMs, TimeUnit.MILLISECONDS);
        }

        // 4. 生成新的双 Token
        Long userId = Long.valueOf(claims.getSubject());
        return generateTokenPair(userId);
    }

    /**
     * 注销 Token（退出登录/修改密码）
     * 将当前 Access Token 的 jti 加入黑名单
     */
    public void revokeToken(String accessToken) {
        Claims claims = parseToken(accessToken);
        String jti = claims.getId();
        long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (remainingMs > 0) {
            redisOperator.set("user:token:blacklist:" + jti, "1",
                    remainingMs, TimeUnit.MILLISECONDS);
        }
        // 同时删除 Redis 中存储的 Token
        Long userId = Long.valueOf(claims.getSubject());
        redisOperator.delete("user:token:access:" + userId);
        redisOperator.delete("user:token:refresh:" + userId);
    }
}
```

### 6.4 Gateway 统一鉴权过滤器

```java
/**
 * Gateway JWT 鉴权过滤器（WebFlux GlobalFilter）
 * 关键点：白名单放行 → 提取 Token → 校验签名 → 检查黑名单 → 注入 Header
 *
 * 注意：Gateway 基于 WebFlux，不能引入 spring-webmvc（会冲突）
 * 所以 JWT 解析用独立的 JwtUtil，不依赖 my-xhs-common
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 1. 白名单放行（登录/注册/验证码/公开接口）
        if (isWhiteListed(path)) {
            return chain.filter(exchange);
        }

        // 2. 提取 Token
        String token = extractToken(exchange.getRequest());
        if (token == null) {
            return unauthorized(exchange, "缺少认证Token");
        }

        // 3. 校验 JWT 签名
        Claims claims;
        try {
            claims = JwtUtil.parseToken(token, jwtSecret);
        } catch (ExpiredJwtException e) {
            return unauthorized(exchange, "Token已过期");
        } catch (JwtException e) {
            return unauthorized(exchange, "Token无效");
        }

        // 4. 检查黑名单
        String jti = claims.getId();
        Boolean inBlacklist = redisTemplate.hasKey("user:token:blacklist:" + jti);
        if (Boolean.TRUE.equals(inBlacklist)) {
            return unauthorized(exchange, "Token已注销");
        }

        // 5. 注入用户信息到 Header，传递给下游服务
        String userId = claims.getSubject();
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header("X-User-Id", userId)
                .header("X-Trace-Id", UUID.randomUUID().toString())
                .build();

        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return -800; // 鉴权优先级
    }
}
```

### 6.5 登录防暴力破解

```java
/**
 * 登录失败计数 + 账号锁定
 * 同一账号 5 分钟内失败 5 次 → 锁定 15 分钟
 */
public UserLoginResponse login(UserLoginRequest request) {
    // 1. 校验验证码（同注册逻辑）
    validateCaptcha(request.getCaptchaKey(), request.getCaptchaCode());

    // 2. 检查账号是否被锁定
    String lockKey = "user:login:lock:" + request.getUsername();
    if (redisOperator.hasKey(lockKey)) {
        throw new BizException(BizErrorCode.ACCOUNT_LOCKED,
                "账号已锁定，请15分钟后重试");
    }

    // 3. 查询用户
    User user = userMapper.selectByUsername(request.getUsername());
    if (user == null) {
        throw new BizException(BizErrorCode.USER_NOT_FOUND);
    }

    // 4. 校验密码
    if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
        // 失败计数
        String failKey = "user:login:fail:" + request.getUsername();
        Long failCount = redisOperator.increment(failKey);
        if (failCount == 1) {
            redisOperator.expire(failKey, 5, TimeUnit.MINUTES); // 首次失败设置5分钟窗口
        }
        if (failCount >= 5) {
            // 锁定账号15分钟
            redisOperator.set(lockKey, "1", 15, TimeUnit.MINUTES);
            redisOperator.delete(failKey);
            throw new BizException(BizErrorCode.ACCOUNT_LOCKED,
                    "密码错误次数过多，账号已锁定15分钟");
        }
        throw new BizException(BizErrorCode.PASSWORD_ERROR,
                "密码错误，还剩" + (5 - failCount) + "次机会");
    }

    // 5. 登录成功，清除失败计数
    redisOperator.delete("user:login:fail:" + request.getUsername());

    // 6. 检查账号状态
    if (user.getStatus() == 0) {
        throw new BizException(BizErrorCode.ACCOUNT_DISABLED);
    }

    // 7. 生成双 Token
    TokenPair tokenPair = tokenService.generateTokenPair(user.getId());

    // 8. 更新最后登录时间（异步，不影响登录响应速度）
    CompletableFuture.runAsync(() -> {
        userMapper.updateLastLoginTime(user.getId(), LocalDateTime.now());
    });

    return new UserLoginResponse(tokenPair);
}
```

### 6.6 图形验证码生成

```java
/**
 * 验证码服务
 * Kaptcha 生成图形验证码 → Base64 返回前端 → Redis 存储答案
 */
@Service
public class CaptchaService {

    @Resource
    private Producer kaptchaProducer; // Kaptcha 配置 Bean

    public CaptchaVO generateCaptcha() {
        // 1. 生成验证码文本
        String code = kaptchaProducer.createText();

        // 2. 生成验证码图片 → Base64
        BufferedImage image = kaptchaProducer.createImage(code);
        String base64Image = imageToBase64(image);

        // 3. UUID 作为 Key，存入 Redis（5分钟过期）
        String captchaKey = UUID.randomUUID().toString();
        redisOperator.set("user:captcha:" + captchaKey, code, 5, TimeUnit.MINUTES);

        return new CaptchaVO(captchaKey, "data:image/png;base64," + base64Image);
    }
}
```

---

## ⚖️ 七、方案对比

### 7.1 Token 机制：单 Token vs 双 Token

| 维度 | 单 Token | 双 Token（✅ 选定） |
|------|---------|-------------------|
| 用户体验 | Token 过期需重新登录 | Access 过期用 Refresh 静默续期，用户无感知 |
| 安全性 | Token 有效期长（如7天）→ 被窃取风险大 | Access 仅30分钟，被窃取影响有限 |
| 实现复杂度 | 简单 | 中等（需实现刷新逻辑） |
| 适用场景 | 内部管理系统 | 面向用户的 App/Web |

**选择理由**：面向 C 端用户，体验优先。Access Token 30分钟过期降低被窃取风险，Refresh Token 7天免频繁登录。

### 7.2 鉴权位置：Gateway 统一鉴权 vs 各服务鉴权

| 维度 | Gateway 统一鉴权（✅ 选定） | 各服务自行鉴权 |
|------|--------------------------|---------------|
| 代码侵入 | 业务服务零侵入 | 每个服务都要写鉴权逻辑 |
| 维护成本 | 改一处生效全局 | 改 N 处 |
| 性能 | Token 校验在入口完成，下游服务不重复校验 | 每个服务都校验一次 |
| 灵活性 | 白名单统一管理 | 各服务自定义 |

**选择理由**：微服务架构下，鉴权是横切关注点，应在 Gateway 统一处理。下游服务通过 `X-User-Id` Header 获取用户信息，不需要再解析 Token。

### 7.3 密码加密：BCrypt vs MD5/SHA256

| 维度 | BCrypt（✅ 选定） | MD5/SHA256 |
|------|-----------------|-----------|
| 计算速度 | 慢（每秒几千次） | 快（每秒数十亿次） |
| 暴力破解成本 | 极高 | 极低 |
| 自带盐值 | ✅ 每次加密结果不同 | ❌ 需手动加盐 |
| 彩虹表攻击 | 免疫 | 不加盐则易受攻击 |

**选择理由**：BCrypt 是密码存储的行业标准。"慢"正是它的优势——暴力破解成本极高。

### 7.4 注册唯一性保证：分布式锁 vs 仅 DB 唯一索引

| 维度 | 分布式锁 + DB 唯一索引（✅ 选定） | 仅 DB 唯一索引 |
|------|-------------------------------|---------------|
| 并发安全 | 锁保证串行，DB 兜底 | 依赖 DB 异常处理 |
| 用户体验 | 锁冲突时友好提示 | DuplicateKeyException 需转换为业务异常 |
| 性能 | 锁粒度细（按手机号），不影响其他用户 | 直接打 DB，高并发下 DB 压力大 |

**选择理由**：分布式锁在应用层拦截并发，减少 DB 压力；DB 唯一索引作为最后兜底，双重保障。

---

## 🐛 八、踩坑记录

### 8.1 Gateway 引入 spring-webmvc 导致启动失败

- **现象**：Gateway 启动报错 `Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway`
- **原因**：my-xhs-common 依赖了 `spring-boot-starter-web`，Gateway 引入 common 后冲突
- **解决**：common 中将 `spring-boot-starter-web` 设为 `<optional>true</optional>`，Gateway 不传递引入
- **教训**：Gateway 基于 WebFlux，绝对不能引入 WebMVC 相关依赖

### 8.2 Refresh Token 重用攻击

- **现象**：旧 Refresh Token 被截获后可无限刷新
- **原因**：刷新时未将旧 Refresh Token 失效
- **解决**：刷新成功后，旧 Refresh Token 的 jti 加入 Redis 黑名单
- **教训**：Token 刷新必须是"一次性"的，旧 Token 用完即废

### 8.3 验证码被重复使用

- **现象**：同一个验证码可以多次提交注册
- **原因**：校验验证码时只 GET 未 DEL
- **解决**：校验时 GET + DEL 原子操作（或先 DEL 再判断返回值）
- **教训**：验证码必须"一次性消费"，校验后立即删除

### 8.4 登录失败计数窗口期问题

- **现象**：用户第1次失败后等4分59秒再失败，窗口期被重置
- **原因**：每次失败都重新设置 5 分钟 TTL
- **解决**：只在 `failCount == 1`（首次失败）时设置 TTL，后续失败只 INCR 不重置 TTL
- **教训**：滑动窗口计数的 TTL 只能设置一次

---

## 📊 九、测试验证

### 9.1 功能测试

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 正常注册 | 合法用户名+手机号+密码+验证码 | 返回 userId | ⬜ |
| 重复用户名注册 | 已存在的用户名 | 返回"用户名已存在" | ⬜ |
| 重复手机号注册 | 已存在的手机号 | 返回"手机号已注册" | ⬜ |
| 验证码错误 | 错误的验证码 | 返回"验证码错误" | ⬜ |
| 验证码过期 | 超过5分钟的验证码 | 返回"验证码已过期" | ⬜ |
| 验证码重复使用 | 已使用过的验证码 | 返回"验证码错误" | ⬜ |
| 正常登录 | 正确用户名+密码+验证码 | 返回双 Token | ⬜ |
| 密码错误 | 错误密码 | 返回"密码错误，还剩N次" | ⬜ |
| 账号锁定 | 连续5次密码错误 | 返回"账号已锁定15分钟" | ⬜ |
| Token 刷新 | 有效的 Refresh Token | 返回新双 Token | ⬜ |
| 旧 Refresh Token 重用 | 已刷新过的 Refresh Token | 返回"Token已失效" | ⬜ |
| 退出登录 | 有效的 Access Token | Token 加入黑名单 | ⬜ |
| 退出后访问 | 已注销的 Token | 返回 401 | ⬜ |

### 9.2 压测数据（预期基线）

| 场景 | 并发数 | 目标 QPS | 目标平均 RT | 目标 P99 RT | 目标错误率 |
|------|--------|---------|-----------|-----------|-----------|
| 登录接口 | 100 | 3000 | < 50ms | < 200ms | < 0.1% |
| Token 校验（Gateway） | 200 | 10000 | < 10ms | < 50ms | < 0.01% |
| 注册接口 | 50 | 500 | < 100ms | < 500ms | < 0.1% |

### 9.3 关键场景验证

- [ ] 并发注册同一手机号：分布式锁保证只有1个成功
- [ ] Access Token 过期后自动刷新：前端拦截 401 → 调用 refresh → 重试原请求
- [ ] Redis 宕机时登录：降级为直接查 DB 校验（Token 生成失败则返回服务不可用）
- [ ] 修改密码后旧 Token 失效：改密码 → 旧 Token jti 加入黑名单 → 旧 Token 请求返回 401

---

## 🎤 十、面试考察点

### Q1: JWT 双 Token 怎么实现无感刷新？

**推荐回答思路**：

> 1. "登录成功签发两个 Token：Access Token 30分钟有效，Refresh Token 7天有效"
> 2. "正常请求携带 Access Token，Gateway 校验。Access 过期返回 401"
> 3. "前端拦截 401，自动用 Refresh Token 调用刷新接口，拿到新的双 Token，重试原请求——用户无感知"
> 4. "刷新时旧 Refresh Token 加入 Redis 黑名单，防止被截获后重复使用"

### Q2: 如何防止暴力破解登录？

**推荐回答思路**：

> 1. "三层防护：图形验证码 + @RateLimit 限频 + 失败锁定"
> 2. "图形验证码：每次登录必须输入，防止脚本自动化攻击"
> 3. "@RateLimit：同一 IP 1分钟最多10次登录请求，超过直接拒绝"
> 4. "失败锁定：同一账号5分钟内密码错误5次，锁定15分钟。用 Redis INCR 计数，首次失败设置5分钟 TTL 窗口"

### Q3: 分布式环境下如何保证用户名唯一？

**推荐回答思路**：

> 1. "两层保障：应用层分布式锁 + 数据库唯一索引"
> 2. "分布式锁：Redisson 按手机号加锁（`user:register:lock:{phone}`），10秒 TTL，保证同一手机号串行注册"
> 3. "DB 唯一索引：`uk_username` 和 `uk_phone`，极端情况下锁失效（如 Redis 宕机），DB 唯一索引兜底，捕获 DuplicateKeyException 转为业务异常"
> 4. "为什么不只用 DB 唯一索引？高并发下大量请求打到 DB，DB 压力大。分布式锁在应用层拦截，减少 DB 无效写入"

### Q4: Token 无法主动失效怎么解决？

**推荐回答思路**：

> 1. "JWT 是无状态的，签发后无法撤回——这是 JWT 的天然缺陷"
> 2. "解决方案：Redis 黑名单。每个 Token 有唯一的 jti（JWT ID），注销时将 jti 存入 Redis，TTL = Token 剩余有效期"
> 3. "Gateway 每次校验 Token 时，先查 Redis 黑名单，在黑名单中则拒绝"
> 4. "场景：用户退出登录、修改密码、账号被封禁——都需要将当前 Token 加入黑名单"

### Q5: 为什么密码用 BCrypt 而不是 MD5？

**推荐回答思路**：

> 1. "BCrypt 是慢哈希算法，每秒只能计算几千次；MD5 每秒能算数十亿次"
> 2. "慢 = 安全：暴力破解 BCrypt 密码需要数年，破解 MD5 只需几秒"
> 3. "BCrypt 自带随机盐值，每次加密结果不同，免疫彩虹表攻击"
> 4. "MD5 不加盐的话，相同密码的哈希值相同，可以用彩虹表直接查出明文"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-1/README.md | §3.1 | 用户注册登录完整设计（API/表/Redis Key/Java文件清单） |
| 📄 02-module-detailed-design.md | §3 | 用户服务核心功能/验证码/缓存/风控 |
| 📄 00-technical-specification-outline.md | §4.1 | 用户服务功能清单与方案对比 |
| 📄 30-security-compliance-system | §2.2 | JWT 双 Token 机制流程 |
| 📄 40-production-pitfall-quick-reference | §五 | @Transactional 失效 / Feign 6大坑 |
