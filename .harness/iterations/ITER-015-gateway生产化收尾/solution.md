---
iteration: ITER-015
status: confirmed
created: 2026-10-19
updated: 2026-10-19
---

# 技术方案: gateway 生产化收尾

## 方案

```
F1 BodyCacheFilter：
  注入 AuthProperties；filter 首行 if (!authProperties.isHmacEnabled()) return chain.filter(exchange);
  （HMAC 关闭时 body 无需求方：签名校验跳过、下游转发走原始流——原无条件缓冲纯开销 + 1MB 上限副作用）
F2 死路径移除：
  删除 GrayRouteFilter.java / ApiVersionFilter.java（属性 grayTag/apiVersion 无消费者；
  自动灰度仅日志噪音）；TrafficColoring 的 X-Gray-Tag/X-Api-Version 头透传保留（与过滤器无关）
F3 fail-closed：
  GatewayAuthFilter.initSecretKey：空/演示密钥（MyXhs@2026#JwtSecretKey!ForTokenSign）→ IllegalStateException
F4 死配置：
  AuthProperties 删 hmacSecret 字段；application.yml + deploy nacos my-xhs-gateway.yaml 删 hmac-secret
测试：
  pom 加 spring-boot-starter-test；GatewayAuthFilterTest / BodyCacheFilterTest / HmacSignatureFilterTest
  （mock ServerWebExchange/GatewayFilterChain，Mono.block() 断言——不引入 reactor-test）
```

## 关键决策

1. **BodyCache 短路而非删**：HMAC 开启时需要 body；关闭时零成本——保留能力、消除默认开销；
2. **灰度/版本移除**：与 im LB（C-013）、home 分页注释（C-014）同族——"未接线组件不留"；头透传保留；
3. **fail-closed 与 im 对齐**：部署环境已设置 JWT_SECRET（否则 im 无法启动）→ 网关同步收紧无部署风险。
