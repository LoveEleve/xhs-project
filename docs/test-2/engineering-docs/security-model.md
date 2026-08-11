# my-xhs 安全模型

> 三层防护 | JWT + HMAC + 双 Token 体系 | IM/SSE ticket 两步鉴权

---

## 一、安全分层

```
┌────────────────────────────────────────────────────┐
│ L1: Gateway 层                                     │
│  RequestLogFilter → GatewayAuthFilter(JWT)          │
│  → HmacSignatureFilter(HMAC) → RateLimitFilter     │
│  → ApiVersionFilter → GrayRouteFilter              │
│  → TrafficColoringFilter                           │
├────────────────────────────────────────────────────┤
│ L2: 服务间鉴权                                     │
│  X-Admin-Call + ADMIN_TOKEN (管理员操作)           │
│  X-Internal-Call + INTERNAL_TOKEN (内部Feign)      │
│  X-User-Id 防伪造 (Gateway覆盖注入)                │
├────────────────────────────────────────────────────┤
│ L3: 特殊场景鉴权                                   │
│  IM WebSocket: HTTP取ticket → WS建连              │
│  SSE: HTTP取ticket → EventSource建连              │
│  HMAC签名白名单 (公开读接口)                       │
└────────────────────────────────────────────────────┘
```

---

## 二、外部鉴权 (L1: Gateway)

### 2.1 Gateway 过滤器链（按优先级顺序）

| 顺序 | 过滤器 | 优先级 | 职责 |
|:--:|------|:--:|------|
| 1 | RequestLogFilter | HIGHEST+100 | 注入 `X-Trace-Id` |
| 2 | GatewayAuthFilter | HIGHEST+1000 | JWT 鉴权 |
| 3 | TrafficColoringFilter | HIGHEST+1200 | 流量染色 |
| 4 | HmacSignatureFilter | HIGHEST+1500 | HMAC-SHA256 防篡改防重放 |
| 5 | RateLimitFilter | HIGHEST+2500 | 限流 |
| 6 | GrayRouteFilter | HIGHEST+3000 | 灰度路由 |
| 7 | ApiVersionFilter | HIGHEST+3100 | API 版本路由 |

### 2.2 JWT 鉴权 (GatewayAuthFilter)

```
请求 → 提取 Authorization: Bearer {token}
  → 校验签名 (secret: MyXhs@2026#JwtSecretKey!ForTokenSign, 288位)
  → 校验过期时间
  → 校验 token type = "access" (非 refresh)
  → 查询 Redis 黑名单 myxhs:user:token:blacklist:{jti}
  → 黑名单命中 → 401
  → 解析 userId → h.set("X-User-Id", userId) 覆盖注入 ← 防客户端伪造
  → 放行
```

| 属性 | 值 |
|------|------|
| 密钥 | `MyXhs@2026#JwtSecretKey!ForTokenSign` (36字节=288位) |
| Access Token TTL | 30min |
| Refresh Token TTL | 7day |
| 签名算法 | HMAC-SHA256 |
| 黑名单机制 | 登出时 Redis SET `jti` (TTL=剩余有效期)，鉴权时先查黑名单 |
| 刷新防重放 | RLock `TOKEN_REFRESH_LOCK+{jti}` — 双重校验黑名单 |

#### JWT 白名单路径 (免鉴权, gateway application.yml)

```
认证类:    /api/user/auth/captcha, register, login, refresh, logout
公开信息:  /api/user/*/info
笔记公开:  /api/note/detail/**, /api/note/user/**
评论公开:  /api/comment/list/**, children/**, count/**, page/**
社交公开:  /api/social/following/**, follower/**, /api/social/like/count
计数公开:  /api/counter/get, /api/counter/batch-get
SSE:       /api/notification/sse, /api/notification/sse/online-count
IM:        /api/im/online-count, /api/im/ws (WebSocket握手)
搜索:      /api/search/** (被注释, 当前需JWT)
```

### 2.3 HMAC-SHA256 签名 (HmacSignatureFilter)

```
请求 → 提取 X-Timestamp + X-Nonce + X-Signature
  → 校验 timestamp (当前 ±5min) → 超时 403
  → Redis SETNX nonce (Lua原子, TTL=5min) → 重复 403 ← 防重放攻击
  → 取 per-session secret (Redis myxhs:user:hmac:secret:{userId})
  → 计算签名: Base64(HMAC-SHA256(method+path+timestamp+nonce, secret))
  → 对比 → 不匹配 403
  → 放行
```

| 属性 | 值 |
|------|------|
| 密钥 | **per-session secret** — 登录时生成存 Redis `myxhs:user:hmac:secret:{userId}`，前端从登录响应获取 |
| 时间窗口 | 5 分钟容忍 (防时钟偏移) |
| Nonce 去重 | Redis SETNX Lua 原子 `nx + ex 300` (5min, 与时间窗口一致) |
| 失败响应 | 全部 403 (forbidden) — 与 JWT 的 401 区分 |
| 适用接口 | **写接口强制签名** (创建/修改/删除); 公开读接口白名单跳过 |

