# 16-gateway API 网关 — 架构文档

> 端口：19000 | 数据库：无（仅 Redis） | 更新时间：2026-07-30

---

## 1. 模块定位

Gateway 是 my-xhs 平台的统一入口网关，基于 Spring Cloud Gateway（WebFlux 响应式），承担所有外部请求的路由转发、JWT 鉴权、HMAC 签名校验、Sentinel 限流、流量染色等职责。

核心能力：
- **路由转发**：13 条路由，LB 分发到各微服务
- **JWT 鉴权**：Bearer Token 解析 + 白名单放行 + Redis 黑名单
- **HMAC 签名**：防篡改 + nonce 防重放 + timestamp 有效期
- **流量染色**：灰度标记 / AB 分组 / 压测标记 / API 版本
- **Sentinel 限流**：按服务差异化 QPS 限流

---

## 2. 过滤器链

| Order | 过滤器 | 职责 |
|---|---|---|
| **100** | `RequestLogFilter` | TraceId 生成 + 入站/出站 ACCESS 日志 |
| **1000** | `GatewayAuthFilter` | JWT 鉴权 + 白名单放行 + Redis 黑名单 |
| **1200** | `TrafficColoringFilter` | 灰度标记/AB 分组/压测标记/API 版本 |
| **1500** | `HmacSignatureFilter` | HMAC 签名校验 + nonce 防重放 |
| **2500** | `RateLimitFilter` | Sentinel 按服务 QPS 限流 |
| **3000** | `GrayRouteFilter` | 灰度流量路由 |
| **3100** | `ApiVersionFilter` | API 版本路由 |

---

## 3. 架构图

```
客户端请求 → Port 19000
    │
    ├─ (100) RequestLogFilter
    │   ├─ 生成/透传 X-Trace-Id
    │   └─ 记录 [Gateway] >>> / <<< 日志
    │
    ├─ (1000) GatewayAuthFilter
    │   ├─ 白名单路径？→ 放行
    │   ├─ JWT 验证（签名/过期/type=access）
    │   ├─ Redis 黑名单检查（jti）
    │   └─ 注入 X-User-Id Header
    │
    ├─ (1200) TrafficColoringFilter
    │   ├─ X-Gray-Tag: stable/gray（基于 userId Hash）
    │   ├─ X-AB-Group: A/B/C
    │   ├─ X-Pressure-Test: true（仅内网 IP）
    │   └─ X-Api-Version: v1/v2
    │
    ├─ (1500) HmacSignatureFilter
    │   ├─ HMAC 白名单？→ 放行
    │   ├─ timestamp 5min 有效期
    │   ├─ nonce 防重放（Lua SET NX EX 300s）
    │   └─ signature 校验（HmacSHA256）
    │
    ├─ (2500) RateLimitFilter
    │   └─ Sentinel 按服务 QPS（user=50, search=300, order=10...）
    │
    ├─ (3000) GrayRouteFilter
    │   └─ 灰度流量路由标记
    │
    ├─ (3100) ApiVersionFilter
    │   └─ API 版本路由标记
    │
    └─ → 路由转发到目标服务（lb://my-xhs-*）
    │
    └─ (100) RequestLogFilter（then）
        └─ 出站日志 [Gateway] <<< status=200, duration=85ms
```

---

## 4. 源码清单

| 文件 | 职责 |
|---|---|
| `GatewayApplication.java` | 启动类（排除 DataSource） |
| `config/AuthProperties.java` | JWT/HMAC 密钥 + 白名单配置 |
| `config/GatewayConfig.java` | CORS + Sentinel Nacos Converter + RedisTemplate |
| `config/RateLimiterConfig.java` | 限流 KeyResolver（IP/用户/路径） |
| `filter/RequestLogFilter.java` | TraceId 生成 + ACCESS 日志 |
| `filter/GatewayAuthFilter.java` | JWT 鉴权 + 黑名单 + userId 注入 |
| `filter/TrafficColoringFilter.java` | 流量染色（Gray/AB/PressureTest/ApiVersion） |
| `filter/HmacSignatureFilter.java` | HMAC 签名校验 + nonce 防重放 |
| `filter/RateLimitFilter.java` | Sentinel 网关限流 |
| `filter/GrayRouteFilter.java` | 灰度流量路由 |
| `filter/ApiVersionFilter.java` | API 版本路由 |
| `handler/GlobalExceptionHandler.java` | 全局异常（503/504/500） |
| `handler/CachingFilteringWebHandler.java` | 过滤器缓存优化 |

---

## 5. 路由表

