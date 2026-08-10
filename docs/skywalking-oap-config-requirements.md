# SkyWalking OAP 侧配置需求 — 给中间件团队

> 日期：2026-08-02
> 背景：agent 侧已修复（采样率 50/3s + Redisson 插件移除），OAP 侧还需以下 4 项配置

---

## 1. Service Level 只算 HTTP（当前 MQ 消费失败算进错误率）

**问题**：SkyWalking Service Level（Success Rate）把所有 `is_error=1` 的 span 算作错误，包括 MQ consumer / scheduled task / Redis heartbeat。导致 my-xhs-home 显示 90% Success Rate，但实际 HTTP 成功率是 100%——10% "错误"全部来自 `RocketMQ/FEED_TOPIC/Consumer` 消费失败。

**需要做的**：在 OAP `application.yml` 配置 `service-level-spec`，只统计 HTTP entry span：
```yaml
# OAP application.yml
service-level-spec: |
  http.status.code: 200-299
```
或者如果 SkyWalking 9.7.0 不支持 `service-level-spec`，在 `alarm-settings.yml` 里排除非 HTTP endpoint 的错误计数。

---

## 2. ES 数据 TTL（segment 无限增长）

**问题**：当前 ES 19201 上的 `sw_segment-YYYYMMDD` 索引没有 TTL 配置，segment 数据无限增长。今天已有 179 万 segment（采样前），如果不清理最终会磁盘满。

**需要做的**：在 OAP `application.yml` 配置数据保留期：
```yaml
# OAP application.yml — 核心 OAP 配置
core:
  Selector: ${SW_CORE_SELECTOR:elastic}
  recordDataTTL: 3               # segment/trace 数据保留 3 天（单位：天）
  metricsDataTTL: 7              # metrics 数据保留 7 天
  minuteMetricsDataTTL: 30       # 分钟级 metrics 保留 30 天
  hourMetricsDataTTL: 90         # 小时级 metrics 保留 90 天
  dayMetricsDataTTL: 365         # 天级 metrics 保留 365 天
```

同时建议配置 ES ILM（Index Lifecycle Management）自动删除过期索引：
```json
// ES ILM Policy: sw-segment-ttl
{
  "policy": {
    "phases": {
      "delete": {
        "min_age": "3d",
        "actions": { "delete": {} }
      }
    }
  }
}
```

---

## 3. RocketMQ Console 部署

**问题**：当前没有 RocketMQ Console/Dashboard，无法查看消费 lag / 死信列表 / 消费 TPS / 重试次数。

**需要做的**：部署 `apache/rocketmq-dashboard`（官方 Web 控制台）：
```bash
docker run -d --name rocketmq-dashboard \
  -p 18081:8080 \
  -e "rocketmq.config.namesrvAddr=21.130.247.89:9876;21.130.247.89:9877" \
  apacherocketmq/rocketmq-dashboard:latest
```

部署后可访问 `http://21.130.247.89:18081`，功能：
- 查看所有 Topic / Consumer Group
- 消费 lag（积压量）
- 死信队列列表 + 重发
- 消费 TPS 趋势
- 消息搜索（按 messageId / key）

---

## 已完成（agent 侧，无需中间件团队操作）

| 项 | 状态 | 配置方式 |
|---|---|---|
| 采样率 50/3s | ✅ 已生效 | `-Dskywalking.agent.sample_n_per_3_secs=50` |
| Redisson 噪音过滤 | ✅ 已生效 | 插件从 plugins/ 移到 optional-plugins/ |
| Gateway MDC traceId | ✅ 已生效 | RequestLogFilter 加 MDC.put + doFinally |
| 5 个增强插件 | ✅ 已生效 | gateway/sentinel/nacos/mybatis/spring-tx |
| Tomcat mbeanregistry | ✅ 已生效 | `-Dserver.tomcat.mbeanregistry.enabled=true` |
| HTTP histogram P95/P99 | ✅ 已生效 | `-Dmanagement.metrics.distribution.percentiles-histogram.*` |

---

## 联系方式

- 服务机：21.214.97.212（15 个微服务 19000-19016）
- 中间件机：21.130.247.89（OAP 8080/11800 + ES 19201 + Prometheus 19090 + Grafana 13000）
- SSH 到中间件机：`ssh -p 36000 root@21.130.247.89`
