# Common 模块 Review（横切：日志/监控/全链路/安全）

## 全链路追踪（Trace）
1. **染色标记设计好**：6 维 TraceContext（traceId/userId/grayTag/apiVersion/abGroup/pressureTest）
   经 HTTP 头 + MQ 头全链路透传（TraceIdConfig + MqTraceHelper + Feign 拦截器）。结构完整。

2. **[中] O1 确认：MDC 未写 userId** (TraceIdConfig.java:84)
   preHandle 只 `MDC.put("traceId", ...)`，未写 userId。虽 TraceContext 持有 userId，但日志 MDC 无 userId 维度。
   → 按用户维度检索全链路日志困难。修复：`MDC.put("userId", ctx.getUserId())`（空则跳过），响应头可回传 X-User-Id。

3. **[中] O2 确认：异步线程池 MDC 支持各模块不一致**
   - home：MdcAwareExecutorService（正确，可作为标准模板）。
   - product(SPU_ASYNC_EXECUTOR)/inventory(inventoryAsyncExecutor)：裸 `new Thread`，无 MDC 透传。
   - order/cart 多处 `CompletableFuture.runAsync` 用默认 commonPool：无 MDC + 可能阻塞公共池。
   → 异步链（缓存刷新/补偿/并发Feign）日志 traceId 断裂。建议统一用 home 的 MdcAwareExecutorService。

## 监控（Metrics）
4. **ApiMetricsFilter**：URI 归一化 + P50/90/95/99 百分位 —— 指标基数控制良好。
   [低] 仅 Servlet 环境生效，Gateway(WebFlux) 无等效 HTTP 指标（注释已说明）。
5. BusinessMetrics/DlqMetrics/MyBatisMetrics —— 基础埋点齐全。

## 安全（横切）
6. **[高·系统性] X-User-Id 由下游信任 + X-Internal-Call 无统一服务端强制**
   UserContext 从 header 直接取 userId；X-Internal-Call 仅由 Feign 客户端添加，
   服务端逐端点手工 `token.equals(v)` 校验（非恒定时间，易漏配）。整体安全依赖"服务端口防火墙封闭"。
   建议：统一 InternalCall 过滤/拦截器 + 端口级网络安全兜底。

7. **Redis Key 跨模块硬编码耦合**：gateway 硬编码 blacklist/nonce/per-user-hmac key 前缀与 common
   RedisKeyConstants 双维护（代码注释已标注同步要求）—— 漂移风险。

## 工程
8. GlobalExceptionHandler（Servlet 版）、R、PageResult、BizException —— 统一错误模型，规范。
9. 依赖 TTL(transmittable) 做上下文透传（UserContext）—— 好，但需注意其与 MDC 是两套并存机制，
   易混淆（UserContext 存 ThreadLocal，MDC 存 SLF4J）。
