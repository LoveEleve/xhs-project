# Phase 6：工程化与生产力 — 详细梳理

> 🎯 目标：系统具备生产级运维和工程化能力，可安全上线
>
> ⚠️ **前置条件**：Phase 1 + Phase 2 + Phase 3 + Phase 4 + Phase 5 全部功能开发完成并验收通过

### Phase 间依赖清单

| 依赖Phase | 依赖的功能点 | 本Phase使用场景 |
|-----------|------------|----------------|
| Phase 1 | 用户体系（JWT签发/刷新） | 安全合规：JWT黑名单、HMAC签名校验、RBAC权限 |
| Phase 1 | 内容服务、社交服务、计数服务 | 混沌演练：Redis/MySQL故障注入验证降级；日志体系：TraceId关联业务日志 |
| Phase 2 | 商品/库存/购物车/优惠券/订单/支付 | 消息可靠性全链路：订单事务消息6环节验证；分布式ID：订单雪花ID、优惠券分库分表ID |
| Phase 2 | 优惠券分库、订单分库 | 限流降级：分库分表场景限流策略；全链路压测：分库分表后性能基线 |
| Phase 3 | 搜索/Home BFF/通知 | 安全合规：XSS过滤搜索输入；全链路压测：搜索场景基线 |
| Phase 4 | Gateway鉴权/限流/灰度/染色 | 限流降级：网关层滑动窗口限流；CI/CD：灰度发布Gateway路由；混沌工程：网关故障演练 |
| Phase 4 | IM服务（WebSocket） | 混沌工程：IM服务Pod被杀演练；高可用：WebSocket连接故障预案 |
| Phase 4 | 公共组件（@Idempotent/@DistributedLock/@RateLimit/IdGeneratorUtil） | 限流降级：@RateLimit注解级限流；分布式ID：IdGeneratorUtil雪花ID/号段模式增强 |
| Phase 5 | 缓存一致性方案 | 限流降级：缓存故障降级走DB+限流；混沌工程：Redis故障注入验证延迟双删+Canal兜底 |
| Phase 5 | 分布式事务 | 消息可靠性全链路：6环节验证事务消息+本地消息表；混沌工程：MQ Broker故障验证本地消息表补偿 |
| Phase 5 | 全链路流量染色 | 全链路压测：染色标记透传+影子表验证；CI/CD：灰度发布流量隔离 |
| Phase 5 | 监控告警体系 | 日志体系：Promtail→Loki→Grafana日志采集；混沌工程：演练观察监控面板；全链路压测：容量水位监控 |
| Phase 5 | 分库分表实战 | 分布式ID：雪花ID在分库分表中的使用；限流降级：分库分表查询限流；数据备份：分库分表备份策略 |
| Phase 5 | Canal数据同步 | 数据备份：Canal位点管理+断点续传；混沌工程：Canal同步故障验证 |
| Phase 5 | 优雅停机与服务治理 | CI/CD：K8s滚动更新+优雅停机；高可用：各组件HA方案+故障预案 |

---

## 一、Phase 6 概览

| 序号 | 专题 | 类别 | 涉及服务 | 核心技术 |
|------|------|------|----------|----------|
| 29 | 混沌工程与故障演练 | 工程实践 | 全部服务 | ChaosBlade 7场景、演练报告 |
| 30 | 安全合规体系 | 工程实践 | Gateway + 全部业务服务 + Admin | HMAC签名、BCrypt、XSS过滤、RBAC、审计日志 |
| 31 | 日志体系与可观测性 | 工程实践 | 全部服务 + 基础设施 | JSON结构化、Promtail→Loki→Grafana、TraceId关联 |
| 32 | CI/CD与自动化部署 | 工程实践 | 全部服务 + DevOps | Jenkins Pipeline、Docker多阶段构建、K8s灰度发布 |
| 33 | 测试策略与质量保障 | 工程实践 | 全部服务 | 测试金字塔、Testcontainers、契约测试 |
| 34 | 数据备份与容灾 | 工程实践 | MySQL + Redis + ES + Nacos | RTO/RPO标准、MySQL+Redis+ES备份策略 |
| 35 | 配置中心与多环境管理 | 工程实践 | 全部服务 + Nacos | Nacos分组、dev/test/pre/prod环境隔离 |
| 36 | 高可用与故障预案 | 工程实践 | 全部组件 | P0-P3故障分级、各组件HA方案、灾备切换 |
| 37 | 限流降级方案 | 分布式方案 | Gateway + 全部业务服务 | 4算法对比、分层限流（网关+服务+接口+注解） |
| 38 | 消息可靠性全链路 | 分布式方案 | Order + Inventory + Coupon + Payment + Notification | 6环节保障（生产/存储/消费/重试/死信/对账） |
| 39 | 分布式ID方案 | 分布式方案 | User + Order + Content + 全部服务 | 雪花ID、号段模式、时钟回拨处理 |
| 40 | 生产踩坑速查与防御 | 生产经验 | 全部组件 | 12组件×42坑、防御速查表 |
| 41 | 全链路压测基线 | 性能工程 | 全部服务 + 基础设施 | 基线定义、容量水位线、压测报告模板 |

---

## 二、涉及的模块与端口

| 服务 | 端口 | 数据库 | 本阶段变更 | 说明 |
|------|------|--------|-----------|------|
| my-xhs-common | — | — | ❌ 已存在(增强) | 公共模块（补齐SecurityHelper、RateLimitLuaScript、IdempotentConsumer等） |
| my-xhs-gateway | 9000 | — | ❌ 已存在(增强) | 网关（补齐滑动窗口限流Lua、安全合规增强、灰度路由） |
| my-xhs-user | 9001 | my_xhs_user | ❌ 已存在(增强) | 用户（补齐号段模式ID生成、RBAC权限、审计日志） |
| my-xhs-content | 9002 | my_xhs_note | ❌ 已存在(增强) | 笔记（补齐XSS过滤增强、测试用例） |
| my-xhs-analytics | 9003 | my_xhs_social | ❌ 已存在(增强) | 社交（补齐测试用例、限流配置） |
| my-xhs-counter | 9004 | my_xhs_counter | ❌ 已存在(增强) | 计数（补齐混沌演练验证、监控告警覆盖） |
| my-xhs-product | 9005 | my_xhs_product | ❌ 已存在(增强) | 商品（补齐热点参数限流、Testcontainers测试） |
| my-xhs-order | 9006 | my_xhs_order (分库) | ❌ 已存在(增强) | 订单（补齐消息可靠性6环节、全链路压测基线） |
| my-xhs-payment | 9007 | my_xhs_payment | ❌ 已存在(增强) | 支付（补齐消息可靠性验证、安全合规增强） |
| my-xhs-inventory | 9008 | my_xhs_inventory | ❌ 已存在(增强) | 库存（补齐消息可靠性验证、限流降级配置） |
| my-xhs-cart | 9009 | my_xhs_cart | ❌ 已存在(增强) | 购物车（补齐Testcontainers测试、降级规范） |
| my-xhs-coupon | 9010 | my_xhs_coupon (分库) | ❌ 已存在(增强) | 优惠券（补齐消息可靠性验证、分布式ID增强） |
| my-xhs-search | 9011 | my_xhs_search (ES+MySQL) | ❌ 已存在(增强) | 搜索（补齐数据备份ES Snapshot、压测基线） |
| my-xhs-notification | 9012 | my_xhs_notification | ❌ 已存在(增强) | 通知（补齐消息可靠性验证、SSE推送降级） |
| my-xhs-im | 9014 | my_xhs_im | ❌ 已存在(增强) | IM（补齐WebSocket连接故障预案、混沌演练场景） |
| my-xhs-home | 9015 | 无（BFF聚合） | ❌ 已存在(增强) | Home BFF（补齐压测基线、降级规范） |

> **Phase 6 是工程化增强Phase，不新增模块**，只在现有模块上增强。所有变更需兼容已有功能。

---

## 三、专题详细梳理

### 专题 29：混沌工程与故障演练

#### 3.29.1 功能描述

使用 ChaosBlade 在测试环境主动注入故障，验证系统的容错能力和降级预案是否真正生效。7大场景覆盖：Redis不可用→降级走DB+限流、MySQL主库宕机→主从切换30秒、库存服务网络隔离→Sentinel熔断降级、下单接口延迟→Feign超时+重试、MQ Broker不可用→本地消息表补偿、CPU飙高→限流验证、Pod被杀→K8s自动重启+优雅停机。

#### 3.29.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| 全部业务服务 | 演练对象 | 各服务的容错能力验证 |
| my-xhs-gateway | 入口 | 限流验证、灰度路由验证 |
| 基础设施（MySQL/Redis/MQ/Nacos/ES） | 故障注入对象 | 各组件高可用验证 |
| Prometheus + Grafana + SkyWalking | 观测工具 | 演练过程监控 |

#### 3.29.3 演练场景清单

| # | 演练场景 | ChaosBlade命令 | 验证目标 | 预期结果 |
|---|----------|---------------|----------|----------|
| 1 | Redis不可用 | `blade create network drop --port 6379` | 缓存降级走DB+限流 | 服务不挂，RT升高但可接受 |
| 2 | MySQL主库宕机 | `blade create process kill --process mysqld` | 主从切换 | 30秒内切换完成，写入恢复 |
| 3 | 库存服务网络隔离 | `blade create network drop --remote-port 9008` | Sentinel熔断降级 | 订单服务熔断，返回降级提示 |
| 4 | 下单接口延迟 | `blade create network delay --time 3000 --interface com...OrderService` | Feign超时+重试 | 3秒超时触发降级，不无限等待 |
| 5 | MQ Broker不可用 | `blade create process kill --process java --cmd-keyword broker` | 本地消息表补偿 | 消息暂存本地表，MQ恢复后补发 |
| 6 | CPU飙高 | `blade create cpu fullload` | 限流是否生效 | Sentinel限流，非核心接口被限 |
| 7 | Pod被杀 | `blade create process kill --process java --cmd-keyword my-xhs` | K8s自动重启+优雅停机 | 新Pod启动，请求不丢失 |

#### 3.29.4 Java 文件清单