| 路由 ID | 目标服务 | 端口 | 路径 | QPS |
|---|---|---|---|---|
| user-service | my-xhs-user | 19001 | /api/user/** | 50 |
| content-service | my-xhs-content | 19002 | /api/content/**,/api/note/**,/api/comment/**,/api/topic/** | 200 |
| search-service | my-xhs-search | 19016 | /api/search/** | 300 |
| order-service | my-xhs-order | 19011 | /api/order/** | 10 |
| payment-service | my-xhs-payment | 19012 | /api/payment/** | 5 |
| inventory-service | my-xhs-inventory | 19009 | /api/inventory/** | 30 |
| analytics-service | my-xhs-analytics | 19003 | /api/analytics/**,/api/social/** | 100 |
| product-service | my-xhs-product | 19006 | /api/product/** | 100 |
| cart-service | my-xhs-cart | 19008 | /api/cart/** | 50 |
| coupon-service | my-xhs-coupon | 19010 | /api/coupon/** | 30 |
| home-service | my-xhs-home | 19015 | /api/home/** | 50 |
| notification-service | my-xhs-notification | 19013 | /api/notification/** | 50 |
| im-service | my-xhs-im | 19014 | /api/im/** | 50 |

---

## 6. 核心流程

### 6.1 JWT 鉴权

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
    │
    ├─ 白名单路径？→ 跳过（登录/注册/公开接口）
    ├─ JWT 解析：verifyWith(hmacShaKey(secret))
    ├─ type 必须是 "access"（防止 refresh token 冒充）
    ├─ Redis 黑名单 GET myxhs:user:token:blacklist:{jti}
    │   └─ 有值 → 401 Token 已注销
    └─ 注入 X-User-Id: {userId} 到请求 Header
```

**Fail-Closed**：Redis 黑名单查询异常时视为在黑名单中，拒绝请求（安全优先）。

### 6.2 HMAC 签名校验

```
客户端构建：
  signStr = HTTP_METHOD + URI_PATH + timestamp + nonce
  signature = Base64(HmacSHA256(signStr, hmacSecret))

Gateway 验证：
  ├─ HMAC 白名单？→ 跳过
  ├─ |now - timestamp| > 5min → 403
  ├─ Redis SET NX EX 300 nonce → 重复则 403
  └─ MessageDigest.isEqual(expected, signature) → 不匹配则 403
```

`MessageDigest.isEqual` 常量时间比较，防止时序攻击。

**注意**：HMAC nonce 去重依赖 Redis。Redis 异常时 nonce 检查会跳过（fail-open），但 signature 校验仍然执行。

### 6.3 流量染色

```
X-Gray-Tag 缺失 → hash(userId) % 100 < 10 → gray，否则 stable
X-AB-Group 缺失 → hash(userId) % 3 → A/B/C
X-Pressure-Test → true 且来源 IP 为 10.x.x.x → 标记压测
```

---

## 7. 安全设计

| 层 | 机制 | 说明 |
|---|---|---|
| 认证 | JWT Bearer Token | Access Token 30min，type 校验防冒充 |
| 授权 | 路径白名单 | 公开接口无需 Token |
| 防篡改 | HMAC-SHA256 | method+path+timestamp+nonce |
| 防重放 | nonce + timestamp | Lua SET NX 300s，5min 窗口 |
| 防时序攻击 | MessageDigest.isEqual | 常量时间比较 |
| Token 注销 | Redis 黑名单 | jti 粒度，fail-closed |
| 压测防护 | 内网 IP 校验 | 仅 10.0.0.0/8 |

---

## 8. 配置要点

| 配置项 | 值 | 说明 |
|---|---|---|
| 端口 | 19000 | |
| Tomcat 线程 | max=300（WebFlux 下不生效，实际用 Netty EventLoop） | 配置冗余，可清理 |
| JWT 密钥 | MyXhs@2026#JwtSecretKey!ForTokenSign | HmacSHA256 |
| HMAC 密钥 | myxhs-hmac-secret-key-2024 | 与 JWT 密钥分离 |
| Timestamp 容忍 | 5 分钟 | |
| Nonce TTL | 300 秒 | |
| Redis | Sentinel 26379/26380/26381 | Business 16381 |
| Nacos | 18848 | namespace=my-xhs |
| Sentinel | 8858 | 网关适配器 |

---

## 9. 依赖关系

### 上游
- **Redis (16381)**：Token 黑名单 + nonce 去重
- **Nacos**：服务发现（路由 LB 地址）

### 下游
- **所有 13 个微服务**：Gateway 作为统一入口

### 不依赖
- MySQL / MyBatis（WebFlux，不引入 JDBC）
- RocketMQ（不做消息收发）

---

## 10. 关键设计决策

| 决策 | 选择 | 原因 |
|---|---|---|
| 网关框架 | Spring Cloud Gateway (WebFlux) | 响应式非阻塞，IO 密集场景 |
| JWT vs Session | JWT 无状态 | 微服务 + 多端友好 |
| Token 注销 | Redis 黑名单（白名单模式） | 黑名单量小 |
| 密钥分离 | JWT ≠ HMAC 密钥 | 功能隔离 |
| Fail-Closed | JWT 黑名单 Redis 异常时拒绝（HMAC nonce 异常时放行，仅跳过 nonce 检查） | 安全优先 |

---

## 11. 限流规则

| 服务 | QPS | 说明 |
|---|---|---|
| search | 300 | 最高 QPS |
| content | 200 | 读多写少 |
| product/analytics | 100 | |
| user/cart/home/im/notification | 50 | |
| inventory/coupon | 30 | |
| order | 10 | 严格限流 |
| payment | 5 | 最严格 |

---

## 12. 测试场景建议

1. 白名单路径直接放行
2. 带 JWT 访问受保护路径 → 鉴权通过
3. 无 Token → 401
4. Token 过期/伪造 → 401
5. HMAC 完整签名请求 → 200
6. 无签名 Header → 403
7. timestamp 超 5 分钟 → 403
8. nonce 重复 → 403
9. 超过服务 QPS → 429
10. TraceId 透传至下游
