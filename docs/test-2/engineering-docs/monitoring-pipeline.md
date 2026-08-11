# my-xhs 监控管道全景

> 四层监控体系 | SkyWalking + Prometheus + ELK + XXL-Job

---

## 一、四层监控体系

```
┌─────────────────────────────────────────────────────────────┐
│ L1: 应用层 — SkyWalking                                      │
│  Agent: skywalking-agent-9.6.0.jar (15服务全挂载)          │
│  OAP:  gRPC :11800 ← 链路数据         HTTP :12800 ← 查询   │
│  UI:   :8080 拓扑图+调用链+慢查询                           │
│  存储: ES :19201 (elastic/Xhs@2026#ElasticSW)              │
├─────────────────────────────────────────────────────────────┤
│ L2: 指标层 — Prometheus + Grafana + VictoriaMetrics          │
│  抓取: /actuator/prometheus (15服务)                        │
│  PM:   :19090 指标采集 (15天保留)                           │
│  VM:  :8428 Prometheus兼容TSDB (30天保留, 长期存储)        │
│  Grafana: :13000 可视化面板 (admin/Xhs@2026#Admin)         │
├─────────────────────────────────────────────────────────────┤
│ L3: 日志层 — ELK (双通道)                                   │
│  通道1: 微服务(TCP直连) → Logstash :15044 → ES :19200      │
│  通道2: /logs/*.json → Filebeat → Logstash :15045 → ES     │
│  Kibana: :15601 日志可视化                                  │
├─────────────────────────────────────────────────────────────┤
│ L4: 业务层 — XXL-Job                                        │
│  Admin: :18080 (9服务启用定时任务)                           │
│  Executor: 9990-9999 (各服务独立端口)                       │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、SkyWalking — 全链路追踪

### 2.1 Agent 挂载

所有 15 服务通过 `start-all.sh` 统一挂载 agent:

```bash
JAVA_OPTS_BASE="-javaagent:/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar
  -Dskywalking.agent.service_name=SW_PLACEHOLDER    ← 启动时替换为 my-xhs-{svc}
  -Dskywalking.collector.backend_service=21.130.247.89:11800"
```

| 组件 | 端口 | 说明 |
|------|:--:|------|
| OAP gRPC | **11800** | Agent → OAP 链路数据上报 |
| OAP HTTP | **12800** | 健康检查 + 查询 API |
| SkyWalking UI | **8080** | 可视化面板 http://21.130.247.89:8080 |
| 存储 (ES) | **19201** | OAP 数据持久化到 `my-xhs-es-skywalking` |

### 2.2 链路追踪覆盖

| 能力 | 覆盖范围 |
|------|------|
| HTTP 调用链 | Gateway → 微服务 → Feign → DB/Redis/MQ (全链路) |
| MQ 消费链 | Producer → Broker → Consumer (跨进程追踪) |
| 数据库 | MySQL JDBC 自动埋点 → SQL耗时/慢查询 |
| 缓存 | Redis Lettuce 自动埋点 → GET/SET耗时 |
| 慢端点 | >1s 自动标记 → OAP 持久化 → UI 告警 |

### 2.3 拓扑图

SkyWalking 自动生成服务拓扑（无需手动配置）:
- 15 服务实例数、平均响应时间、错误率
- 服务间调用关系 (Feign/MQ)
- 端点级别的 QPS/延迟百分位 (p50/p90/p95/p99)

---

## 三、Prometheus — 指标采集

### 3.1 指标端点

每个微服务暴露 `/actuator/prometheus`:
```yaml
management:
  endpoints:
    web.exposure.include: health,info,prometheus,metrics,loggers
  endpoint:
    health.probes.enabled: true  # K8s 探针
```

### 3.2 Prometheus 配置

```yaml
# config/prometheus/prometheus.yml (实际配置)
global:
  scrape_interval: 5s        # 高频采集
  evaluation_interval: 15s
  scrape_timeout: 10s

rule_files:
  - "alert_rules/*.yml"      # 告警规则文件

scrape_configs:
  # 15 微服务 (每服务独立 static_config, 带 service label)
  - job_name: 'my-xhs-services'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 10s
    static_configs:
      - targets: ['21.214.97.212:19000']   # gateway
        labels: { service: 'my-xhs-gateway' }
      - targets: ['21.214.97.212:19001']   # user
        labels: { service: 'my-xhs-user' }
      # ... 全部 15 服务 (完整见 config/prometheus/prometheus.yml)

  # Prometheus 自身
  - job_name: 'prometheus'
    static_configs:
      - targets: ['21.130.247.89:19090']
