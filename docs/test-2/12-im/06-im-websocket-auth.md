# IM WebSocket 两步认证 — 深度技术分析

> 关联源码：`ImController.java` / `ImHandshakeInterceptor.java`

---

## 业务背景

WebSocket 连接建立时，浏览器 `new WebSocket(url)` 不支持自定义 Header。Token 只能放 URL 参数：

```
ws://example.com/ws?token=eyJhbGciOiJIUzI1NiJ9...
```

这会带来安全风险：
- URL 被浏览器历史记录保存（`window.location` 可见）
- URL 被 Nginx/Apache 访问日志记录
- URL 可能被 CDN 边缘节点缓存
- URL 被 Referer Header 泄漏给第三方

两步认证解决：先用 HTTP Header（安全通道）换取短期 Ticket，再用 Ticket（短期、一次性）建立 WebSocket。

---

## 方案对比

| 方案 | Token 暴露风险 | 复杂度 | 额外延迟 | 一次性 |
|---|---|---|---|---|---|
| Token 直接放 URL | 高 | 低 | 0 | 否 |
| **两步法：JWT Ticket** | 低 | 中 | 1 RTT | 否（5min 有效期内可复用） |
| Session + Cookie | 低 | 高 | 0 | 否 |

---

## 流程详解

### 第 1 步：HTTP 换取 Ticket

```http
POST /api/im/ws/ticket
Header: X-User-Id: 10001

← 200 {"ticket": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMDAwMSIsInR5cGUiOiJ3c190aWNrZXQiLCJleHAiOjE3ODUzNzQ3MzR9..."}
```

生成的 JWT 包含：
```
{
  "jti": "230e0f9f4807498b9e30c8faacebcb6b", // JWT ID（唯一）
  "sub": "10001",                             // 用户 ID
  "type": "ws_ticket",                        // 类型标记（防止误用）
  "iat": 1785374434,                          // 签发时间
  "exp": 1785374734                           // 过期时间（5 分钟后）
}
```

**关键**：
- `type=ws_ticket`，与 `access` 类型区分——防止客户端误用 Access Token 建立 WebSocket
- 5 分钟有效期，足够客户端完成连接建立

### 第 2 步：WebSocket 携带 Ticket

```javascript
// 前端
const ticket = await fetchTicket();  // POST /api/im/ws/ticket
const ws = new WebSocket(`ws://host/api/im/ws?ticket=${ticket}`);
```

服务端 `ImHandshakeInterceptor.beforeHandshake()` 校验：

```java
public boolean beforeHandshake(ServerHttpRequest request, ...) {
    String ticket = request.getParameter("ticket");
    Claims claims = JwtUtil.parseToken(ticket, jwtSecret);
    String tokenType = claims.get("type", String.class);
    if (!"ws_ticket".equals(tokenType)) {
        return false;  // 防止 access token 冒充
    }
    attributes.put("userId", Long.valueOf(claims.getSubject()));
    return true;
}
```

**双重校验**：
1. JWT 签名验证（防篡改）
2. `type` 字段检查（防滥用）

---

## 生产实验

### Ticket 签发验证

```bash
curl -s -X POST http://localhost:19014/api/im/ws/ticket \
  -H "X-User-Id: 10001"
