# Gateway 模块 Review (fresh, 独立于旧文档)

## 关键发现

### 安全
1. **[高] HMAC 不签名请求体** (HmacSignatureFilter.java:183)
   signStr = method+path+timestamp+nonce，不含 body。攻击者拿到合法签名的写请求后可直接篡改 body（下单数量/金额字段）重放，签名仍有效。防篡改维度失效，只防了 header 重放。

2. **[高] 压测标记 IP 校验可被伪造** (TrafficColoringFilter.java:82-93, 110)
   `getClientIp` 在覆盖 XFF 之前执行，读取的是**客户端可控的原始 X-Forwarded-For**。
   攻击者设置 `X-Forwarded-For: 10.0.0.100` 即被判定为压测流量（isPressure=true）。
   预期：应基于 `remoteAddress`（不可伪造）做 IP 白名单判断；XFF 只用于代理场景且需信任边界。
   注：第110行把 XFF 覆盖为 remoteAddress 是好的，但发生在 pressure 判断之后，救不了该漏洞。

3. **[中] per-user HMAC secret 读取无 try/catch** (HmacSignatureFilter.java:169-174)
   `stringRedisTemplate.opsForValue().get(...)` 未包裹异常 → Redis 瞬时故障时该路径直接抛异常 → 500。
   与 nonce 路径（try/catch fail-open）不一致。建议与 #45 统一：Redis 故障时 fail-open 或明确策略。

4. **[中] HMAC secret 引号剥离 hack** (HmacSignatureFilter.java:177-179)
   依赖 RedisOperator(Jackson) 序列化后带引号这一实现细节，强耦合、脆弱。若 secret 含 `"` 或写入端序列化方式变化即失效。

5. **[低] timestamp 校验 `Math.abs(...)`** 允许未来 5min 内时间戳，略宽，可改单向校验。

### 死代码 / 未完成
6. **[中] 灰度路由 & API版本路由是 no-op** (GrayRouteFilter/ApiVersionFilter)
   均只设置 exchange 属性/日志，无任何自定义 LoadBalancer 消费（全仓无自定义 LoadBalancer）。
   灰度/版本路由实际不生效。

7. **[中] CachingFilteringWebHandler 未接线** (handler/CachingFilteringWebHandler.java)
   实现 WebHandler 但未注册为 bean，纯死代码。

8. **[低] RateLimiterConfig 三个 KeyResolver 未使用**
   限流实际走 Sentinel，Spring Gateway 内置 RequestRateLimiter 未启用，KeyResolver 全死。

### 配置 / 正确性
9. **[低] WebFlux(Netty) 网关注入了 `server.tomcat.*` 与 `spring.mvc`** (application.yml)
   Netty 不读 Tomcat 配置，300 线程等调优无效，属死配置。

10. **[低] Sentinel transport `client-ip: 21.214.97.212` 硬编码** (application.yml:76)
    公网 IP 硬编码，换机/IP 变更即失效；应自动获取或环境变量。

11. **[低] DataSource 排除重复**（yml autoconfigure.exclude + @SpringBootApplication exclude）
    冗余但无害。

12. **[中] 双白名单 (JWT whiteList vs HMAC hmacWhiteList) 维护成本高**
    两套独立路径列表易漂移；若新增写接口忘记加入 hmacWhiteList 校验之外，需注意默认必须签名（fail-closed 是对的）。

### 监控/日志
13. RequestLogFilter 用 MDC traceId + JSON encoder，正确。
14. 出站日志在 `.then(fromRunnable)` 记录，OK。