#### HMAC 白名单路径 (gateway application.yml hmac-white-list)

```
认证类:    /api/user/auth/captcha, register, login, refresh, logout
公开读:    /api/user/*/info, /api/user/me, /api/user/me/password, /api/user/block/**,
           /api/user/address/**, /api/note/detail/**, /api/note/user/**,
           /api/comment/list/**, children/**, count/**, page/**,
           /api/social/following/**, follower/**, /api/social/like/count,
           /api/counter/get, /api/counter/batch-get, /api/home/feed, /api/home/note/**,
           /api/home/product/**, /api/home/user/**
SSE:       /api/notification/sse, /api/notification/sse/**, /api/notification/ticket/**
通知业务:  /api/notification/test/**, list, unread-count, read/**, read-by-type/**, read-all
IM:        /api/im/online-count
管理端点:  /api/coupon/template/**, /api/product/spu/**, /api/product/sku,
           /api/inventory/init, /api/inventory/reinit, /api/counter/reconcile, /api/search/index/rebuild
```

### 2.4 管理端点双重鉴权

```
管理端点 (如 POST /api/product/spu):
  1. GatewayAuthFilter: JWT验证 + 用户身份
  2. Controller层: @RequestHeader("X-Admin-Call") 手工校验
     → 等于 ADMIN_TOKEN (my-xhs-admin-token-2026) → 通过
     → 不一致 → 403
  例: ProductController.createSpu() 接收 X-Admin-Call header 并 compareAndCheck
```

---

## 三、内部鉴权 (L2: 服务间)

### 3.1 AdminToken — 管理端点

| 属性 | 值 |
|------|------|
| Token 值 | `my-xhs-admin-token-2026` (**✅ 已修复** — start-all.sh 中 `export ADMIN_TOKEN` 注入) |
| 传递方式 | HTTP Header `X-Admin-Call` |
| 注入方式 | start-all.sh: `export ADMIN_TOKEN=my-xhs-admin-token-2026` → Spring `${ADMIN_TOKEN:}` 读取 |
| 默认策略 | **空 = fail-closed** (未设置环境变量时全部管理端点 403。已修复后不再为空) |
| 特殊服务 | analytics 需 `-Dmanagement.admin-token` JVM 参数 |
| 校验位置 | 各 Controller 手工 `@RequestHeader("X-Admin-Call")` 校验 (如 ProductController/InventoryController/CouponController) |

**✅ 已修复**: start-all.sh 顶部注入 `export ADMIN_TOKEN=my-xhs-admin-token-2026` + `export INTERNAL_TOKEN=my-xhs-internal-token-2026`，所有 12 服务通过 `${ADMIN_TOKEN:}` 读取，管理端点 403 问题已解决。

**为什么是 fail-closed**: 如果忘记配置 ADMIN_TOKEN 环境变量，空默认值会导致所有管理端点返回 403 — 宁可服务不可用也不暴露管理端点，安全优先。

### 3.2 InternalToken — 内部服务调用

| 属性 | 值 |
|------|------|
| Token 值 | `my-xhs-internal-token-2026` |
| 传递方式 | HTTP Header `X-Internal-Call` |
| 注入方式 | Feign: `FeignInternalCallInterceptor` (全局 @Component) |
| 覆盖注入 | order/cart/payment 的 `InternalCallFeignConfig` 双重注入 |
| 校验位置 | 各 Controller 手工 `@RequestHeader("X-Internal-Call")` 校验 (如 ProductController/InventoryController/CouponController) |
| 默认策略 | **有默认值** (非空，避免 Feign 调用 403) |

**配置统一**: 12 个服务已统一默认值 `${INTERNAL_TOKEN:my-xhs-internal-token-2026}` (content/analytics/counter/product/cart/inventory/coupon/order/payment/notification/im/search)

| 服务 | 默认值 | 状态 |
|------|------|:--:|
| inventory, search, content, im, payment, order, product, analytics, cart, coupon, counter, notification | `my-xhs-internal-token-2026` | ✅ 统一 |
| gateway, user, home | 未配置内部端点 (gateway不消费/user纯上游/home BFF不暴露内部端点) |

### 3.3 X-User-Id 防伪造

```
关键安全设计: Gateway 覆盖注入，客户端无法伪造

客户端 Header: X-User-Id: 999 (伪造值)
       ↓ GatewayAuthFilter
Gateway 覆盖:   h.set("X-User-Id", actualUserId)  ← 从 JWT 解析的真实 userId
       ↓
微服务:        @RequestHeader("X-User-Id") Long userId  ← 取到真实值

如果请求无 JWT: X-User-Id 不存在 → 微服务 @RequestHeader required=false 处理空
```

**防伪造原理**: GatewayAuthFilter 接收所有外部请求，JWT 验证通过后才写入 X-User-Id。客户端直接发 Header 会被 Gateway 覆盖。仅内部 Feign 调用才由 `FeignTraceInterceptor` 从 TraceContextHolder 透传（该值源于 Gateway 注入，非客户端输入）。

