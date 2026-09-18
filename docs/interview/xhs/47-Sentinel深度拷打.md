# 第47题 | 组件深度拷打：Sentinel

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：Slot 链、LeapArray 滑动窗口、GatewayFlowRule、规则持久化、双轨限流、集群限流边界

## 问题
限流怎么做的？为什么用 Sentinel？规则放在哪？单机限流还是集群限流？

## 面试可讲版（五段式）

**① 原理层**
- **核心是 Slot 责任链**：NodeSelector（资源调用树）→ ClusterBuilder（簇点）→ Statistic（**LeapArray 滑动窗口**统计）→ Authority/System → **FlowSlot（限流）** → DegradeSlot（熔断），按序执行、链上扩展；
- **滑动窗口实现**：时间切成等长 bucket（默认 1s 窗口切 2 个 500ms 桶）循环复用，避免固定窗口在切换点的 2 倍突刺；
- **流量控制**：QPS 或并发线程数；直接/关联/链路三种模式；快速失败 / Warm Up（冷启动预热）/ 匀速排队（漏桶）；
- **熔断降级**：慢调用比例 / 异常比例 / 异常数，半开探测；
- **网关适配**：`sentinel-spring-cloud-gateway-adapter` 把 route 映射为资源，GatewayFlowRule 支持 intervalSec 粒度、ParamFlowRule 支持按参数限流；
- **规则持久化**：内存（重启丢）→ 推模式数据源（Nacos/Apollo）→ 拉模式。**Dashboard 上直接改的规则不持久**，只适合调试。

**② 项目用法（双轨设计，分得很清楚）**
- **网关轨（Sentinel）**：规则 JSON 放 Nacos（`my-xhs-gateway-sentinel-flow.json`，namespace my-xhs，rule-type=gw-flow），**15 条路由级规则**（user 50、content 500、search 300、order 10、payment 5、inventory 30、product 500、home/recommend 300…），数量按容量实测校准；
- **服务轨（自定义 @RateLimit，40 处）**：Redis ZSet 滑动窗口 + Lua 原子脚本，key=`myxhs:ratelimit:{userId}:{controller}:{method}`，`@Order(10)` 最先执行；
- **为什么双轨**：网关要的是**路由级 QPS 挡量**（Sentinel 网关适配开箱即用）；服务要的是**业务键级限流**（按 userId+接口防单用户刷单/刷评论），Redis 自定义切面更直接；Sentinel 服务侧仅接 Dashboard（transport 8858、eager）做观测，**0 个 @SentinelResource**，不做业务限流。
- 算法选型（代码注释里的论证）：滑动窗口 > 固定窗口（无 2 倍突刺）、不需要令牌桶的突发能力、不需要漏桶的整形。

**③ 事故/坑（网关规则加载，真实设计演进）**
- **规则"真空期"覆盖坑**：Nacos 数据源异步拉取——若在 `@PostConstruct` 判断"规则为空"就加载本地兜底，会把随后 Nacos 推送的规则**覆盖掉**；现方案改为 `ApplicationReadyEvent` + **30s 真空期兜底**（30s 后仍空才加载本地规则，否则等待推送）；
- **本地兜底来源**：路由 `metadata.rate-limit-qps`（配置在 gateway yml），Nacos 不可用时仍有限流；
- **规则真源唯一**：Nacos；Dashboard 修改不落盘、重启即失（生产必须走数据源）；
- **@PostConstruct 时数据源未就绪**（类加载/Bean 顺序）→ 已由上面的时序方案规避。

**④ 兜底与边界（主动披露）**
- 限流被触发：网关统一 429 JSON（与 401 格式一致）；
- 服务侧 @RateLimit 在 Redis 故障时**降级放行**（可用性优先，记录异常）——限流失效但业务可用；
- **用户级限流（ParamFlowRule）未实现**：代码注释明确"当前状态：需从 X-User-Id Header 提参数，后续实现"——主动承认的缺口；
- **单机限流**：每网关实例独立计数（当前单实例无影响）；集群精确限流需 Token Server，未上；
- 熔断规则（DegradeRule）未配置——降级靠 Feign fallback + 超时（组件各司其职）。