```

JWT 解码结果：
```json
{"sub":"10001","type":"ws_ticket","iat":1785374434,"exp":1785374734}
```

- `exp-iat=300s=5min` ✅ 有效期正确
- `type=ws_ticket` ✅ 类型标记正确
- `sub=10001` ✅ 用户 ID 正确

### 无效 Ticket 拒绝验证

```python
websocket.create_connection("ws://...?ticket=INVALID", timeout=5)
# → WebSocketBadStatusException: status=200
```

拒绝时返回 HTTP 200 非 101，无明确错误码。**改进建议**：返回 401+错误描述 body。

### 正常连接验证

```python
ticket = POST /api/im/ws/ticket → 获取 JWT
ws = WebSocket("ws://.../api/im/ws?ticket={ticket}")
# → 连接成功，afterConnectionEstablished() 触发
```

连接成功后 Redis 中确认：
```bash
GET im:route:10001 → "60d744fd-3399467"
GET im:online:10001 → "1"
```

### type 字段防护验证

用 Access Token（type=access）替代 ws_ticket 连接：

```python
access_token = jwt.encode({"sub":"10001","type":"access",...}, secret)
ws = WebSocket("ws://.../api/im/ws?ticket={access_token}")
# → WebSocketBadStatusException: status=200（拒绝连接 ✅）
```

实际测试结果：
- 目标连接拒绝，`beforeHandshake` 返回 false
- `type=access` 不匹配 `"ws_ticket"`，校验拦截生效
- 日志输出：`[IM握手] ticket 解析失败: type=access`

通知模块的 SSE 认证用 Redis `getAndDelete` 确保 Ticket 一次性使用。但 IM 的 WebSocket 认证**没有使用这个机制**——多个客户端可以复用同一个 Ticket 建立连接：

```java
// 通知模块：一次性
String userId = stringRedisTemplate.opsForValue().getAndDelete(ticketKey);

// IM 模块：非一次性
Claims claims = JwtUtil.parseToken(ticket, jwtSecret);  // 只验签名，不删
```

**原因**：
- WebSocket 断线重连时，客户端需要重新发起 HTTP Ticket 请求（不是复用旧 Ticket）
- Ticket 5 分钟过期，窗口期很短
- 即使 Ticket 泄漏，攻击者也只能在 5 分钟内连接，且连接的 userId 固定（sub 字段）
- 通知模块的 Ticket 是 30 秒 + 一次性，因为 SSE 连接是前端直接暴露给浏览器，风险更高

---

## 安全假设

这套认证体系依赖**上游 Gateway 的 JWT 鉴权**（`GatewayAuthFilter`）。`/api/im/ws/ticket` 不是公开接口——Gateway 会拦截未登录请求：

```
请求 → Gateway (19000)
    ├─ 白名单？否 → 需要 JWT
    ├─ Authorization: Bearer {access_token} → 校验 JWT
    └─ 注入 X-User-Id Header → IM 服务
```

IM 服务的 `createTicket` 直接从 `X-User-Id` Header 读取 userId，不再验证。**如果 Gateway 被绕过、或 Gateway 白名单配置错误，IM 服务会信任上游传递的 Header 签发任意身份的 Ticket。**

---

## 面试 Q&A

**Q: 为什么不直接在 Gateway 把 JWT 换成 Ticket，IM 只认 Ticket？**
A: 可以，但 Gateway 是通用路由层，不应感知 IM 的 Ticket 业务逻辑。当前设计：Gateway 管认证（JWT），IM 管授权（Ticket）。职责分离。

**Q: Ticket 泄漏了怎么办？**
A: 5 分钟过期 + `type=ws_ticket` 防止误用 Access Token。即使泄漏，攻击者也只能以该用户身份连接 WebSocket，无法获取 Access Token 的刷新能力。

**Q: 为什么 IM 不用 Redis getAndDelete（通知模块的机制）？**
A: 见"为什么不用 getAndDelete"章节。核心是 IM Ticket 的 5 分钟有效期 + 非一次性设计对安全影响有限，而通知模块的 SSE 直接与浏览器交互，风险面更大。

---

## 发散

### Gateway 签发 Ticket

如果未来要求 Gateway 完全接管认证，可以将 Ticket 签发移到 Gateway：

```
客户端 → Gateway (验证 JWT) → Gateway 生成 Ticket → 返回客户端
客户端 → Gateway (携带 Ticket) → Gateway 透传到 IM → IM 验证 Ticket
```

Gateway 和 IM 共享 JWT 密钥即可。

### Refresh Token 模式

当前 Access Token 30 分钟过期，如果 WebSocket 连接时长超过 Token 有效期，客户端可以在 WS 连接期间通过 Refresh Token 续期。但 IM 的 WebSocket 连接只需在建立时认证——连接建立后不再校验 Token 状态。断线重连需要重新获取 Ticket。
