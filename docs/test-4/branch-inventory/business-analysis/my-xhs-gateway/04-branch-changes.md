# gateway 辅助能力与改动点

## 1. BodyCacheFilter / CachingFilteringWebHandler
- **职责**：缓存请求 Body，以便在 Filter 链多次读取（HMAC 签名校验需要读取 Body）。
- **现状**：必须配合，防止 Body 流消费完无法转发给下游。

## 2. RequestLogFilter
- **职责**：入站请求全链路日志打印（TraceId 记录）。
- **现状**：配合 JSON Logback 配置实现。

## 3. GlobalExceptionHandler
- **职责**：处理未被 Filter 直接消费的异常，并按连接失败、超时、路由不存在和未知异常映射响应。
- **现状**：JWT/HMAC/限流 Filter 的 401/403/429 多数是直接写响应，不会统一经过这里；该 handler 不能被描述为所有错误响应的唯一出口。

## 4. 当前分支改动点
- 已修正 HMAC 签名漏洞、DCL 风险
- 已配置 `JWT_SECRET` 环境变量注入
- 已配置 ELK JSON 日志落盘（logback-spring.xml）
- 旧 IP 已替换

## 5. 文件覆盖对账
- `BodyCacheFilter.java` (已覆盖)
- `RequestLogFilter.java` (已覆盖)
- `CachingFilteringWebHandler.java` (已覆盖)
- `GlobalExceptionHandler.java` (已覆盖)
- `logback-spring.xml` (已覆盖)
- `GatewayApplication.java` (已覆盖)
- `GatewayConfig.java` (已覆盖)
- `GatewayMetricsConfig.java` (已覆盖)
- `RateLimiterConfig.java` (已覆盖)