**common/chaos/**
```
ChaosDrillRunner.java         — 演练执行器（调用ChaosBlade CLI创建/销毁故障）
DrillReport.java              — 演练报告实体（场景/预期/实际/是否符合/发现问题）
DrillReportMapper.java        — 演练报告Mapper
DrillReportService.java       — 演练报告服务
```

**common/chaos/**
```
ChaosDrillController.java     — 演练管理API（创建演练/查询报告/导出报告）
```

#### 3.29.5 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 故障注入 | ChaosBlade CLI | 网络(drop/delay)、进程(kill)、CPU(fullload)、IO |
| 演练编排 | XXL-Job定时触发 | 凌晨低峰期自动执行7个场景 |
| 结果观测 | Prometheus + Grafana + SkyWalking | 演练前后对比RT/QPS/错误率 |
| 降级验证 | Sentinel熔断 + CacheHelper降级 | 验证降级返回值是否正确 |
| 主从切换 | ShardingSphere + MySQL MHA | 验证30秒内切换完成 |
| 报告生成 | XXL-Job + 自定义模板 | 自动生成演练报告，不符合预期的标红 |

---

### 专题 30：安全合规体系

#### 3.30.1 功能描述

构建完整的安全合规体系，覆盖四层防护：①接口安全（HMAC-SHA256签名防篡改+防重放、JWT双Token鉴权、@RateLimit防刷、CORS跨域限制）；②数据安全（BCrypt密码存储、敏感信息脱敏、SQL注入防护、XSS过滤、CSRF防护、文件上传安全）；③管理后台安全（网络隔离、RBAC权限、操作审计、二次确认）；④合规要求（内容审核、敏感词过滤、隐私保护、数据留存）。

#### 3.30.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-gateway | 安全入口 | HMAC签名校验、JWT鉴权、CORS、防重放 |
| my-xhs-user | 认证中心 | JWT签发/刷新/黑名单、BCrypt密码、RBAC权限 |
| my-xhs-content | 数据安全 | XSS过滤、敏感词过滤、内容审核 |
| my-xhs-common | 基础组件 | SecurityHelper封装、XssFilter、SensitiveDataFilter |

#### 3.30.3 安全体系清单

| 安全层 | 安全项 | 实现方案 | Phase |
|--------|--------|----------|-------|
| 接口安全 | 请求签名 | HMAC-SHA256(timestamp+nonce+body+secretKey) | Phase-4已有，Phase-6增强 |
| 接口安全 | 认证鉴权 | JWT双Token(Access 30min + Refresh 7d) | Phase-1已有 |
| 接口安全 | 接口限频 | @RateLimit + Sentinel | Phase-4已有，Phase-6增强 |
| 接口安全 | 防重放攻击 | timestamp 5分钟过期 + nonce Redis去重 | Phase-4已有 |
| 数据安全 | 密码存储 | BCrypt慢哈希 | Phase-1已有 |
| 数据安全 | 敏感信息脱敏 | 手机号/邮箱/地址打码 | Phase-6新增 |
| 数据安全 | XSS防护 | 前端转义 + 后端XssFilter | Phase-6新增 |
| 数据安全 | SQL注入 | MyBatis-Plus参数化 + Review | 已有 |
| 管理安全 | RBAC权限 | 角色→菜单权限映射 | Phase-6新增 |
| 管理安全 | 操作审计 | 所有管理操作记录审计日志 | Phase-6新增 |
| 管理安全 | 网络隔离 | Admin服务只在内网暴露 | Phase-6新增 |

#### 3.30.4 Java 文件清单

**common/security/**
```
SecurityHelper.java           — 安全工具封装（HMAC签名、签名校验、nonce去重）
XssFilter.java                — XSS过滤过滤器（HTML标签转义/剥离）
XssHttpServletRequestWrapper.java — XSS请求包装器
SensitiveDataFilter.java      — 敏感信息脱敏（手机号/邮箱/地址）
```

**user/security/**
```
RbacService.java              — RBAC权限服务（角色→菜单→权限映射）
AuditLogService.java          — 审计日志服务（记录管理操作）
AuditLog.java                 — 审计日志实体
AuditLogMapper.java           — 审计日志Mapper
```

**user/security/**
```
RbacController.java           — RBAC管理API（角色CRUD、权限分配）
AuditLogController.java       — 审计日志查询API
```

#### 3.30.5 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| HMAC签名 | HMAC-SHA256(timestamp+nonce+body+secretKey) | Gateway GlobalFilter校验，5分钟过期+nonce去重 |
| XSS过滤 | Jsoup白名单 + 自定义Filter | 只允许安全HTML标签(a/img/p/br)，剥离script/iframe/onerror |
| 敏感信息脱敏 | 自定义Jackson序列化器 | @Sensitive(phone)→138\*\*\*\*8000，@Sensitive(email)→t\*\*\*@gmail.com |
| RBAC | 角色→菜单→权限三级映射 | 用户→角色→菜单→权限，接口级权限校验@RequiresPermission |
| 审计日志 | AOP + 注解 | @AuditLog(module="order", action="cancel")自动记录操作人/时间/IP/参数 |
| JWT黑名单 | Redis SET(jti, "1", TTL=refreshToken剩余有效期) | 修改密码/封禁账号→旧Token jti加入黑名单→Gateway校验 |

---

### 专题 31：日志体系与可观测性

#### 3.31.1 功能描述

构建完整的三维可观测性体系，将 Phase-5 已搭建的监控告警与日志体系整合：①Logs日志（JSON结构化+TraceId MDC注入+Promtail采集→Loki存储→Grafana查询）；②Metrics指标（Phase-5已实现Prometheus+Grafana，本专题增强Loki日志与Prometheus指标的Grafana联动）；③Traces链路追踪（Phase-5已实现SkyWalking，本专题增强TraceId与Loki日志的关联查询）。核心价值：一个问题排查链路 = Grafana指标面板 → SkyWalking链路 → Loki日志详情。

#### 3.31.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| 全部业务服务 | 日志产出 | JSON结构化日志 + TraceId MDC |
| my-xhs-common | 基础组件 | LoggingConfig增强、TraceIdFilter增强 |
| Promtail + Loki + Grafana | 日志基础设施 | 日志采集→存储→查询 |
| SkyWalking | 链路追踪 | TraceId与日志关联 |

#### 3.31.3 日志格式定义

```json
{
  "timestamp": "2025-05-09T18:00:00.123+08:00",
  "level": "INFO",
  "traceId": "abc123def456",
  "spanId": "789xyz",
  "service": "my-xhs-order",
  "instance": "order-pod-abc123",
  "thread": "http-nio-9006-exec-1",
  "logger": "com.myxhs.order.service.OrderService",
  "message": "下单成功",
  "userId": 10001,
  "orderId": "202505091800001234",
  "rt": 156,
  "extra": {}
}
```

#### 3.31.4 日志告警规则

| 告警规则 | PromQL/LogQL | 阈值 | 级别 |
|----------|-------------|------|------|
| 错误日志突增 | `sum(rate({service=~"my-xhs-.*"} \|="ERROR"[5m])) by (service)` | >10条/5min | P1 |
| 下单失败 | `count_over_time({service="my-xhs-order"} \|="下单失败"[5m])` | >5条/5min | P1 |
| OOM检测 | `count_over_time({service=~"my-xhs-.*"} \|="OutOfMemoryError"[5m])` | >0 | P0 |
| 慢查询 | `count_over_time({service=~"my-xhs-.*"} \|="Slow SQL"[5m])` | >10条/5min | P2 |

#### 3.31.5 Java 文件清单

**common/logging/**
```
LoggingConfig.java            — 日志配置增强（JSON结构化 + TraceId MDC注入 + 服务名 + 实例名）
TraceIdFilter.java            — TraceId过滤器增强（MDC注入 + SkyWalking TraceId关联）
LogstashEncoderConfig.java    — Logstash编码器配置（自定义字段：service/instance/environment）
```

#### 3.31.6 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| JSON结构化日志 | Logback + LogstashEncoder | 统一格式（timestamp/level/traceId/service/message/业务字段） |
| TraceId MDC注入 | Filter + SkyWalking TraceContext | 每条日志含TraceId，关联SkyWalking链路 |
| 日志采集 | Promtail DaemonSet | 每个Node采集Pod日志→发送到Loki |
| 日志存储 | Loki | 按service/level/traceId标签索引，低成本存储 |
| 日志查询 | Grafana Explore | LogQL查询：`{service="my-xhs-order"} \|="下单失败"` |
| 日志与链路关联 | Grafana → SkyWalking → Loki 跳转 | 点击Span→自动跳转Loki查该TraceId日志 |

#### 3.31.7 Spring Boot 3.x Observation API 深度（P0 补充）

> 云原生架构训练营§5的核心洞察：可观测性的三根支柱（Metrics/Tracing/Logging）长期各自为战，Spring Boot 3.0 引入的 Observation API 是统一抽象的关键一步。my-xhs 当前使用 Micrometer + SkyWalking Agent 的组合方案，存在以下痛点：
> - **重复埋点**：同一个方法既要加 `@Timed`（Metrics）又要加 `@Trace`（Tracing），观察点写两遍
> - **Agent 依赖**：SkyWalking Java Agent 在容器中需要额外挂载（initContainer），升级 JDK 时 Agent 兼容性是坑
> - **上下文断裂**：Metrics 用 Micrometer Context、Tracing 用 SkyWalking Context、Logging 用 MDC，三者上下文无法统一传播

##### （1）Observation API 核心思想

Spring Boot 3.0 的 `io.micrometer:observation-api` 提供了一个统一抽象——**一次埋点，自动生成 Metrics + Tracing + Logging 三种信号**：

```java
// 传统方式：同一个操作写两遍观察点
@Timed(value = "order.create", description = "创建订单耗时")
@Trace(operationName = "order-create")  // SkyWalking 注解
public OrderDTO createOrder(CreateOrderRequest request) { ... }

// Observation API：一次埋点，自动生成 Metric + Span + 日志
public OrderDTO createOrder(CreateOrderRequest request) {
    return observationRegistry.observe("order.create", () -> {
        // 业务逻辑
        return orderService.doCreate(request);
    });
    // 自动产出：
    // 1. Metrics: order.create.timer (count, totalTime, max, percentiles)
    // 2. Tracing: Span "order.create" (startTime, endTime, tags, events)
    // 3. Logging: 自动注入 traceId/spanId 到 MDC（Spring Boot 3.4+ 原生支持）
}
```

**关键接口**：
```java
// Observation 的生命周期
public interface Observation {
    void start();                    // 开始观察
    void stop();                     // 结束观察，自动记录耗时
    void error(Throwable t);         // 记录错误
    void event(Event event);         // 记录事件（如"cache.hit"/"db.query"）
    ObservationContext getContext();  // 上下文（可携带自定义数据）
}

// ObservationHandler：处理观察信号的扩展点
// 每种信号对应一个 Handler 实现
public interface ObservationHandler<T extends Observation.Context> {
    void onStart(T context);         // → Tracing: 创建 Span; Metrics: 开始计时
    void onStop(T context);          // → Tracing: 关闭 Span; Metrics: 记录耗时
    void onError(T context);         // → Tracing: 标记 error tag; Metrics: 递增 error counter
}
```

##### （2）架构演进对比

```
┌───────────────────────────────────────────────────────────┐
│  传统方案（my-xhs 当前）                                    │
│                                                           │
│  @Controller → @Timed(Micrometer) → Prometheus → Grafana  │
│  @Controller → SkyWalking Agent    → SkyWalking → Grafana │
│  @Controller → MDC(Logback)        → Loki → Grafana       │
│                                                           │
│  问题：三套上下文、重复埋点、Agent 挂载复杂                    │
└───────────────────────────────────────────────────────────┘
                          ↓ 演进
┌───────────────────────────────────────────────────────────┐
│  Observation API 方案                                      │
│                                                           │
│  @Controller → Observation API ─┬→ Micrometer → Prometheus │
│                                 ├→ Micrometer Tracing →    │
│                                 │   Zipkin/Jaeger/Otel     │
│                                 └→ MDC/Structured Logging  │
│                                                           │
│  优势：一次埋点、统一上下文、无 Agent 依赖                    │
└───────────────────────────────────────────────────────────┘
```

##### （3）my-xhs 迁移路径

| 阶段 | 目标 | 具体动作 | 风险 |
|------|------|---------|------|
| **短期**（当前） | 保持现状 | Micrometer + SkyWalking Agent + MDC 继续使用 | 无 |
| **中期**（Phase-6 开发期） | 引入 Observation API 作为标准埋点方式 | 新代码用 `ObservationRegistry.observe()` 替代 `@Timed`；Spring Boot 3.x 的 `spring-boot-starter-observation` 自动注册 Handler | 低，新旧方案可并行 |
| **长期**（生产稳定后） | 去掉 SkyWalking Agent 依赖 | 用 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-zipkin` 替代 Agent | 中，需验证链路完整性 |

**关键依赖**：
```xml
<!-- 引入 Observation API + Micrometer Tracing + OTel Bridge -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-observation</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-zipkin</artifactId>
</dependency>
```

##### （4）Observation API 自动覆盖的 Spring 组件

> Spring Boot 3.x 已在以下组件内置 Observation 支持，**无需手动埋点**：

| 组件 | 自动观察的 Operation | 产出的信号 |
|------|---------------------|-----------|
| Spring MVC | `http.server.requests` | 请求耗时 Metric + HTTP Span |
| Spring WebFlux | `http.server.requests` | 同上 |
| RestTemplate | `http.client.requests` | 外部调用耗时 Metric + Client Span |
| WebClient | `http.client.requests` | 同上 |
| JDBC（HikariCP） | `hikaricp.connections` | 连接池 Metric |
| Redis（Lettuce） | `redis.commands` | Redis 命令耗时 Metric + Span |
| RocketMQ | `rocketmq.produce`/`rocketmq.consume` | 消息发送/消费 Metric + Span |
| Feign | `http.client.requests` | Feign 调用自动观察 |
| Gateway | `http.server.requests` | 网关请求自动观察 |

**批判性思考**：
- Observation API 是 **可观测性领域的正确方向**——从"每个信号各自埋点"到"一次观察、多维信号"
- 但 `micrometer-tracing` 生态目前不如 SkyWalking 成熟（缺少拓扑图、告警规则、慢SQL分析等开箱即用功能）
- **建议 my-xhs 采用渐进式迁移**：新代码用 Observation API 标准埋点，但保留 SkyWalking Agent 做深度分析。等 `micrometer-tracing` 生态成熟后再考虑完全替换
- 特别注意：Spring Boot 3.4+ 才原生支持 Observation 自动注入 MDC（`structured.logging.enabled=true`），my-xhs 需升级到 3.4+ 才能完整受益

---

### 专题 32：CI/CD与自动化部署

#### 3.32.1 功能描述

构建完整的CI/CD流水线，实现代码提交→自动构建→自动测试→Docker镜像构建→镜像推送→K8s灰度部署的全流程自动化。Jenkins Pipeline编排，Docker多阶段构建优化镜像大小，K8s RollingUpdate保证零停机部署，灰度发布（新版本1个Pod+Gateway灰度路由→验证→全量）。

#### 3.32.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| 全部服务 | 构建对象 | Maven Build → Docker Build → Push → K8s Deploy |
| Jenkins | CI/CD引擎 | Pipeline编排（Build→Test→Image→Push→Deploy） |
| Docker Registry | 镜像仓库 | 存储各服务Docker镜像 |
| K8s集群 | 运行环境 | Deployment + Service + Ingress + HPA |

#### 3.32.3 CI/CD流水线

```
代码提交(Git Push)
    │
    ▼
┌──────────────────────────────────────────────────────────────┐
│ Jenkins Pipeline                                              │
│                                                                │
│  Stage 1: Checkout ──── git pull                              │
│  Stage 2: Build    ──── mvn clean package -DskipTests         │
│  Stage 3: Test     ──── mvn test (单元测试+集成测试)           │
│  Stage 4: Build Images ── docker build (多阶段构建)            │
│  Stage 5: Push Images ── docker push (推送到Registry)          │
│  Stage 6: Deploy   ──── kubectl apply (灰度发布)               │
│    ├─ 6a: 创建新版本Deployment(replicas=1, version=v2)         │
│    ├─ 6b: Gateway灰度路由(Header x-canary:true → v2)          │
│    ├─ 6c: 验证通过→扩大v2实例数                                │
│    └─ 6d: 全量切换→删除v1 Deployment                           │
└──────────────────────────────────────────────────────────────┘
```

#### 3.32.4 Dockerfile模板

```dockerfile
# 多阶段构建：编译阶段
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY my-xhs-common/pom.xml my-xhs-common/
COPY my-xhs-order/pom.xml my-xhs-order/
RUN mvn dependency:go-offline -B
COPY . .
RUN mvn clean package -DskipTests -pl my-xhs-order -am

# 运行阶段：精简镜像
FROM eclipse-temurin:17-jre-slim
WORKDIR /app
COPY --from=builder /app/my-xhs-order/target/*.jar app.jar
ADD skywalking-agent.jar /skywalking/agent/skywalking-agent.jar
EXPOSE 9006
ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENV SW_AGENT_NAME="my-xhs-order"
ENV SW_AGENT_COLLECTOR_BACKEND_SERVICES="skywalking-oap:11800"
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -javaagent:/skywalking/agent/skywalking-agent.jar -jar app.jar"]
```

#### 3.32.5 K8s Deployment关键配置

| 配置项 | 值 | 说明 |
|--------|-----|------|
| replicas | 2 | 最少2个实例保高可用 |
| strategy | RollingUpdate(maxSurge=1, maxUnavailable=0) | 滚动更新不丢流量 |
| resources.requests | 512Mi / 250m | 资源预留 |
| resources.limits | 1Gi / 500m | 资源上限 |
| readinessProbe | /actuator/health/readiness | 就绪检测(30s初始延迟) |
| livenessProbe | /actuator/health/liveness | 存活检测(60s初始延迟) |
| terminationGracePeriodSeconds | 45 | 优雅停机时间 |
| preStop | curl nacos-deregister + sleep 15 | 先注销Nacos再停 |

#### 3.32.6 Java 文件清单

**deploy/**
```
docker-compose.yml            — 基础设施一键启动（MySQL/Redis/MQ/ES/Nacos/SkyWalking等）
Dockerfile (每个服务一个)      — 多阶段构建模板
Jenkinsfile                   — Jenkins Pipeline定义
k8s/                          — K8s部署清单
  ├── namespace.yaml
  ├── configmap.yaml
  ├── {service}-deployment.yaml  — 每个服务一个Deployment
  ├── {service}-service.yaml     — 每个服务一个Service
  ├── ingress.yaml
  └── hpa.yaml                   — 自动伸缩配置
```

#### 3.32.7 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 多阶段构建 | Docker Build Stage 1: Maven编译→Stage 2: JRE运行 | 最终镜像~200MB(vs 单阶段~800MB) |
| 零停机部署 | K8s RollingUpdate + maxUnavailable=0 | 旧Pod未停新Pod已Ready |
| 灰度发布 | Nacos元数据 + Gateway路由规则 | 新版本标记version=v2，Gateway Header路由 |
| 优雅停机 | Spring Boot graceful shutdown + K8s preStop | preStop: 注销Nacos→sleep 15s→graceful shutdown 30s |
| 自动伸缩 | K8s HPA (Horizontal Pod Autoscaler) | CPU>70%自动扩容，min=2, max=10 |
| 镜像版本管理 | Git Commit Hash + Build Number | 镜像Tag: {service}:{buildNumber}-{gitHash} |

#### 3.32.8 K8s 原生服务发现与 Spring Cloud 桥接（P1 补充）

> 云原生架构训练营§2 的核心洞察：当应用部署到 K8s 后，服务注册与发现存在"双重注册"问题——Spring Cloud 用 Nacos 注册，K8s 用 Service + Endpoint 注册。两个系统各自维护一份服务列表，导致：①信息不一致（Pod 滚动更新时 Nacos 和 K8s Endpoint 不同步）②流量路由冲突（Spring Cloud LoadBalancer 按 Nacos 实例列表路由，K8s Service 按 Endpoint 路由）③运维复杂度翻倍（两套健康检查、两套负载均衡）。

##### （1）双重注册问题全景

```
传统 Spring Cloud 部署（VM）：
  Provider → Nacos 注册 → Consumer 从 Nacos 发现 → 直连 Provider IP

K8s 部署（双重注册）：
  Provider Pod → Nacos 注册（Spring Cloud 自动）
  Provider Pod → K8s Endpoint 注册（kube-proxy 自动）
  
  Consumer 走哪条路？
  - 路径 A：Consumer → Nacos 发现 → 直连 Pod IP → 绕过 K8s Service
  - 路径 B：Consumer → K8s Service → kube-proxy iptables → Pod IP → 绕过 Nacos

  问题：
  - 走路径 A：K8s 的 Service Mesh（Istio）、NetworkPolicy、HPA 全部失效
  - 走路径 B：Spring Cloud 的 LoadBalancer、灰度路由、区域感知全部失效
```

##### （2）三种解决策略

**策略 1：Spring Cloud 为主，K8s Service 为辅（my-xhs 当前方案）**

```
架构：
  Consumer → Spring Cloud LoadBalancer → Nacos 发现 → 直连 Pod IP
  外部流量 → Ingress → K8s Service → Pod

特点：
  ✅ Spring Cloud 完整能力（灰度路由、区域感知、权重调节）
  ✅ 开发/测试环境可用 Nacos，生产环境也用 Nacos
  ⚠️ 绕过 K8s Service，无法使用 Istio Service Mesh
  ⚠️ Pod 滚动更新时，Nacos 注销和 K8s Endpoint 更新存在时间差

最佳实践：
  1. preStop 钩子：先从 Nacos 注销 → sleep 15s → 再接收 K8s SIGTERM
  2. Spring Cloud LoadBalancer 缓存刷新间隔：设为 3s（默认 35s 太长）
  3. 健康检查：readinessProbe 确保只有 Nacos 注册的 Pod 才接流量
```

**策略 2：K8s Service 为主，Spring Cloud 服务发现为辅**

```
架构：
  Consumer → K8s Service → kube-proxy → Pod IP
  服务治理 → Istio VirtualService + DestinationRule

特点：
  ✅ 完全融入 K8s 生态（NetworkPolicy、Istio、HPA 全部生效）
  ✅ 无需维护 Nacos 集群
  ❌ 失去 Spring Cloud 的编程式路由能力（灰度、区域感知需用 Istio YAML）
  ❌ 开发/测试环境需部署 K8s 集群（minikube/kind），本地开发不便
  ❌ Istio 学习曲线陡峭
```

**策略 3：Spring Cloud Kubernetes 桥接**

```xml
<!-- 用 K8s API 替代 Nacos 做服务发现 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-kubernetes-fabric8</artifactId>
</dependency>
```

```
架构：
  Consumer → Spring Cloud LoadBalancer → K8s API 发现 Endpoint → 直连 Pod IP
  
特点：
  ✅ Spring Cloud 编程式路由 + K8s 原生服务列表，两全其美
  ✅ 无需 Nacos 做注册中心（配置中心仍需 Nacos 或 K8s ConfigMap）
  ❌ spring-cloud-kubernetes 项目已进入维护模式，社区活跃度低
  ❌ 依赖 K8s API Server 稳定性（API Server 压力大时服务发现延迟）
  ❌ 本地开发仍需 K8s 环境
```

##### （3）my-xhs 的务实选择

| 环境 | 服务发现方式 | 理由 |
|------|------------|------|
| **dev（本地）** | Nacos 单机 | Docker Compose 一键启动，无需 K8s |
| **test/pre** | Nacos 集群 + K8s Service（双路） | Nacos 做服务发现 + 灰度路由，K8s Service 做运维入口 |
| **prod** | Nacos 集群 + K8s Service（双路） | 同上，但需严格保证 preStop 钩子执行 |

**关键操作：消除双重注册的时间差**

```yaml
# K8s Deployment 关键配置
spec:
  terminationGracePeriodSeconds: 45  # 给足优雅停机时间
  template:
    spec:
      containers:
      - name: my-xhs-order
        lifecycle:
          preStop:
            exec:
              command:
              - /bin/sh
              - -c
              - |
                # 1. 先从 Nacos 注销（通过 HTTP 调用 Nacos API）
                curl -X DELETE "http://nacos:8848/nacos/v1/ns/instance?serviceName=my-xhs-order&ip=${POD_IP}&port=9006"
                # 2. 等待 Nacos 推送变更给所有 Consumer（默认 3s 刷新）
                sleep 15
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 9006
          initialDelaySeconds: 30  # 等 Nacos 注册完成后再接流量
          periodSeconds: 5
```

**批判性思考**：
- 双重注册不是"bug"，而是 **VM 思维和容器思维碰撞的必然结果**——Spring Cloud 为 VM 设计，K8s 为容器设计
- 长期看，**Service Mesh 是终极解**——Istio 用 Sidecar 代理接管流量，Spring Cloud 不再需要注册中心。但 Istio 的成熟度和运维成本是门槛
- my-xhs 当前阶段用 **Nacos 为主 + K8s Service 为辅** 是最务实的选择——开发和生产体验一致，Spring Cloud 生态完整

---

### 专题 33：测试策略与质量保障

#### 3.33.1 功能描述

建立测试金字塔策略，三层互补：①单元测试（JUnit5+Mockito，核心业务逻辑≥80%覆盖率）；②集成测试（Testcontainers启动真实MySQL/Redis容器，避免H2方言差异）；③契约测试（Spring Cloud Contract，服务间接口兼容性自动验证）。测试纳入CI流水线，每次提交自动运行。

#### 3.33.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| 全部业务服务 | 测试对象 | 单元测试+集成测试+契约测试 |
| my-xhs-common | 基础组件 | 测试工具类（Testcontainers配置模板、MockFeignConfig） |
| CI/CD | 运行环境 | Jenkins自动运行测试，覆盖率报告 |

#### 3.33.3 测试金字塔

| 测试类型 | 覆盖目标 | 工具 | 运行时机 | 目标覆盖率 |
|----------|----------|------|----------|-----------|
| 单元测试 | 核心业务逻辑 | JUnit5 + Mockito | 每次提交 | ≥80% |
| 集成测试 | 数据库/Redis/MQ操作 | Testcontainers | 每次PR | 关键路径100% |
| 契约测试 | 服务间接口兼容性 | Spring Cloud Contract | 每次发布 | 全部Feign接口 |
| E2E测试 | 核心业务链路 | Playwright | 每次发布 | 核心链路100% |

#### 3.33.4 Java 文件清单

**common/test/**
```
TestcontainersConfig.java     — Testcontainers通用配置（MySQL/Redis/MQ容器）
MockFeignConfig.java          — Feign Mock配置（模拟远程服务调用）
TestDataBuilder.java          — 测试数据构建器（Builder模式快速构造测试对象）
```

**{service}/src/test/**
```
{Service}Test.java            — 单元测试（Mockito Mock依赖）
{Service}IntegrationTest.java — 集成测试（Testcontainers真实容器）
```

**contract/**
```
{provider}/contracts/         — 契约定义（Groovy DSL）
{consumer}/src/test/          — 契约验证测试（自动生成）
```

#### 3.33.5 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| Testcontainers | Docker容器启动真实MySQL/Redis | @Container + @DynamicPropertySource动态配置数据源 |
| Mockito | Mock依赖服务行为 | @Mock+@InjectMocks，verify调用次数和参数 |
| 契约测试 | Spring Cloud Contract | 生产者定义契约→自动生成Stub→消费者引用Stub验证 |
| 测试数据构建 | Builder模式 | OrderCreateCmd.builder().userId(1L).skuId(1L).build() |
| 覆盖率 | JaCoCo | mvn jacoco:report，CI流水线生成覆盖率报告 |

---

### 专题 34：数据备份与容灾

#### 3.34.1 功能描述

建立完整的数据备份和容灾体系，确保RTO/RPO达标。MySQL备份：全量(mysqldump每天凌晨)+增量(binlog实时)+主从热备(ShardingSphere主从切换<30秒)；Redis备份：RDB快照每6小时+AOF每秒持久化+从MySQL全量重建兜底；ES备份：Snapshot每天凌晨+从MySQL源数据全量重建兜底；Nacos配置备份：Git版本管理。

#### 3.34.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| MySQL (1主1从) | 数据源 | 全量+增量+主从热备 |
| Redis Sentinel (1主2从) | 缓存数据 | RDB+AOF+MySQL全量重建 |
| Elasticsearch | 搜索数据 | Snapshot+MySQL全量重建 |
| Nacos | 配置数据 | Git版本管理 |
| XXL-Job | 调度器 | 定时触发备份任务 |

#### 3.34.3 备份策略清单

| 数据类型 | 备份方式 | 备份频率 | 保留周期 | RTO | RPO |
|----------|----------|----------|----------|-----|-----|
| MySQL | 全量(mysqldump)+binlog增量 | 全量每天凌晨+binlog实时 | 全量7天，binlog30天 | <30秒(主从切换) | <1分钟 |
| Redis | RDB快照+AOF | RDB每6小时+AOF每秒 | RDB 7天 | <5分钟(MySQL重建) | <1分钟 |
| ES | Snapshot | 每天凌晨 | 7天 | <30分钟(MySQL重建) | 0 |
| Nacos配置 | Git | 每次修改 | 永久 | <1分钟 | 0 |

#### 3.34.4 恢复方案

| 故障场景 | 恢复方案 | 恢复时间 | 数据损失 |
|----------|----------|----------|----------|
| MySQL误删数据 | 从库恢复+binlog回放 | <30分钟 | <1分钟 |
| MySQL主库宕机 | ShardingSphere主从切换 | <30秒 | 0 |
| Redis数据丢失 | 从MySQL全量重建+XXL-Job对账 | <5分钟 | <1分钟 |
| ES索引损坏 | 从MySQL源数据全量重建 | <30分钟 | 0 |
| 整机故障 | K8s重新调度+PVC数据卷 | <5分钟 | 取决于最后备份 |

#### 3.34.5 Java 文件清单

**deploy/scripts/**
```
mysql-backup.sh               — MySQL全量备份脚本（mysqldump + gzip + 清理7天前）
redis-backup.sh               — Redis RDB备份触发脚本（BGSAVE + 拷贝RDB文件）
es-snapshot.sh                — ES Snapshot创建脚本（创建/清理旧快照）
backup-verify.sh              — 备份完整性验证脚本（检查文件大小/MD5）
```

**common/backup/**
```
BackupScheduleConfig.java     — 备份调度配置（XXL-Job注册备份任务）
DataRebuildService.java       — 数据重建服务（Redis/ES从MySQL全量重建）
ReconcileJob.java             — 对账修复任务（Redis vs MySQL差异检测修复）
```

#### 3.34.6 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| MySQL全量备份 | mysqldump --single-transaction | 不锁InnoDB表，凌晨2点XXL-Job触发 |
| MySQL增量备份 | binlog实时复制 | 主从同步+binlog 30天保留 |
| Redis重建 | 从MySQL全量加载+Lua批量写入 | 遍历user/product/note表→写入Redis缓存 |
| ES全量重建 | MySQL分页查询+ES Bulk API | 每页500条+断点续传+进度记录Redis |
| 对账修复 | XXL-Job定时对比Redis与MySQL | 差异→修复→记录日志 |

---

### 专题 35：配置中心与多环境管理

#### 3.35.1 功能描述

基于 Nacos Config 建立完整的配置中心和多环境管理方案：①多环境隔离（Namespace: dev/test/pre/prod）；②配置分组（COMMON_GROUP公共配置+SERVICE_GROUP服务私有配置+SENTINEL_GROUP限流规则）；③配置热更新（@RefreshScope动态刷新，无需重启）；④功能开关（feature.newCheckout=false→true渐进式发布）；⑤配置版本管理（Nacos历史版本+Git备份）。

#### 3.35.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| 全部业务服务 | 配置消费者 | 从Nacos拉取配置，@RefreshScope热更新 |
| Nacos Config | 配置中心 | Namespace环境隔离+Group分组+配置管理 |
| my-xhs-common | 基础组件 | DynamicConfig封装、FeatureToggle功能开关 |

#### 3.35.3 多环境隔离方案

| 环境 | Namespace | 用途 | 数据 | 配置 |
|------|-----------|------|------|------|
| dev | `dev` | 本地开发 | 本地Docker | 默认值+调试开关 |
| test | `test` | 测试环境 | 测试数据 | 与prod结构一致 |
| pre | `pre` | 预发环境 | 生产数据副本 | 与prod完全一致 |
| prod | `prod` | 生产环境 | 生产数据 | 生产级配置 |

#### 3.35.4 配置分组规划

| Group | 配置项 | 说明 |
|-------|--------|------|
| COMMON_GROUP | my-xhs-common.yml | 公共配置(连接池/Redis/MQ/日志) |
| SERVICE_GROUP | my-xhs-{service}.yml | 各服务私有配置 |
| SENTINEL_GROUP | flow-rules.json, degrade-rules.json | Sentinel限流熔断规则 |

#### 3.35.5 Java 文件清单

**common/config/**
```
DynamicConfig.java            — 动态配置封装（@RefreshScope + @Value + getter）
FeatureToggle.java            — 功能开关枚举（NEW_CHECKOUT/HOT_SEARCH/PUSH_SSE）
NacosConfigListener.java      — Nacos配置变更监听器（变更日志+告警）
```

#### 3.35.6 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 环境隔离 | Nacos Namespace | bootstrap.yml: namespace: ${NACOS_NAMESPACE:dev} |
| 配置分组 | Nacos Group | COMMON_GROUP(公共)+SERVICE_GROUP(私有)+SENTINEL_GROUP(规则) |
| 热更新 | @RefreshScope + Nacos长轮询 | 修改Nacos配置→实时推送到服务→Bean刷新 |
| 功能开关 | DynamicConfig + if判断 | feature.newCheckout: false→true，无需重启 |
| 配置版本 | Nacos历史版本 + Git | Nacos自动保留30天历史，关键变更Git备份 |
| 敏感配置加密 | Nacos加密配置 | datasource.password→加密存储，服务端解密 |

#### 3.35.7 配置中心选型对比（P1 补充）

> Stage-3 课程 026-027 讲了 Nacos Config 和 etcd 配置中心，但缺少选型对比。以下是批判性分析。

##### （1）主流配置中心对比

| 维度 | Nacos | Apollo | etcd | Spring Cloud Config |
|------|-------|--------|------|-------------------|
| **配置推送** | 长轮询（1.x）/ gRPC（2.x） | 长轮询 + 实时推送 | Watch（事件监听） | Webhook + Bus（需MQ） |
| **一致性协议** | Distro（AP）+ Raft（CP） | Eureka（AP） | Raft（CP） | Git（最终一致） |
| **多环境隔离** | Namespace + Group | AppId + Cluster + Namespace | Prefix（键前缀） | Git Branch / Profile |
| **灰度发布** | ✅ 原生支持（Beta配置） | ✅ 原生支持（灰度规则） | ❌ 需自行实现 | ❌ 需自行实现 |
| **权限控制** | ✅ RBAC | ✅ 细粒度权限 | ⚠️ 基础RBAC | ❌ 依赖Git权限 |
| **配置回滚** | ✅ 历史版本一键回滚 | ✅ 历史版本+审计 | ⚠️ 需自行维护 | ✅ Git版本管理 |
| **运维复杂度** | 低（单组件） | 中（ConfigService+AdminService+Portal） | 低（单组件，但需配合Confd等） | 中（需Git Server+MQ） |
| **K8s集成** | ⚠️ 需部署Nacos Server | ⚠️ 需部署Apollo | ✅ K8s原生etcd | ⚠️ 需部署Config Server |
| **社区活跃度** | 高（阿里巴巴） | 高（携程） | 高（CNCF） | 中（Spring官方） |

**批判性思考**：
- 小马哥课程介绍了 etcd 作为配置中心，但 etcd **更擅长做注册中心/分布式锁/Leader选举**，做配置中心需要大量自研（Watch监听+版本管理+灰度发布+多环境隔离），**投入产出比不高**
- Nacos 是**最实用的配置中心**：开箱即用、灰度发布、多环境隔离、权限控制，my-xhs 已经在用
- Apollo 功能最全（细粒度权限、审计、灰度），但组件多、部署复杂
- Spring Cloud Config 依赖 Git + MQ，架构重、推送延迟高，**不推荐新项目使用**

**my-xhs 选型结论**：继续使用 Nacos Config，无需更换。etcd 的 Watch/Lease 机制作为**前置知识**理解即可。

##### （2）etcd Watch/Lease 机制（理解配置中心底层原理）

> etcd 的 Watch + Lease + Compare-And-Swap 是配置中心的核心原语，理解它们有助于深入理解 Nacos Config 的设计。

```
Watch 机制：
  客户端注册 Watch(key prefix) → etcd 任意键变更 → 推送事件给客户端
  → Nacos 的长轮询本质上是对 Watch 的模拟（HTTP 长连接替代 gRPC 推送）
  → Nacos 2.x 已改用 gRPC 长连接，与 etcd Watch 原理一致

Lease 机制：
  客户端创建 Lease(TTL=10s) → 续租(KeepAlive) → TTL 到期键自动删除
  → 用于配置的临时属性（如服务实例配置，实例下线后配置自动清理）
  → Nacos 的临时实例也用了类似机制（心跳续约）

Compare-And-Swap（CAS）：
  etcd Txn: IF key.value = old THEN key.value = new ELSE fail
  → 用于配置的原子更新（防止并发修改覆盖）
  → Nacos Config 的 MD5 比对本质上是 CAS 的简化版
```

**Nacos Config 工作原理（与 etcd 对照理解）**：

| Nacos Config 概念 | etcd 对应概念 | 说明 |
|------------------|-------------|------|
| 长轮询/gRPC推送 | Watch | 变更通知机制 |
| MD5比对 | Revision/ModRevision | 版本检测，避免全量拉取 |
| Namespace | Prefix | 隔离维度 |
| Group | Prefix | 分组维度 |
| β灰度发布 | — | Nacos独有，etcd需自研 |
| 本地缓存文件 | — | Nacos客户端自带容灾，etcd需自研 |

##### （3）分布式配置客户端手写实现要点（P1 补充）

> Stage-3 课程 028 讲了手写配置客户端，以下是核心设计要点，可作为 mini-nacos 的迭代参考。

```java
// 配置客户端核心流程
public class MiniConfigClient {
    // 1. 长轮询：定时向服务端发送请求，服务端hold住直到配置变更或超时
    public void startLongPolling() {
        scheduler.scheduleWithFixedDelay(() -> {
            for (String dataId : watchedConfigs) {
                // 发送HTTP请求，携带MD5（版本标识）
                // 服务端比较MD5：相同则hold 30s，不同则立即返回
                ConfigResponse resp = httpClient.post("/listener",
                    Map.of("dataId", dataId, "md5", localMd5.get(dataId)));
                if (resp.isChanged()) {
                    // 2. 配置变更 → 拉取最新配置
                    String newConfig = fetchConfig(dataId);
                    // 3. 更新本地缓存
                    localCache.put(dataId, newConfig);
                    localMd5.put(dataId, md5(newConfig));
                    // 4. 通知监听器
                    listeners.forEach(l -> l.onChange(dataId, newConfig));
                }
            }
        }, 0, 100, TimeUnit.MILLISECONDS);  // 长轮询间隔
    }

    // 5. 本地缓存容灾：服务端不可用时从本地文件读取
    public String getConfig(String dataId) {
        String config = localCache.get(dataId);
        if (config == null) {
            config = readFromLocalFile(dataId);  // {user.home}/nacos/config/{dataId}
        }
        return config;
    }
}
```

**5 大核心设计要点**：

| 要点 | 实现 | 作用 |
|------|------|------|
| 长轮询 | 请求携带MD5，服务端hold到变更或超时 | 准实时感知变更，降低拉取频率 |
| MD5比对 | 客户端本地存储配置MD5 | 避免全量传输，只拉取变更的配置 |
| 本地缓存 | 配置写入本地文件（`{user.home}/nacos/config/`） | 服务端不可用时容灾 |
| 监听器回调 | Observer模式，配置变更通知监听器 | 解耦配置获取和业务处理 |
| 防抖合并 | 短时间内多次变更只通知一次 | 避免频繁刷新Bean |

#### 3.35.8 配置元数据处理机制 — APT + Spring Metadata（P1 补充）

> 云原生架构训练营§3 的核心洞察：Spring Boot 的 `@ConfigurationProperties` 之所以能在 IDE 中自动补全、提示类型和默认值，靠的不是魔法，而是**配置元数据**（`spring-configuration-metadata.json`）。这个文件的生成链路是：`Java APT` → `@ConfigurationProperties` → `spring-boot-configuration-processor` → `metadata JSON` → `IDE 自动补全`。理解这条链路，才能理解 Spring Boot 自动配置的"最后一公里"。

##### （1）配置元数据生成链路

```
源码编译时：
┌──────────────────────────────────────────────────────────────┐
│  @ConfigurationProperties(prefix = "myxhs.order")            │
│  public class OrderProperties {                               │
│      private int timeout = 3000;  // ← 注释会生成描述          │
│      private boolean enabled = true;                           │
│  }                                                            │
│                     ↓ javac 编译                               │
│  spring-boot-configuration-processor（APT处理器）               │
│    → 扫描 @ConfigurationProperties 类                          │
│    → 提取字段名/类型/默认值/注释                                │
│    → 生成 META-INF/spring-configuration-metadata.json         │
│                     ↓                                          │
│  {                                                            │
│    "groups": [{                                               │
│      "name": "myxhs.order",                                   │
│      "type": "com.myxhs.order.config.OrderProperties"         │
│    }],                                                        │
│    "properties": [{                                           │
│      "name": "myxhs.order.timeout",                           │
│      "type": "java.lang.Integer",                             │
│      "defaultValue": 3000,                                    │
│      "description": "订单超时时间（毫秒）"                       │
│    }, {                                                       │
│      "name": "myxhs.order.enabled",                           │
│      "type": "java.lang.Boolean",                             │
│      "defaultValue": true,                                    │
│      "description": "是否启用订单功能"                          │
│    }]                                                         │
│  }                                                            │
│                     ↓ IDE 读取                                 │
│  application.yml 中输入 "myxhs.order." → 自动补全 + 提示        │
└──────────────────────────────────────────────────────────────┘
```

##### （2）Java APT（Annotation Processing Tool）原理

> APT 是 JDK 内置的编译期注解处理工具，在 `javac` 编译期间运行，可以**读取注解 → 生成新文件**（不会修改已有源码）。

```java
// 自定义 APT 处理器的基本结构
@SupportedAnnotationTypes("com.myxhs.*")  // 处理哪些注解
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class MyConfigProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // 1. 扫描所有被 @ConfigurationProperties 标注的类
        for (Element element : roundEnv.getElementsAnnotatedWith(ConfigurationProperties.class)) {
            // 2. 提取字段信息
            for (Element field : element.getEnclosedElements()) {
                String fieldName = field.getSimpleName().toString();
                String fieldType = field.asType().toString();
                // 3. 生成 metadata JSON
            }
        }
        // 4. 写入 META-INF/spring-configuration-metadata.json
        Filer filer = processingEnv.getFiler();
        FileObject file = filer.createResource(StandardLocation.CLASS_OUTPUT, "",
            "META-INF/spring-configuration-metadata.json");
        try (Writer writer = file.openWriter()) {
            writer.write(metadataJson);
        }
        return true;  // 声明已处理
    }
}
```

**APT 的关键特性**：
- **编译期执行**：不侵入运行时，零性能开销
- **只能生成文件，不能修改源码**：这是 Lombok 争议的根源（Lombok 用了 hack 方式修改 AST，绕过了 APT 的限制）
- **多轮处理**：APT 可能运行多轮（第一轮生成的新代码可能带新注解，触发第二轮）
- **注册方式**：`META-INF/services/javax.annotation.processing.Processor` 文件声明处理器

##### （3）Spring Boot 配置处理器的工作方式

```xml
<!-- pom.xml 中引入配置处理器（编译期依赖，不打入最终 JAR） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
    <optional>true</optional>
</dependency>
```

**处理器自动提取的信息**：

| 源码元素 | 提取的元数据 | 示例 |
|---------|------------|------|
| `@ConfigurationProperties(prefix)` | group name + type | `myxhs.order` + `OrderProperties` |
| 字段名 + prefix | property name | `timeout` → `myxhs.order.timeout` |
| 字段类型 | property type | `int` → `java.lang.Integer` |
| 字段默认值 | defaultValue | `3000` |
| Javadoc / `@value` 描述 | description | `/** 订单超时时间 */` → description |
| `@Deprecated` 标记 | deprecated 标记 | IDE 中会划删除线 |

**额外元数据文件**：`META-INF/additional-spring-configuration-metadata.json`
```json
{
  "properties": [{
    "name": "myxhs.order.strategy",
    "type": "java.lang.String",
    "description": "订单策略，可选值：NORMAL, FLASH_SALE, GROUP_BUY",
    "sourceType": "com.myxhs.order.config.OrderProperties"
  }]
}
```
> 用于补充无法通过 APT 提取的元数据（如第三方库的配置、枚举值的描述等）。

##### （4）为什么这很重要？— my-xhs 项目视角

| 场景 | 没有元数据 | 有元数据 |
|------|----------|---------|
| 新人接手项目 | 翻源码找配置项 | IDE 自动补全，输入 `myxhs.` 即可看到全部配置 |
| 配置项变更 | 改了属性名但忘记改 yml | IDE 标红提示：unknown property |
| 配置类型错误 | `timeout: abc`（String 赋给 int） | IDE 标红提示：type mismatch |
| Starter 开发 | 使用者不知道有哪些配置 | 自动补全 + 描述 + 默认值 |

**批判性思考**：
- 配置元数据是 **"开发者体验"（DX）的基础设施**——它让 Starter 从"黑盒"变成"白盒"
- APT 不只是配置元数据的工具——**Lombok、MapStruct、QueryDSL、AutoValue、Dagger 2** 都基于 APT
- my-xhs 如果开发自定义 Starter（如 `my-xhs-common-starter`），**必须生成配置元数据**，否则使用者无法自动补全
- 与 Microsphere 框架的关系：小马哥在课程中用 Microsphere 扩展了 Spring 的配置元数据机制，增加了"配置校验"、"配置文档生成"等能力。思路值得借鉴，但 my-xhs 不需要引入 Microsphere，用标准 `spring-boot-configuration-processor` 即可

---

### 专题 36：高可用与故障预案

#### 3.36.1 功能描述

建立系统级高可用方案和故障预案体系：①可用性目标99.95%（年宕机≤4.38小时）；②各组件HA方案（MySQL主从切换<30秒、Redis Sentinel<30秒、MQ DLedger<60秒、Nacos集群无感知、ES副本提升<30秒、业务服务K8s重启<60秒）；③故障分级P0~P3（P0核心链路5分钟响应→P3次日处理）；④核心场景故障预案（Redis/MySQL/MQ/ES/全链路5大场景）。

#### 3.36.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| 全部组件 | 高可用对象 | 各组件HA方案 |
| my-xhs-common | 基础组件 | HealthCheckConfig、FailoverHelper |
| K8s | 运行环境 | 自动重启、HPA、PDB |

#### 3.36.3 各组件HA方案

| 组件 | 故障场景 | HA方案 | 恢复时间 | my-xhs落地 |
|------|----------|--------|----------|------------|
| MySQL | 主库挂 | ShardingSphere主从切换 | <30秒 | ✅ |
| Redis | 主节点挂 | Sentinel哨兵自动故障转移 | <30秒 | ✅ |
| RocketMQ | Broker挂 | 主从同步复制+DLedger自动切换 | <60秒 | ✅ |
| Nacos | 节点挂 | 集群部署(3节点)+本地缓存兜底 | 无感知 | ✅ |
| ES | 节点挂 | 副本分片自动提升 | <30秒 | ✅ |
| Gateway | 实例挂 | K8s自动重启+Nacos摘除 | <60秒 | ✅ |
| 业务服务 | 实例挂 | K8s重启+Nacos摘除+Sentinel熔断 | <60秒 | ✅ |

#### 3.36.4 故障等级定义

| 等级 | 定义 | 响应时间 | 通知方式 | 典型场景 |
|------|------|----------|----------|----------|
| P0 | 核心链路不可用 | 5分钟内响应 | 电话+企微 | 下单不可用、登录不可用、库存负数 |
| P1 | 非核心功能不可用 | 15分钟内响应 | 企微 | 搜索不可用、通知不可用 |
| P2 | 性能降级 | 30分钟内响应 | 企微 | RT升高、错误率升高 |
| P3 | 告警但未影响用户 | 次日处理 | 邮件 | 磁盘使用率>70%、慢查询 |

#### 3.36.5 Java 文件清单

**common/ha/**
```
HealthCheckConfig.java        — 健康检查配置（Actuator + K8s探针：readiness/liveness）
FailoverHelper.java           — 故障转移工具（自动重试+降级+熔断判断）
CircuitBreakerConfig.java     — 熔断器配置（Sentinel规则统一管理）
```

#### 3.36.6 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 冗余 | 多实例多副本 | K8s Deployment replicas≥2 + HPA自动伸缩 |
| 隔离 | Sentinel熔断降级 | 慢调用比例>50%→熔断10秒→降级返回 |
| 监控 | Prometheus+Grafana+SkyWalking | 系统级+应用级+中间件级+业务级4层监控 |
| 容灾 | 主从自动切换 | MySQL MHA + Redis Sentinel + MQ DLedger |
| 故障预案 | P0~P3分级 + 预案文档 | 每个P0场景有明确操作步骤和恢复目标 |
| K8s PDB | Pod Disruption Budget | minAvailable=1，保证滚动更新期间至少1个实例 |

---

### 专题 37：限流降级方案

#### 3.37.1 功能描述

建立四层分层限流体系，对比四种限流算法（固定窗口/滑动窗口/令牌桶/漏桶），选择最优方案落地：①网关层：IP级滑动窗口（Redis Lua ZSet实现，1秒100次）；②服务层：接口级令牌桶（Sentinel，下单接口QPS限5000）；③参数级：热点参数限流（Sentinel，商品详情按SKU ID限流）；④注解级：@RateLimit注解（AOP+Redis Lua，同一用户1分钟点赞10次）。同时整合Sentinel熔断降级策略。

#### 3.37.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-gateway | 网关层限流 | IP级滑动窗口 + Sentinel网关限流 |
| 全部业务服务 | 服务层限流 | Sentinel接口限流 + 热点参数限流 |
| my-xhs-common | 基础组件 | @RateLimit注解 + RateLimitLuaScript + SentinelBlockHandler |

#### 3.37.3 四种限流算法对比

| 算法 | 原理 | 优点 | 缺点 | my-xhs采用 |
|------|------|------|------|-------------|
| 固定窗口 | 1分钟内不超过N次 | 简单 | 临界点突发流量(0:59和1:01各N次=2N次) | ❌ |
| 滑动窗口 | 窗口平滑滑动 | 解决临界问题 | 实现复杂 | ✅ Redis Lua(ZSet) |
| 漏桶 | 固定速率流出 | 流量平滑 | 无法应对突发 | ❌ |
| 令牌桶 | 固定速率生成令牌 | 允许适度突发 | — | ✅ Sentinel |

#### 3.37.4 分层限流方案

| 层级 | 限流方式 | 场景 | 实现 | 粒度 |
|------|----------|------|------|------|
| 网关层 | IP级滑动窗口 | 同一IP 1秒100次 | Redis Lua ZSet | IP地址 |
| 服务层 | 接口级令牌桶 | 下单接口QPS限5000 | Sentinel | 接口路径 |
| 参数级 | 热点参数限流 | 商品详情按SKU ID限流 | Sentinel ParamFlow | 参数值 |
| 注解级 | @RateLimit | 同一用户1分钟点赞10次 | AOP + Redis Lua | SpEL表达式 |

#### 3.37.5 Java 文件清单

**common/ratelimit/**
```
@RateLimit.java               — 限流注解（count/window/key/message）
RateLimitAspect.java          — 限流切面（AOP + Redis Lua滑动窗口）
RateLimitLuaScript.java       — 限流Lua脚本（ZAdd+ZRemRangeByScore+ZCard判断）
SentinelBlockHandler.java     — Sentinel统一降级处理器（Flow→429, Degrade→503）
SentinelConfig.java           — Sentinel配置（规则持久化到Nacos + Dashboard连接）
```

**gateway/filter/**
```
IpRateLimitFilter.java        — IP级限流GlobalFilter（Redis Lua滑动窗口）
```

#### 3.37.6 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 滑动窗口Lua | Redis ZSet + Lua原子操作 | ZREMRANGEBYSCORE移除窗口外→ZCARD计数→ZADD添加 |
| Sentinel令牌桶 | Sentinel FlowRule | grade=QPS, count=5000, controlBehavior=RATE_LIMITER |
| 热点参数限流 | Sentinel ParamFlowRule | 参数索引0=skuId, count=100/s, durationSec=1 |
| @RateLimit AOP | Aspect + SpEL解析key | 解析"#userId"→实际值→拼限流Key→Lua执行 |
| 熔断降级 | Sentinel DegradeRule | 慢调用比例>50%→熔断10秒→降级返回 |
| 规则持久化 | Nacos DataSource | Sentinel规则推送到Nacos→服务启动自动加载 |

#### 3.37.7 Resilience4j vs Sentinel 选型对比（P1 补充）

> 云原生架构训练营§4 同时讲了 Sentinel 和 Resilience4j，并用 Microsphere 封装了两者。但 my-xhs 需要明确选型理由——面试必问"为什么选 Sentinel 不选 Resilience4j"。

##### （1）核心设计哲学对比

| 维度 | Sentinel | Resilience4j |
|------|---------|-------------|
| **设计哲学** | 面向**流量控制**（限流优先，熔断其次） | 面向**容错**（熔断优先，限流其次） |
| **核心抽象** | Resource（资源 = 接口/方法） | Decorator（装饰器 = 函数式包装） |
| **编程风格** | 声明式（注解 + 规则配置） | 函数式（Supplier/Runnable 包装） |
| **运行模式** | SDK + 独立 Dashboard（推拉结合） | 纯 SDK（无 Dashboard，需自建或用 Grafana） |
| **生态归属** | 阿里巴巴（Spring Cloud Alibaba） | 社区（Spring Boot 官方推荐替代 Hystrix） |

##### （2）功能逐项对比

| 功能 | Sentinel | Resilience4j | 胜出 |
|------|---------|-------------|------|
| **限流（Flow Control）** | ✅ QPS/线程数 + 5种流控效果（快速失败/Warm Up/排队等待/速率限制/冷启动） | ⚠️ 限流功能弱，只有 RateLimiter（令牌桶） | **Sentinel** |
| **熔断（Circuit Breaker）** | ✅ 慢调用比例/异常比例/异常数 3种策略 | ✅ 慢调用比例/异常比例 2种策略 + 3种状态机（CLOSED/OPEN/HALF_OPEN） | **平手** |
| **系统保护** | ✅ Load/CPU/RT/线程数/入口QPS 5维系统级保护 | ❌ 无 | **Sentinel** |
| **热点参数限流** | ✅ 按参数值限流（如按 SKU ID 限流） | ❌ 无 | **Sentinel** |
| **集群限流** | ✅ Token Server 模式（集群统一限流） | ❌ 无（单机限流） | **Sentinel** |
| **Dashboard** | ✅ 开箱即用的 Web Dashboard（实时监控+规则推送） | ❌ 无（需 Micrometer + Grafana 自建） | **Sentinel** |
| **规则持久化** | ✅ Nacos/ZooKeeper/Apollo 多种数据源 | ⚠️ 需自行实现（如用 Spring Cloud Config） | **Sentinel** |
| **函数式 API** | ❌ 偏声明式，函数式 API 不如 R4j 优雅 | ✅ 原生函数式，`Supplier.decorateWithCircuitBreaker()` | **Resilience4j** |
| **轻量性** | ⚠️ 核心约 600KB + Dashboard 约 50MB | ✅ 核心约 200KB，无外部依赖 | **Resilience4j** |
| **Spring Boot 3 兼容** | ⚠️ Sentinel 1.8.7+ 兼容，但 Dashboard 需独立部署 | ✅ 原生支持 | **Resilience4j** |
| **云原生友好** | ⚠️ Dashboard 是有状态服务，K8s 中需额外部署 | ✅ 无状态，更适合 Service Mesh | **Resilience4j** |

##### （3）my-xhs 选 Sentinel 的 4 个核心理由

**理由 1：流量控制是 my-xhs 的第一优先级**

my-xhs 是社交+电商双场景，流量特征是**突发尖峰**（热搜、秒杀、KOL 发帖）。限流比熔断更关键——熔断是"已经出问题了才断"，限流是"还没出问题就挡住"。

```
Sentinel 的限流能力远超 Resilience4j：
- QPS 限流（接口级）：下单接口 QPS ≤ 5000
- 线程数限流（并发控制）：数据库查询线程 ≤ 50
- 热点参数限流（SKU 级）：热门商品 SKU 详情 ≤ 100/s
- 系统级保护（全局兜底）：CPU > 80% 自动限流
- 集群限流（多实例统一口径）：全集群下单 QPS ≤ 10000
```

**理由 2：Dashboard 开箱即用，降低运维成本**

Resilience4j 没有 Dashboard，要实现实时监控需要 `Micrometer + Prometheus + Grafana` 三件套。而 Sentinel Dashboard 提供：
- 实时监控（秒级 QPS/RT/通过数/拒绝数）
- 动态规则推送（修改规则实时生效，无需重启）
- 集群流量分布（哪个实例流量最高）
- 热点参数 Top N（哪个 SKU 被访问最多）

**理由 3：Nacos 生态整合，规则与配置统一管理**

Sentinel 的规则可以直接持久化到 Nacos，与 my-xhs 已有的 Nacos 配置体系无缝整合：
```yaml
# Sentinel 规则持久化到 Nacos
spring:
  cloud:
    sentinel:
      datasource:
        flow:
          nacos:
            server-addr: ${NACOS_ADDR}
            namespace: ${NACOS_NAMESPACE}
            group-id: SENTINEL_GROUP
            data-id: flow-rules.json
            rule-type: flow
