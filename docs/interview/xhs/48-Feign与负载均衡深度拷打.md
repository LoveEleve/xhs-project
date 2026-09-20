# 第48题 | 组件深度拷打：Feign / Spring Cloud LoadBalancer

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：动态代理、HC5 连接池、超时预算、Retryer 默认禁用、FallbackFactory、最小连接、实例列表缓存

## 问题
服务间调用怎么做的？超时和重试怎么定？负载均衡怎么选实例？发布时怎么不抖动？

## 面试可讲版（五段式）

**① 原理层**
- **Feign 调用链**：`@FeignClient` 动态代理 → Contract 解析注解 → RequestTemplate → Encoder → **Client（HTTP 客户端）** → Decoder；扩展点：RequestInterceptor（透传头）、ErrorDecoder（异常映射）、Retryer（重试）、Fallback/Factory（降级）；
- **HTTP 客户端演进**：默认 `HttpURLConnection`（无连接池、性能差）→ Apache HttpClient / OkHttp / **HC5**（连接池 + HTTP/2 + 更细的连接管理）；
- **SCLB（Spring Cloud LoadBalancer）**：`ReactorServiceInstanceLoadBalancer.choose()` 是扩展点；实例列表由 `ServiceInstanceListSupplier` 链提供（discovery → caching → zone/自定义）；
- **超时语义**：`connect-timeout`（建连）≠ `read-timeout`（等待响应）；两者必须按下游 P99 与链路总预算分配；
- **重试语义（易踩）**：Spring Cloud OpenFeign **默认 `Retryer.NEVER_RETRY`**——不重试是刻意的：非幂等写重试 = 重复下单/重复支付。

**② 项目用法（24 个 Feign 客户端，全链路统一）**
- **HTTP 客户端**：HC5 连接池（`max-connections: 200`、`per-route: 50`）；
- **超时分级**（按链路预算）：connect **500ms×11**（核心链路：订单/库存/支付）、1000ms×10、2000/3000/5000ms 少量；read **2000ms×8**、3000/5000ms 少量；
- **统一配置 `FeignUnifiedConfig`**：Decoder 自动解包 `R<T>.data`；ErrorDecoder 把非 2xx 还原为 `BizException`（业务异常穿透，不被当成网络错误吞掉）；
- **内部调用安全**：`FeignInternalCallInterceptor` 注入内部令牌，缺失时**不携带**（fail-closed，与 17 题一致）；
- **降级**：`feign.sentinel.enabled=true`（Sentinel 作为熔断器，FallbackFactory 才能被触发）；24 个客户端配 **52 处 fallback**——降级统一"记日志 + 返回降级值"（`[Feign降级]` 日志可检索）；
- **负载均衡**：自定义 `LeastConnectionsLoadBalancer`（按 `活跃请求数/权重` 比值选实例；需要业务侧 `markRequestStart/End` 埋点，否则计数恒 0——注释里写明的"用错即退化轮询"）；`ZonePreferenceServiceInstanceListSupplier` 同 zone 优先（`myxhs.availability.zone.preference.enabled` 开关，默认关）；
- **实例列表缓存**：home 服务显式 `spring.cloud.loadbalancer.cache.enabled=false`——**缓存 TTL 会拖长故障感知**（zone 演练教训：5s 缓存把切换 RTO 拖到 6.16s）；
- **优雅停机**（发布不抖动）：`server.shutdown=graceful` + ContextClosedEvent 里**先摘注册 + sleep 10s**（等 LB 实例列表传播）再停服，`timeout-per-shutdown-phase=30s` 收尾在途请求。

**③ 事故/坑**
1. **自定义 LB 未埋点 = 静默退化为轮询**：LeastConnections 依赖调用方 `markRequestStart/End`，没埋点时所有实例 active=0，选择退化为"相等取第一个"——必须配合埋点使用；
2. **实例列表缓存 vs 故障切换**：LB 缓存（默认 35s TTL）期间摘除的实例仍会被选中 → 请求失败后才重选；关闭/缩短缓存换来更快的故障感知（zone RTO 从 6.16s 降到 ~3s 级的因素之一）；
3. **降级是把双刃剑**：FallbackFactory 让购物车在商品服务不可用时仍能展示（缓存/默认值），但**降级数据不完整**要能说清边界；降级日志必须可观测（否则失败静默化）；
4. **重复头/透传**：traceId、灰度标记、X-Zone、内部令牌都靠 RequestInterceptor，拦截器顺序与幂等性要保证（多次重试不产生重复头，常见坑是 add 而非 replace）。

**④ 兜底**
- 超时 + fallback + 熔断（Sentinel）三层；降级返回值显式设计（不抛 500）；
- 非幂等写不重试（默认 NEVER_RETRY）+ 业务幂等兜底；
- 优雅停机让发布"无感"（摘除→传播→停服）。