---

## 四、特殊场景鉴权 (L3)

### 4.1 IM WebSocket 两步鉴权

```
Phase 1: HTTP 取 ticket
  Client → POST /api/im/ws/ticket + Authorization: Bearer {jwt}
    → GatewayAuthFilter: JWT验证通过
    → ImController: 生成短期 JWT ticket (type=ws_ticket, 5分钟, jwtSecret签名)
    → 返回 { ticket: "xxx" }

Phase 2: WebSocket 建连
  Client → ws://host:19014/api/im/ws?ticket=xxx
    → Gateway白名单 /api/im/ws 放行
    → WebSocket handshake: 验证 ticket (JWT解析 userId)
    → 通过 → 升级连接 → 维护 userId 上下文
```

**为什么两步**: 浏览器 WebSocket API 不支持自定义 Header (无法传 `Authorization`)，必须通过 URL 参数传递临时凭证。

### 4.2 SSE 两步鉴权

```
Phase 1: HTTP 取 ticket
  Client → POST /api/notification/sse/ticket + Authorization: Bearer {jwt}
    → GatewayAuthFilter: JWT验证通过
    → NotificationController: sseTicketService.generateTicket() → 一次性ticket
    → 返回 { ticket: "xxx", expiresIn: 30 }  (30秒有效)

Phase 2: EventSource 建连
  Client → EventSource(/api/notification/sse?ticket=xxx)
    → Gateway: 路径白名单放行
    → NotificationController: validateAndConsume(ticket) → 一次性消费 → 建立 SSE 通道
```

**与 IM 的区别**: SSE ticket 用 Redis 存储(一次性消费)，IM ticket 用 JWT 签名；两者都在 Gateway 白名单中（EventSource/WS 无法带 Header），ticket 验证在服务端完成。

### 4.3 WS 白名单

```
Gateway 白名单: /api/im/ws (WS握手路径) + /api/im/online-count
WS 路径: ws://host/api/im/ws (经 Gateway lb://my-xhs-im 转发, 非直连)
  → 白名单放行 → WebSocket 握手验证在 im 服务内完成
```

---

## 五、安全加固清单

### 5.1 已实施

| 措施 | 实现 | 位置 |
|------|------|------|
| JWT 签名验证 | HMAC-SHA256 288位密钥 | GatewayAuthFilter |
| Token 黑名单 | Redis SET 剩余有效期 | TokenService.logout() |
| HMAC 防重放 | Nonce + Timestamp + Lua 原子 | HmacSignatureFilter |
| SQL 注入防护 | MyBatis-Plus `#{}` 参数化查询 | 全局 |
| X-User-Id 防伪造 | Gateway 覆盖注入 | GatewayAuthFilter |
| 多层鉴权 | JWT → HMAC → AdminToken → InternalToken | 4层独立 |
| Feign 安全 | 3 个 `InternalCallFeignConfig` 自动注入 | order/cart/payment |
| 密钥分离 | JWT 密钥 ≠ HMAC 密钥 | 独立配置 |
| 验证码原子化 | Redis GETDEL (GET+DELETE 单命令) | CaptchaService |
| fail-closed | AdminToken 空默认 → 403 | 安全优先 |
| InternalToken 去重 | Lua 幂等标记 + 唯一索引兜底 | Notification/Coupon |

### 5.2 待增强

| 措施 | 现状 | 建议 |
|------|------|------|
| JWT 密钥轮换 | 写死在 application.yml | 定期轮换 + 旧密钥过渡期双验证 |
| HMAC 密钥管理 | 同 JWT 密钥硬编码 | 与 JWT 分离管理，定期轮换 |
| 敏感信息加密 | application.yml 明文 | 使用 Jasypt 或 Vault |
| 日志脱敏 | 未实施 | 手机号/密码/Token 等敏感字段脱敏 |
| 接口频控 | Gateway 限流规则(metadata) | 需自定义 GatewayFilter 才生效 |
| DLQ 死信监控 | 21 消费者无显式 DLQ 消费 | 需人工介入或告警自动化 |
| WebSocket 连接限制 | 无 | 单用户最大连接数限制 |

### 5.3 安全审计检查点

```bash
# 1. 验证 Gateway JWT 鉴权
curl -s http://localhost:19000/api/user/info -H "Authorization: Bearer invalid" | grep 401

# 2. 验证 X-User-Id 不可伪造
curl -s http://localhost:19000/api/user/info -H "X-User-Id: 999" | grep 401

# 3. 验证 AdminToken fail-closed
curl -s http://localhost:19001/api/user/admin/stats | grep 403

# 4. 验证 InternalToken
curl -s http://localhost:19006/api/product/sku/batch -H "X-Internal-Call: bad-token" | grep 403

# 5. 验证 HMAC 签名
curl -s http://localhost:19001/api/user/update -X PUT -d '{...}'  # 无签名头 → 403

# 6. 验证验证码 GETDEL 原子性
curl -s http://localhost:19001/api/user/auth/captcha | python3 -c "..." # 验证只能读一次
```