```

**理由 4：与 Spring Cloud Alibaba 一体化**

my-xhs 使用 Spring Cloud Alibaba（Nacos + Sentinel + Seata），三者天然整合。如果换成 Resilience4j，限流用 R4j、熔断用 R4j、配置用 Nacos、监控用 Micrometer——碎片化严重。

##### （4）Resilience4j 值得学习的 3 个设计点

> 虽然不选用，但 R4j 的设计思想值得借鉴：

| 设计点 | R4j 做法 | Sentinel 对应 | 可借鉴之处 |
|--------|---------|-------------|-----------|
| **函数式装饰器模式** | `Supplier<String> decorated = CircuitBreaker.decorateSupplier(cb, () -> call());` | `SphU.entry("resource")` try-catch | R4j 的装饰器模式更优雅，可参考改进 `@RateLimit` AOP |
| **熔断器状态机** | CLOSED → OPEN → HALF_OPEN，精确的状态转换条件 | 类似但接口不够清晰 | 理解状态机有助于排查"为什么突然熔断/为什么一直不恢复" |
| **CallRateLimiter 优先级** | 限流在熔断之外独立决策 | 限流和熔断在 Slot Chain 中串行 | R4j 的正交设计更清晰——限流和熔断是两个独立关注点 |

**批判性思考**：
- Sentinel 的"大一统"设计（限流+熔断+系统保护+热点+集群+Dashboard）对中小项目是福音，但对大型项目可能"过度集成"
- 如果 my-xhs 未来迁移到 Service Mesh，Sentinel 的 SDK 模式需要改为 Istio RateLimit，而 R4j 的函数式 API 更容易做 Sidecar 适配
- **面试回答策略**：先说"选 Sentinel 是因为限流能力+Dashboard+Nacos 整合"，再说"Resilience4j 的函数式 API 和轻量设计也值得学习"，体现全面思考

---

### 专题 38：消息可靠性全链路

#### 3.38.1 功能描述

建立消息可靠性6环节保障体系，确保"消息不丢失、不重复消费"：①生产端发送（本地消息表+定时重发保证消息一定发出）；②MQ存储（同步刷盘+主从复制保证Broker不丢）；③MQ投递（消费者确认机制保证网络不丢）；④消费端处理（重试16次阶梯间隔+死信队列保证最终处理）；⑤消费端幂等（@Idempotent+Redis SETNX保证重复消费不出错）；⑥消息顺序（HashQueue同一key走同一queue保证同一业务消息有序）。

#### 3.38.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-order | 生产者 | 事务消息发送方（创建订单→半消息→提交/回滚） |
| my-xhs-inventory | 消费者 | 本地消息表消费（扣减库存→确认/回滚） |
| my-xhs-coupon | 消费者 | 本地消息表消费（扣减优惠券→确认/回滚） |
| my-xhs-payment | 消费者 | 本地消息表消费（创建支付单→确认/回滚） |
| my-xhs-notification | 消费者 | 普通消息消费（发送通知→幂等消费） |
| my-xhs-common | 基础组件 | IdempotentConsumer、LocalMessageService增强 |

#### 3.38.3 六环节保障清单

| # | 环节 | 问题 | 解决方案 | my-xhs落地 |
|---|------|------|----------|------------|
| 1 | 生产端发送 | 发到MQ失败 | 本地消息表+XXL-Job定时重发 | ✅ Phase-5已实现 |
| 2 | MQ存储 | MQ宕机丢消息 | 同步刷盘+主从复制 | ✅ RocketMQ配置 |
| 3 | MQ投递 | 网络问题未投递 | 消费者确认机制(ACK) | ✅ |
| 4 | 消费端处理 | 业务处理失败 | 重试16次阶梯间隔+死信队列 | ✅ Phase-5已实现 |
| 5 | 消费端幂等 | 重复消费 | @Idempotent+Redis SETNX | ✅ Phase-4已有注解 |
| 6 | 消息顺序 | 同一业务消息乱序 | HashQueue(同一key走同一queue) | ✅ 评论场景 |

#### 3.38.4 死信队列处理流程

```
消费者失败 → 重试1次(10s) → 重试2次(30s) → ... → 重试16次(2h)
                                                            ↓
                                                      进入死信队列 %DLQ%
                                                            ↓
                                                    DeadLetterHandler
                                                            ↓
                                              ┌────────────┼────────────┐
                                              ↓            ↓            ↓
                                          记录日志     告警通知    XXL-Job定时
                                          (ES/DB)     (企微)       人工处理
