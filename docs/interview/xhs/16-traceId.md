# 第16题 | traceId 跨协议透传

> 难度：★★★☆☆｜频率：★★★★☆｜区分度：中
> 关键词：X-Trace-Id、MDC、ThreadLocal、染色标记 6 件套、MQ Header、Feign、线程池断链、长连接握手

## 问题
问题：一个请求经过网关、服务、MQ、异步线程、长连接，日志怎么串成一条线？异步和消息为什么会断链？

## 面试可讲版（五段式）

**① 业界背景**
链路追踪两派：**APM 自动埋点**（SkyWalking/Zipkin/OTel，跨进程自动传播）vs **业务 traceId + MDC**（日志级最小方案）。跨协议传播是共同难点：HTTP 有 Header、MQ 要放消息头、长连接要握手带、**线程池复用会丢 ThreadLocal**。标准侧有 W3C `traceparent`、B3 等。染色标记（灰度/压测）常与 traceId 一起透传，但用途不同：traceId 找链路，染色是路由依据。

**② 项目选择**
- 入口：网关 `RequestLogFilter` 生成/透传 **32 位无横线 UUID 的 `X-Trace-Id`**（上游有则透传不覆盖）；`TrafficColoringFilter` 负责把 4 个染色标记写入 `TraceContext`（grayTag / apiVersion 默认 v1 / abGroup 按 userId 自动分组 / pressureTest）；
- 上下文：`TraceContextHolder`（ThreadLocal）+ `TraceContext` **6 个染色标记**：traceId/userId/grayTag/apiVersion/abGroup/pressureTest；
- 跨服务：`FeignTraceInterceptorConfig` 从 **TraceContext 取** 6 标记透传 Header（不是从 HttpServletRequest 取——MQ 消费触发的 Feign 没有 request）；
- 跨 MQ：`MqTraceHelper` Producer 注入 Message Header、Consumer 恢复 TraceContext + MDC；
- 跨异步：`MdcAwareExecutorService` 包装线程池，任务执行前恢复 MDC、执行后清理（覆盖缓存刷新/延迟双删/布隆加载/补偿等异步链路）；
- 跨长连接：IM 握手 `X-Trace-Id` → URL 参数 → 生成三层取值，落 MDC；跨实例 Pub/Sub 消息带 traceId 并恢复 MDC（test-4 双实例两端同一 traceId）。

**③ 坑**
- **线程池复用丢 MDC**（O2 修复）：不包装线程池的话，异步任务的日志全丢 traceId，全链路就断在这一跳；
- **MQ 消费侧染色断裂**：Producer 不带、Consumer 不恢复，链路断；且下游 Feign 从 request 取上下文会 NPE/为空——所以必须从 TraceContext 取；
- **长连接没有标准 Header**：浏览器 WS 不能自定义 Header，回退 URL 参数；SSE 也是（所以有 ticket，见 10 题）；
- 两套体系别混：业务 `X-Trace-Id`（日志/MQ/审计）与 **SkyWalking 的 traceId**（APM span）是两个 ID，答辩时要分清用途。

**④ 兜底**
- "有则透传、无则生成"保证上游接入零改造；MDC 必须 finally 清理（防内存泄漏/串日志）；
- 异步任务与线程池统一走包装类；证据侧：SkyWalking 跨服务 22 span、test-4 IM 双实例同一 traceId、ES 可按 traceId 检索（traceid-es 问题单处理过）。
- 压测标记/灰度标记随 traceId 一起透传：`ShadowTableInterceptor` 读 `TraceContextHolder.isPressureTest()` 决定是否改写影子表（避免压测污染真实数据），`GrayRouteFilter`/`ApiVersionFilter` 消费对应标记做路由。

**⑤ 话术**
> "traceId 的难点不在生成，在'断链点'：MQ 的 Header、线程池的 ThreadLocal、长连接的握手。我们把 6 个标记装进 TraceContext，Feign/MQ/线程池/WS 各做一个透传适配，原则是'有则透传、无则生成、用完必清'。"

## 追问与参考回答
**追问1：MDC 是什么原理？会泄漏吗？** logback 的 ThreadLocal Map；线程池复用时不清会串日志/泄漏——所以包装类在 finally 里 remove，且池化线程的任务前先 put 当前上下文。
**追问2：ThreadLocal 怎么传给子线程？** 默认不传（InheritableThreadLocal 只解决 new Thread，不解决池）；池场景要显式捕获-恢复（我们 MdcAwareExecutorService 就是这个模式），或任务包装时携带快照。
**追问3：为什么不用 OTel 全自动？** SkyWalking 有自动埋点但跨 MQ/自定义异步/长连接仍要手动增强；业务 traceId 方案可控、无 agent 依赖、能进审计与消息表；两者并存（APM 管性能，业务 traceId 管日志与审计）。
**追问4：traceId 会冲突吗？** 32 位 UUID 空间足够；上游透传时冲突由上游负责——我们只在无上游时生成。
**追问5：染色标记与 traceId 的区别？** traceId 用于"看链路"，染色（灰度/压测/AB）用于"改行为"（路由/影子表）；一起透传但消费方不同。

## 发散追问地图（横向）
- 标准与生态：W3C tracecontext/B3/OTel SDK；Zipkin/Jaeger/SkyWalking/ARMS 对比。
- 采样与成本：头部采样/尾部采样、采样率对排障的影响；日志量与存储成本。
- 日志管线：Logback→Filebeat→ELK 的字段设计；traceId 索引与检索（我们修过 traceid-es 问题）。
- 异步传播：CompletableFuture/Reactor Context、@Async、定时任务的上下文注入。
- 跨语言：Java 与 Go/Python 服务的 header 约定；网关统一注入策略。

## 面试官评分点
**高级开发级**：能列出 4 类断链点与各自方案；知道 MDC 清理与线程池问题。
**架构师加分**：业务 traceId vs APM 定位、透传原则（透传/生成/清理）、采样与成本、与审计/染色的关系。
**危险信号**：以为 ThreadLocal 自动跨线程；MQ/WS 断链无方案；不清 MDC。

## 本项目真实证据
- `TraceContext.java:9,12,15,18,21,24`（6 字段）；`TraceContextHolder`（ThreadLocal）。
- `RequestLogFilter:21,27,32-33,39`（生成/透传 X-Trace-Id、32 位 UUID、不覆盖上游）；`TrafficColoringFilter:58-96`（grayTag/apiVersion/abGroup/pressureTest 写入 TraceContext）。
- `FeignTraceInterceptorConfig`（6 标记从 TraceContext 透传）；`MqTraceHelper`（Producer 注入/Consumer 恢复）；`MdcAwareExecutorService`（O2 修复注释：缓存刷新/双删/布隆/补偿防断链）。
- `ImHandshakeInterceptor:58-66`（Header→URL→生成三取一，落 attributes/MDC）；`ImRouteSubscriber` pub/sub 传 traceId；test-4 双实例同一 traceId。
- 旁证：FINAL-HANDOFF SkyWalking 22 span；`docs/traceid-es-issue.md`。

## 版本与来源
W3C Trace Context 规范；SkyWalking/MDC 文档；本项目 trace 包代码与 test-4/FINAL-HANDOFF 记录。

## 真实性说明
6 标记、过滤器行为、MQ/Feign/线程池适配均为代码事实；test-4 双实例同 traceId 为实测；APM 与业务 traceId 并存是现状口径。