```

> **注意**: 采集目标 IP 为 `21.214.97.212`（外部访问 IP），与中间件内部 IP `21.130.247.89` 不同 — 迁移部署时需同步修改 prometheus.yml。

### 3.3 核心指标

| 指标 | 来源 | 用途 |
|------|------|------|
| `jvm_memory_used_bytes` | JVM MXBean | 堆/非堆内存 |
| `jvm_gc_pause_seconds` | GC MXBean | GC 暂停时间 |
| `tomcat_threads_current` | Tomcat MBean | 线程池饱和度 |
| `http_server_requests_seconds` | Spring MVC | 端点延迟百分位 |
| `feign_http_client_requests_seconds` | Feign HC5 | 客户端调用延迟 |
| `hikaricp_connections_active` | HikariCP | 数据库连接池 |
| `lettuce_command_seconds` | Lettuce | Redis 命令延迟 |
| `rocketmq_consumer_offset` | RocketMQ Client | 消费位点 |
| `logback_events_total` | Logback | 日志事件计数(按级别) |

### 3.4 VictoriaMetrics (长期存储)

| 属性 | 值 |
|------|------|
| 端口 | **8428** |
| 保留期 | 30 天 (Prometheus 默认 15 天) |
| 兼容 | Prometheus Query API (Grafana 直接对接) |
| 用途 | 历史趋势分析、容量规划 |
| **当前状态** | ⚠️ **未接线** — prometheus.yml 无 `remote_write` 块，VM 容器在跑但无数据流入。启用需添加 remote_write 到 `http://21.130.247.89:8428/api/v1/write` |

### 3.5 Grafana Dashboard

| 属性 | 值 |
|------|------|
| 端口 | **13000** |
| 数据源 | Prometheus (自动 provisioning) |
| Dashboard | `config/grafana/provisioning/dashboards/json/` |
| 认证 | `admin` / `Xhs@2026#Admin` |

---

## 四、ELK — 日志管道

### 4.1 双通道架构

```
通道1 (TCP 直连, 实时):
  微服务 logback appender → TCP Socket → Logstash :15044 → ES :19200
  索引: myxhs-logs-YYYY.MM.dd

通道2 (Filebeat, 批量):
  微服务 → logback JSON_FILE → /logs/*.json
    → Filebeat (只读挂载) → Logstash :15045 → ES :19200
```

### 4.2 Logstash 配置

```
input {
  tcp   { port => 15044  codec => json_lines }     # 微服务直连
  beats { port => 15045 }                          # Filebeat 采集
}

output {
  elasticsearch {
    hosts    => ["127.0.0.1:19200"]
    user     => "elastic"
    password => "Xhs@2026#Elastic"
    index    => "myxhs-logs-%{+YYYY.MM.dd}"
  }
}
```

### 4.3 Filebeat 配置

```
filebeat.inputs:
  - type: log
    enabled: true
    paths: ["/logs/*.json"]             # 只读挂载
    json.keys_under_root: true          # JSON 字段提升到顶层
    json.add_error_key: true            # 解析错误标记

output.elasticsearch.enabled: false     # 不直写 ES
output.logstash.hosts: ["127.0.0.1:15045"]
```

### 4.4 Kibana

| 属性 | 值 |
|------|------|
| 端口 | **15601** |
| ES 连接 | :19200, 用户 `kibana_system` |
| 索引模式 | `myxhs-logs-*` |
| 访问 | http://21.130.247.89:15601 |

---

## 五、XXL-Job — 定时任务监控

### 5.1 已注册执行器

> 注: 仅 9 个服务 `xxl.job.enabled: true` — analytics 虽配置 executor port 9999 但缺 enabled 标志，实际不注册。

| 服务 | Executor 端口 | 核心任务 |
|------|:--:|------|
| order | 9991 | LocalMessageRetryJob (30s 指数退避补发) + DeadLetterScanJob (每小时死信扫描) |
| payment | 9992 | — |
| cart | 9993 | CartReconcileJob (对账) |
| home | 9994 | FeedCleanupJob + FeedMessageRetryJob (30s/60s 补偿) |
| coupon | 9995 | CouponOutboxSenderJob (5s 扫描补发) |
| inventory | 9996 | PreDeductTimeoutJob (1min 超时回退) + InventoryOutboxSenderJob (5s) + InventoryReconcileJob |
| search | 9997 | IncrementalIndexSyncJob + IndexRebuildJob + HotSearchJob + AntiSpamJob |
| counter | 9998 | CounterReconcileJob + CounterFlushJob |
| notification | 9990 | — |

### 5.2 核心定时任务

| 任务 | 调度 | 作用 |
|------|:--:|------|
| LocalMessageRetryJob | 30s | 本地消息表补发 (order 事务消息兜底) |
| FeedMessageRetryJob | 30s/60s | Feed 推送断点续推 |
| CouponOutboxSenderJob | 5s | Outbox 补发 (Redis 锁) |
| InventoryOutboxSenderJob | 5s | 库存 Outbox 补发 |
| PreDeductTimeoutJob | 1min | 超时冻结库存回退 |
| IncrementalIndexSyncJob | — | ES 失败记录补同步 |
| CounterReconcileJob | 凌晨 3:00 | Redis ↔ MySQL 对账 |
| TccTimeoutJob | 60s | 超 10 分钟 TCC 冻结支消 |

