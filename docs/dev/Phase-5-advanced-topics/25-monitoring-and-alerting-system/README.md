# 监控告警体系

> 跨服务专题 | 开发阶段：Phase-5 | 状态：⏳ 待开发

---

## 🎯 一、可观测性三支柱

| 支柱 | 工具 | 解决什么问题 |
|------|------|------------|
| **指标（Metrics）** | Prometheus + Grafana | 系统健不健康？QPS/RT/错误率多少？ |
| **链路（Traces）** | SkyWalking | 请求慢在哪个服务？哪个方法？ |
| **日志（Logs）** | Loki + Promtail | 出了什么错？上下文是什么？ |

---

## 🏗️ 二、监控架构

```
                    ┌─────────────┐
                    │   Grafana   │ ← 统一可视化面板
                    └──────┬──────┘
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │Prometheus│ │SkyWalking│ │   Loki   │
        │ 指标存储  │ │ 链路存储  │ │ 日志存储  │
        └────┬─────┘ └────┬─────┘ └────┬─────┘
             │            │            │
        Micrometer    SkyWalking   Promtail
        (各服务暴露)   Agent(探针)  (日志采集)
             │            │            │
        ┌────┴────────────┴────────────┴────┐
        │         15 个微服务实例              │
        └───────────────────────────────────┘
```

---

## 📊 三、指标设计

### 3.1 业务指标

| 指标 | 类型 | 标签 | 告警阈值 |
|------|------|------|---------|
| `order_create_total` | Counter | status=success/fail | 失败率 > 1% |
| `payment_success_total` | Counter | pay_type | 成功率 < 99% |
| `api_request_duration` | Histogram | uri, method | P99 > 500ms |
| `mq_consume_lag` | Gauge | topic, group | 积压 > 10000 |

### 3.2 系统指标

| 指标 | 告警阈值 | 说明 |
|------|---------|------|
| CPU 使用率 | > 80% 持续 5 分钟 | P0 告警 |
| 内存使用率 | > 85% | P0 告警 |
| JVM GC 停顿 | > 500ms | P1 告警 |
| 磁盘使用率 | > 90% | P0 告警 |
| Redis 连接数 | > 80% maxconn | P1 告警 |

### 3.3 告警分级

| 级别 | 定义 | 响应时间 | 通知方式 |
|------|------|---------|---------|
| P0 | 核心链路不可用（下单/支付） | 5 分钟 | 电话 + 短信 + 钉钉 |
| P1 | 非核心功能异常（搜索/推荐） | 30 分钟 | 钉钉 + 邮件 |
| P2 | 性能劣化（RT 升高/QPS 下降） | 2 小时 | 钉钉 |
| P3 | 预警（磁盘/内存接近阈值） | 次日 | 邮件 |

---

## 💻 四、核心代码实现

### 4.1 Micrometer 业务指标埋点

```java
@Component
public class BusinessMetrics {
    private final Counter orderSuccessCounter;
    private final Counter orderFailCounter;
    private final Timer apiTimer;

    public BusinessMetrics(MeterRegistry registry) {
        this.orderSuccessCounter = Counter.builder("order_create_total")
            .tag("status", "success").register(registry);
        this.orderFailCounter = Counter.builder("order_create_total")
            .tag("status", "fail").register(registry);
        this.apiTimer = Timer.builder("api_request_duration")
            .publishPercentiles(0.5, 0.9, 0.99)
            .register(registry);
    }

    public void recordOrderSuccess() { orderSuccessCounter.increment(); }
    public void recordOrderFail() { orderFailCounter.increment(); }
}
```

### 4.2 JSON 结构化日志

```java
// logback-spring.xml 配置 JSON 格式
// 每条日志包含：timestamp, level, traceId, userId, service, message
{
  "timestamp": "2026-05-12T18:00:00.000",
  "level": "INFO",
  "traceId": "abc123def456",
  "userId": "10001",
  "service": "my-xhs-order",
  "class": "OrderCreateService",
  "message": "订单创建成功: orderId=200001"
}
```

---

## 🐛 五、踩坑记录

### 5.1 Prometheus 拉取超时

- **原因**：服务暴露的 /actuator/prometheus 端点数据量太大
- **解决**：过滤不需要的指标，只暴露业务指标 + 关键 JVM 指标

### 5.2 SkyWalking Agent 导致启动变慢

- **原因**：Agent 字节码增强耗时
- **解决**：排除不需要增强的包（第三方库）

---

## 🎤 六、面试考察点

### Q1: 你们的监控体系是怎么搭建的？

> 1. "三支柱：Prometheus（指标）+ SkyWalking（链路）+ Loki（日志）"
> 2. "Grafana 统一面板：服务概览→单服务详情→接口级明细"
> 3. "告警分级：P0 电话+短信（5分钟响应），P1 钉钉（30分钟），P2/P3 邮件"

### Q2: 日志怎么和链路追踪关联起来的？

> 1. "Gateway 注入 X-Trace-Id → 全链路透传"
> 2. "SkyWalking Agent 自动注入 traceId 到 MDC"
> 3. "日志 JSON 格式包含 traceId 字段"
> 4. "Grafana 中点击 traceId → 跳转到 SkyWalking 链路详情"

---

## 📚 参考资料

| 资料 | 参考内容 |
|------|----------|
| 📄 phase-5/README.md §3.25 | 监控告警体系完整设计 |
| 📄 31-logging-and-observability | 日志体系与可观测性 |