```

#### 3.38.5 MQ Topic 清单

| Topic | 生产者 | 消费者 | 可靠性级别 | 说明 |
|-------|--------|--------|-----------|------|
| `order-transaction` | OrderService | Inventory/Coupon/Payment | 最高(事务消息) | 下单→扣库存→扣券→创支付单 |
| `order-transaction-dlq` | 消费失败3次后进入 | DLQConsumer | — | 死信队列：人工处理 |
| `order-status-change` | OrderService | Notification | 高(本地消息表) | 订单状态变更通知 |
| `coupon-expire-reminder` | CouponService | Notification | 普通 | 优惠券到期提醒 |
| `comment-create` | ContentService | CounterService | 顺序消息 | 评论创建→计数更新（HashQueue: noteId） |

> Phase 1/2/3/4/5 的 Topic 不再重复列出。

#### 3.38.6 Java 文件清单

**common/mq/**
```
IdempotentConsumer.java      — 幂等消费基类（Redis SETNX去重 + 业务唯一键）
MessageReliabilityHelper.java — 消息可靠性工具（发送确认+重试+死信处理）
LocalMessageRecoveryJob.java  — 本地消息恢复任务（XXL-Job定时扫描未发送/超时消息）
ConsumeMonitor.java           — 消费监控（消费延迟/堆积/失败数→Micrometer指标）
```

**order/mq/**
```
OrderTransactionProducer.java — 订单事务消息生产者（Phase-5已有）
OrderTransactionListener.java — 事务监听器（Phase-5已有）
OrderStatusChangeProducer.java — 订单状态变更消息生产者
```

**{service}/mq/**
```
Idempotent{Service}Consumer.java — 各服务幂等消费者（继承IdempotentConsumer）
```

#### 3.38.7 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 本地消息表 | 同一事务写业务表+消息表 | 下单→INSERT order + INSERT local_message→COMMIT→发送MQ |
| 消息恢复 | XXL-Job每分钟扫描 | status=0(待发送) AND created_at < NOW()-60s → 重新发送 |
| 幂等消费 | Redis SETNX(bizId, "1", 24h) | msg.getKeys()作为bizId，SETNX成功→处理，失败→跳过 |
| 顺序消息 | HashQueue(msgKey) | 发送时: queueSelector→同一noteId走同一queue→消费有序 |
| 死信队列 | RocketMQ DLQ %DLQ%ConsumerGroup | 16次重试失败→DLQ→DeadLetterHandler→告警+人工 |
| 消费监控 | Micrometer Counter/Gauge | 消费成功数/失败数/堆积数/延迟→Prometheus→Grafana |

---

### 专题 39：分布式ID方案

#### 3.39.1 功能描述

建立统一的分布式ID方案，解决分库分表后自增ID冲突问题。根据业务特点选择不同方案：①雪花ID（CosId实现，用于订单ID/笔记ID/评论ID，趋势递增适合B+树索引）；②号段模式（MySQL号段表+内存分配，用于用户ID，短ID适合URL展示）；③Redis自增（用于流水号，简单高效）。关键问题：WorkerId分配（Redis INCR）、时钟回拨处理（<5ms等待，≥5ms告警）。

#### 3.39.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-user | 号段模式 | 用户ID（短ID适合URL：myxhs.com/user/10001） |
| my-xhs-order | 雪花ID | 订单ID（趋势递增适合B+树分库分表索引） |
| my-xhs-content | 雪花ID | 笔记ID/评论ID |
| my-xhs-common | 基础组件 | IdGeneratorUtil增强（雪花ID+号段模式+Redis自增） |

#### 3.39.3 ID策略汇总

| 业务 | ID方案 | 理由 | 长度 | 示例 |
|------|--------|------|------|------|
| 用户ID | 号段模式 | 短ID，适合URL展示 | 8-10位 | 10001 |
| 订单ID | 雪花ID | 趋势递增，适合B+树分库分表 | 18位 | 1895761234567890123 |
| 笔记ID | 雪花ID | 趋势递增 | 18位 | 1895761234567890456 |
| 评论ID | 雪花ID | 趋势递增 | 18位 | 1895761234567890789 |
| 流水号 | Redis自增 | 简单，适合日志流水 | 10位 | 20250509001 |

#### 3.39.4 Java 文件清单

**common/id/**
```
IdGeneratorUtil.java          — ID生成工具（增强：雪花ID+号段模式+Redis自增三合一）
SnowflakeIdGenerator.java     — 雪花ID生成器（CosId封装+WorkerId分配+时钟回拨检测）
SegmentIdGenerator.java       — 号段模式生成器（MySQL号段表+内存双Buffer+异步预取）
RedisIdGenerator.java         — Redis自增生成器（INCR + 日期前缀）
Segment.java                  — 号段对象（currentValue + maxValue + step）
SegmentBuffer.java            — — 双Buffer号段容器（当前号段+下一号段，无缝切换）
```

**user/id/**
```
UserIdSegmentService.java     — 用户ID号段服务（一次取1000个ID，内存分配）
```

#### 3.39.5 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 雪花ID | CosId SnowflakeId | 41bit时间戳+5bit数据中心+5bit机器+12bit序列号 |
| WorkerId分配 | Redis INCR "snowflake:worker:counter" | 服务启动→INCR得WorkerId(0-31)→关闭时释放 |
| 时钟回拨检测 | 当前时间vs上次时间 | <5ms: Thread.sleep(回拨时间); ≥5ms: 拒绝+告警 |
| 号段模式 | MySQL号段表+双Buffer | 一次取1000个ID→内存分配→用到20%时异步预取下一段 |
| 号段双Buffer | SegmentBuffer(当前+下一) | 当前号段用到20%→异步线程从MySQL取下一号段→无缝切换 |
| Redis自增 | INCR + 日期前缀 | key= "id:seq:{yyyyMMdd}" → INCR → 拼接日期前缀 |

---

### 专题 40：生产踩坑速查与防御

#### 3.40.1 功能描述

整理12个技术组件×42个常见生产踩坑的速查表和防御方案。每个坑按"现象→原因→解决→教训"四段式整理，按组件分类，支持快速定位。核心原则：**所有坑都必须有防御代码或配置，不能只停留在文档上**。

#### 3.40.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| 全部组件 | 踩坑对象 | 12组件42坑 |
| my-xhs-common | 防御代码 | 防御性工具类（SafeFeignConfig、SafeRedisConfig等） |
| CI/CD | 自动检测 | 代码扫描+配置校验 |

#### 3.40.3 最致命的坑速查表

| 组件 | 最致命的坑 | 影响 | 防御 |
|------|-----------|------|------|
| Redis | 大Key阻塞 | Redis卡死3秒 | XXL-Job扫描大Key+分桶拆分 |
| RocketMQ | 消息重复消费 | 重复扣库存 | @Idempotent幂等Key |
| MySQL | 主从延迟读不到刚写的数据 | 下单后查不到订单 | HintManager强制走主库 |
| MySQL | 索引失效(函数/隐式转换/左模糊) | 慢查询拖垮DB | explain + 慢查询日志 |
| Feign | GET传POJO参数丢失 | 接口调用失败 | @SpringQueryAnnotation |
| Spring | @Transactional失效(5种场景) | 数据不一致 | 自调用/catch吞异常/非public/异常类型/引擎 |
| ShardingSphere | 不带分片键=全库扫描 | 查询超时 | 必须带分片键 |
| ES | 深分页OOM | ES崩溃 | search_after游标分页 |
| K8s | OOM Killed | Pod被杀 | JVM堆=容器内存60% |
| 分布式锁 | 锁没释放 | 死锁 | finally释放 |
| JWT | Token无法主动失效 | 账号被盗 | Redis黑名单 |
| Canal | binlog位点丢失 | 数据不同步 | 定期全量重建 |

#### 3.40.4 Java 文件清单

**common/safe/**
```
SafeFeignConfig.java          — Feign安全配置（超时5+10s + NEVER_RETRY + RequestInterceptor）
SafeRedisConfig.java          — Redis安全配置（连接池+大Key扫描+SCAN替代KEYS）
SafeDatasourceConfig.java     — 数据源安全配置（HikariCP参数+leakDetection+慢SQL日志）
SafeMvcConfig.java            — MVC安全配置（XssFilter+请求体大小限制+编码）
```

#### 3.40.5 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| Redis大Key | XXL-Job扫描+分桶拆分 | `redis-cli --bigkeys` + Hash>5000字段→拆分 |
| MQ重复消费 | @Idempotent + Redis SETNX | 所有消费者必须做幂等，没有例外 |
| MySQL主从延迟 | HintManager + 前端延迟 | 关键查询5秒内走主库 + 前端延迟3秒 |
| Feign参数丢失 | @SpringQueryAnnotation | GET请求POJO参数必须加此注解 |
| ES深分页 | search_after游标 | 禁止from+size > 10000，改用search_after |
| K8s OOM | JVM堆=容器内存60% | -Xmx设为resources.limits.memory的60% |

---

### 专题 41：全链路压测基线

#### 3.41.1 功能描述

建立全链路性能基线，确保系统达到SLA承诺的QPS和RT指标。使用JMeter进行基准压测，GoReplay录制线上流量回放验证。每次发布前必须跑压测，与基线对比，退化超过5%必须排查原因。定义6大场景性能基线、5级容量水位线、标准化压测报告模板。

#### 3.41.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| 全部业务服务 | 压测对象 | 6大核心场景 |
| my-xhs-gateway | 压测入口 | 配合流量染色区分压测流量 |
| 基础设施 | 监控对象 | CPU/内存/连接池/网络IO水位 |
| JMeter + GoReplay | 压测工具 | JMeter基准+GoReplay真实流量回归 |

#### 3.41.3 性能基线

| 场景 | 目标QPS | RT P99 | 成功率 | 可接受RT上限 |
|------|---------|--------|--------|-------------|
| 商品详情 | 5000 | <100ms | 99.99% | 500ms |
| 首页Feed | 3000 | <200ms | 99.99% | 1s |
| 搜索 | 2000 | <200ms | 99.99% | 500ms |
| 下单 | 1000 | <500ms | 99.9% | 1s |
| 秒杀领券 | 10000 | <1s | 99.9% | 3s |
| 点赞 | 5000 | <50ms | 99.99% | 200ms |

#### 3.41.4 容量水位线

| 资源 | 安全水位 | 告警水位 | 危险水位 |
|------|----------|----------|----------|
| CPU | <50% | >70% | >85% |
| 内存 | <70% | >80% | >90% |
| MySQL连接数 | <50% max | >70% | >85% |
| Redis内存 | <70% maxmemory | >80% | >90% |
| MQ消费堆积 | <1000 | >5000 | >10000 |

#### 3.41.5 Java 文件清单

**deploy/jmeter/**
```
product-detail.jmx            — 商品详情压测脚本
home-feed.jmx                 — 首页Feed压测脚本
search.jmx                    — 搜索压测脚本
order-create.jmx              — 下单压测脚本
coupon-grab.jmx               — 秒杀领券压测脚本
like.jmx                      — 点赞压测脚本
full-chain-mix.jmx            — 全链路混合压测脚本
```

**common/perf/**
```
PerformanceBaseline.java      — 性能基线常量（各场景QPS/RT/成功率目标）
WatermarkConfig.java          — 水位线配置（安全/告警/危险阈值）
StressTestTag.java            — 压测标记注解（标记压测专用API，配合流量染色）
```

#### 3.41.6 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| JMeter压测 | 线程组+HTTP请求+监听器 | 模拟并发用户→记录RT/TPS/错误率 |
| GoReplay流量回放 | 录制线上流量→倍速回放 | `gor --input-raw :9000 --output-http "http://target:9000\|2"` |
| 基线对比 | 发布前自动压测+对比 | 退化>5%→阻断发布+告警 |
| 水位监控 | Prometheus + Grafana | CPU/内存/连接池实时水位+告警 |
| 压测隔离 | 流量染色(Phase-5) | 压测流量写影子表，不影响生产数据 |
| 压测报告 | 自动生成 | Jenkins Pipeline→压测→解析结果→生成报告→对比基线 |

---

## 四、Feign 调用关系

> Phase 6 主要是工程化增强，新增的 Feign 调用较少。以下仅列出与限流降级和消息可靠性相关的变更。

| 调用方 | 被调用方 | 方法 | 用途 | 所属专题 |
|--------|---------|------|------|----------|
| Order | Inventory | `deductStock(orderId, skuId, quantity)` | 扣减库存（幂等增强） | 38-消息可靠性 |
| Order | Coupon | `useCoupon(orderId, couponId, userId)` | 使用优惠券（幂等增强） | 38-消息可靠性 |
| Order | Payment | `createPayment(orderId, amount, payMethod)` | 创建支付单（幂等增强） | 38-消息可靠性 |
| Admin | User | `getUserRoles(userId)` | 查询用户角色（RBAC） | 30-安全合规 |
| Admin | Content | `auditNote(noteId, action)` | 审核笔记（审计日志） | 30-安全合规 |

> Phase 1/2/3/4/5 的 Feign 调用关系不再重复列出。

---

## 五、降级规范

| 服务 | 降级策略 | 返回值 | 所属专题 |
|------|---------|--------|----------|
| Inventory | Sentinel熔断降级 | `{stock: 0, available: false}` | 37-限流降级 |
| Coupon | Sentinel熔断降级 | `{available: false, reason: "服务降级"}` | 37-限流降级 |
| Payment | Sentinel熔断降级 | `{status: "fail", reason: "服务降级"}` | 37-限流降级 |
| Search (ES) | 降级返回空搜索结果 | `{total: 0, hits: []}` | 36-高可用 |
| Home BFF | 部分服务降级→聚合降级 | 返回已有数据+降级模块默认值 | 36-高可用 |
| Notification | SSE推送降级→轮询 | 降级返回"稍后重试" | 36-高可用 |
| Counter | 缓存降级→直接查DB | 限流保护DB | 37-限流降级 |
| Gateway | IP限流→429 | `{code: 429, message: "请求过于频繁"}` | 37-限流降级 |

> Phase 1/2/3/4/5 的降级规范不再重复列出。

---

## 六、公共组件复用

| 组件 | 模块 | Phase 6新增用途 | 所属专题 |
|------|------|----------------|----------|
| @Idempotent | my-xhs-common | 消息可靠性消费幂等增强 | 38-消息可靠性 |
| @DistributedLock | my-xhs-common | 号段模式双Buffer切换锁、审计日志并发控制 | 39/30 |
| @RateLimit | my-xhs-common | 注解级限流（Gateway + 业务服务） | 37-限流降级 |
| IdGeneratorUtil | my-xhs-common | 号段模式+Redis自增增强 | 39-分布式ID |
| R\<T\> | my-xhs-common | 降级返回统一格式 | 37-限流降级 |
| TraceContext | my-xhs-common | 混沌演练流量标记 | 29-混沌工程 |
| CacheHelper | my-xhs-common | 混沌演练缓存降级验证 | 29-混沌工程 |
| TransactionHelper | my-xhs-common | 消息可靠性事务消息增强 | 38-消息可靠性 |
| LocalMessage | my-xhs-common | 消息恢复任务增强 | 38-消息可靠性 |
| MetricsConfig | my-xhs-common | 消费监控指标增强 | 38-消息可靠性 |

### 新增公共组件

| 组件 | 模块 | 说明 | 所属专题 |
|------|------|------|----------|
| SecurityHelper | my-xhs-common | 安全工具封装（HMAC签名校验+nonce去重+XSS过滤） | 30-安全合规 |
| XssFilter | my-xhs-common | XSS过滤过滤器（Jsoup白名单+自定义Filter） | 30-安全合规 |
| SensitiveDataFilter | my-xhs-common | 敏感信息脱敏（手机号/邮箱/地址打码） | 30-安全合规 |
| RateLimitLuaScript | my-xhs-common | 滑动窗口限流Lua脚本（ZSet实现） | 37-限流降级 |
| RateLimitAspect | my-xhs-common | @RateLimit限流切面（AOP+SpEL+Redis Lua） | 37-限流降级 |
| IpRateLimitFilter | my-xhs-gateway | IP级限流GlobalFilter | 37-限流降级 |
| SentinelBlockHandler | my-xhs-common | Sentinel统一降级处理器 | 37-限流降级 |
| IdempotentConsumer | my-xhs-common | 幂等消费基类 | 38-消息可靠性 |
| MessageReliabilityHelper | my-xhs-common | 消息可靠性工具 | 38-消息可靠性 |
| SnowflakeIdGenerator | my-xhs-common | 雪花ID生成器（CosId封装+时钟回拨） | 39-分布式ID |
| SegmentIdGenerator | my-xhs-common | 号段模式生成器（双Buffer+异步预取） | 39-分布式ID |
| Segment / SegmentBuffer | my-xhs-common | 号段对象+双Buffer容器 | 39-分布式ID |
| DynamicConfig | my-xhs-common | 动态配置封装（@RefreshScope+功能开关） | 35-配置中心 |
| FeatureToggle | my-xhs-common | 功能开关枚举 | 35-配置中心 |
| DrillReport | my-xhs-common | 演练报告实体 | 29-混沌工程 |
| FailoverHelper | my-xhs-common | 故障转移工具 | 36-高可用 |
| PerformanceBaseline | my-xhs-common | 性能基线常量 | 41-全链路压测 |
| WatermarkConfig | my-xhs-common | 容量水位线配置 | 41-全链路压测 |
| SafeFeignConfig | my-xhs-common | Feign安全配置 | 40-踩坑防御 |
| SafeRedisConfig | my-xhs-common | Redis安全配置 | 40-踩坑防御 |
| SafeDatasourceConfig | my-xhs-common | 数据源安全配置 | 40-踩坑防御 |

---

## 七、实现步骤

### Step 1：限流降级方案（2天）

- [ ] common补齐 `RateLimitLuaScript.java`（滑动窗口Lua：ZAdd+ZRemRangeByScore+ZCard）
- [ ] common补齐 `RateLimitAspect.java`（AOP + SpEL解析 + Redis Lua执行）
- [ ] gateway补齐 `IpRateLimitFilter.java`（IP级滑动窗口限流GlobalFilter）
- [ ] common补齐 `SentinelBlockHandler.java`（统一降级处理器：Flow→429, Degrade→503）
- [ ] common补齐 `SentinelConfig.java`（规则持久化到Nacos + Dashboard连接）
- [ ] 各服务配置Sentinel流控规则（接口级+热点参数级）
- [ ] 编写单元测试验证四种限流算法
- [ ] 编写集成测试验证分层限流（网关→服务→注解）

### Step 2：消息可靠性全链路增强（2天）

- [ ] common补齐 `IdempotentConsumer.java`（幂等消费基类：Redis SETNX+业务唯一键）
- [ ] common补齐 `MessageReliabilityHelper.java`（发送确认+重试+死信处理封装）
- [ ] common补齐 `LocalMessageRecoveryJob.java`（XXL-Job定时扫描未发送/超时消息）
- [ ] common补齐 `ConsumeMonitor.java`（消费监控：延迟/堆积/失败数→Micrometer）
- [ ] 各消费者服务继承IdempotentConsumer，实现幂等消费
- [ ] 配置RocketMQ顺序消息（评论场景：HashQueue noteId）
- [ ] 配置死信队列处理（DeadLetterHandler→告警+人工）
- [ ] 编写集成测试验证6环节消息可靠性

### Step 3：分布式ID方案增强（1.5天）

- [ ] common补齐 `SnowflakeIdGenerator.java`（CosId封装+WorkerId Redis INCR分配+时钟回拨检测）
- [ ] common补齐 `SegmentIdGenerator.java`（号段模式：MySQL号段表+双Buffer+异步预取）
- [ ] common补齐 `Segment.java` + `SegmentBuffer.java`（号段对象+双Buffer容器）
- [ ] common补齐 `RedisIdGenerator.java`（Redis INCR + 日期前缀流水号）
- [ ] 增强 `IdGeneratorUtil.java`（三合一：雪花+号段+Redis自增）
- [ ] user服务集成号段模式（UserIdSegmentService，一次取1000个ID）
- [ ] order/content服务确认雪花ID配置正确
- [ ] 编写单元测试验证时钟回拨检测
- [ ] 编写性能测试验证ID生成QPS

### Step 4：安全合规体系（2天）

- [ ] common补齐 `SecurityHelper.java`（HMAC签名校验+nonce去重增强）
- [ ] common补齐 `XssFilter.java` + `XssHttpServletRequestWrapper.java`（Jsoup白名单过滤）
- [ ] common补齐 `SensitiveDataFilter.java`（自定义Jackson序列化器：手机号/邮箱/地址脱敏）
- [ ] user服务补齐 `RbacService.java`（角色→菜单→权限映射）
- [ ] user服务补齐 `AuditLogService.java` + `AuditLog.java`（审计日志记录）
- [ ] user服务补齐 `RbacController.java` + `AuditLogController.java`
- [ ] user服务配置网络隔离（只在内网暴露管理API）
- [ ] Gateway增强JWT黑名单校验（Redis SET: token:blacklist:{jti}）
- [ ] 编写单元测试验证XSS过滤
- [ ] 编写集成测试验证RBAC权限

### Step 5：日志体系与可观测性增强（1.5天）

- [ ] common补齐 `LoggingConfig.java` 增强（JSON结构化+TraceId MDC+服务名+实例名）
- [ ] common补齐 `LogstashEncoderConfig.java`（自定义字段：service/instance/environment）
- [ ] 部署Promtail DaemonSet（每个Node采集Pod日志）
- [ ] 部署Loki StatefulSet（日志存储+索引）
- [ ] 配置Grafana Loki数据源（日志查询）
- [ ] 配置Grafana→SkyWalking→Loki跳转关联
- [ ] 配置日志告警规则（错误突增/业务异常/OOM/慢查询）
- [ ] 编写单元测试验证TraceId MDC注入

### Step 6：CI/CD与自动化部署（2天）

- [ ] 编写各服务Dockerfile（多阶段构建）
- [ ] 编写docker-compose.yml（基础设施一键启动）
- [ ] 编写Jenkinsfile（Pipeline: Build→Test→Image→Push→Deploy）
- [ ] 编写K8s部署清单（Deployment+Service+Ingress+HPA+PDB）
- [ ] 配置Jenkins+Docker Registry+K8s集群
- [ ] 配置灰度发布流程（新版本v2→Gateway灰度路由→验证→全量）
- [ ] 验证滚动更新零停机（maxUnavailable=0 + preStop + graceful shutdown）
- [ ] 验证HPA自动伸缩（CPU>70%扩容）

### Step 7：测试策略与质量保障（2天）

- [ ] common补齐 `TestcontainersConfig.java`（MySQL/Redis/MQ通用容器配置）
- [ ] common补齐 `MockFeignConfig.java`（Feign Mock配置模板）
- [ ] common补齐 `TestDataBuilder.java`（Builder模式测试数据构建器）
- [ ] 编写核心服务单元测试（OrderService/InventoryService/CouponService）
- [ ] 编写核心服务集成测试（Testcontainers真实容器）
- [ ] 配置Spring Cloud Contract契约测试
- [ ] 配置JaCoCo覆盖率报告
- [ ] Jenkins Pipeline集成测试阶段

### Step 8：数据备份与容灾（1.5天）

- [ ] 编写 `mysql-backup.sh`（mysqldump全量+清理7天前）
- [ ] 编写 `redis-backup.sh`（BGSAVE+拷贝RDB文件）
- [ ] 编写 `es-snapshot.sh`（ES Snapshot创建/清理）
- [ ] 编写 `backup-verify.sh`（备份完整性验证）
- [ ] common补齐 `BackupScheduleConfig.java`（XXL-Job注册备份任务）
- [ ] common补齐 `DataRebuildService.java`（Redis/ES从MySQL全量重建）
- [ ] common补齐 `ReconcileJob.java`（Redis vs MySQL对账修复）
- [ ] 验证MySQL主从切换<30秒
- [ ] 验证Redis数据丢失恢复流程

### Step 9：配置中心与多环境管理（1天）

- [ ] common补齐 `DynamicConfig.java`（@RefreshScope + @Value + 功能开关）
- [ ] common补齐 `FeatureToggle.java`（功能开关枚举）
- [ ] common补齐 `NacosConfigListener.java`（配置变更监听+日志+告警）
- [ ] 配置Nacos多环境Namespace（dev/test/pre/prod）
- [ ] 配置Nacos配置分组（COMMON_GROUP/SERVICE_GROUP/SENTINEL_GROUP）
- [ ] 迁移各服务application.yml到Nacos Config
- [ ] 验证配置热更新（修改Nacos→服务实时生效）

### Step 10：高可用与故障预案（1.5天）

- [ ] common补齐 `HealthCheckConfig.java`（Actuator + K8s探针配置）
- [ ] common补齐 `FailoverHelper.java`（自动重试+降级+熔断判断）
- [ ] common补齐 `CircuitBreakerConfig.java`（Sentinel熔断规则统一管理）
- [ ] 配置K8s PDB（Pod Disruption Budget: minAvailable=1）
- [ ] 配置HPA自动伸缩（CPU>70%扩容，min=2, max=10）
- [ ] 编写故障预案文档（5大P0场景操作步骤）
- [ ] 验证各组件HA方案（MySQL/Redis/MQ/Nacos/ES）
- [ ] 验证K8s自动重启+优雅停机

### Step 11：混沌工程与故障演练（1.5天）

- [ ] common补齐 `ChaosDrillRunner.java`（ChaosBlade CLI调用封装）
- [ ] common补齐 `DrillReport.java` + `DrillReportMapper.java` + `DrillReportService.java`
- [ ] common补齐 `ChaosDrillController.java`（演练管理API）
- [ ] 编写7个演练场景脚本（ChaosBlade命令）
- [ ] 测试环境执行7个演练场景
- [ ] 记录实际结果 vs 预期结果
- [ ] 不符合预期的→修复代码/配置→重新演练
- [ ] 输出演练报告

### Step 12：生产踩坑速查与防御（1天）

- [ ] common补齐 `SafeFeignConfig.java`（超时5+10s + NEVER_RETRY + RequestInterceptor）
- [ ] common补齐 `SafeRedisConfig.java`（连接池+大Key扫描配置）
- [ ] common补齐 `SafeDatasourceConfig.java`（HikariCP参数+leakDetection）
- [ ] common补齐 `SafeMvcConfig.java`（XssFilter+请求体限制+编码）
- [ ] 集成XXL-Job大Key扫描任务（Redis --bigkeys定期扫描）
- [ ] 配置慢SQL日志（long_query_time=0.1s）
- [ ] 配置HikariCP连接泄漏检测（leakDetectionThreshold=60000）
- [ ] 代码Review确认@Transactional使用正确

### Step 13：全链路压测基线（2天）

- [ ] common补齐 `PerformanceBaseline.java`（各场景QPS/RT/成功率常量）
- [ ] common补齐 `WatermarkConfig.java`（安全/告警/危险水位阈值）
- [ ] common补齐 `StressTestTag.java`（压测标记注解）
- [ ] 编写7个JMeter压测脚本（6场景+1全链路混合）
- [ ] 部署GoReplay录制线上流量
- [ ] 执行第一轮压测，记录基线数据
- [ ] 分析瓶颈，调优（JVM/连接池/缓存/异步）
- [ ] 执行第二轮压测，对比基线
- [ ] 输出压测报告

---

## 八、配置文件清单

### 8.1 my-xhs-common（❌ 待增强）

> common 模块无 application.yml，配置由各服务自行管理。common 仅提供注解、切面、工具类等代码级组件。

### 8.2 my-xhs-gateway（❌ 待增强，IP限流+安全+监控）

```yaml
# 在Phase-4/5配置基础上追加：

