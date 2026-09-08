# gateway 测试用例 05 — 可观测性

> 依据：RequestLogFilter、TraceId 透传、actuator/prometheus、SkyWalking、ELK。

## 测试目标
验证请求日志、TraceId 全链路、指标暴露与日志脱敏。

## 用例清单

| # | 用例 | 请求构造 | 预期 | 状态 |
|---|------|----------|------|------|
| E1 | 请求访问日志 | 任意接口 | RequestLogFilter `>>>`/`<<<`（method/path/status/duration/traceId） | ⬜ |
| E2 | TraceId 透传下游 | 带/不带 X-Trace-Id 请求 | gateway 生成并透传到下游，下游日志同 traceId | ⬜ |
| E3 | Prometheus 指标 | GET /actuator/prometheus | 暴露请求/限流/路由指标 | ⬜ |
| E4 | Actuator 健康 | GET /actuator/health | UP + 下游健康 | ✅ |
| E5 | 日志脱敏 | 带 token/敏感参数请求 | 日志不打印 Authorization/token 明文 | ⬜ |
| E6 | 异常日志完整 | 触发 4xx/5xx | GlobalExceptionHandler 完整异常日志（含 cause） | ⬜ |

## 下游证据
- gateway 日志 traceId 与下游（如 order）日志 traceId 一致
- prometheus 指标数据点
- ELK（myxhs-logs-* 索引）能检索到网关日志

## 已实测结果
- E4：actuator health ✅
- E2 部分：日志可见 traceId，跨服务一致未端到端断言

## 覆盖对账
- SkyWalking agent 未挂载（服务未用 SW），ELK 日志索引存在（myxhs-logs-2026.09.06）
