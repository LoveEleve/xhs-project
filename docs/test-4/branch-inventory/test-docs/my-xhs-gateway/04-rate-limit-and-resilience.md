# gateway 测试用例 04 — 限流与弹性

> 依据：RateLimitFilter（Sentinel）、TrafficColoringFilter、GrayRouteFilter、Sentinel 规则。

## 测试目标
验证 Sentinel 限流、QPS 非法值保护、灰度 Header、降级行为。

## 用例清单

| # | 用例 | 请求构造 | 预期 | 状态 |
|---|------|----------|------|------|
| D1 | 限流触发 | 超限频请求（如支付接口 60s 内>10） | 429/限流响应 | ⬜ |
| D2 | 限流未超 | 正常频率请求 | 放行 | ⬜ |
| D3 | 非法 QPS 配置保护 | Sentinel 规则 QPS 非法/0/负 | 回退默认 QPS（RateLimitFilter 修复） | ⬜ |
| D4 | 灰度 Header 透传 | 带 X-Gray-Tag 请求 | TrafficColoringFilter 处理 + 透传 | ⬜ |
| D5 | 灰度默认不阻断 | 无灰度 Header | 交 GrayRouteFilter 判断（修复后不默认 stable） | ⬜ |
| D6 | 下游熔断降级 | 下游故障触发 Sentinel 熔断 | 降级响应而非挂起 | ⬜ |

## 下游证据
- RateLimitFilter 限流日志/响应码
- Sentinel 规则加载（nacos 动态数据源）
- 下游是否收到灰度 Header

## 已实测结果
- 部分限流接口（支付/领券 RateLimit 注解）在 controller 层有 RateLimitAspect；gateway Sentinel 层未实测

## 覆盖对账
- 灰度/版本实例筛选未接入 LoadBalancer（设计说明）
- Sentinel 实际规则加载/限流响应 待真实高频请求验证
