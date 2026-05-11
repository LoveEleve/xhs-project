# 日志体系与可观测性

> 所属维度：可观测性 | 开发阶段：Phase-6 | 核心组件：Logback + Promtail + Loki + Grafana + SkyWalking

---

## 🎯 一、可观测性三支柱

> 📖 **知识来源**：《高可用架构第1卷》第5章 — 监控体系
> - "可观测性三支柱——Logs(日志)+Metrics(指标)+Traces(链路)，缺一不可"
> - "监控分层：基础设施(CPU/内存) → 中间件(Redis/MySQL/MQ) → 应用(QPS/RT) → 业务(订单量/转化率)"

| 支柱 | 工具 | 用途 |
|------|------|------|
| Logs（日志） | Promtail → Loki → Grafana | 按TraceId/服务名/关键字检索日志 |
| Metrics（指标） | Prometheus → Grafana | QPS/RT/错误率/JVM/中间件监控 |
| Traces（链路） | SkyWalking | 全链路追踪，定位跨服务瓶颈 |

---

## 🏗️ 二、日志规范

### 2.1 规范要求

| 规范项 | 要求 | 说明 |
|--------|------|------|
| 日志格式 | JSON结构化 | 便于Loki解析，不用写复杂的Grok正则 |
| 日志级别 | ERROR/WARN/INFO/DEBUG | 生产环境默认INFO，需要排查时动态调为DEBUG |
| TraceId | 每条日志必须包含 | 关联SkyWalking链路，一个请求的所有日志能串起来 |
| 业务字段 | userId/orderId等关键ID | 便于按业务维度检索 |
| 敏感信息 | 脱敏处理 | 手机号/密码/Token不能明文打印 |

### 2.2 日志格式定义

```json
{
  "timestamp": "2025-05-09T18:00:00.123+08:00",
  "level": "INFO",
  "traceId": "abc123def456",
  "spanId": "789xyz",
  "service": "my-xhs-order",
  "instance": "order-pod-abc123",
  "thread": "http-nio-9011-exec-1",
  "logger": "com.myxhs.order.service.OrderService",
  "message": "下单成功",
  "userId": 10001,
  "orderId": "202505091800001234",
  "rt": 156,
  "extra": {}
}
```

---

## 💻 三、核心实现

### 3.1 Logback配置（JSON结构化 + SkyWalking TraceId）

```xml
<!-- my-xhs-common/src/main/resources/logback-spring.xml -->
<configuration>
    <!-- 控制台输出(开发环境) -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${SERVICE_NAME:-unknown}"}</customFields>
        </encoder>
    </appender>
    
    <!-- 文件输出(生产环境) -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/var/log/my-xhs/${SERVICE_NAME}.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/var/log/my-xhs/${SERVICE_NAME}.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>7</maxHistory>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${SERVICE_NAME:-unknown}"}</customFields>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

### 3.2 TraceId注入（Gateway → 全链路透传）

```java
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = Optional.ofNullable(TraceContext.traceId())
                .orElse(UUID.randomUUID().toString().replace("-", ""));
        
        MDC.put("traceId", traceId);
        
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header("X-Trace-Id", traceId)
                .build();
        
        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signalType -> MDC.clear());
    }
}
```

### 3.3 日志采集架构

```
┌──────────┐    ┌──────────┐       ┌─────────────┐
│ Pod日志  │───→│ Promtail │──────→│    Loki     │
└──────────┘    └──────────┘       └─────────────┘
                                         │
                                         ↓
                                   ┌─────────────┐
                                   │   Grafana   │
                                   └─────────────┘
```

| 组件 | 作用 | 部署方式 |
|------|------|----------|
| Promtail | 日志采集Agent | DaemonSet(每个Node一个) |
| Loki | 日志存储+索引 | StatefulSet |
| Grafana | 日志查询UI | 与指标监控共用 |

---

## 📋 四、日志告警规则

```yaml
groups:
  - name: my-xhs-log-alerts
    rules:
      # 错误日志突增
      - alert: HighErrorRate
        expr: sum(rate({service=~"my-xhs-.*"} |= "ERROR" [5m])) by (service) > 10
        for: 2m
        annotations:
          summary: "服务 {{ $labels.service }} 错误日志突增"
          
      # 关键业务异常
      - alert: OrderCreateFailed
        expr: count_over_time({service="my-xhs-order"} |= "下单失败" [5m]) > 5
        for: 1m
        annotations:
          summary: "下单失败数量异常"
          
      # OOM检测
      - alert: OutOfMemory
        expr: count_over_time({service=~"my-xhs-.*"} |= "OutOfMemoryError" [5m]) > 0
        for: 0m
        annotations:
          summary: "服务 {{ $labels.service }} 发生OOM"
```

---

## 🔗 五、日志与链路追踪关联

**效果**：在Grafana中点击SkyWalking链路的某个Span → 自动跳转到Loki查询该TraceId的所有日志。

查询示例：
```
{service="my-xhs-order"} |= "下单失败"
{traceId="abc123def456"}
```

---

## 🎤 六、面试考察点

### Q1: 你们的日志体系怎么搭建的？

**推荐回答思路**：

> 1. "JSON结构化日志+TraceId贯穿全链路，方便检索和链路追踪关联"
> 2. "采集用Promtail Agent→Loki存储→Grafana查询，比ELK轻量很多"
> 3. "告警规则：错误日志突增、关键业务异常、OOM检测"

### Q2: 线上排查问题的流程是什么？

**推荐回答思路**：

> 1. "用户反馈/告警触发 → Grafana看指标面板(QPS/RT/错误率)"
> 2. "定位到问题服务后 → SkyWalking看链路追踪，找到慢Span"
> 3. "拿到TraceId → Loki查该请求的全部日志，定位根因"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📖 《高可用架构第1卷》 | 第5章 | 可观测性三支柱理论 |
| 📄 04-基础设施与部署.md | §6, §14 | 可观测性方案+日志体系 |
