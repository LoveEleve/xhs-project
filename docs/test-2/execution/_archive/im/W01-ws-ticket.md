# W01 — WebSocket Ticket 签发

`POST /api/im/ws/ticket` | JWT required

## ASCII 流转图

```
[curl] → Gateway:19000 → im:19014
  → ImController.createTicket(X-User-Id=10001)
  └ JwtUtil.generateToken("10001", "ws_ticket", 5min, jwtSecret)
     → 返回 JWT ticket (211 chars, 5分钟有效)
```

## 业务逻辑

两步法 WebSocket 鉴权——客户端先调用此 REST 接口获取短期 JWT ticket（5 分钟有效期），然后用 ticket 作为参数建立 WebSocket 连接。避免在 WS URL 中暴露长期 accessToken。纯 JWT 生成，无 MySQL/Redis 操作。

## curl

```bash
curl -s -X POST "http://localhost:19000/api/im/ws/ticket" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
# → {"code":200,"data":{"ticket":"eyJhbGciOiJIUzI1NiJ9..."}}
```

## 七层验证

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, 211 chars JWT ticket | ✅ |
| MySQL | N/A（纯 JWT） | — |
| Redis | N/A | — |
| MQ | N/A | — |
| SkyWalking | traceId=05e05d76bc31432a96c719b29e241726 | ✅ |
| Prometheus | POST /ws/ticket 指标已曝光 | ✅ |
| 日志 | JWT 生成无额外日志 | ✅ |

## 踩坑

| 问题 | 根因 | 解决 |
|------|------|------|
| 403 "签名校验失败" | Gateway hmac-white-list 缺 `/api/im/ws/**` | 加白名单，编译重启 Gateway |
