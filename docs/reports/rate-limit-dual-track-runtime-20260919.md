# 限流双轨：Sentinel 网关 + @RateLimit 业务注解（运行时实测）2026-09-19

> 目的：为 xhs/12（限流）补两层限流的运行时证据与口径。
> 关联：`sqlguard-sentinel-hardening-20260917.md`（规则托管 Nacos）、`xhs/12`、`xhs/17`（Filter 顺序）。

## 一、双轨职责（为什么两层）

| 层 | 机制 | 粒度 | 数据源 | 失败策略 |
|---|---|---|---|---|
| 网关 | Sentinel `GatewayFlowRule` | 路由级 QPS | Nacos（16 条，动态推送）+ metadata 兜底 | 429 + 自定义文案 |
| 业务 | `@RateLimit` AOP（Redis ZSet 滑动窗口 Lua） | 接口/用户级（perUser 或全局） | 注解（代码内 40 处） | Redis 不可用 **fail-open 放行**（日志告警） |

- 网关侧防"入口洪峰/恶意刷路由"；业务侧防"单用户高频"与业务语义限频（评论 10/分、发布 5/分、发货 10/分…）。
- 网关规则加载策略（防覆盖）：`ApplicationReadyEvent` 时若配置了 Nacos 数据源**不加载本地兜底**（避免 loadRules 覆盖后续 Nacos 推送）；30s 真空期兜底；启动日志 `[Gateway-Sentinel] Nacos规则已到达(规则数: 16)，跳过本地兜底`。

## 二、实测 A：Sentinel 网关层（payment-service 路由，count=5 QPS）

**方法**：登录用户携 JWT，25 并发 `GET /api/payment/status/1`（经网关 19000）。

| 结果 | 数值 |
|---|---|
| 通过 | **5 × 200** |
| 拦截 | **20 × 429**，body `{"code":429,"message":"请求过于频繁，请稍后再试","data":null}` |
| 网关日志 | `[Gateway-Sentinel-WebFlux] 请求被限流, path=/api/payment/status/1`（20 条） |
| Prometheus | `http_server_requests_seconds_count{status="429", error="ParamFlowException", method="GET"}=20` |

结论：路由级 5 QPS 精确生效（5 通过 / 20 拦截），规则来自 Nacos（16 条，payment 5 / order 10 / inventory 30 / coupon 30 / user 50 / cart 50 / … / product 500）。

## 三、实测 B：业务层 @RateLimit（评论 10 次/60s/用户）

**方法**：新用户突发 13 次 `POST /api/comment`（同一笔记）。

| 结果 | 数值 |
|---|---|
| 通过 | **10 × 200** |
| 拦截 | **3 × 429**，body `{"code":40202,"message":"评论过于频繁，请稍后重试"}` |
| Redis | `myxhs:comment:create:CommentController:createComment:{uid}` → **ZCARD=10，TTL=60s**（ZSet 滑动窗口，score=毫秒时间戳，member=时间戳+UUID 防重复） |
| 日志/指标 | `[限流拦截] key=…, maxRequests=10/60s`；content `http_server_requests{status="429", uri="/api/comment"}=3` |

结论：滑动窗口按"窗口内 ZCARD < maxRequests 才放行"原子判定；窗口过期由 ZREMRANGEBYSCORE + EXPIRE 维护。

## 四、边界与面试口径

1. **两层为什么不合并**：网关只认识路由，不懂业务语义（评论 vs 查询）；业务注解拿得到 userId/方法语义，但拦不住入口洪峰。互补而非重复。
2. **降级策略差异**：网关 Sentinel 失败按规则（快速失败）；@RateLimit 对 Redis 故障 **fail-open**（"失去限流保护好过全站不可用"，error 日志可观测）。
3. **规则变更**：网关规则 Nacos 动态推送（元数据 30s 兜底 + 防覆盖设计）；业务限流参数随代码发布（注解编译期），改动成本高但可控。
4. **可观测**：无自定义限流指标，但默认 HTTP 指标按 `status=429` 可聚合；@RateLimit 另有 WARN 日志（key 含 userId）。
5. **风险提示**：perUser 且未登录时按 IP 限流（NAT 下会误伤）；网关 429 的指标 `uri="UNKNOWN"`（路由未匹配标签），排查时看日志更直接。

## 五、证据索引
- 规则 JSON：Nacos（`my-xhs-gateway-sentinel-flow.json`，租户 my-xhs，16 条）；`[Gateway-Sentinel] Nacos规则已到达(规则数: 16)`
- 命令与结果：§二、§三（可复现：25 并发 / 13 突发）
- 代码：`RateLimitAspect.java`（Lua）、`RateLimitFilter.java`（Nacos 优先 + 30s 兜底）、`GatewayConfig.java`（gw-flow 转换器）
