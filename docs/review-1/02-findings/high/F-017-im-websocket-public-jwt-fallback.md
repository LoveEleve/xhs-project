# F-017 IM WebSocket 握手使用公开 JWT Secret fallback，可伪造任意 ws_ticket

## 严重度

High

## 涉及文件

- `my-xhs-im/src/main/java/com/myxhs/im/handler/ImHandshakeInterceptor.java:28-29`
- `my-xhs-im/src/main/java/com/myxhs/im/controller/ImController.java:46-63`

## 现象

IM WebSocket 握手拦截器在缺少 `jwt.secret` 时回退到公开写入源码的固定密钥：

```java
@Value("${jwt.secret:MyXhs@2026#JwtSecretKey!ForTokenSign}")
```

而 `ImController.createTicket()` 使用同一 JWT secret 签发 `ws_ticket`。

## 证据

1. `ImHandshakeInterceptor.java:28` 包含公开已知的 JWT fallback。
2. `ImHandshakeInterceptor.java:42-56` 只校验 JWT 签名、`type=ws_ticket` 和 subject。
3. `ImController.java:59-63` 用该 secret 为任意 `X-User-Id` 签发五分钟 ws_ticket。
4. 找不到服务内对 ticket 是否被 gateway 签发、是否已撤销、是否绑定会话的额外校验。

## 触发条件

IM 服务未正确注入 `jwt.secret`，或配置漂移导致实际运行使用 fallback。

## 影响

知道源码中固定密钥的攻击者可自行签发任意用户的 `ws_ticket`，建立任意用户的 WebSocket 身份并发送/读取对应会话数据。

## 修复建议

1. 删除公开 fallback，缺失 `jwt.secret` 时拒绝启动。
2. IM 与 user/gateway 使用受保护的统一密钥配置，但避免源码内置默认值。
3. ticket 增加 jti、短 TTL、Redis 一次性消费或会话绑定，并在撤销/封禁时失效。
4. 增加配置启动检查，确认运行态 secret 非空且不是已知默认值。

## 是否需要补充验证

检查生产/测试 IM 进程的实际 `jwt.secret` 来源，并使用默认密钥生成伪造 ws_ticket 验证是否能建立连接。