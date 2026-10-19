---
iteration: ITER-015
status: confirmed
created: 2026-10-19
updated: 2026-10-19
---

# 测试设计: gateway 生产化收尾

- 单测（无运行时；mock ServerWebExchange/GatewayFilterChain + Mono.block()）：
  - GatewayAuthFilterTest：白名单放行 / 缺 Header 401 / 类型错 401 / 黑名单 401 / Redis 异常 503 / sub 空 401 / 通过注入（set 覆盖）
  - BodyCacheFilterTest：HMAC 关闭短路（不读 body）/ 开启 POST 缓存 + 装饰器 / multipart 跳过 / Content-Length 超限 413 / GET 跳过
  - HmacSignatureFilterTest：关闭透传 / 缺签名头 403 / 时间戳过期 403 / 密钥缺失 403 / nonce 重放 403 / 验签通过放行
- 回归：十五模块