# IP级滑动窗口限流
gateway:
  rate-limit:
    enabled: true
    max-requests: 100    # 单IP每秒最大请求数
    window-ms: 1000      # 窗口大小(毫秒)

# 安全增强
gateway:
  security:
    hmac:
      enabled: true
      secret-key: ${HMAC_SECRET_KEY}
      timestamp-ttl: 300000  # 5分钟过期
    xss:
      enabled: true
      allowed-tags: "a,img,p,br,b,i,em,strong"
    cors:
      allowed-origins: "https://myxhs.com"

# 监控指标暴露
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

### 8.3 my-xhs-order（❌ 待增强，消息可靠性+限流+压测标记）

```yaml
# 在Phase-5配置基础上追加：

# Sentinel限流规则
spring:
  cloud:
    sentinel:
      transport:
        dashboard: sentinel-dashboard:8080
      datasource:
        flow:
          nacos:
            server-addr: ${NACOS_SERVER}
            namespace: ${NACOS_NAMESPACE}
            group-id: SENTINEL_GROUP
            data-id: flow-rules.json
            rule-type: flow

# 消息可靠性增强
rocketmq:
  consumer:
    consume-timeout: 15  # 消费超时(分钟)
    max-reconsume-times: 16  # 最大重试次数
  producer:
    retry-times-when-send-failed: 3  # 发送失败重试次数
    send-message-timeout: 3000  # 发送超时(毫秒)

# 压测标记
stress-test:
  shadow-table-suffix: _shadow
  enabled: ${STRESS_TEST_ENABLED:false}
```

