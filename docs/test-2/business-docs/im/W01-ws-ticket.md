# W01: WebSocket Ticket — POST /api/im/ws/ticket

## § 源码分析

- **Controller**: `ImController.java:59` → `@PostMapping("/ws/ticket")`, 参数 `X-User-Id`
- **Service**: `ImController.createTicket(userId)`
  - `JwtUtil.generateToken(String.valueOf(userId), "ws_ticket", 5*60*1000L, jwtSecret)`
  - 短期JWT ticket, 5分钟有效期, subject=userId
- **前置条件**: `jwt.secret` 必须 >= 256位(32字节)，否则启动失败
- **下游**: 无DB/Redis操作，纯JWT生成

## § 业务逻辑

两步法第一步 → 用HTTP POST(Header可带X-User-Id)获取短期JWT ticket → 5分钟有效 → 返回 {ticket: "jwt..."} → 客户端用ticket建立WebSocket连接

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header (Gateway注入) | 401 |
| JWT secret已配置 | 系统属性 `jwt.secret` >= 256位 | 启动失败 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/im/ws/ticket` | 200, {"data":{"ticket":"jwt..."}} |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | JWT 5min短期 | ✅ |
| 安全 | 不从URL暴露长期Token | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19014/api/im/ws/ticket \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图

```
curl POST /api/im/ws/ticket + X-User-Id
  → ImController.createTicket(userId)
    → JwtUtil.generateToken(userId, "ws_ticket", 5min, jwtSecret)
    → 返回 {ticket: "jwt..."} → 客户端拿ticket建WS连接
```
