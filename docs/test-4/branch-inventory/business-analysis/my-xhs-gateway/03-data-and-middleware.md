# gateway 流量与路由控制分析

## 1. 流量染色 (TrafficColoringFilter)
- **职责**：将请求染色（灰度/版本/压测），通过 `ServerWebExchange` 属性向下透传。
- **现状**：仅实现 Header 解析 + 属性注入，下游负载均衡需自定义实现。

## 2. 路由控制 (GrayRouteFilter / ApiVersionFilter)
- **职责**：动态路由判断。
- **现状**：同样只做了属性解析，未打通真实 LoadBalancer 实例过滤，当前能力未闭环。

## 3. 限流 (RateLimitFilter)
- **职责**：把 route metadata 转换为 Sentinel Gateway FlowRule，再交给 Sentinel 执行。
- **现状**：当前主要是 routeId 级别限流；虽然存在 `KeyResolver`，但没有看到用户 key 被接入 `GatewayParamFlowRule`，因此不能描述为已实现用户级限流。
- **规则来源**：当前配置中未发现 Sentinel Nacos flow datasource 的完整配置，实际路径需要以运行日志和 Sentinel 规则状态确认；route metadata 是本地 fallback 依据。

## 4. 文件覆盖对账状态
本文件只覆盖流量控制相关逻辑；完整源码覆盖对账以 `06-source-deep-analysis.md` 第 15 节为准。
- `RateLimitFilter.java`：已覆盖
- `TrafficColoringFilter.java`：已覆盖
- `GrayRouteFilter.java`：已覆盖
- `ApiVersionFilter.java`：已覆盖
- `BodyCacheFilter.java`：已覆盖
- `RequestLogFilter.java`：已覆盖