### 8.4 my-xhs-user（❌ 待增强，号段模式+RBAC+审计）

```yaml
# 在Phase-1配置基础上追加：

# 号段模式ID生成
id-generator:
  segment:
    enabled: true
    step: 1000  # 每次取1000个ID
    table: t_id_segment  # 号段表名

# RBAC权限
rbac:
  enabled: true
  super-admin-role: SUPER_ADMIN

# 审计日志
audit:
  enabled: true
  log-table: t_audit_log
  exclude-paths: "/actuator/**,/health/**"
```

### 8.5 各服务通用追加配置（安全+限流+监控+备份）

```yaml
# 所有业务服务需追加以下配置：

# 安全配置
security:
  xss-filter:
    enabled: true
  sensitive-data:
    enabled: true

# Sentinel
spring.cloud.sentinel:
  transport.dashboard: sentinel-dashboard:8080
  eager: true  # 服务启动立即注册

# 监控指标
management:
  endpoints.web.exposure.include: health,info,prometheus
  metrics.export.prometheus.enabled: true
  endpoint.health:
    show-details: always
    probes.enabled: true

# 备份调度（由Nacos公共配置下发）
backup:
  mysql:
    cron: "0 0 2 * * ?"  # 每天凌晨2点
  redis:
    cron: "0 0 */6 * * ?"  # 每6小时
  es:
    cron: "0 0 3 * * ?"  # 每天凌晨3点
```

