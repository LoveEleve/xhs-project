# Phase 5：进阶专题 — 详细梳理

> 🎯 目标：解决跨服务的架构难题，系统具备高可用、高性能基础
>
> ⚠️ **前置条件**：Phase 1 + Phase 2 + Phase 3 + Phase 4 全部功能开发完成并验收通过

### Phase 间依赖清单

| 依赖Phase | 依赖的功能点 | 本Phase使用场景 |
|-----------|------------|----------------|
| Phase 1 | 用户体系（JWT签发/刷新） | 流量染色透传X-User-Id、监控告警关联用户 |
| Phase 1 | 内容服务、社交服务、计数服务 | 缓存一致性方案涉及笔记/社交缓存、Canal同步笔记数据 |
| Phase 2 | 商品/库存/购物车/优惠券/订单/支付 | 分布式事务（下单→扣库存→扣券→创订单）、分库分表（订单/优惠券）、Canal同步商品数据 |
| Phase 2 | 优惠券分库、订单分库 | 分库分表实战参考已有配置 |
| Phase 3 | 搜索/Home BFF/通知 | Canal增量同步到ES、全链路染色经过Home BFF |
| Phase 4 | Gateway鉴权/限流/灰度/染色 | 流量染色透传经过网关、性能压测入口、监控覆盖网关 |
| Phase 4 | IM服务 | 监控告警覆盖IM、优雅停机覆盖IM的WebSocket连接处理 |
| Phase 4 | 公共组件（@Idempotent/@DistributedLock/@RateLimit/IdGeneratorUtil） | 分库分表使用雪花ID、缓存一致性使用分布式锁 |

---

## 一、Phase 5 概览

| 序号 | 专题 | 涉及服务 | 核心技术 |
|------|------|----------|----------|
| 21 | 缓存一致性方案 | 跨服务 | Cache Aside + 延迟双删 + Canal异步兜底 |
| 22 | 分布式事务 | 跨服务 | RocketMQ事务消息 + 本地消息表 + 死信队列处理 |
| 23 | 全链路流量染色 | 跨服务 | 压测隔离、影子表、染色标记透传（Feign+MQ+异步线程） |
| 24 | 性能优化与压测 | 跨服务 | JMeter压测计划、GoReplay流量录制、调优记录 |
| 25 | 监控告警体系 | 跨服务 | Prometheus + Grafana + SkyWalking + 告警规则 |
| 26 | 分库分表实战 | 跨服务 | ShardingSphere配置、订单/优惠券分片、分片算法 |
| 27 | Canal数据同步 | 跨服务 | Binlog监听→MQ→ES、增量+全量重建、断点续传 |
| 28 | 优雅停机与服务治理 | 跨服务 | Nacos优雅下线、健康检查、异常分级处理 |

---

## 二、涉及的模块与端口

| 服务 | 端口 | 数据库 | 本阶段变更 | 说明 |
|------|------|--------|-----------|------|
| my-xhs-common | — | — | ❌ 已存在(增强) | 公共模块（补齐CacheHelper、TraceFilter、ShadowDataSource等） |
| my-xhs-gateway | 9000 | — | ❌ 已存在(增强) | 网关（补齐流量染色标记注入） |
| my-xhs-order | 9006 | my_xhs_order (分库) | ❌ 已存在(增强) | 订单（补齐本地消息表、事务消息发送） |
| my-xhs-coupon | 9010 | my_xhs_coupon (分库) | ❌ 已存在(增强) | 优惠券（补齐本地消息表） |
| my-xhs-product | 9005 | my_xhs_product | ❌ 已存在(增强) | 商品（补齐Canal同步、缓存一致性） |
| my-xhs-inventory | 9008 | my_xhs_inventory | ❌ 已存在(增强) | 库存（补齐分布式事务参与者） |
| my-xhs-payment | 9007 | my_xhs_payment | ❌ 已存在(增强) | 支付（补齐分布式事务参与者） |
| my-xhs-content | 9002 | my_xhs_note | ❌ 已存在(增强) | 笔记（补齐Canal同步到ES） |
| my-xhs-notification | 9012 | my_xhs_notification | ❌ 已存在(增强) | 通知（补齐Canal同步、监控告警） |
| my-xhs-search | 9011 | my_xhs_search (ES+MySQL) | ❌ 已存在(增强) | 搜索（补齐Canal全量/增量重建索引） |
| my-xhs-home | 9015 | 无（BFF聚合） | ❌ 已存在(增强) | Home BFF（补齐流量染色标记读取） |
| my-xhs-im | 9014 | my_xhs_im | ❌ 已存在(增强) | IM（补齐优雅停机WebSocket处理、监控覆盖） |
| my-xhs-admin | 9013 | my_xhs_admin | ❌ 已存在(增强) | 后台管理（补齐Canal数据同步管理） |
| my-xhs-user | 9001 | my_xhs_user | ❌ 已存在(增强) | 用户（补齐缓存一致性） |
| my-xhs-analytics | 9003 | my_xhs_social | ❌ 已存在(增强) | 社交（补齐缓存一致性） |
| my-xhs-counter | 9004 | my_xhs_counter | ❌ 已存在(增强) | 计数（补齐监控告警） |
| my-xhs-cart | 9009 | my_xhs_cart | ❌ 已存在(增强) | 购物车（补齐监控覆盖） |

> **Phase 5 是跨服务专题，不新增模块**，只在现有模块上增强。所有变更需兼容已有功能。

---

## 三、专题详细梳理

### 专题 21：缓存一致性方案

#### 3.21.1 功能描述

缓存与数据库的一致性是分布式系统中的经典难题。本专题提供三重保障方案：①Cache Aside（先更新DB→再删缓存，通用场景）；②延迟双删（强一致需求，删缓存→更新DB→延时再删缓存）；③Canal异步兜底（Binlog监听→MQ→更新缓存/ES，最终一致性保底）。

#### 3.21.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-common | 主服务 | CacheHelper封装、DelayDeleteHelper封装 |
| my-xhs-product | 使用方 | 商品多级缓存一致性（SPU/SKU详情） |
| my-xhs-user | 使用方 | 用户信息缓存一致性 |
| my-xhs-content | 使用方 | 笔记详情缓存一致性 |
| my-xhs-analytics | 使用方 | 社交数据缓存一致性（关注关系/点赞状态） |

#### 3.21.3 缓存更新策略清单

| 场景 | 策略 | 操作步骤 | 一致性级别 |
|------|------|----------|-----------|
| 商品详情更新 | Cache Aside | 1.更新MySQL→2.删除Redis缓存 | 最终一致（毫秒级不一致窗口） |
| 商品上下架 | 延迟双删 | 1.删缓存→2.更新DB→3.延时500ms再删缓存 | 强一致（双删覆盖并发写窗口） |
| 笔记审核通过 | 延迟双删 | 同上 | 强一致 |
| 用户改昵称/头像 | Cache Aside | 1.更新MySQL→2.删除Redis缓存 | 最终一致 |
| Canal异步兜底 | Binlog监听 | Binlog→Canal→MQ→Consumer→更新缓存/删除缓存 | 最终一致（秒级延迟） |

#### 3.21.4 缓存异常处理

| 问题 | 方案 | 实现 |
|------|------|------|
| 缓存穿透 | 布隆过滤器（Phase-2已实现） | `product:bloom:spu` / `product:bloom:sku` |
| 缓存击穿 | 逻辑过期（Phase-2已实现） + 分布式锁 | `@DistributedLock(key="'lock:cache:' + #spuId")` |
| 缓存雪崩 | 随机TTL偏移 + 多级缓存 | TTL = baseTTL + random(0, 300)s |

#### 3.21.5 Java 文件清单