---

## 六、告警策略

### 6.1 应用级告警 (config/prometheus/alert_rules/myxhs_rules.yml)

```yaml
# 实际告警规则 (与配置文件一致)
groups:
  - name: myxhs_application_alerts
    rules:
      # 5xx 错误率 > 1% (持续 2 分钟) → P1
      - alert: HighErrorRate
        expr: sum(rate(myxhs_http_request_duration_seconds_count{status_group="5xx"}[5m])) by (application)
               / sum(rate(myxhs_http_request_duration_seconds_count[5m])) by (application) > 0.01
        for: 2m
        labels: { severity: critical, level: P1 }

      # P99 响应时间 > 1s (持续 5 分钟) → P2
      - alert: HighResponseTime
        expr: histogram_quantile(0.99, sum(rate(myxhs_http_request_duration_seconds_bucket[5m])) by (application, le)) > 1
        for: 5m
        labels: { severity: warning, level: P2 }

      # JVM 堆内存 > 85% (持续 5 分钟) → P1
      - alert: HighJvmMemoryUsage
        expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85
        for: 5m
        labels: { severity: critical, level: P1 }

      # GC 平均暂停 > 500ms → P2
      - alert: HighGcPause
        expr: increase(jvm_gc_pause_seconds_sum[5m]) / (increase(jvm_gc_pause_seconds_count[5m]) > 0) > 0.5
        for: 5m
        labels: { severity: warning, level: P2 }

      # HikariCP 连接池使用率 > 90% → P2
      - alert: HighHikariPoolUsage
        expr: hikaricp_connections_active / hikaricp_connections_max > 0.9
        for: 3m
        labels: { severity: warning }
```

### 6.2 业务级告警 (需接入 Webhook)

| 告警 | 检测方式 | 严重度 |
|------|------|:--:|
| RocketMQ 消息堆积 >1000 | `rocketmq_consumer_offset` 滞后 | 🔴 严重 |
| ES 索引同步失败率 >10% | search 服务自定义 metrics | 🟡 中等 |
| 库存预扣超时未释放 >100 | PreDeductTimeoutJob 扫到 | 🟡 中等 |
| MySQL 主从延迟 >10s | 从库 `SHOW SLAVE STATUS` 轮询 | 🟡 中等 |

> **注意**: 
> - 无 AlertManager 容器 — 告警仅停留在 Prometheus 规则文件，通知需自行接入 AlertManager/Webhook
> - 无 redis-exporter/mysql-exporter 容器 — 中间件指标 (Redis/MySQL) 未采集，需额外部署 exporter

### 6.3 健康检查端点

```bash
# 单服务
curl http://21.130.247.89:19000/actuator/health
# 预期: {"status":"UP","components":{"redis":{"status":"UP"},...}}

# 批量检查
for p in 19000 19001 19002 19003 19004 19006 19008 19009 19010 19011 19012 19013 19014 19015 19016; do
  curl -sf "http://21.130.247.89:$p/actuator/health" 2>/dev/null | python3 -c "import json,sys;d=json.load(sys.stdin);print(f':$p {d[\"status\"]}')"
done
```

---

## 七、生产就绪清单

| 检查项 | 状态 | 说明 |
|------|:--:|------|
| SkyWalking Agent 全服务挂载 | ✅ | 15/15，start-all.sh 自动注入 |
| Prometheus 指标暴露 | ✅ | /actuator/prometheus 15/15 |
| ELK 双通道就绪 | ✅ | TCP直连+Filebeat |
| Grafana Dashboard | ⚠️ | 基础 provisioning，需补充业务面板 |
| 告警规则 | ⚠️ | 基础规则就绪，需接入 Slack/Webhook |
| Logstash 管道 | ✅ | 双输入，日索引 myxhs-logs-YYYY.MM.dd |
| XXL-Job 监控 | ⚠️ | 9/9 执行器在线 (analytics 缺 enabled:true 未注册) |
| VictoriaMetrics 长期存储 | ⚠️ | 容器运行但未接线 (prometheus.yml 无 remote_write) |
| K8s 就绪探针 | ✅ | /actuator/health/readiness, /actuator/health/liveness |
| 日志级别动态调整 | ✅ | management.endpoint.loggers 暴露 |

### 待补充

1. **Grafana 业务面板**: JVM 健康总览 / 服务 QPS+延迟 / MQ 积压 / ES 状态
2. **告警通知**: Slack Webhook / 钉钉 / 邮件 / PagerDuty
3. **慢查询分析**: MySQL slow_query_log 0.5s → Filebeat 采集 → Kibana 聚合
4. **SkyWalking 告警**: OAP 内置告警规则 → Webhook 通知
