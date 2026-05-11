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
| my-xhs-admin | 9013 | my_xhs_admin | ❌ 已存在(增强) | 后台管理（补齐RBAC权限、审计日志、网络隔离） |
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

**admin/chaos/**
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
| my-xhs-admin | 管理安全 | RBAC权限、操作审计、网络隔离、二次确认 |
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

**admin/security/**
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
- [ ] admin服务补齐 `RbacController.java` + `AuditLogController.java`
- [ ] admin服务配置网络隔离（只在内网暴露）
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
- [ ] admin补齐 `ChaosDrillController.java`（演练管理API）
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
      allowed-origins: "https://myxhs.com,https://admin.myxhs.com"

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
| 17 | admin服务缺RbacController/AuditLogController | 需补齐RBAC管理+审计查询API | ❌ 待实现 |
| 18 | admin服务缺网络隔离配置 | 需配置K8s NetworkPolicy只允许内网访问 | ❌ 待配置 |
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
| my_xhs_admin | t_drill_report | 演练报告表（scenario, expected, actual, passed, issues） | 29-混沌工程 |

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
    module      VARCHAR(64) NOT NULL COMMENT '模块(user/order/content/admin)',
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