**common/cache/**
```
CacheHelper.java             — 缓存操作封装（get/set/delete + Cache Aside封装）
DelayDeleteHelper.java       — 延迟双删封装（scheduleDelay删除+分布式锁防并发）
CacheAsideProcessor.java      — Cache Aside模板方法（更新DB→删缓存→异常处理）
```

**common/canal/**
```
CanalHelper.java             — Canal客户端封装（启动/停止/断点续传）
```

#### 3.21.6 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| Cache Aside | 先更新DB→再删缓存 | 更新DB成功→DEL缓存→失败重试3次→Canal兜底 |
| 延迟双删 | 删缓存→更新DB→延时再删 | Redis DEL→MySQL UPDATE→ScheduledExecutor 500ms→Redis DEL |
| Canal异步兜底 | Binlog→Canal→MQ→Consumer→更新缓存 | Canal监听binlog事件→RocketMQ→Consumer根据事件类型更新缓存 |
| 分布式锁防并发 | @DistributedLock | 缓存更新期间加锁，防止并发读写导致脏数据 |
| 延迟双删延时时间 | 500ms | 基于主从同步延迟（通常<500ms）+业务读耗时估算 |

---

### 专题 22：分布式事务

#### 3.22.1 功能描述

电商核心链路"下单→扣库存→扣券→创订单→支付"涉及4个服务，需要保证分布式事务的最终一致性。采用RocketMQ事务消息 + 本地消息表双重保障方案：①事务消息保证"下单消息"可靠投递；②本地消息表保证"扣库存/扣券"操作可回溯、可重试；③死信队列处理3次重试仍失败的消息，人工介入。

#### 3.22.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-order | 发起方 | 事务消息发送方（创建订单→发送半消息→提交/回滚） |
| my-xhs-inventory | 参与者 | 本地消息表消费者（扣减库存→确认/回滚） |
| my-xhs-coupon | 参与者 | 本地消息表消费者（扣减优惠券→确认/回滚） |
| my-xhs-payment | 参与者 | 本地消息表消费者（创建支付单→确认/回滚） |
| my-xhs-common | 基础组件 | TransactionHelper封装、LocalMessage表实体 |

#### 3.22.3 分布式事务流程

```
下单请求
    │
    ▼
┌─────────────────────┐
│ OrderService        │
│ 1. 创建订单（DB）   │
│ 2. 发送事务半消息   │──── RocketMQ ────▶ ┌──────────────────┐
│    "order-created"  │                    │ 事务回查          │
│ 3. 本地提交/回滚    │                    │ OrderService查订单│
└──────┬──────────────┘                    │ 存在→commit      │
       │ 半消息commit                      │ 不存在→rollback  │
       ▼                                   └──────────────────┘
┌─────────────────────┐
│ MQ Consumer         │
│ 收到"order-created" │
│ 1. 扣库存（Feign）  │── inventory服务 ──▶ 本地消息表记录
│ 2. 扣优惠券（Feign）│── coupon服务 ──────▶ 本地消息表记录
│ 3. 创支付单（Feign）│── payment服务 ──────▶ 本地消息表记录
│ 全部成功→ACK        │
│ 任一失败→NACK+重试  │
└──────┬──────────────┘
       │ 重试3次仍失败
       ▼
┌─────────────────────┐
│ 死信队列            │
│ DLQConsumer         │────▶ 告警+人工介入
└─────────────────────┘
```

#### 3.22.4 本地消息表设计

**t_local_message**（每个参与者服务一张）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 消息ID（雪花算法） |
| transaction_id | VARCHAR(64) | 事务ID（全局唯一，关联下单请求） |
| service_name | VARCHAR(32) | 服务名（inventory/coupon/payment） |
| operation_type | VARCHAR(32) | 操作类型（DEDUCT_INVENTORY/USE_COUPON/CREATE_PAYMENT） |
| payload | TEXT | 操作参数JSON |
| status | TINYINT | 状态:0待处理1成功2失败3死信 |
| retry_count | INT | 重试次数（最大3次） |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

> KEY `idx_transaction_id` (transaction_id), KEY `idx_status_created` (status, created_at)

#### 3.22.5 MQ Topic 清单

| Topic | 生产者 | 消费者 | 说明 |
|-------|--------|--------|------|
| `order-transaction` | OrderService | Inventory/Coupon/Payment | 事务消息：下单→扣库存→扣券→创支付单 |
| `order-transaction-dlq` | 消费失败3次后进入 | DLQConsumer | 死信队列：人工处理 |

#### 3.22.6 Java 文件清单

**common/transaction/**
```
TransactionHelper.java        — 事务消息发送封装（半消息→执行本地事务→提交/回滚）
LocalMessage.java             — 本地消息实体
LocalMessageMapper.java       — 本地消息Mapper
LocalMessageService.java      — 本地消息服务接口
LocalMessageServiceImpl.java  — 本地消息服务实现（记录/更新状态/查询待重试/清理过期）
```

**order/transaction/**
```
OrderTransactionProducer.java — 订单事务消息生产者（发送半消息+本地事务回查）
OrderTransactionListener.java — 事务监听器（executeLocalTransaction→checkLocalTransaction）
```

**order/localmessage/**
```
LocalMessageConsumer.java     — 本地消息消费者（扣库存/扣券/创支付单→确认/回滚）
DeadLetterConsumer.java       — 死信队列消费者（告警+记录→人工介入）
```

**inventory/localmessage/**
```
InventoryLocalMessageConsumer.java — 库存本地消息消费者
```

**coupon/localmessage/**
```
CouponLocalMessageConsumer.java    — 优惠券本地消息消费者
```

**payment/localmessage/**
```
PaymentLocalMessageConsumer.java   — 支付本地消息消费者
```

#### 3.22.7 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 事务消息 | RocketMQ TransactionMQProducer | 半消息→executeLocalTransaction→commit/rollback→checkLocalTransaction回查 |
| 本地消息表 | 每个参与者服务维护一张t_local_message | 记录操作→执行→更新状态→失败重试（最多3次）→死信 |
| 死信队列 | RocketMQ DLQ (%DLQ%ConsumerGroup) | 3次重试失败→进入DLQ→DLQConsumer→告警+人工介入 |
| 幂等消费 | @Idempotent注解 | 防止MQ重复投递导致重复扣库存/扣券 |
| 事务回查 | checkLocalTransaction | 查订单表是否存在→commit/rollback |
| 补偿机制 | 定时任务扫描本地消息表 | XXL-Job扫描status=2失败且retry_count<3→重新投递 |

---

### 专题 23：全链路流量染色

#### 3.23.1 功能描述

全链路流量染色实现压测流量与生产流量的完全隔离。通过在请求入口（Gateway）注入染色标记（`X-Trace-Tag: shadow`），经由 Feign + MQ + 异步线程全链路透传，下游服务根据标记路由到影子表/影子库，实现压测数据不影响生产数据。

#### 3.23.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-gateway | 入口 | 注入染色标记 `X-Trace-Tag: shadow`（压测流量） |
| my-xhs-common | 基础组件 | TraceContext（ThreadLocal透传染色标记）、ShadowDataSource（影子数据源路由） |
| 所有业务服务 | 使用方 | 根据TraceContext决定读写影子表/影子库 |
| 所有Feign调用 | 透传 | Feign RequestInterceptor透传染色Header |
| 所有MQ消息 | 透传 | 消息Header携带染色标记 |
| 所有@Async方法 | 透传 | TaskDecorator透传ThreadLocal |

#### 3.23.3 染色标记流转

```
压测请求（带X-Trace-Tag: shadow header）
    │
    ▼
┌──────────────────────────────┐
│ Gateway GlobalFilter        │
│ 检测X-Trace-Tag Header      │
│ 注入TraceContext             │
│ (ThreadLocal: traceTag=shadow)│
└──────────┬───────────────────┘
           │
           ▼
┌──────────────────────────────┐
│ 业务Service                  │
│ 读TraceContext.traceTag      │
│ "shadow" → 路由影子表       │
│ null/production → 路由生产表 │
└──────┬───────────┬───────────┘
       │           │
  ┌────▼───┐  ┌────▼────┐
  │ Feign  │  │ MQ消息  │
  │透传Header│  │透传Header│
  └────┬───┘  └────┬────┘
       │           │
       ▼           ▼
  下游Service  MQ Consumer
  同样检查TraceContext
```

#### 3.23.4 Java 文件清单

**common/trace/**
```
TraceContext.java              — 染色上下文（ThreadLocal: traceId + traceTag + userId）
TraceContextHolder.java        — 上下文持有者（set/get/clear）
```

**common/shadow/**
```
ShadowDataSource.java          — 影子数据源（根据traceTag路由到影子库）
ShadowTableSuffix.java         — 影子表后缀（_shadow）
```

**common/feign/**
```
TraceFeignRequestInterceptor.java — Feign请求拦截器（透传traceId+traceTag+userId）
```

**common/mq/**
```
TraceMessagePostProcessor.java    — MQ消息后处理器（Header透传traceTag）
```

**common/async/**
```
TraceTaskDecorator.java          — 异步线程TaskDecorator（透传ThreadLocal）
```

**gateway/filter/**
```
TraceGlobalFilter.java           — 追踪过滤器（注入/透传TraceContext，含染色标记检测）
```

#### 3.23.5 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 染色标记注入 | Gateway GlobalFilter | 检测请求头X-Trace-Tag→存入TraceContext(ThreadLocal) |
| Feign透传 | RequestInterceptor | 从ThreadLocal读取traceId/traceTag→设到Feign Request Header |
| MQ透传 | MessagePostProcessor | 从ThreadLocal读取traceTag→设到Message Header |
| 异步线程透传 | TaskDecorator | 包装Runnable→执行前set ThreadLocal→执行后clear |
| 影子表路由 | ShardingSphere + 自定义分片算法 | traceTag=shadow → 路由到表名_shadow |
| 影子库路由 | ShadowDataSource | traceTag=shadow → 切换到影子数据源（my_xhs_order_shadow） |
| ThreadLocal清理 | Filter + Interceptor finally | 请求结束→ThreadLocal.remove()防止内存泄漏 |

---

### 专题 24：性能优化与压测

#### 3.24.1 功能描述

系统性进行性能优化和压测，确保系统达到SLA承诺的QPS和RT指标。使用JMeter进行压力测试，GoReplay录制线上流量回放，逐步调优JVM/连接池/缓存/异步等参数。

#### 3.24.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| 全部服务 | 压测对象 | 核心链路（首页/商品详情/下单/搜索） |
| my-xhs-common | 基础组件 | 连接池配置、线程池配置、JVM参数模板 |
| my-xhs-gateway | 入口 | 压测流量入口（需配合流量染色） |

#### 3.24.3 压测计划

| 阶段 | 场景 | 并发数 | 目标QPS | 目标RT(P99) | 备注 |
|------|------|--------|---------|------------|------|
| 1 | 首页Feed流 | 500 | 1000 | <200ms | 验证BFF并行聚合+缓存 |
| 2 | 商品详情页 | 1000 | 2000 | <100ms | 验证多级缓存+逻辑过期 |
| 3 | 下单链路 | 200 | 500 | <500ms | 验证分布式事务+MQ异步 |
| 4 | 搜索 | 500 | 1000 | <200ms | 验证ES索引+缓存 |
| 5 | 全链路混合 | 2000 | 5000 | — | 验证整体容量水位 |

#### 3.24.4 性能优化清单

| 优化项 | 方案 | 预期效果 |
|--------|------|----------|
| JVM调优 | G1 GC + 合理堆大小(4G) + -XX:MaxGCPauseMillis=200 | GC暂停<200ms |
| HikariCP连接池 | maximumPoolSize=20 + minimumIdle=10 + connectionTimeout=3000 | 连接复用、避免超时 |
| Redis Pipeline | 批量操作用Pipeline | 网络RT降低80% |
| CompletableFuture并行 | BFF聚合6服务并行调用 | RT从320ms降到100ms |
| 异步化 | @Async + MQ解耦 | 下单RT从同步秒级→异步百ms级 |
| 缓存预热 | XXL-Job定时刷新热点缓存 | 避免冷启动缓存Miss |
| 批量刷盘 | Buffer-Trigger计数 | Redis→DB批量写入减少DB压力 |

#### 3.24.5 Java 文件清单

**common/perf/**
```
ThreadPoolConfig.java          — 线程池配置模板（核心/最大/队列/拒绝策略）
JvmConfig.java                — JVM参数建议（G1 GC + 堆大小 + GC日志）
HikariPoolConfig.java          — HikariCP连接池配置模板
```

**common/goreplay/**
```
GoReplayConfig.java            — GoReplay录制回放配置（输入/输出/速率）
```

#### 3.24.6 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| JMeter压测 | 线程组+HTTP请求+监听器 | 模拟并发用户→记录RT/TPS/错误率 |
| GoReplay流量回放 | 录制线上流量→倍速回放 | `gor --input-raw :9000 --output-http "http://target:9000|2"` 2倍速回放 |
| JVM监控 | JMX + Prometheus | Runtime MBean采集GC/堆/线程指标 |
| 慢查询优化 | MySQL slow_query_log + Explain | >100ms查询→Explain分析→加索引/改SQL |
| 热点Key优化 | 逻辑过期 + 本地缓存 | 热点Key→Caffeine缓存→逻辑过期防击穿 |
| 连接池泄漏检测 | HikariCP leakDetectionThreshold=60000 | 连接借出>60秒未还→WARN日志 |

---

### 专题 25：监控告警体系

#### 3.25.1 功能描述

构建完整的三维可观测性体系：①Metrics指标（Prometheus采集+Grafana展示）；②Tracing链路追踪（SkyWalking全链路追踪）；③Logging日志（JSON结构化+TraceId关联+Loki收集）。告警规则覆盖系统级+业务级指标。

#### 3.25.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| 全部服务 | 监控对象 | 指标暴露（Micrometer）、链路追踪（SkyWalking Agent）、日志输出 |
| Prometheus | 指标采集 | 抓取各服务/metrics端点 |
| Grafana | 可视化 | Dashboard展示指标+告警 |
| SkyWalking | 链路追踪 | Java Agent注入+OAP分析 |
| Loki | 日志聚合 | Promtail采集→Loki存储→Grafana查询 |

#### 3.25.3 监控指标清单

| 类别 | 指标 | 采集方式 | 告警条件 |
|------|------|----------|----------|
| **系统级** | CPU使用率 | Prometheus Node Exporter | >85% 持续5min → P1告警 |
| | 内存使用率 | Prometheus Node Exporter | >90% 持续5min → P1告警 |
| | 磁盘使用率 | Prometheus Node Exporter | >85% → P2告警 |
| | 网络丢包率 | Prometheus Node Exporter | >1% → P2告警 |
| **应用级** | QPS | Micrometer + Prometheus | >限流阈值 → 已触发限流 |
| | 响应时间(P99) | Micrometer + Prometheus | P99 > 500ms → P2告警 |
| | 错误率(5xx) | Micrometer + Prometheus | >1% 持续5min → P1告警 |
| | GC暂停时间 | Micrometer + Prometheus | P99 > 200ms → P2告警 |
| | 线程池活跃度 | Micrometer + Prometheus | 活跃/最大 > 90% → P2告警 |
| | HikariCP连接使用率 | Micrometer + Prometheus | 活跃/最大 > 90% → P2告警 |
| **中间件** | Redis内存使用 | redis_exporter | >maxmemory 80% → P2告警 |
| | MySQL连接数 | mysqld_exporter | >max_connections 80% → P2告警 |
| | MySQL复制延迟 | mysqld_exporter | >5s → P1告警 |
| | RocketMQ积压 | 自定义指标 | >10000 → P1告警 |
| | ES集群状态 | elasticsearch_exporter | status=red → P0告警 |
| **业务级** | 下单失败率 | 业务指标 | >5% → P1告警 |
| | 支付超时率 | 业务指标 | >1% → P1告警 |
| | 库存负数 | 业务指标 | any → P0告警 |
| | 登录失败率 | 业务指标 | >10% → P2告警 |

#### 3.25.4 告警分级与响应

| 级别 | 定义 | 响应时间 | 通知方式 |
|------|------|----------|----------|
| P0 | 服务不可用/数据损坏 | 5分钟内响应 | 电话+IM+短信 |
| P1 | 核心功能受损/性能严重下降 | 15分钟内响应 | IM+短信 |
| P2 | 非核心功能受损/性能轻微下降 | 1小时内响应 | IM通知 |
| P3 | 优化建议/预警 | 24小时内响应 | IM通知 |

#### 3.25.5 Grafana Dashboard 清单

| Dashboard | 面板 | 说明 |
|-----------|------|------|
| 系统总览 | CPU/内存/磁盘/网络 | 物理机级别监控 |
| 微服务概览 | QPS/RT/错误率/实例数 | Spring Boot Admin风格 |
| JVM详情 | 堆/非堆/GC/线程 | 各服务JVM对比 |
| MySQL监控 | QPS/慢查询/连接数/复制延迟 | 数据库级别 |
| Redis监控 | 内存/命中率/连接数/慢日志 | 缓存级别 |
| RocketMQ监控 | 生产/消费TPS/积压/延迟 | 消息级别 |
| 业务大盘 | 订单量/支付量/用户活跃 | 业务级别 |

#### 3.25.6 Java 文件清单

**common/metrics/**
```
MetricsConfig.java            — Micrometer配置（自定义指标注册+标签注入）
BusinessMetrics.java          — 业务指标定义（order_count/payment_count/login_count）
```

**common/logging/**
```
LoggingConfig.java            — 日志配置（JSON结构化+TraceId MDC注入）
TraceIdFilter.java            — TraceId过滤器（MDC.put("traceId", ...)）
```

**common/skywalking/**
```
SkyWalkingConfig.java         — SkyWalking Agent配置（服务名+采样率+插件）
```

#### 3.25.7 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 指标采集 | Micrometer + Prometheus | @Timed/@Counted注解→Micrometer→Prometheus scrape |
| 链路追踪 | SkyWalking Agent | javaagent注入→自动拦截HTTP/RPC/MQ→生成Trace/Span |
| 日志关联 | MDC TraceId | Filter注入TraceId→MDC→logback输出→Loki按TraceId查询 |
| 告警规则 | Prometheus AlertManager + Grafana Alerts | 规则配置→AlertManager路由→通知渠道 |
| JSON结构化日志 | Logback + JSON Encoder | 统一格式（timestamp/level/traceId/service/message） |
| 业务指标 | 自定义Counter/Gauge | 下单数/支付数/活跃用户→Prometheus→Grafana业务大盘 |

---

### 专题 26：分库分表实战

#### 3.26.1 功能描述

订单和优惠券表数据量大（订单预估10亿+、优惠券领取预估5亿+），单库单表无法支撑，需要使用 ShardingSphere 进行分库分表。结合 Phase-4 引入的 CosId 雪花ID替代MySQL自增ID，保证分片后ID全局唯一且有序。

#### 3.26.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-order | 分库分表 | 订单表按 buyer_id 分4库×16表=64表 |
| my-xhs-coupon | 分库分表 | 优惠券领取记录按 user_id 分2库×8表=16表 |
| my-xhs-common | 基础组件 | CosId雪花ID（已实现）、ShardingSphere配置模板 |
| my-xhs-im | 分表 | t_chat按send_uid分64表（Phase-4已梳理） |

#### 3.26.3 分库分表策略

**订单表（t_order）**

| 维度 | 策略 | 说明 |
|------|------|------|
| 分片键 | buyer_id | 买家ID（查自己的订单，按买家分片效率最高） |
| 分片算法 | buyer_id % 4 (库) × buyer_id % 16 (表) | 取模分片 |
| 库数×表数 | 4库×16表 = 64表/库 | my_xhs_order_0 ~ my_xhs_order_3 |
| 表命名 | t_order_0 ~ t_order_15 | 每库16张表 |
| ID生成 | CosId雪花ID | 全局唯一有序，替代MySQL自增 |

**优惠券领取记录（t_coupon_user）**

| 维度 | 策略 | 说明 |
|------|------|------|
| 分片键 | user_id | 用户ID |
| 分片算法 | user_id % 2 (库) × user_id % 8 (表) | 取模分片 |
| 库数×表数 | 2库×8表 = 16表/库 | my_xhs_coupon_0 ~ my_xhs_coupon_1 |
| 表命名 | t_coupon_user_0 ~ t_coupon_user_7 | 每库8张表 |
| ID生成 | CosId雪花ID | 全局唯一有序 |

#### 3.26.4 ShardingSphere 配置示例

```yaml
# order-service application.yml
spring:
  shardingsphere:
    datasource:
      names: ds0,ds1,ds2,ds3
      ds0:
        type: com.zaxxer.hikari.HikariDataSource
        jdbc-url: jdbc:mysql://localhost:3306/my_xhs_order_0?...
        username: root
        password: root
      ds1:
        # ... 同上，指向 my_xhs_order_1
      ds2:
        # ... 同上，指向 my_xhs_order_2
      ds3:
        # ... 同上，指向 my_xhs_order_3
    rules:
      sharding:
        tables:
          t_order:
            actual-data-nodes: ds$->{0..3}.t_order_$->{0..15}
            database-strategy:
              standard:
                sharding-column: buyer_id
                sharding-algorithm-name: order-db-mod
            table-strategy:
              standard:
                sharding-column: buyer_id
                sharding-algorithm-name: order-tb-mod
        sharding-algorithms:
          order-db-mod:
            type: MOD
            props:
              sharding-count: 4
          order-tb-mod:
            type: MOD
            props:
              sharding-count: 16
```

#### 3.26.5 Java 文件清单

**order/sharding/**
```
ShardingConfig.java           — ShardingSphere分片配置类
OrderShardingAlgorithm.java   — 自定义分片算法（如需特殊路由逻辑）
```

**coupon/sharding/**
```
ShardingConfig.java           — ShardingSphere分片配置类
CouponShardingAlgorithm.java  — 自定义分片算法
```

#### 3.26.6 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 分片键选择 | buyer_id/user_id | 按买家/用户维度路由，避免跨库查询 |
| 分片算法 | 取模(MOD) | buyer_id % 库数 → 库路由，buyer_id % 表数 → 表路由 |
| ID唯一性 | CosId雪花ID | 替代MySQL自增ID，分片后仍全局唯一有序 |
| 非分片键查询 | 广播表/绑定表/冗余字段 | 卖家查订单→按seller_id冗余写入+索引 |
| 分库连接管理 | ShardingSphereDataSource | 代理模式，应用无感知分库 |
| 读写分离 | ShardingSphere + MySQL主从 | 主库写→从库读，Phase-1已规划 |

---

### 专题 27：Canal数据同步

#### 3.27.1 功能描述

Canal 通过监听 MySQL Binlog 实现增量数据同步，解决两个核心问题：①ES索引增量更新（MySQL变更→Canal→MQ→ES）；②缓存一致性兜底（MySQL变更→Canal→MQ→删除/更新缓存）。同时支持全量索引重建（XXL-Job分页断点续传）。Canal 断点续传确保数据不丢失。

#### 3.27.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-product | 数据源 | 商品表变更→Canal监听→更新ES索引+更新缓存 |
| my-xhs-content | 数据源 | 笔记表变更→Canal监听→更新ES索引+更新缓存 |
| my-xhs-search | 数据消费 | Canal Consumer写入ES索引 |
| my-xhs-common | 基础组件 | Canal客户端封装、断点续传管理 |

#### 3.27.3 Canal 同步流程

```
MySQL Binlog
    │
    ▼
┌──────────────────────┐
│ Canal Server         │
│ 监听Binlog事件       │
│ INSERT/UPDATE/DELETE │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│ Canal Client         │
│ (嵌入各服务或独立服务)│
│ 解析Binlog事件       │
│ 根据表名路由处理     │
└──────┬───────┬───────┘
       │       │
  ┌────▼──┐ ┌──▼────┐
  │ MQ    │ │ MQ    │
  │product│ │note   │
  └────┬──┘ └──┬────┘
       │       │
       ▼       ▼
  Consumer  Consumer
  更新ES    更新ES
  product   note索引
  索引
```

#### 3.27.4 数据同步清单

| 源表 | 目标 | 同步方式 | 说明 |
|------|------|----------|------|
| t_spu / t_sku | ES product索引 | Canal增量→MQ→Consumer→ES upsert | 商品上下架→索引实时更新 |
| t_note | ES note索引 | Canal增量→MQ→Consumer→ES upsert | 笔记审核通过→索引实时更新 |
| t_spu / t_sku | Redis缓存 | Canal增量→MQ→Consumer→DEL缓存 | 商品更新→缓存失效 |
| t_note | Redis缓存 | Canal增量→MQ→Consumer→DEL缓存 | 笔记更新→缓存失效 |

#### 3.27.5 全量重建流程

```
XXL-Job定时任务（每天凌晨3点或手动触发）
    │
    ▼
┌───────────────────────────┐
│ 全量重建任务               │
│ 1. 分页查询MySQL           │
│    每页500条，断点续传      │
│ 2. 批量写入ES              │
│    BulkRequest批量索引     │
│ 3. 记录进度到Redis         │
│    canal:rebuild:{table}   │
│    {lastId, totalCount}    │
│ 4. 任务中断后可恢复        │
└───────────────────────────┘
```

#### 3.27.6 MQ Topic 清单

| Topic | 生产者 | 消费者 | 说明 |
|-------|--------|--------|------|
| `canal-product-sync` | Canal Client (product) | ES Consumer | 商品数据增量同步到ES |
| `canal-note-sync` | Canal Client (content) | ES Consumer | 笔记数据增量同步到ES |
| `canal-cache-invalidate` | Canal Client | Cache Consumer | 缓存失效通知（删除Redis Key） |

#### 3.27.7 Java 文件清单

**common/canal/**
```
CanalHelper.java              — Canal客户端封装（启动/停止/断点续传/位点管理）
CanalEventListener.java       — Canal事件监听器基类（根据表名+事件类型分发）
CanalDataHandler.java         — Canal数据处理接口（processInsert/processUpdate/processDelete）
```

**product/canal/**
```
ProductCanalHandler.java      — 商品Canal处理器（INSERT/UPDATE→发MQ→更新ES+缓存）
```

**content/canal/**
```
NoteCanalHandler.java         — 笔记Canal处理器（INSERT/UPDATE→发MQ→更新ES+缓存）
```

**search/canal/**
```
CanalSyncConsumer.java        — Canal同步消费者（接收MQ→ES BulkRequest写入）
FullRebuildJob.java           — 全量重建任务（XXL-Job调度+分页断点续传）
```

#### 3.27.8 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| Binlog监听 | Canal Server + Client | Canal伪装MySQL从库→接收Binlog→解析事件 |
| 增量同步 | Canal → MQ → Consumer → ES/Cache | 异步解耦，MQ保证可靠投递 |
| 全量重建 | XXL-Job分页 + 断点续传 | 分页查询MySQL→批量写入ES→进度存Redis→中断可恢复 |
| 断点续传 | Canal位点管理 + Redis记录 | Canal记录binlog位点→重启后从位点继续消费 |
| 幂等消费 | @Idempotent + ES upsert | 防止MQ重复消费导致ES重复索引 |
| 数据格式 | Canal JSON → 自定义DTO | 统一Canal事件格式：{table, type, before, after, pk} |

---

### 专题 28：优雅停机与服务治理

#### 3.28.1 功能描述

生产环境服务上下线需要优雅处理，避免请求丢失和连接中断。实现：①优雅停机（SIGTERM→拒绝新请求→等待存量请求完成→断开连接→退出）；②Nacos优雅下线（主动注销实例→等待网关路由更新→停止服务）；③健康检查（Spring Boot Actuator + Nacos）；④异常分级处理（P0~P3故障分级响应）。

#### 3.28.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| 全部服务 | 停机对象 | 优雅停机配置 |
| my-xhs-gateway | 路由感知 | Nacos实例下线→网关路由实时更新→不再转发 |
| my-xhs-im | 特殊处理 | WebSocket连接需要优雅断开（发送Close帧→等待确认→关闭） |
| Nacos | 注册中心 | 实例注销→网关感知→路由摘除 |

#### 3.28.3 优雅停机流程

```
SIGTERM / kill -15
    │
    ▼
┌──────────────────────────────────────┐
│ Spring Boot 优雅停机                  │
│ server.shutdown=graceful              │
│ spring.lifecycle.timeout-per-shutdown │
│ -phase=30s                            │
│                                       │
│ 1. Web容器停止接受新请求              │
│ 2. 等待存量请求完成（最多30s）        │
│ 3. 关闭数据库连接池                   │
│ 4. 关闭Redis连接                      │
│ 5. 关闭MQ消费者（NACK未处理消息）     │
│ 6. 主动注销Nacos实例                  │
│ 7. 发送WebSocket Close帧（IM服务）    │
│ 8. 关闭线程池                         │
│ 9. 退出进程                           │
└──────────────────────────────────────┘
```

#### 3.28.4 Nacos 优雅下线流程

```
服务主动下线
    │
    ▼
┌──────────────────────────────┐
│ 1. 调用Nacos API注销实例     │
│    /nacos/v1/ns/instance     │
│    DELETE                     │
│                               │
│ 2. Nacos推送实例变更事件     │
│                               │
│ 3. Gateway接收Nacos事件      │
│    更新路由表                 │
│    摘除下线实例               │
│                               │
│ 4. 等待路由刷新完成           │
│    （通常<5秒）               │
│                               │
│ 5. 停止服务进程               │
└──────────────────────────────┘
```

#### 3.28.5 异常分级处理

| 级别 | 定义 | 影响 | 响应时间 | 处理方式 |
|------|------|------|----------|----------|
| P0 | 服务完全不可用/数据损坏 | 核心业务中断 | 5分钟 | 立即回滚/切换灾备/重启 |
| P1 | 核心功能受损/性能严重下降 | 用户可感知 | 15分钟 | 限流降级/扩容/修复 |
| P2 | 非核心功能受损/性能轻微下降 | 部分用户受影响 | 1小时 | 排查修复/重启 |
| P3 | 优化建议/预警 | 暂无影响 | 24小时 | 排期修复 |

#### 3.28.6 各组件HA方案

| 组件 | HA方案 | 故障切换时间 |
|------|--------|------------|
| MySQL | 1主1从 + MHA自动切换 | <30秒 |
| Redis | Sentinel 3哨兵 + 故障转移 | <15秒 |
| Nacos | 3节点集群 + Raft选举 | <30秒 |
| RocketMQ | 主从同步 + Dledger自动切换 | <30秒 |
| Elasticsearch | 3节点集群 + 副本分片 | 节点故障自动恢复 |
| Gateway | 多实例 + Nacos负载均衡 | 实例下线后路由实时更新 |

#### 3.28.7 Java 文件清单

**common/shutdown/**
```
GracefulShutdownConfig.java    — 优雅停机配置（server.shutdown=graceful + 超时30s）
NacosShutdownHook.java        — Nacos注销钩子（preStop: 注销实例→sleep 5s→退出）
```

**im/shutdown/**
```
ImGracefulShutdown.java       — IM优雅停机（发送WebSocket Close帧→等待连接关闭→停机）
```

**common/health/**
```
HealthCheckConfig.java        — 健康检查配置（Actuator端点+读写探针）
```

#### 3.28.8 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 优雅停机 | Spring Boot graceful shutdown | server.shutdown=graceful + lifecycle.timeout-per-shutdown-phase=30s |
| Web容器停机 | 拒绝新请求+等待存量请求完成 | Embedded Tomcat/Netty的graceful shutdown |
| Nacos注销 | preStop钩子 | @PreDestroy / ShutdownHook → Nacos API注销 → sleep → 退出 |
| WebSocket断开 | 发送Close帧 | CloseStatusCode.NORMAL → 等待客户端确认 → 关闭Session |
| MQ消费者关闭 | NACK未处理消息 | RocketMQ消费者shutdown→未处理消息NACK→Broker重新投递 |
| 数据库连接关闭 | HikariCP close | 等待活跃事务完成→关闭连接 |
| 健康检查 | Actuator + K8s liveness/readiness | readiness=0→K8s不再转发请求→liveness=0→K8s重启Pod |

---

## 四、中间件需求

### 4.1 新增中间件

| 中间件 | 版本 | 用途 | 所属专题 |
|--------|------|------|----------|
| Canal | 1.1.7 | Binlog监听→缓存/ES同步 | 21-缓存一致性/27-Canal数据同步 |
| Prometheus | 2.x | 指标采集 | 25-监控告警 |
| Grafana | 10.x | 可视化Dashboard | 25-监控告警 |
| SkyWalking | 9.7.0 | 全链路追踪 | 25-监控告警 |
| Loki | 2.x | 日志聚合 | 25-监控告警 |
| Promtail | 2.x | 日志采集 | 25-监控告警 |
| JMeter | 5.6 | 压测工具 | 24-性能压测 |
| GoReplay | 1.x | 流量录制回放 | 24-性能压测 |
| ChaosBlade | 1.7 | 故障注入 | Phase-6专题（Phase-5预备） |

### 4.2 已有中间件（本阶段新用途）

| 中间件 | 版本 | 新增用途 | 所属专题 |
|--------|------|----------|----------|
| Redis | 7.x | 分布式锁（缓存一致性）、影子表路由标记（流量染色）、本地消息表状态（分布式事务） | 21/22/23 |
| RocketMQ | 5.x | 事务消息（分布式事务）、Canal增量同步（Canal数据同步）、告警通知（监控告警） | 22/27/25 |
| Nacos | 2.x | 优雅下线实例注销（优雅停机）、灰度实例标记（流量染色）、Sentinel规则数据源（监控告警） | 28/23/25 |
| ShardingSphere | 5.4.1 | 分库分表路由（影子库/影子表）、订单/优惠券分片（分库分表实战） | 23/26 |
| Sentinel | 1.8.7 | 监控指标暴露（监控告警）、限流规则动态加载 | 25 |

### 4.3 中间件版本总表（含Phase-5新增）

| 中间件 | 版本 | 端口 | 说明 | 引入Phase |
|--------|------|------|------|----------|
| MySQL | 8.0+ | 3306 | 主存储 | 1 |
| Redis | 7.x | 6379 | 缓存+分布式锁+幂等+限流+会话+影子路由 | 1 |
| Nacos | 2.x | 8848 | 注册中心+配置中心+灰度标记+限流规则 | 1 |
| RocketMQ | 5.x | 9876 | 异步消息+事务消息+Canal同步 | 2 |
| Elasticsearch | 8.12 | 9200 | 搜索引擎 | 3 |
| Sentinel | 1.8.7 | 8080(控制台) | 限流熔断+监控指标 | 4 |
| ShardingSphere | 5.4.1 | — | 分库分表+影子表路由 | 4 |
| Canal | 1.1.7 | 11111 | Binlog监听→缓存/ES同步 | **5** |
| Prometheus | 2.x | 9090 | 指标采集 | **5** |
| Grafana | 10.x | 3000 | 可视化Dashboard | **5** |
| SkyWalking | 9.7.0 | 11800/12800 | 全链路追踪 | **5** |
| Loki | 2.x | 3100 | 日志聚合 | **5** |
| Promtail | 2.x | — | 日志采集 | **5** |

---

## 五、MQ Topic 清单

| Topic | 生产者 | 消费者 | 说明 | 所属专题 |
|-------|--------|--------|------|----------|
| `order-transaction` | OrderService | Inventory/Coupon/Payment | 事务消息：下单→扣库存→扣券→创支付单 | 22-分布式事务 |
| `order-transaction-dlq` | 消费失败3次后进入 | DLQConsumer | 死信队列：人工处理 | 22-分布式事务 |
| `canal-product-sync` | Canal Client (product) | ES Consumer | 商品数据增量同步到ES | 27-Canal数据同步 |
| `canal-note-sync` | Canal Client (content) | ES Consumer | 笔记数据增量同步到ES | 27-Canal数据同步 |
| `canal-cache-invalidate` | Canal Client | Cache Consumer | 缓存失效通知（删除Redis Key） | 27-Canal数据同步 |

> Phase 1/2/3/4 的 Topic 不再重复列出。

---

## 六、Feign 调用关系

### 6.1 分布式事务链路

| 调用方 | 被调用方 | 方法 | 用途 | 所属专题 |
|--------|---------|------|------|----------|
| Order | Inventory | `deductStock(orderId, skuId, quantity)` | 扣减库存 | 22-分布式事务 |
| Order | Coupon | `useCoupon(orderId, couponId, userId)` | 使用优惠券 | 22-分布式事务 |
| Order | Payment | `createPayment(orderId, amount, payMethod)` | 创建支付单 | 22-分布式事务 |

### 6.2 Canal同步链路

| 调用方 | 被调用方 | 方法 | 用途 | 所属专题 |
|--------|---------|------|------|----------|
| Canal Consumer | Search (ES) | `upsertProductIndex(productDTO)` | 增量更新商品ES索引 | 27-Canal |
| Canal Consumer | Search (ES) | `upsertNoteIndex(noteDTO)` | 增量更新笔记ES索引 | 27-Canal |

### 6.3 流量染色透传

| 调用方 | 被调用方 | 透传方式 | 用途 | 所属专题 |
|--------|---------|----------|------|----------|
| Gateway | 所有下游服务 | HTTP Header透传 | 染色标记传递 | 23-流量染色 |
| 任何服务 | 任何下游服务 | Feign RequestInterceptor | 染色标记透传 | 23-流量染色 |
| 任何服务 | MQ Consumer | MQ Message Header | 染色标记透传 | 23-流量染色 |

> Phase 1/2/3/4 的 Feign 调用关系不再重复列出。

---

## 七、降级规范

| 服务 | 降级策略 | 返回值 | 所属专题 |
|------|---------|--------|----------|
| Inventory | 降级返回库存不足 | `{stock: 0, available: false}` | 22-分布式事务 |
| Coupon | 降级返回优惠券不可用 | `{available: false, reason: "服务降级"}` | 22-分布式事务 |
| Payment | 降级返回支付失败 | `{status: "fail", reason: "服务降级"}` | 22-分布式事务 |
| Search (ES) | 降级返回空搜索结果 | `{total: 0, hits: []}` | 27-Canal |
| Grafana Dashboard | 降级返回静态缓存数据 | 上一次成功采集的数据 | 25-监控告警 |
| SkyWalking | Agent降级关闭链路追踪 | 不注入Agent | 25-监控告警 |

> Phase 1/2/3/4 的降级规范不再重复列出。

---

## 八、公共组件复用

| 组件 | 模块 | 新增用途 | 所属专题 |
|------|------|----------|----------|
| @Idempotent | my-xhs-common | 分布式事务消费幂等 | 22-分布式事务 |
| @DistributedLock | my-xhs-common | 缓存更新并发锁、影子表路由锁 | 21/23/26 |
| @RateLimit | my-xhs-common | 性能压测场景限流 | 24-性能压测 |
| IdGeneratorUtil | my-xhs-common | 本地消息表ID、分库分表ID | 22/26 |
| R\<T\> | my-xhs-common | 统一响应（降级返回也用此格式） | 全部专题 |
| BaseEntity | my-xhs-common | 本地消息表基类 | 22-分布式事务 |
| UserContextHolder | my-xhs-common | TraceContext复用ThreadLocal | 23-流量染色 |
| SpELParser | my-xhs-common | 影子表名动态解析 | 23-流量染色 |
| JsonUtil | my-xhs-common | Canal事件JSON解析 | 27-Canal |
| RedisUtil | my-xhs-common | 缓存删除/更新封装 | 21/27 |

### 新增公共组件

| 组件 | 模块 | 说明 | 所属专题 |
|------|------|------|----------|
| CacheHelper | my-xhs-common | 缓存操作封装（Cache Aside + 延迟双删） | 21-缓存一致性 |
| DelayDeleteHelper | my-xhs-common | 延迟双删封装 | 21-缓存一致性 |
| CanalHelper | my-xhs-common | Canal客户端封装 | 21/27 |
| TransactionHelper | my-xhs-common | 事务消息发送封装 | 22-分布式事务 |
| LocalMessage | my-xhs-common | 本地消息表实体 | 22-分布式事务 |
| TraceContext | my-xhs-common | 染色上下文（ThreadLocal） | 23-流量染色 |
| ShadowDataSource | my-xhs-common | 影子数据源路由 | 23-流量染色 |
| TraceFeignRequestInterceptor | my-xhs-common | Feign透传拦截器 | 23-流量染色 |
| TraceMessagePostProcessor | my-xhs-common | MQ透传处理器 | 23-流量染色 |
| TraceTaskDecorator | my-xhs-common | 异步线程透传 | 23-流量染色 |
| MetricsConfig | my-xhs-common | Micrometer指标配置 | 25-监控告警 |
| BusinessMetrics | my-xhs-common | 业务指标定义 | 25-监控告警 |
| LoggingConfig | my-xhs-common | JSON结构化日志配置 | 25-监控告警 |
| GracefulShutdownConfig | my-xhs-common | 优雅停机配置 | 28-优雅停机 |
| NacosShutdownHook | my-xhs-common | Nacos注销钩子 | 28-优雅停机 |

---

## 九、实现步骤

### Step 1：缓存一致性方案（2天）

- [ ] common补齐 `CacheHelper.java`（get/set/delete + Cache Aside封装）
- [ ] common补齐 `DelayDeleteHelper.java`（延迟双删：scheduleDelay+分布式锁）
- [ ] common补齐 `CacheAsideProcessor.java`（模板方法：更新DB→删缓存→异常处理）
- [ ] common补齐 `CanalHelper.java`（Canal客户端封装：启动/停止/断点续传）
- [ ] product服务集成Cache Aside（SPU/SKU更新→删缓存）
- [ ] product服务集成延迟双删（上下架→双删）
- [ ] user/content服务集成Cache Aside
- [ ] 编写单元测试验证缓存一致性方案

### Step 2：分布式事务（2天）

- [ ] common补齐 `TransactionHelper.java`（事务消息发送封装）
- [ ] common补齐 `LocalMessage.java` + `LocalMessageMapper.java` + `LocalMessageService`
- [ ] order补齐 `OrderTransactionProducer.java` + `OrderTransactionListener.java`
- [ ] order补齐 `LocalMessageConsumer.java` + `DeadLetterConsumer.java`
- [ ] inventory/coupon/payment各补齐 `LocalMessageConsumer.java`
- [ ] 各参与者服务创建 `t_local_message` 表
- [ ] RocketMQ配置事务消息（事务回查+半消息+提交/回滚）
- [ ] 死信队列处理+人工介入告警
- [ ] XXL-Job定时任务扫描本地消息表重试失败记录
- [ ] 编写集成测试验证分布式事务端到端流程

### Step 3：全链路流量染色（2天）

- [ ] common补齐 `TraceContext.java` + `TraceContextHolder.java`（ThreadLocal: traceId+traceTag+userId）
- [ ] common补齐 `ShadowDataSource.java`（影子数据源路由）+ `ShadowTableSuffix.java`
- [ ] common补齐 `TraceFeignRequestInterceptor.java`（Feign Header透传）
- [ ] common补齐 `TraceMessagePostProcessor.java`（MQ Header透传）
- [ ] common补齐 `TraceTaskDecorator.java`（异步线程ThreadLocal透传）
- [ ] gateway补齐 `TraceGlobalFilter.java`（检测X-Trace-Tag→存入TraceContext）
- [ ] order/coupon配置ShardingSphere影子表路由（_shadow后缀表）
- [ ] 编写单元测试验证染色标记全链路透传
- [ ] 编写集成测试验证影子表读写隔离

### Step 4：性能优化与压测（2天）

- [ ] common补齐 `ThreadPoolConfig.java` + `HikariPoolConfig.java` + `JvmConfig.java`
- [ ] 编写JMeter压测脚本（5个场景）
- [ ] 执行第一轮压测，记录基线数据
- [ ] 分析瓶颈，逐步调优（JVM/连接池/缓存/异步）
- [ ] 执行第二轮压测，对比优化效果
- [ ] 输出压测报告（QPS/RT/P99/错误率）

### Step 5：监控告警体系（2天）

- [ ] 各服务集成Micrometer + Prometheus指标暴露
- [ ] 部署Prometheus + Grafana + SkyWalking + Loki + Promtail
- [ ] 配置Grafana Dashboard（7个Dashboard）
- [ ] 配置Prometheus告警规则（系统级+应用级+中间件级+业务级）
- [ ] 配置AlertManager告警路由（P0~P3分级通知）
- [ ] common补齐 `MetricsConfig.java` + `BusinessMetrics.java`
- [ ] common补齐 `LoggingConfig.java`（JSON结构化+TraceId MDC）
- [ ] common补齐 `SkyWalkingConfig.java`
- [ ] 验证全链路日志TraceId关联

### Step 6：分库分表实战（1.5天）

- [ ] order服务配置ShardingSphere分库分表（4库×16表）
- [ ] coupon服务配置ShardingSphere分库分表（2库×8表）
- [ ] 配置CosId雪花ID替代MySQL自增ID
- [ ] 创建分库数据库和分表
- [ ] 编写自定义分片算法（如需特殊路由逻辑）
- [ ] 编写测试验证分库分表路由正确性

### Step 7：Canal数据同步（1.5天）

- [ ] 部署Canal Server
- [ ] common补齐 `CanalEventListener.java` + `CanalDataHandler.java`
- [ ] product服务补齐 `ProductCanalHandler.java`（商品变更→MQ→ES+缓存）
- [ ] content服务补齐 `NoteCanalHandler.java`（笔记变更→MQ→ES+缓存）
- [ ] search服务补齐 `CanalSyncConsumer.java` + `FullRebuildJob.java`
- [ ] 配置Canal MQ Topic（canal-product-sync / canal-note-sync / canal-cache-invalidate）
- [ ] 验证增量同步：MySQL变更→Canal→MQ→ES更新
- [ ] 验证全量重建：XXL-Job分页断点续传
- [ ] 验证断点续传：Canal重启后从上次位点继续消费

### Step 8：优雅停机与服务治理（1天）

- [ ] 所有服务配置 `server.shutdown=graceful` + `lifecycle.timeout-per-shutdown-phase=30s`
- [ ] common补齐 `GracefulShutdownConfig.java`
- [ ] common补齐 `NacosShutdownHook.java`（preStop: 注销实例→sleep 5s→退出）
- [ ] im服务补齐 `ImGracefulShutdown.java`（WebSocket Close帧→等待→关闭）
- [ ] common补齐 `HealthCheckConfig.java`（Actuator + K8s探针）
- [ ] 配置各组件HA方案（MySQL MHA / Redis Sentinel / Nacos集群 / RocketMQ Dledger）
- [ ] 制定异常分级处理流程（P0~P3）
- [ ] 验证优雅停机：kill -15 → 存量请求完成 → Nacos注销 → 进程退出
- [ ] 验证Nacos优雅下线：注销实例 → 网关路由更新 → 不再转发

---

## 十、配置文件清单

### 10.1 my-xhs-common（❌ 待增强）

> common 模块无 application.yml，配置由各服务自行管理。common 仅提供注解、切面、工具类等代码级组件。

### 10.2 my-xhs-order（❌ 待增强，分库分表配置）

```yaml
spring:
  shardingsphere:
    datasource:
      names: ds0,ds1,ds2,ds3
      ds0:
        type: com.zaxxer.hikari.HikariDataSource
        jdbc-url: jdbc:mysql://localhost:3306/my_xhs_order_0?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
        username: root
        password: root
      ds1:
        type: com.zaxxer.hikari.HikariDataSource
        jdbc-url: jdbc:mysql://localhost:3306/my_xhs_order_1?...
      ds2:
        # ... my_xhs_order_2
      ds3:
        # ... my_xhs_order_3
    rules:
      sharding:
        tables:
          t_order:
            actual-data-nodes: ds$->{0..3}.t_order_$->{0..15}
            database-strategy:
              standard:
                sharding-column: buyer_id
                sharding-algorithm-name: order-db-mod
            table-strategy:
              standard:
                sharding-column: buyer_id
                sharding-algorithm-name: order-tb-mod
        sharding-algorithms:
          order-db-mod:
            type: MOD
            props:
              sharding-count: 4
          order-tb-mod:
            type: MOD
            props:
              sharding-count: 16
  # 优雅停机
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
  # 监控指标
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

### 10.3 my-xhs-coupon（❌ 待增强，分库分表配置）

```yaml
spring:
  shardingsphere:
    datasource:
      names: ds0,ds1
      ds0:
        type: com.zaxxer.hikari.HikariDataSource
        jdbc-url: jdbc:mysql://localhost:3306/my_xhs_coupon_0?...
      ds1:
        # ... my_xhs_coupon_1
    rules:
      sharding:
        tables:
          t_coupon_user:
            actual-data-nodes: ds$->{0..1}.t_coupon_user_$->{0..7}
            database-strategy:
              standard:
                sharding-column: user_id
                sharding-algorithm-name: coupon-db-mod
            table-strategy:
              standard:
                sharding-column: user_id
                sharding-algorithm-name: coupon-tb-mod
        sharding-algorithms:
          coupon-db-mod:
            type: MOD
            props:
              sharding-count: 2
          coupon-tb-mod:
            type: MOD
            props:
              sharding-count: 8
```

### 10.4 my-xhs-gateway（❌ 待增强，流量染色+监控）

```yaml
# 在现有配置基础上追加：

# 流量染色标记注入
gateway:
  trace:
    shadow-header: X-Trace-Tag
    shadow-value: shadow

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

### 10.5 各服务通用追加配置（监控+优雅停机+日志）

```yaml
# 所有业务服务需追加以下配置：

# 优雅停机
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s

# 监控指标
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true  # K8s liveness/readiness探针

# JSON结构化日志
logging:
  pattern:
    console: '{"time":"%d","level":"%p","traceId":"%X{traceId}","service":"${spring.application.name}","msg":"%m"}%n'
```

---

## 十一、骨架问题清单

| # | 问题 | 说明 | 状态 |
|---|------|------|------|
| 1 | common模块缺CacheHelper等缓存一致性组件 | 需补齐CacheHelper、DelayDeleteHelper、CacheAsideProcessor | ❌ 待实现 |
| 2 | common模块缺CanalHelper及Canal事件处理 | 需补齐CanalHelper、CanalEventListener、CanalDataHandler | ❌ 待实现 |
| 3 | common模块缺TransactionHelper及本地消息表 | 需补齐TransactionHelper、LocalMessage、LocalMessageMapper、LocalMessageService | ❌ 待实现 |
| 4 | common模块缺TraceContext及流量染色透传组件 | 需补齐TraceContext、ShadowDataSource、TraceFeignRequestInterceptor、TraceMessagePostProcessor、TraceTaskDecorator | ❌ 待实现 |
| 5 | common模块缺MetricsConfig及BusinessMetrics | 需补齐MetricsConfig、BusinessMetrics | ❌ 待实现 |
| 6 | common模块缺LoggingConfig及TraceIdFilter | 需补齐LoggingConfig（JSON结构化+TraceId MDC）、TraceIdFilter | ❌ 待实现 |
| 7 | common模块缺GracefulShutdownConfig及NacosShutdownHook | 需补齐GracefulShutdownConfig、NacosShutdownHook | ❌ 待实现 |
| 8 | gateway缺TraceGlobalFilter | 需补齐流量染色标记检测和注入 | ❌ 待实现 |
| 9 | order缺事务消息生产者和监听器 | 需补齐OrderTransactionProducer、OrderTransactionListener | ❌ 待实现 |
| 10 | order缺本地消息消费者和死信消费者 | 需补齐LocalMessageConsumer、DeadLetterConsumer | ❌ 待实现 |
| 11 | order/coupon缺ShardingSphere分库分表配置 | 需配置分片策略和分片算法 | ❌ 待实现 |
| 12 | order/coupon缺分库数据库和分表 | 需创建my_xhs_order_0~3、my_xhs_coupon_0~1及分表 | ❌ 待创建 |
| 13 | 参与者服务缺本地消息表 | inventory/coupon/payment需创建t_local_message表 | ❌ 待创建 |
| 14 | product/content缺Canal处理器 | 需补齐ProductCanalHandler、NoteCanalHandler | ❌ 待实现 |
| 15 | search缺Canal同步消费者和全量重建任务 | 需补齐CanalSyncConsumer、FullRebuildJob | ❌ 待实现 |
| 16 | im缺优雅停机WebSocket处理 | 需补齐ImGracefulShutdown | ❌ 待实现 |
| 17 | 所有服务缺优雅停机和监控配置 | 需追加server.shutdown=graceful + prometheus暴露 | ❌ 待配置 |
| 18 | Canal Server未部署 | 需部署Canal Server并配置Binlog监听 | ❌ 待部署 |
| 19 | Prometheus/Grafana/SkyWalking/Loki未部署 | 需部署监控告警全套基础设施 | ❌ 待部署 |
| 20 | JMeter压测脚本未编写 | 需编写5个核心场景的压测脚本 | ❌ 待编写 |

---

## 十二、数据库变更清单

### 12.1 新建表

| 数据库 | 表名 | 说明 | 所属专题 |
|--------|------|------|----------|
| my_xhs_order_0~3 | t_local_message | 订单服务本地消息表 | 22-分布式事务 |
| my_xhs_coupon_0~1 | t_local_message | 优惠券服务本地消息表 | 22-分布式事务 |
| my_xhs_inventory | t_local_message | 库存服务本地消息表 | 22-分布式事务 |
| my_xhs_payment | t_local_message | 支付服务本地消息表 | 22-分布式事务 |

### 12.2 分库分表

| 数据库 | 原表 | 分表 | 说明 |
|--------|------|------|------|
| my_xhs_order_0~3 | t_order | t_order_0~15（每库16张） | 按 buyer_id 取模分片 |
| my_xhs_coupon_0~1 | t_coupon_user | t_coupon_user_0~7（每库8张） | 按 user_id 取模分片 |

### 12.3 影子表

| 数据库 | 生产表 | 影子表 | 说明 |
|--------|--------|--------|------|
| my_xhs_order_0~3 | t_order_0~15 | t_order_shadow_0~15 | 压测数据写入影子表 |
| my_xhs_coupon_0~1 | t_coupon_user_0~7 | t_coupon_user_shadow_0~7 | 压测数据写入影子表 |

---

## Phase 5 核心技术点

- 缓存一致性三重保障（Cache Aside + 延迟双删 + Canal兜底）
- 分布式事务最终一致性（RocketMQ事务消息 + 本地消息表 + 死信队列）
- 全链路流量染色（TraceContext ThreadLocal + Feign/MQ/异步线程透传 + 影子表/影子库）
- 分库分表实战（ShardingSphere + CosId雪花ID + 取模分片 + 影子表）
- Canal数据同步（Binlog→MQ→ES/缓存 + 全量重建断点续传）
- 完整可观测性体系（Prometheus + Grafana + SkyWalking + Loki + 告警分级）
- 优雅停机与服务治理（graceful shutdown + Nacos注销 + 异常分级P0~P3）

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
- [21-缓存一致性方案](./a-前置知识-缓存一致性方案.md)
- [21-缓存一致性方案](./b-问题驱动实现-缓存一致性方案.md)
- [21-缓存一致性方案](./c-现状梳理-缓存一致性方案.md)
-->

> 📌 待编写：当前尚无已完成文档，请按文档编写规范依次创建。

> 📌 文档编写要求：必须参考本地真实框架源码（如Nacos/Dubbo/ShardingSphere/Canal），不能凭空设计。"问题驱动实现"是最重要的文档。