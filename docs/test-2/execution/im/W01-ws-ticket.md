# W01 — WS票据 (POST /api/im/ws/ticket)

> 2026-08-08 | 链7-5 | im | chaintest_c1

## § 业务逻辑

生成JWT WebSocket连接票据(5min有效期)→JwtUtil.generateToken(userId,"ws_ticket",5min,secret)。IM服务需JWT secret≥256位(`-Djwt.secret=...32+字节`)。

## § ASCII 流转图

```
curl → Gateway:19000(JWT+X-User-Id)
       → my-xhs-im:19014(POST /api/im/ws/ticket)
         → JwtUtil.generateToken(userId, "ws_ticket", 300s, jwtSecret)
```

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200, ticket=eyJ...HMAC384 ✅ |
| JWT密钥 | 需≥256位,已设32字节密钥 ✅ |

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 安全 | JWT HS384签名≥256位密钥 ✅ |
| 性能 | 5min短有效期防重放 ✅ |

## § 踩坑

JWT密钥需≥256位(HS256强制要求)，默认空值→500 WeakKeyException。已设`-Djwt.secret=...`