---

## 九、骨架问题清单

| # | 问题 | 说明 | 状态 |
|---|------|------|------|
| 1 | common模块缺RateLimitLuaScript及限流切面 | 需补齐滑动窗口Lua + AOP切面 | ❌ 待实现 |
| 2 | common模块缺IdempotentConsumer幂等消费基类 | 需补齐Redis SETNX + 业务唯一键封装 | ❌ 待实现 |
| 3 | common模块缺SnowflakeIdGenerator雪花ID增强 | 需补齐CosId封装+WorkerId分配+时钟回拨检测 | ❌ 待实现 |
| 4 | common模块缺SegmentIdGenerator号段模式 | 需补齐MySQL号段表+双Buffer+异步预取 | ❌ 待实现 |
| 5 | common模块缺SecurityHelper/XssFilter/SensitiveDataFilter | 需补齐安全合规全套组件 | ❌ 待实现 |
| 6 | common模块缺SentinelBlockHandler统一降级处理器 | 需补齐Flow→429, Degrade→503统一响应 | ❌ 待实现 |
| 7 | common模块缺SafeFeignConfig/SafeRedisConfig等安全配置 | 需补齐踩坑防御代码 | ❌ 待实现 |
| 8 | common模块缺DynamicConfig/FeatureToggle | 需补齐配置中心增强组件 | ❌ 待实现 |
| 9 | common模块缺DrillReport/ChaosDrillRunner | 需补齐混沌工程组件 | ❌ 待实现 |
| 10 | common模块缺PerformanceBaseline/WatermarkConfig | 需补齐压测基线组件 | ❌ 待实现 |
| 11 | common模块缺FailoverHelper/CircuitBreakerConfig | 需补齐高可用组件 | ❌ 待实现 |
| 12 | common模块缺BackupScheduleConfig/DataRebuildService | 需补齐数据备份组件 | ❌ 待实现 |
| 13 | common模块缺TestcontainersConfig/MockFeignConfig | 需补齐测试工具类 | ❌ 待实现 |
| 14 | gateway缺IpRateLimitFilter | 需补齐IP级滑动窗口限流 | ❌ 待实现 |
| 15 | gateway缺XSS过滤增强 | 需补齐Jsoup白名单XssFilter | ❌ 待实现 |
| 16 | user服务缺RbacService/AuditLogService | 需补齐RBAC权限+审计日志 | ❌ 待实现 |
| 17 | user服务缺RbacController/AuditLogController | 需补齐RBAC管理+审计查询API | ❌ 待实现 |
| 18 | user服务缺网络隔离配置 | 需配置管理API只允许内网访问 | ❌ 待配置 |
| 19 | 各服务缺Sentinel规则配置 | 需配置Nacos规则持久化+Dashboard连接 | ❌ 待配置 |
| 20 | 各服务缺JMeter压测脚本 | 需编写7个核心场景压测脚本 | ❌ 待编写 |
| 21 | Dockerfile未编写 | 需为每个服务编写多阶段构建Dockerfile | ❌ 待编写 |
| 22 | K8s部署清单未编写 | 需编写Deployment+Service+Ingress+HPA+PDB | ❌ 待编写 |
| 23 | Jenkins Pipeline未编写 | 需编写Jenkinsfile（Build→Test→Image→Push→Deploy） | ❌ 待编写 |
| 24 | Promtail/Loki未部署 | 需部署日志采集存储基础设施 | ❌ 待部署 |
| 25 | 号段表t_id_segment未创建 | 需在my_xhs_user库创建号段表 | ❌ 待创建 |
| 26 | 审计日志表t_audit_log未创建 | 需在my_xhs_user库创建审计日志表 | ❌ 待创建 |
| 27 | RBAC相关表未创建 | 需创建角色表/菜单表/权限表/用户角色关联表 | ❌ 待创建 |
| 28 | GoReplay未部署 | 需部署流量录制回放工具 | ❌ 待部署 |
| 29 | ChaosBlade未部署 | 需部署混沌工程工具 | ❌ 待部署 |

