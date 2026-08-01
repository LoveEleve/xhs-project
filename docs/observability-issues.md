# 可观测性问题清单 — 给中间件团队

> 验证日期：2026-07-31
> 状态：**全部闭环**（2026-08-01）

---

## 全部已解决 ✅

### SkyWalking 全链路（含 refs 跨进程/跨线程引用）
- 根因：时区差 8h + 无业务流量 + springmvc 3/4/5/6 共存冲突 + Redisson 噪音占满分页 + refs 解包字段号误判
- 修复：移走 v3/4/5 插件 + `SW_MOUNT_FOLDERS=plugins,activations,bootstrap-plugins` + webflux-6 插件移入
- **最终实证（22 span，4 服务贯通，refs 完整）**：
```
traceId: 3696c8ed7b034096be6f2a345387e8cc.240.17855678610420037
  [Entry] SpringMVC my-xhs-home GET:/api/home/user/{targetUserId}
  [Exit]  Feign    my-xhs-home /api/user/10001/info   ←home→user
  [Entry] SpringMVC my-xhs-user GET:/api/user/{userId}/info  ←CROSS_PROCESS refs ✅
  [Exit]  Feign    my-xhs-home /api/counter/batch-get ←home→counter
  [Entry] SpringMVC my-xhs-counter POST:/api/counter/batch-get ←CROSS_PROCESS ✅
  [Exit]  Feign    my-xhs-home /api/note/user/10001   ←home→content
  [Entry] SpringMVC my-xhs-content GET:/api/note/user/{userId} ←CROSS_PROCESS ✅
  + JdkThreading SwRunnableWrapper ←CROSS_THREAD ✅（线程传播）
  + SwCallableWrapper ←CROSS_THREAD ✅（mybatis 线程）
  + Lettuce/MySQL/HikariCP 完整调用链
```

### Gateway 入口（webflux 兜底）
- `apm-spring-webflux-6.x-plugin` 移入 plugins → `[Entry] spring-webflux my-xhs-gateway` ✅

### 遗留：Gateway 转发（route 级）链路
- SCG 4.1.2 无 `responseCacheSizeWeigher`（4.2+ 才有），gateway-4.x 插件 witness 盲区
- 对方已代报 apache/skywalking-java issue（B-1 方案）

### Prometheus / Grafana / Logstash→ES
- Prometheus 16/16 UP、Grafana 4 面板 + ES 日志、traceId 精确检索

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