**⑤ 拷打追问**
1. **"为什么用 HC5 不用默认实现？"** 默认 `HttpURLConnection` 无连接池，每请求新建连接（TIME_WAIT 风暴 + 延迟）；HC5 池化 + 连接复用 + 可观测（pool stats）。
2. **"超时怎么定才合理？"** connect 短（500ms-1s，建连失败快失败）；read 按下游 P99+余量（2-5s）；总量受入口 SLA 约束（网关 response-timeout 与链路预算对齐）。
3. **"为什么默认不重试？"** 写操作非幂等：重试可能重复下单；读操作重试也可能放大下游压力。要重试的场景用幂等键+显式配置。
4. **"fallback 里应该写什么？"** 记日志（含降级原因）+ 返回业务可接受的降级值；绝不能空实现吞掉异常——那是"假可用"。
5. **"负载均衡算法怎么选？"** 无状态均质 → 轮询；长连接/耗时差异大 → 最小连接/响应时间加权；有状态路由（IM 会话）→ 一致性哈希；多 zone → zone 优先 + 本地降级（26 题）。
6. **"实例摘除后为什么还会被调用？"** 列表缓存 + 传播延迟：注册中心推送 → 客户端缓存刷新窗口；解决靠关闭/缩短缓存 + 停机前摘除 + 重试/熔断兜底。
7. **"Feign 和 Dubbo 怎么选？"** 我们做过 Dubbo 试点（同接口双协议、Nacos 注册）后**决策不引入**（运维复杂度/收益不匹配，文档保留选型数据）——Feign 基于 HTTP、调试直观、与网关/服务网格天然兼容；性能不够时再考虑 gRPC/Dubbo。
8. **"Feign 调用怎么排查慢？"** 客户端 BASIC 日志 + HC5 pool 指标 + traceId 贯穿（16 题）；先区分建连慢/等服务慢/下游慢。

**⑥ 话术**
> "Feign 我们 24 个客户端全部 HC5 连接池，超时按链路预算分级——核心链路 connect 500ms、read 2s，长任务放到 5s。默认不重试是刻意的：非幂等写重试就是重复下单。降级用 FallbackFactory + Sentinel 熔断，52 处降级点都记日志、返回降级值。负载均衡有两个自定义：最小连接（要埋点，不埋点会退化轮询）和同 zone 优先。发布不抖动的关键是优雅停机：先从注册中心摘除、等 10 秒让实例列表传播、再停服，LB 缓存关掉以减少故障感知延迟。"

## 发散追问地图（横向）
- 客户端：HttpURLConnection/Apache/OkHttp/HC5 对比、连接池参数、TIME_WAIT。
- 治理：重试与幂等、熔断（Sentinel/Resilience4j）、舱壁、限流联动。
- LB：算法对比、一致性哈希（IM 有状态）、实例元数据路由（zone/灰度/版本）。
- 可观测：Feign 指标、连接池指标、traceId 透传、慢调用归因。
- 选型：Feign/Dubbo/gRPC/RestTemplate、服务网格（Sidecar）。

## 面试官评分点
**高级开发级**：能讲 Feign 调用链、超时语义、fallback 机制、LB 基本用法。
**架构师加分**：超时预算的链路视角；"默认不重试"的幂等解释；LB 缓存与故障感知的取舍；优雅停机三段式；自定义 LB 未埋点退化这类实战坑。
**危险信号**：用默认 HttpURLConnection 还谈性能；给非幂等写开重试；fallback 空实现吞异常；不知道实例缓存会导致切换慢。

## 本项目真实证据
- `FeignUnifiedConfig.java`（Decoder 解包/ErrorDecoder）、`FeignInternalCallInterceptor.java`（fail-closed 令牌）；24 个 @FeignClient / 52 处 fallback（代码统计）；
- 超时/HC5 配置（cart 等 application.yml）；`LeastConnectionsLoadBalancer.java`（active/weight、埋点注释）；`ZonePreferenceServiceInstanceListSupplier`、`GracefulShutdownListener`（摘除 + 10s 传播等待）；home 的 LB cache=false；
- Dubbo 试点与不引入决策：`docs/design/rpc-upgrade.md`。

## 版本与来源
OpenFeign / Spring Cloud LoadBalancer 官方文档；本项目配置与 zone 演练报告。

## 真实性说明
客户端数/降级数/超时分布/配置项均为代码与运行态事实；LeastConnections 需埋点、LB 缓存的取舍等边界来自代码注释与演练复盘。

## 本轮补充（2026-09-20 依赖韧性实测）
- 分级超时 + **52 处降级点**；重试策略 **NEVER_RETRY**（防写链路重试放大）。
- HC5 池化 + 同 zone 优先 + 优雅停机（摘注册+传播等待）；netem 4s 慢下游 → 聚合 **3.0s 准时降级**（404 伪装 500 的语义修复为 503）。