**⑤ 拷打追问**
1. **"Sentinel 和 Hystrix/Resilience4j 的区别？"** Hystrix 线程池隔离重、已停维护；Resilience4j 轻量但无控制台/网关适配；Sentinel 中文生态+控制台+网关适配+热点参数，适合本项目（Spring Cloud Alibaba 同栈）。
2. **"滑动窗口怎么实现的？"** LeapArray：每个资源一个环形数组，窗口按 bucket 分片，统计时累加当前窗口内 bucket，过期 bucket 复用前先重置——空间 O(bucket 数)，时间 O(1)。
3. **"QPS 限流和线程数限流怎么选？"** 线程数适合"下游慢、要保护线程池"（如调用第三方）；QPS 适合入口挡量。本项目统一 QPS。
4. **"集群限流怎么做？"** Token Server（嵌入/独立）+ cluster flow rule，客户端请求 token；代价是多一次 RPC 与 Token Server 高可用，本项目未上（边界）。
5. **"规则为什么不在 Dashboard 改？"** Dashboard 改的是内存规则、重启丢失；本项目规则真源是 Nacos 数据源（可审计/可回滚/多实例共享）；Dashboard 只看实时 QPS/拒绝数。
6. **"429 之前会发生什么？"** FlowSlot 在 StatisticSlot 之后判断——本窗口统计值先累加，再决定放行/拒绝，拒绝也计入统计（避免"越限越多"的观测盲区）。
7. **"Warm Up 怎么用？"** 冷启动或缓存预热期，限流阈值从低到高爬坡，防止大流量直接把刚启动实例打穿；本项目网关路由未启用（流量已由 LB 均摊）。

**⑥ 话术**
> "限流我们分两轨：网关用 Sentinel GatewayFlowRule，15 条路由规则放 Nacos，按容量实测校准，本地还有路由 metadata 兜底——这里踩过一个时序坑：Nacos 规则异步到达，早期在 @PostConstruct 判断空就加载本地规则，会把推送覆盖掉，后来改成应用就绪事件 + 30 秒真空期兜底。服务侧没用 Sentinel，而是 40 处自定义 @RateLimit：Redis ZSet 滑动窗口加 Lua，按 userId+接口防单用户刷。规则真源只有 Nacos，Dashboard 只看不写。用户级网关限流和集群限流都是记录在案的缺口。"

## 发散追问地图（横向）
- 算法：固定/滑动窗口、令牌桶、漏桶、Warm Up、匀速排队。
- 架构：Slot 扩展点、热点参数、系统保护（Load/CPU）、集群限流 Token Server。
- 规则治理：Nacos/Apollo 数据源、Dashboard 推模式、规则版本与灰度。
- 对比：网关层限流（Sentinel/Nginx/Kong）与服务层限流的职责边界。
- 观测：pass/block QPS、RT、资源调用链、告警。

## 面试官评分点
**高级开发级**：能讲 Slot 链、滑动窗口、QPS/线程数模式、规则持久化。
**架构师加分**：双轨限流的职责划分；Nacos 规则异步时序坑与 30s 真空期设计；"Dashboard 不是真源"的治理观；主动给出用户级/集群限流缺口。
**危险信号**：以为 Dashboard 改规则会持久；不知道单机/集群区别；只会说"加了 @SentinelResource"（本项目恰恰没有）。

## 本项目真实证据
- `RateLimitFilter.java`（Nacos 数据源、ApplicationReadyEvent、30s 真空期、metadata 兜底、429 block handler、算法论证注释）；
- Nacos `my-xhs-gateway-sentinel-flow.json`（15 路由规则实测值）；网关 pom（gateway-adapter + datasource-nacos）；
- `RateLimitAspect.java`（Redis ZSet+Lua 滑动窗口、@Order(10)、Redis 故障降级放行、40 处使用）；
- 容量报告 `capacity-20260917.md:28`（200 突发 → 50×200+150×429 的 Sentinel 快速拒绝通道）。

## 版本与来源
Sentinel 官方文档（Slot/LeapArray/GatewayFlowRule）；Dashboard 1.8.8（容器）；本项目源码与容量报告。

## 真实性说明
规则数值/15 路由/40 处注解/0 处 @SentinelResource/端口均为运行态与代码事实；用户级限流、集群限流、熔断未配均主动披露。