---

## 十、数据库变更清单

### 10.1 新建表

| 数据库 | 表名 | 说明 | 所属专题 |
|--------|------|------|----------|
| my_xhs_user | t_id_segment | 号段表（biz_type, current_value, step, version） | 39-分布式ID |
| my_xhs_user | t_audit_log | 审计日志表（operator, module, action, ip, params, result） | 30-安全合规 |
| my_xhs_user | t_role | 角色表（role_name, description, status） | 30-安全合规 |
| my_xhs_user | t_permission | 权限表（permission_code, permission_name, menu_id） | 30-安全合规 |
| my_xhs_user | t_menu | 菜单表（menu_name, parent_id, path, icon, sort） | 30-安全合规 |
| my_xhs_user | t_user_role | 用户角色关联表（user_id, role_id） | 30-安全合规 |
| my_xhs_user | t_role_permission | 角色权限关联表（role_id, permission_id） | 30-安全合规 |
| my_xhs_user | t_drill_report | 演练报告表（scenario, expected, actual, passed, issues） | 29-混沌工程 |

### 10.2 号段表结构

```sql
CREATE TABLE t_id_segment (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    biz_type    VARCHAR(64) NOT NULL COMMENT '业务类型(user/order/note)',
    current_value BIGINT NOT NULL DEFAULT 0 COMMENT '当前最大值',
    step        INT NOT NULL DEFAULT 1000 COMMENT '号段步长',
    version     INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_biz_type (biz_type)
) COMMENT '号段表';

-- 初始化
INSERT INTO t_id_segment (biz_type, current_value, step) VALUES ('user', 0, 1000);
```

### 10.3 审计日志表结构

```sql
CREATE TABLE t_audit_log (
    id          BIGINT PRIMARY KEY COMMENT '日志ID(雪花ID)',
    operator_id BIGINT NOT NULL COMMENT '操作人ID',
    operator_name VARCHAR(64) NOT NULL COMMENT '操作人姓名',
    module      VARCHAR(64) NOT NULL COMMENT '模块(user/order/content/product)',
    action      VARCHAR(64) NOT NULL COMMENT '操作(create/update/delete/audit)',
    target_id   VARCHAR(128) COMMENT '操作对象ID',
    ip          VARCHAR(64) COMMENT '操作IP',
    params      TEXT COMMENT '请求参数JSON',
    result      TEXT COMMENT '操作结果JSON',
    status      TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1成功0失败',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_operator_id (operator_id),
    KEY idx_module_action (module, action),
    KEY idx_created_at (created_at)
) COMMENT '审计日志表';
```

### 10.4 RBAC相关表结构

```sql
CREATE TABLE t_role (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_name   VARCHAR(64) NOT NULL COMMENT '角色名',
    role_code   VARCHAR(64) NOT NULL COMMENT '角色编码(SUPER_ADMIN/ADMIN/EDITOR/VIEWER)',
    description VARCHAR(256) COMMENT '角色描述',
    status      TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1启用0禁用',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_role_code (role_code)
) COMMENT '角色表';

CREATE TABLE t_menu (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    menu_name   VARCHAR(64) NOT NULL COMMENT '菜单名',
    parent_id   BIGINT NOT NULL DEFAULT 0 COMMENT '父菜单ID(0=顶级)',
    path        VARCHAR(128) COMMENT '路由路径',
    icon        VARCHAR(64) COMMENT '图标',
    sort        INT NOT NULL DEFAULT 0 COMMENT '排序',
    status      TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1启用0禁用',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) COMMENT '菜单表';

CREATE TABLE t_permission (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    permission_code VARCHAR(128) NOT NULL COMMENT '权限编码(user:create,user:delete)',
    permission_name VARCHAR(128) NOT NULL COMMENT '权限名称',
    menu_id         BIGINT COMMENT '关联菜单ID',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_permission_code (permission_code)
) COMMENT '权限表';

CREATE TABLE t_user_role (
    user_id     BIGINT NOT NULL COMMENT '用户ID',
    role_id     BIGINT NOT NULL COMMENT '角色ID',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id)
) COMMENT '用户角色关联表';

CREATE TABLE t_role_permission (
    role_id       BIGINT NOT NULL COMMENT '角色ID',
    permission_id BIGINT NOT NULL COMMENT '权限ID',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, permission_id)
) COMMENT '角色权限关联表';

-- 初始化角色
INSERT INTO t_role (role_name, role_code, description) VALUES
('超级管理员', 'SUPER_ADMIN', '拥有所有权限'),
('管理员', 'ADMIN', '大部分管理权限'),
('编辑', 'EDITOR', '内容编辑权限'),
('查看者', 'VIEWER', '只读权限');
```

---

## Phase 6 核心技术点

- **混沌工程**：ChaosBlade 7场景故障注入+演练报告（验证降级/熔断/主从切换/优雅停机真正生效）
- **安全合规**：HMAC签名+XSS过滤+敏感信息脱敏+RBAC权限+审计日志（四层防护体系）
- **日志体系**：JSON结构化+TraceId关联+Promtail→Loki→Grafana（三维可观测性补齐日志支柱）
- **CI/CD**：Jenkins Pipeline+Docker多阶段构建+K8s灰度发布（代码提交到上线全自动化）
- **测试策略**：测试金字塔+Testcontainers+契约测试（核心逻辑≥80%覆盖率）
- **分层限流**：网关层滑动窗口+服务层令牌桶+参数级热点限流+注解级@RateLimit（四层防护）
- **消息可靠性**：6环节保障+幂等消费基类+消费监控+死信队列处理（消息不丢不重）
- **分布式ID**：雪花ID+号段模式双Buffer+时钟回拨检测（按业务特点选最优方案）
- **生产踩坑42条防御**：12组件×42坑速查表+防御代码（不只文档，代码级防御）
- **全链路压测基线**：6场景基线+5级容量水位+压测报告模板（退化>5%阻断发布）

---

## 文档索引

每个专题的配套文档按以下规范归档：

| 文档类型 | 命名格式 | 说明 |
|----------|----------|------|
| a-前置知识 | `a-前置知识-xxx.md` | Java/JVM/网络知识 + 可运行Demo |
| b-问题驱动实现 | `b-问题驱动实现-xxx.md` | 从0开始以问题驱动推导设计和实现 |
| c-现状梳理 | `c-现状梳理-xxx.md` | 所有文件/类/字段/方法详解 |

### 已完成文档

<!-- 
完成文档后在此添加索引，格式示例：
- [29-混沌工程与故障演练](./a-前置知识-混沌工程与故障演练.md)
- [29-混沌工程与故障演练](./b-问题驱动实现-混沌工程与故障演练.md)
- [29-混沌工程与故障演练](./c-现状梳理-混沌工程与故障演练.md)
-->

> 📌 待编写：当前尚无已完成文档，请按文档编写规范依次创建。

> 📌 文档编写要求：必须参考本地真实框架源码（如Sentinel/ShardingSphere/ChaosBlade），不能凭空设计。"问题驱动实现"是最重要的文档。