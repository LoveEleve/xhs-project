# 可观测性问题清单 — 给中间件团队

> 验证日期：2026-07-31
> 状态：**全部闭环**（2026-08-01）

---

## 问题 1：SkyWalking HTTP 链路 — 已解决 ✅

**根因**：
1. 时区差 8 小时（业务机 CST / OAP UTC），UI 查错时间段
2. 无真实业务 HTTP 流量（只有 Redisson/定时任务/actuator）
3. springmvc 3/4/5/6.x 同系列插件共存时 v6 未注册（移走 v3/v4/v5 后激活）

**验证（traceId 实证）**：
```
82e94fc00b0f43b8bc1e93a37e64f78d.153.17855002004280015
  ✅ SpringMVC | my-xhs-user | GET:/api/user/{userId}/info | Entry
  ✅ Lettuce   | my-xhs-user | Lettuce/GET                  | Exit
```

**agent 配置**：9.6.0 + springmvc-annotation-6.x（plugins 目录，与 OAP 9.7.0 兼容）
**服务名**：-Dskywalking.agent.service_name（15 服务已配）

## 问题 2：Prometheus 自身抓取 DOWN — 已解决 ✅

prometheus.yml 自监控 target 改为 127.0.0.1:19090 后 16/16 UP。

## 问题 3：Kibana 未部署 — 已用 Grafana 替代 ✅

Grafana Elasticsearch-Logs 数据源补 ES 密码，日志可查（含 traceId 精确检索）。

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
