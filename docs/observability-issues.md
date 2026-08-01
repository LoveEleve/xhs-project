# 可观测性问题清单 — 给中间件团队

> 验证日期：2026-07-31
> 状态：**主链路闭环**，遗留 2 个插件覆盖缺口（2026-08-01）

---

## 问题 1：SkyWalking HTTP 链路 — 已解决 ✅

**根因**：
1. 时区差 8 小时（业务机 CST / OAP UTC），UI 查错时间段
2. 无真实业务 HTTP 流量（只有 Redisson/定时任务/actuator）
3. springmvc 3/4/5/6.x 同系列插件共存时 v6 未注册（移走 v3/v4/v5 后激活）
4. queryBasicTraces 列表被 Redisson 噪音占满分页（缩小窗口+加大 pageSize 可翻到）

**验证（traceId 实证）**：
```
82e94fc00b0f43b8bc1e93a37e64f78d.153.17855002004280015（user）
  ✅ SpringMVC | GET:/api/user/{userId}/info | Entry
40e6df72ba9441c88adac783c92fd7a3.207.17855551599440001（home 聚合）
  ✅ SpringMVC | GET:/api/home/user/{targetUserId} | Entry
```

## 遗留缺口（需中间件团队解决——SkyWalking 插件生态）

### 缺口 1：Feign 跨服务调用无 Exit span ❌
**项目侧事实**：
- OpenFeign **feign-core 13.2.1**（SkyWalking 9.6.0 feign 插件最高支持 11.x）
- home 配置 `spring.cloud.sentinel.feign.sentinel.enabled: true`（Sentinel Feign 包装）
- fat jar 无 httpclient5（未用 Apache HC5，Feign 用内置 JDK 客户端）
- agent 日志：`feign.Client$Default ... completely`（插件增强成功）
- traceId 实证：home 聚合请求只有 1 个 SpringMVC Entry span，无 Feign Exit span

**需中间件确认**：SkyWalking 对 OpenFeign 13.x + Sentinel Feign 包装的插件支持情况；是否有对应新插件（如 feign 12/13.x 插件）可下载替换。

### 缺口 2：Gateway（WebFlux）入口无 span ❌
**项目侧事实**：
- gateway 用 Spring Cloud Gateway（WebFlux）
- spring-cloud-gateway-4.x 插件在 optional-plugins（已尝试激活）
- 激活后 witness 失败：`LocalResponseCacheAutoConfiguration.responseCacheSizeWeigher does not exist`
- 疑似 Spring Cloud Gateway 版本与插件 witness 不匹配

**需中间件确认**：gateway 插件匹配的 Spring Cloud Gateway 版本；是否有新版插件。

## 已确认正常
- Prometheus 16/16 UP
- Grafana 4 面板 + ES 日志（traceId 精确检索）
- Logstash→ES traceId
- SkyWalking：HTTP 入口 / Redis / DB / 定时任务 span ✅
- agent 9.6.0 + OAP 9.7.0 兼容 ✅

---

## 问题 2：Prometheus 自身抓取 DOWN（配置地址错误）

**现象**：Prometheus targets 中 `job=prometheus` 一直 DOWN。

**根因**：scrape_configs 中 Prometheus 自身的 target 地址写错：
```
配置：http://21.214.97.212:19090/metrics  ❌（服务机，无服务）
实际：http://21.130.247.89:19090/metrics   ✅（中间件机，Prometheus 在这）
```

**需要做的**：
1. 修改 Prometheus 配置（prometheus.yml）中 job=prometheus 的 target 为 `21.130.247.89:19090`
2. reload Prometheus（`kill -HUP` 或 API reload）
3. 验证：`http://21.130.247.89:19090/api/v1/targets` → job=prometheus UP

---

## 问题 3：Kibana 未部署 → 已用 Grafana 替代 ✅

**状态**：Kibana 无法部署，但**不需要它**——Grafana 已有 Elasticsearch-Logs 数据源。

**发现的问题**：Grafana ES 数据源开了 basicAuth（用户名 elastic）但**未存密码**，查询报 "Authentication to data source failed"。

**已修复（2026-07-31）**：通过 Grafana API 补上 ES 密码（`secureJsonData.basicAuthPassword`）。

**验证**：
```
Grafana 代理查询 ES: myxhs-logs-2026.07.31 → 10000 条命中 ✅
按 traceId 查询: traceId=0d0d58cf... → 1 条命中 ✅
```

**结论**：日志查询用 Grafana（Logs 面板）替代 Kibana 即可，无需部署 Kibana。

---

## 已确认正常的部分

| 组件 | 状态 |
|---|---|
| Prometheus 抓取微服务 | ✅ 15/16（除自身配置错误） |
| Grafana | ✅ 4 仪表盘，数据源查询正常 |
| Logstash→ES | ✅ traceId 字段可检索 |
| SkyWalking OAP | ✅ 正常接收 agent 上报 |
| 微服务 agent 挂载 | ✅ 15/15 |
