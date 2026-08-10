# Gateway JWT 鉴权 + 过滤器链 — 深度技术分析

> 关联源码：`GatewayAuthFilter.java` / `RequestLogFilter.java` / `TrafficColoringFilter.java` / `RateLimitFilter.java` / `GlobalExceptionHandler.java`

---

## JWT 鉴权

### 为什么用 JWT

| 方案 | 服务端状态 | 多端支持 | 扩展性 |
|---|---|---|---|
| Session | 有（Redis/DB） | 一般 | 需要会话同步 |
| **JWT** | 无（自包含） | 好（App/Web/小程序） | 无状态，天然分布式 |

JWT 自包含：用户 ID、过期时间、类型都编码在 Token 里，服务端无需查库。

### Token 结构

```
Header:  {"alg":"HS256","typ":"JWT"}
Payload: {"jti":"唯一ID","sub":"10001","type":"access","iat":...,"exp":...}
Signature: HMACSHA256(base64(header)+"."+base64(payload), secret)
```

| 字段 | 说明 |
|---|---|
| jti | JWT ID（黑名单索引） |
| sub | 用户 ID |
| type | access / refresh / ws_ticket（防冒充） |
| iat | 签发时间 |
| exp | 过期时间（Access 30min，Refresh 7 天） |

### 校验流程

```java
// 1. 白名单路径？→ 跳过
if (isWhiteListed(path)) return chain.filter(exchange);

// 2. 提取 Bearer Token
String authHeader = request.getHeaders().getFirst("Authorization");
if (authHeader == null || !authHeader.startsWith("Bearer ")) {
    return unauthorized("缺少认证信息");
}

// 3. JWT 解析（签名 + 过期校验）
Claims claims = Jwts.parser().verifyWith(secretKey).build()
        .parseSignedClaims(token).getPayload();

// 4. type 必须是 access（防止 refresh token / ws_ticket 冒充）
if (!"access".equals(claims.get("type"))) {
    return unauthorized("Token 类型错误");
}

// 5. Redis 黑名单检查（jti）
if (isBlacklisted(claims.getId())) {
    return unauthorized("Token 已被注销");
}

// 6. 注入 X-User-Id
request.mutate().header("X-User-Id", claims.getSubject()).build();
```

### 黑名单机制

```java
// 登出时把 jti 加入黑名单（TTL = Token 剩余有效期）
SET myxhs:user:token:blacklist:{jti} 1 EX {剩余秒数}

// 校验时查询
if (isBlacklisted(jti)) → 401
```

**为什么黑名单不用白名单**：黑名单只存登出的 Token（量小），白名单要存所有有效 Token（量大）。登出场景黑名单是标准做法。

### Fail-Closed

```java
private boolean isBlacklisted(String jti) {
    try {
        String val = redisTemplate.opsForValue().get(key);
        return val != null;
    } catch (Exception e) {
        log.error("Redis 黑名单查询异常", e);
        return true;  // Redis 挂了 → 视为黑名单 → 拒绝（安全优先）
    }
}
```

Redis 不可用时拒绝所有请求。可用性受损但安全有保障。

---

## 过滤器链设计

### Order 设计

```
Order 100    RequestLogFilter    最早生成 traceId
Order 1000   GatewayAuthFilter   JWT 鉴权
Order 1200   TrafficColoring     流量染色
Order 1500   HmacSignatureFilter 签名校验
Order 2500   RateLimitFilter     Sentinel 限流
Order 3000   GrayRouteFilter     灰度路由
Order 3100   ApiVersionFilter    API 版本
```

**为什么这个顺序**：

| 依赖 | 说明 |
|---|---|
| RequestLog(100) 在最前 | traceId 必须先生成，后续过滤器的日志才能串联 |
| Auth(1000) 在 HMAC(1500) 前 | 先识别用户再验签名；未登录请求提前拒绝，省 HMAC 计算 |
| Coloring(1200) 在 RateLimit(2500) 前 | 限流可能需要区分压测流量（限流阈值不同） |
| HMAC(1500) 在 RateLimit(2500) 前 | 签名非法请求不应计入限流配额 |
| GrayRoute(3000) / ApiVersion(3100) 靠后 | 纯路由标记，无依赖 |

---

## 流量染色（TrafficColoringFilter）

```java
// 灰度标记：无则按 userId hash 自动分配（10% 灰度）
if (grayTag == null) {
    int hash = (userId.hashCode() & 0x7FFFFFFF) % 100;
    if (hash < 10) grayTag = "gray";
}

// AB 分组：无则 hash % 3 → A/B/C
// 压测标记：仅内网 IP（10.x.x.x）可设置，外部伪造记警告
```

6 个标记注入 Header 后传给下游：

```
X-Trace-Id / X-User-Id / X-Gray-Tag / X-Api-Version / X-AB-Group / X-Pressure-Test
```

下游服务通过 common 的 `TraceIdConfig` 恢复（见 common 深度文档）。

---

## 异常处理（GlobalExceptionHandler）

| 异常 | HTTP 状态 | 响应 |
|---|---|---|
| ConnectException | 503 | 服务暂不可用 |
| TimeoutException | 504 | 请求超时 |
| NotFoundException | 404 | 请求的服务不存在 |
| 其他 | 500 | 服务器内部错误 |

统一 JSON 格式：`{"code":503,"message":"...","data":null}`

---

## 面试 Q&A

**Q: JWT 无状态，怎么实现登出？**
A: Redis 黑名单。登出时把 jti 加入黑名单（TTL=剩余有效期），JWT 校验时先查黑名单。牺牲少量无状态性换取登出能力——业界标准做法。

**Q: Access Token 30 分钟过期，用户要重新登录吗？**
A: 不用。Refresh Token（7 天）通过 `/api/user/auth/refresh` 换新 Access Token，无感续期。

**Q: 过滤器顺序为什么 HMAC 在 RateLimit 之前？**
A: 签名非法的请求不应消耗限流配额。如果限流在前，攻击者可以刷非法请求耗尽合法用户的配额（DoS）。

---

## 生产实验

| 场景 | 结果 |
|---|---|
| 白名单路径 | 200 ✅ |
| 无 Token | 401 ✅ |
| JWT+HMAC 完整 | 200 ✅ |
| 无签名 | 403 ✅ |
| nonce 重复 | 403 ✅ |

---

## 发散

### 双 Token 续期

当前是 Access(30min) + Refresh(7d)。更激进的方案是**滑动续期**：Access 过期前 5 分钟自动续期（返回新 Token），用户体验更好但实现复杂。

### 多端互踢

黑名单按 jti 粒度只支持单 Token 注销。多端互踢（手机登录后踢掉 Web）需要按 userId 维护"当前活跃 jti"列表，登录时覆盖旧 jti。
