# gateway 业务分析

## 1. 模块边界与定位
- **入口**：Spring Cloud Gateway (WebFlux)
- **核心能力**：JWT 鉴权、HMAC 签名校验、路由分发、限流、日志透传、灰度/染色
- **下游**：my-xhs-* 全部业务服务

## 2. 鉴权链路（业务入口关键）
- `GatewayAuthFilter` (Order 1000)：JWT 鉴权（基于 `myxhs.gateway.auth.secret`，校验 token 有效性，检查黑名单 Redis）。
- `HmacSignatureFilter` (Order 1500)：HMAC 签名校验（防篡改+防重放）。
- `whiteList` (application.yml)：只绕过 JWT Filter；HMAC 是否绕过由独立的 `hmac-white-list` 和 `hmac-enabled` 决定，不能把两类白名单合并理解。

## 3. 下游分发与边界检查
- `GatewayConfig`：全局连接池、超时时间配置（T-020 连接池调优）。
- 路由表：
  - 用户认证：`/api/user/**` (RateLimit 50QPS)
  - 内容服务：`/api/content/**` 等 (RateLimit 200QPS)
  - 搜索/推荐：`/api/search/**` 等 (RateLimit 300QPS)
  - 下单/支付：严格限流 (5-10QPS)
  - 内部/管理端点：通过 `X-Admin-Call` / `X-Internal-Call` 保障

## 4. 待确认与测试重点
- [ ] 鉴权链路是否存在遗漏？
- [ ] 白名单（JWT/HMAC 独立）是否配置混乱？
- [ ] 路由 metadata 中的限流/超时是否在 Filter 中真实生效？
- [ ] 灰度/染色实现逻辑是否闭环？

## 5. 文件对账清单
- `application.yml` (已覆盖，鉴权与路由)
- `AuthProperties.java` (已覆盖，鉴权配置属性)
- `GatewayAuthFilter.java` (已覆盖，JWT 入口)
- `HmacSignatureFilter.java` (已覆盖，签名校验入口)
- `RateLimitFilter.java`、`TrafficColoringFilter.java`、`GrayRouteFilter.java`、`ApiVersionFilter.java`、`BodyCacheFilter.java`、`RequestLogFilter.java` 等源码逻辑已在 `06-source-deep-analysis.md` 展开；本文件仅保留业务流转摘要。
