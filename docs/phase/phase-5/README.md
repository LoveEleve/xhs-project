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

#### 3.24.7 JVM 调优深度指南（P0 必读）

> Stage-3 课程 007 讲了 JVM 基础，但缺少容器化场景深度和生产故障排查实战。以下是批判性补充。

##### （1）JVM 内存模型全景

```
+-------------------+  ← JVM 进程地址空间（受 cgroup memory.limit 限制）
|   Java Heap       |  ← -Xmx / -XX:MaxRAMPercentage
|   +---------------+
|   | Young Gen     |  ← -Xmn / G1:自适应Region分配
|   | +-----------+ |
|   | | Eden      | |
|   | | Survivor0 | |
|   | | Survivor1 | |
|   | +-----------+ |
|   | Old Gen      |  ← G1: Humongous Region / ZGC: 无分代
|   +---------------+
|   Non-Heap        |
|   +---------------+
|   | Metaspace     |  ← -XX:MaxMetaspaceSize（不受堆限制，受cgroup限制）
|   | Code Cache    |  ← -XX:ReservedCodeCacheSize（JIT编译代码）
|   +---------------+
|   Direct Memory   |  ← -XX:MaxDirectMemorySize（NIO ByteBuffer.allocateDirect）
|   Native Memory   |  ← 线程栈（-Xss）、JNI分配、GC内部数据结构
+-------------------+
```

**关键洞察**：JVM 实际内存占用 = Heap + Non-Heap + Native，远大于 -Xmx 设定值。容器化场景下，cgroup `memory.limit_in_bytes` 必须大于 JVM 全部内存占用，否则 OOMKilled。

##### （2）垃圾收集器选型对比（含容器化场景）

| 收集器 | 算法 | 暂停时间 | 适用场景 | 容器化适配性 |
|--------|------|---------|---------|------------|
| **G1** | 分区+增量标记 | 100-200ms（可调） | 通用场景，JDK9+默认 | ✅ 良好，-XX:MaxGCPauseMillis=200 控制目标暂停 |
| **ZGC** | 着色指针+读屏障 | <10ms（亚毫秒级） | 低延迟要求，JDK15+生产可用 | ✅✅ 最佳，几乎不阻塞请求，容器中推荐 |
| **Shenandoah** | Brooks指针+读屏障 | <10ms | 低延迟，JDK12+ | ✅ 良好，与ZGC类似 |
| **Parallel GC** | 吞吐优先 | 500ms+ | 批处理/离线计算 | ⚠️ 暂停长，容器中不推荐 |

**批判性思考**：小马哥课程重点讲 G1，但在容器化场景下，**ZGC 优势更明显**——
- G1 虽然可通过 MaxGCPauseMillis 控制暂停，但并发标记失败（Evacuation Failure）时仍会 Full GC，暂停秒级
- ZGC 的亚毫秒级暂停（JDK16+），对 SLA 要求 P99 < 100ms 的在线服务更友好
- 容器化环境推荐配置：`-XX:+UseZGC -XX:MaxRAMPercentage=75.0 -Xlog:gc*:file=/var/log/gc.log`

##### （3）容器化 JVM 调优（核心必读）

> 这是 Stage-3 课程未涉及的关键场景：JVM 运行在 cgroup 限制的容器中，而非裸机 VM。

| 问题 | 原因 | 解决方案 | 关键参数 |
|------|------|---------|---------|
| 容器 OOMKilled | JVM 占用内存 > cgroup limit | 精确计算 JVM 全部内存，留 25% 安全余量 | `-XX:MaxRAMPercentage=75.0`（替代 -Xmx） |
| JVM 看不到容器内存限制 | JDK8u191 前，JVM 读取宿主机内存而非容器 cgroup | 升级 JDK ≥ 8u191 或使用 JDK11+ | `-XX:+UseContainerSupport`（JDK10+默认开启） |
| GC 行为异常 | cgroup 限制 CPU 核数，但 JVM 按宿主机核数创建 GC 线程 | 限制 GC 线程数 | `-XX:ParallelGCThreads=4 -XX:ConcGCThreads=2` |
| 元空间泄漏导致 OOMKilled | Metaspace 不受 -Xmx 限制，超出 cgroup 限制 | 限制 Metaspace 大小 | `-XX:MaxMetaspaceSize=256m` |
| Direct Memory 导致 OOMKilled | NIO 直接内存不受 -Xmx 限制 | 限制直接内存大小 | `-XX:MaxDirectMemorySize=256m` |

**容器化 JVM 内存规划公式**：

```
cgroup memory.limit ≥ JVM Heap（MaxRAMPercentage * container memory）
                   + Metaspace（建议256m）
                   + Code Cache（建议128m，-XX:ReservedCodeCacheSize）
                   + Direct Memory（建议256m，-XX:MaxDirectMemorySize）
                   + Thread Stack（线程数 * -Xss，如200线程 * 1M = 200M）
                   + GC 内部开销（约 Heap * 10%）
                   + 25% 安全余量
```

**示例**：容器内存 4G → `-XX:MaxRAMPercentage=75.0`（Heap=3G） + Metaspace=256m + CodeCache=128m + DirectMemory=256m + 200线程 * 1M = 200m + GC开销≈300m ≈ 4.1G → 容器内存需调至 5G 或降低 MaxRAMPercentage 至 60.0。

**K8s 配置示例**：
```yaml
resources:
  requests:
    memory: "4Gi"
    cpu: "2"
  limits:
    memory: "5Gi"   # 必须 > JVM 全部内存占用
    cpu: "4"         # CPU limit 需要考虑 GC 线程数
```

##### （4）生产环境 JVM 故障排查全流程

> Stage-3 课程提了 jmap/jstack 命令，但缺少完整的线上排查实战流程。以下是补充。

**场景 A：OOM（内存溢出）排查**

```
1. 现象：应用抛出 java.lang.OutOfMemoryError，或容器 OOMKilled（dmesg | grep -i oom）
2. 定位步骤：
   a. 容器 OOMKilled → kubectl describe pod <pod-name> | grep OOMKilled
      → 调大 resources.limits.memory 或减少 JVM 内存配置
   b. Java 堆 OOM → 先开启 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/heap.hprof
      → jmap -histo <pid> 快速查看对象分布
      → Eclipse MAT / IntelliJ 分析 hprof 文件，找 Dominator Tree 中的大对象和 GC Roots
   c. Metaspace OOM → jstat -gcutil <pid> 看 MU 列，确认 Metaspace 使用量
      → -XX:MaxMetaspaceSize=256m 限制，避免无限增长
   d. Direct Buffer OOM → jstat -gcutil <pid> 看 NC 列（非堆使用量）
      → Arthas: heapdiag 找 DirectByteBuffer 分配
      → -XX:MaxDirectMemorySize=256m 限制
3. 根因：通常是内存泄漏（对象未释放）或配置不当（堆太小/容器限制太紧）
4. 修复：修复泄漏代码 / 调整 JVM 参数 / 调大容器内存限制
```

**场景 B：CPU 飙升排查**

```
1. 现象：top 命令显示 Java 进程 CPU 占用 > 90%，请求 RT 急剧上升
2. 定位步骤：
   a. top -H -p <pid> → 找到 CPU 最高的线程 ID
   b. printf "%x\n" <thread-id> → 转为十六进制
   c. jstack <pid> | grep <hex-thread-id> → 定位线程堆栈
   d. 常见原因：
      - 死循环（while(true) 无退出条件）
      - 正则表达式回溯（ReDoS，如 (a|a)* 匹配 "aaaa..."）
      - 加密/序列化大量数据（如 JSON 序列化超大对象）
      - GC 线程忙（频繁 GC → CPU 高），用 jstat -gcutil 确认
3. Arthas 快捷方式：
   a. thread -n 5 → 显示 CPU 最高的 5 个线程
   b. thread <thread-id> → 查看指定线程堆栈
   c. profiler start → 生成 CPU Flame Graph（火焰图），定位热点代码
4. 修复：修复死循环 / 优化正则 / 减少 GC 压力
```

**场景 C：死锁排查**

```
1. 现象：应用挂起，部分请求无响应，线程 BLOCKED
2. 定位步骤：
   a. jstack <pid> → 搜索 "Found one Java-level deadlock" 关键字
   b. Arthas: thread -b → 直接打印阻塞其他线程的线程
   c. 常见死锁模式：
      - 数据库连接死锁（两事务互相等待锁）
      - Java synchronized 循环等待（A 持有 lock1 等 lock2，B 持有 lock2 等 lock1）
      - ReentrantLock 未在 finally 中 unlock
3. 修复：保证锁获取顺序一致 / 缩小锁粒度 / 使用 tryLock(timeout) 替代 lock()
```

**场景 D：GC 问题排查**

```
1. 现象：应用 RT 波动，偶发性延迟飙升
2. 定位步骤：
   a. jstat -gcutil <pid> 1000 → 连续观察 GC 频率和耗时
   b. 关注指标：YGC 频率/耗时、FGC 频率/耗时、Old Gen 使用率
   c. GC 日志分析：-Xlog:gc*:file=/var/log/gc.log（JDK11+）或 -XX:+PrintGCDetails（JDK8）
      → GCEasy.io / GCViewer 分析日志
   d. Arthas: gclog 或 jfr 导出分析
3. 常见 GC 问题：
   - 频繁 YGC → 新生代太小，调大 -Xmn 或让 G1 自适应
   - 频繁 FGC → 内存泄漏或 Old Gen 太小，参考 OOM 排查
   - GC 暂停过长 → 考虑换 ZGC 或调低 -XX:MaxGCPauseMillis
4. JFR（Java Flight Recorder）实战：
   a. 启动时添加：-XX:+FlightRecorder -XX:+UnlockCommercialFeatures（JDK8）
      JDK11+ 无需解锁：-XX:+FlightRecorder
   b. 在线启动录制：jcmd <pid> JFR.start duration=60s filename=/var/log/recording.jfr
   c. 用 JDK Mission Control（JMC）打开 .jfr 文件，分析 GC、CPU、内存、线程等
5. 修复：调整 GC 参数 / 修复内存泄漏 / 换收集器
```

##### （5）JVM 参数推荐模板

**裸机/VM 场景（G1）**：
```bash
JAVA_OPTS="
  -XX:+UseG1GC
  -Xmx4g
  -Xms4g
  -XX:MaxGCPauseMillis=200
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/var/log/heap.hprof
  -Xlog:gc*:file=/var/log/gc.log:time,uptime,level,tags
  -Djava.security.egd=file:/dev/./urandom         # 加速 SecureRandom
"
```

> **注意**：JDK8 使用 `-XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:/var/log/gc.log` 替代 `-Xlog` 参数。my-xhs 使用 JDK17，无需兼容 JDK8。

**容器化场景（ZGC 推荐）**：
```bash
JAVA_OPTS="
  -XX:+UseZGC
  -XX:MaxRAMPercentage=75.0
  -XX:MaxMetaspaceSize=256m
  -XX:ReservedCodeCacheSize=128m
  -XX:MaxDirectMemorySize=256m
  -Xss1m
  -XX:ParallelGCThreads=4
  -XX:ConcGCThreads=2
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/var/log/heap.hprof
  -Xlog:gc*:file=/var/log/gc.log:time,uptime,level,tags
  -Djava.security.egd=file:/dev/./urandom
"
```

##### （6）Virtual Threads — Java 高并发的革命（P1 补充）

> Stage-3 课程 036 讲了现代 Java 变化，Virtual Threads 是其中最具革命性的特性。Spring Boot 3.2+ 已正式支持。

**传统线程模型 vs Virtual Threads**：

```
传统模型（Platform Thread = OS Thread）：
  200个并发请求 = 200个OS线程（每个1MB栈 = 200MB内存）
  → 线程数受OS限制，C10K问题需要NIO/Netty异步

Virtual Threads模型（Virtual Thread ≠ OS Thread）：
  10000个并发请求 = 10000个Virtual Threads → 复用少量Carrier Threads（=CPU核数）
  → 每个Virtual Thread只占几KB，百万级并发成为可能
  → 编程模型回归同步阻塞，无需CompletableFuture/WebFlux回调地狱
```

| 维度 | Platform Thread | Virtual Thread |
|------|----------------|---------------|
| 创建成本 | 1MB栈 + OS系统调用 | 几KB + 用户态创建 |
| 调度方式 | OS内核调度 | JVM用户态调度（ForkJoinPool） |
| 阻塞行为 | 阻塞OS线程（浪费资源） | 自动unmount，释放Carrier Thread |
| 适用场景 | CPU密集型 | I/O密集型（网络/数据库/文件） |
| 最大数量 | 几千（受OS限制） | 百万级 |
| JDK版本 | — | JDK 21（正式GA），JDK 19-20（Preview） |

**Spring Boot 3.2+ 启用 Virtual Threads**：

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true   # Tomcat每个请求使用Virtual Thread
```

或手动创建：
```java
// 方式1：工厂方法
Thread.ofVirtual().name("my-vthread").start(() -> {
    // 阻塞I/O操作不会浪费OS线程
    String result = httpClient.get("/api/data");
});

// 方式2：ExecutorService
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    // 提交10000个任务，每个任务一个Virtual Thread
    List<Future<String>> futures = IntStream.range(0, 10000)
        .mapToObj(i -> executor.submit(() -> blockingApiCall(i)))
        .toList();
}
```

**批判性思考**：
- Virtual Threads **不适用于CPU密集型任务**（计算不会自动让出Carrier Thread）
- Virtual Threads **与 synchronized 不兼容**（synchronized会pin Carrier Thread），需改用 ReentrantLock
- Virtual Threads **与 ThreadLocal 不兼容**（百万VT + ThreadLocal = 内存溢出），需改用 ScopedValue（JDK 21+）
- **my-xhs 当前使用 JDK 17 + Spring Boot 3.x，升级到 JDK 21 + Spring Boot 3.2 即可启用**
- 启用后，BFF 层的 CompletableFuture 并行聚合可以简化为同步代码（但当前方案也无问题，不必急着迁移）

**my-xhs 建议的迁移路径**：
1. 短期：保持 JDK 17 + CompletableFuture 方案
2. 中期：升级到 JDK 21，`spring.threads.virtual.enabled=true` 一键启用
3. 长期：逐步简化异步代码，将 CompletableFuture 链改为同步阻塞 + Virtual Thread

#### 3.24.8 JVM 云原生底层原理 — 容器化场景的"知其所以然"（P1 补充）

> 云原生架构训练营§10 把 JVM 底层原理放在云原生架构下讲解，而非基础课，是有深意的——容器化场景下 JVM 的行为与传统裸机完全不同：对象布局受 cgroup 内存限制影响、JIT 编译在 Fat JAR 中退化为解释执行、类加载在多层镜像中产生微妙的类冲突。这些知识不是"面试八股文"，而是 **生产环境排障的必备功底**。

##### （1）Java 对象内存布局 — 为什么 -Xmx 不等于实际内存

```
Java 对象在堆中的布局（64位 JVM，开启压缩指针）：

┌────────────────────────────────────────┐
│ Mark Word (8 bytes)                     │  ← GC年龄、锁状态、Identity HashCode
├────────────────────────────────────────┤
│ Klass Pointer (4 bytes, 压缩后)         │  ← 指向类元数据（方法区/元空间）
├────────────────────────────────────────┤
│ Instance Data (变量长度)                 │  ← 实例字段（按类型对齐）
├────────────────────────────────────────┤
│ Padding (补齐到 8 bytes 倍数)            │  ← 内存对齐，提高CPU缓存命中率
└────────────────────────────────────────┘

一个空 Object = 16 bytes（Mark 8 + Klass 4 + Padding 4）
一个 Integer = 16 bytes（Object 16 + int 4 → 补齐到 16，但实际 Integer 继承了 Object 的 12 + int 4 = 16）
一个空 String (JDK 9+) = 24 bytes（Object 16 + hash 4 + coder 1 + 0-length byte[] 引用 4 → 补齐）
```

**容器化影响**：
```
压缩指针（CompressedOops）：默认开启，将 8 bytes 引用压缩为 4 bytes
  - 前提：Heap < 32GB
  - 容器中：如果 -Xmx > 32G 或 -XX:-UseCompressedOops，引用恢复为 8 bytes
  - 实际影响：32G Heap 不压缩 = 32G * 1.5（引用翻倍），32G Heap 压缩 = 32G * 1.0
  - 结论：**32GB 是 JVM Heap 的甜蜜点，超过反而浪费内存**

OopMap：JVM 在安全点（Safepoint）记录栈上哪些位置是对象引用
  - GC 根扫描依赖 OopMap → Safepoint 越少，GC 停顿越短
  - 容器中：CPU 份额（cpu.cfs_quota_us）影响 JIT 编译速度 → 影响 OopMap 生成
  - 结论：**容器 CPU 份额过低时，JIT 编译延迟导致 GC 效率下降**
```

##### （2）JMM（Java Memory Model）— 并发问题的根源

```
JMM 定义了线程与主内存的交互规则：

    ┌──────────────┐     ┌──────────────┐
    │  Thread A    │     │  Thread B    │
    │  ┌────────┐  │     │  ┌────────┐  │
    │  │工作内存│  │     │  │工作内存│  │   ← CPU Cache（L1/L2/L3）
    │  │(副本) │  │     │  │(副本) │  │
    │  └───┬────┘  │     │  └───┬────┘  │
    │      │read   │     │      │write  │
    │      │write  │     │      │read   │
    └──────┼───────┘     └──────┼───────┘
           │                    │
           ▼                    ▼
    ┌──────────────────────────────────┐
    │          主内存（Main Memory）     │   ← Heap（RAM）
    │     instanceVar = 42             │
    └──────────────────────────────────┘
```

**三大特性与对应机制**：

| JMM 特性 | 含义 | Java 实现 | 容器化影响 |
|----------|------|----------|-----------|
| **可见性** | 线程 A 的写入对线程 B 可见 | `volatile`/`synchronized`/`final` | CPU 份额低 → 上下文切换频繁 → 缓存失效更频繁 |
| **原子性** | 操作不可分割 | `synchronized`/`AtomicXxx`/`LongAdder` | cgroup CPU 限制 → synchronized 竞争更激烈 → 锁膨胀更快 |
| **有序性** | 指令不重排 | `volatile`/`happens-before` | 容器中 CPU 拓扑感知（numactl）→ 影响内存屏障的实际开销 |

**面试高频问题：DCL（Double-Checked Locking）为什么需要 volatile？**

```java
public class Singleton {
    // 没有 volatile：由于指令重排，可能返回未初始化完成的对象
    // new Singleton() 实际分 3 步：
    //   1. 分配内存空间（memory = allocate()）
    //   2. 初始化对象（ctorInstance(memory)）
    //   3. 将引用指向内存（instance = memory）
    // 重排可能变为 1→3→2，此时 instance != null 但对象未初始化
    private static volatile Singleton instance;

    public static Singleton getInstance() {
        if (instance == null) {              // 第一次检查（无锁）
            synchronized (Singleton.class) {
                if (instance == null) {      // 第二次检查（有锁）
                    instance = new Singleton();  // volatile 防止重排
                }
            }
        }
        return instance;
    }
}
```

##### （3）JIT 编译 — 容器化性能的隐藏杀手

```
Java 执行模式演进：

  解释执行（Interpreter）
    → 字节码逐行翻译为机器码 → 慢但启动快
    ↓ 执行频率超过阈值（默认 10000 次）
  C1 编译（Client Compiler）
    → 简单优化（内联、栈上替换）→ 快速编译，中等优化
    ↓ 执行频率继续升高
  C2 编译（Server Compiler）
    → 深度优化（逃逸分析、循环展开、分支预测）→ 慢编译，极致优化
    ↓ 
  分层编译（Tiered Compilation，JDK 8+ 默认）
    → C1 + C2 协作：先用 C1 快速优化，热点代码再用 C2 深度优化
```

**容器化场景的 JIT 痛点**：

| 痛点 | 原因 | 影响 | 解决方案 |
|------|------|------|---------|
| **JIT 编译延迟** | 容器 CPU 份额低，编译线程获得的时间片不足 | 请求延迟高（解释执行慢 10-100 倍） | 设置 `-XX:CICompilerCount=2`（限制编译线程数），AOT 编译（Spring AOT/GraalVM） |
| **CodeCache 不足** | Fat JAR 类多，编译后的机器码占用 CodeCache | JIT 退化回解释执行 | `-XX:ReservedCodeCacheSize=256m` |
| **冷启动问题** | K8s 弹性扩容时新 Pod 需重新 JIT 编译 | 首批请求 RT 飙高 | Spring AOT + GraalVM Native Image，或 AppCDS（类数据共享） |

**逃逸分析（Escape Analysis）— C2 最重要的优化**：

```
逃逸分析判断对象是否"逃逸"出方法/线程：
  - 未逃逸 → 栈上分配（不进堆，无 GC 压力）
  - 未逃逸 → 标量替换（对象拆解为基本类型，直接用寄存器）
  - 未逃逸 → 锁消除（synchronized 无竞争时自动消除）

示例：
  public String concat(String a, String b) {
      StringBuilder sb = new StringBuilder();  // sb 未逃逸出方法
      sb.append(a).append(b);                  // C2: 栈上分配 + 标量替换
      return sb.toString();                     // 不产生 GC 压力
  }

容器化影响：CPU 份额低 → C2 编译延迟 → 逃逸分析未及时生效
→ 临时对象堆积在堆中 → GC 频繁 → 恶性循环
```

##### （4）类加载机制 — Fat JAR 和多层镜像的坑

```
双亲委派模型：
  Bootstrap ClassLoader（rt.jar）
       ↑ 委派
  Extension ClassLoader（ext/*.jar）
       ↑ 委派
  Application ClassLoader（classpath）
       ↑ 委派
  自定义 ClassLoader（如 Tomcat WebAppClassLoader）

打破双亲委派的场景：
  1. SPI 机制（JDBC Driver → ServiceLoader → 线程上下文 ClassLoader）
  2. Tomcat WebApp 隔离（每个 WebApp 独立 ClassLoader）
  3. OSGi 模块化（每个 Bundle 独立 ClassLoader + 网状依赖）
  4. Spring Boot Fat JAR（JarURLConnection + 自定义 ClassLoader）
```

**Spring Boot Fat JAR 的类加载顺序**：

```
Spring Boot Fat JAR 结构：
  my-xhs-order.jar
  ├── BOOT-INF/classes/           ← 应用类（优先加载）
  ├── BOOT-INF/lib/               ← 依赖 JAR
  ├── org/springframework/boot/loader/  ← Spring Boot Loader
  └── META-INF/MANIFEST.MF        ← Main-Class: org.springframework.boot.loader.JarLauncher

类加载顺序：
  1. JarLauncher 启动
  2. 创建 LaunchedURLClassLoader（自定义 ClassLoader）
  3. 加载 BOOT-INF/classes/（应用类优先）
  4. 加载 BOOT-INF/lib/（依赖 JAR）
  
  关键：LaunchedURLClassLoader 打破了标准双亲委派
  → 应用类优先于依赖 JAR 中的同名类（防止依赖冲突覆盖应用代码）
```

**容器化场景的类加载坑**：

| 场景 | 问题 | 解决方案 |
|------|------|---------|
| **Docker 多阶段构建** | Build Stage 和 Run Stage 的 JDK 版本不一致 → 类找不到 | 确保 `java -version` 一致 |
| **AppCDS（类数据共享）** | Fat JAR 的嵌套 JAR 无法直接使用 AppCDS | Spring Boot 3.2+ 支持自动生成 CDS Archive |
| **K8s Init Container** | Init Container 中的类和 Main Container 不共享 ClassLoader | 用共享 Volume 存储 CDS Archive |

##### （5）my-xhs 生产环境 JVM 配置推荐

```bash
# my-xhs-order 服务 K8s Deployment JVM 参数
java \
  -Xms1g -Xmx1g \                          # 固定堆大小，避免动态扩缩引起GC抖动
  -XX:MaxRAMPercentage=75.0 \              # 容器内存4G的75%=3G（堆+非堆）
  -XX:+UseZGC \                             # 低延迟GC（JDK 21+推荐）
  -XX:ZCollectionInterval=0 \               # ZGC 自适应收集频率
  -XX:+ZUncommit \                          # 闲置内存归还OS（cgroup感知）
  -XX:ReservedCodeCacheSize=256m \          # JIT编译缓存（Fat JAR类多）
  -XX:CICompilerCount=2 \                   # 编译线程数（容器CPU份额有限）
  -XX:+UseCompressedOops \                  # 压缩指针（Heap < 32G）
  -XX:+UseCompressedClassPointers \         # 压缩类指针
  -XX:MaxMetaspaceSize=256m \               # 元空间上限（防止类加载泄漏）
  -XX:+HeapDumpOnOutOfMemoryError \         # OOM 时自动 Dump
  -XX:HeapDumpPath=/tmp/heapdump.hprof \    # Dump 路径（K8s emptyDir）
  -Xlog:gc*:file=/tmp/gc.log:time,uptime \ # GC 日志（ZGC 格式）
  -jar my-xhs-order.jar
```

**批判性思考**：
- JVM 底层原理在容器化场景的价值不是"能背出对象布局"，而是 **能快速定位"为什么容器中性能比 VM 差"**——80% 的答案是 JIT 编译延迟 + GC 抖动
- AppCDS + Spring AOT 是解决 K8s 冷启动的**银弹组合**：AppCDS 跳过类解析，Spring AOT 跳过 Bean 定义扫描，两者叠加可减少 30-50% 启动时间
- ZGC 在 JDK 21 中已经是生产就绪（分代 ZGC），my-xhs 用 JDK 21 + ZGC 比用 G1 的 P99 延迟低一个数量级
- **面试策略**：从"对象布局→JMM→JIT→类加载→容器化适配"串联讲，展现深度而非背诵碎片

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

#### 3.26.7 MySQL 高可用架构深度指南（P0 必读）

> Stage-3 课程 013 讲了 MySQL 高可用基础，但缺少容器化场景选型和读写分离路由实战。以下是批判性补充。

##### （1）MySQL 主从复制原理

```
+----------+         +----------+         +----------+
|  Master  |  binlog |  Relay   |  relay  |  Slave   |
| (写入)    | ------→ |  Log     | ------→ | (只读)    |
|          |         +----------+         |          |
|  binlog  |                              |  执行SQL  |
|  dump线程 | ------→  IO 线程读取binlog    |  SQL线程   |
+----------+         → 写入relay log      +----------+
```

**核心概念**：

| 概念 | 说明 |
|------|------|
| **Binlog** | 主库所有变更写入二进制日志，格式：ROW（推荐，数据最完整）、STATEMENT（SQL文本，可能主从不一致）、MIXED |
| **GTID** | Global Transaction ID，格式 `server_uuid:transaction_id`，用于简化主从切换和故障恢复，**生产必开** |
| **Relay Log** | 从库IO线程从主库拉取binlog后写入本地中继日志 |
| **并行复制** | 从库SQL线程按database或logical_clock并行回放relay log，减少延迟。MySQL 5.7+ 支持 `slave_parallel_type=LOGICAL_CLOCK` |
| **半同步复制** | 主库写入后等待至少一个从库确认收到binlog才返回，介于异步和全同步之间。`rpl_semi_sync_master_enabled=1` |

**主从延迟的根因与应对**：

| 根因 | 影响 | 应对方案 |
|------|------|---------|
| 主库大事务（大批量UPDATE/DELETE） | 从库回放慢，延迟飙升 | 拆分大事务，每次操作 < 1000 行 |
| 主库高并发写入，从库单线程回放 | 从库永远追不上 | 开启并行复制（LOGICAL_CLOCK） |
| 从库硬件差（磁盘IO慢） | 回放速度跟不上 | 从库SSD、升级硬件 |
| 网络延迟/抖动 | IO线程拉取binlog慢 | 同机房部署主从、使用半同步复制 |
| DDL操作（ALTER TABLE） | 从库回放DDL时锁表 | pt-online-schema-change 无锁DDL |

**my-xhs 中的实践**：Phase-6 #40 踩坑指南已提了 HintManager 强制走主库解决主从延迟问题，但缺少系统性的主从架构设计。以下补充。

##### （2）读写分离路由策略

> my-xhs 当前只有分库分表路由，缺少读写分离路由。以下是完整的读写分离方案。

**方案 A：ShardingSphere JDBC 模式（推荐 my-xhs 使用）**

```yaml
# application-sharding.yml — 读写分离 + 分库分表组合配置
spring:
  shardingsphere:
    datasource:
      names: ds-master-0,ds-slave-0-0,ds-slave-0-1,ds-master-1,ds-slave-1-0,ds-slave-1-1
      ds-master-0:
        type: com.zaxxer.hikari.HikariDataSource
        jdbc-url: jdbc:mysql://mysql-order-master-0:3306/my_xhs_order_0
      ds-slave-0-0:
        jdbc-url: jdbc:mysql://mysql-order-slave-0-0:3306/my_xhs_order_0
      ds-slave-0-1:
        jdbc-url: jdbc:mysql://mysql-order-slave-0-1:3306/my_xhs_order_0
      # ... 其他数据源
    rules:
      readwrite-splitting:
        data-sources:
          order-rw:
            write-data-source-name: ds-master-0
            read-data-source-names: ds-slave-0-0,ds-slave-0-1
            load-balancer-name: round-robin
        load-balancers:
          round-robin:
            type: ROUND_ROBIN
      sharding:
        tables:
          t_order:
            actual-data-nodes: ds-master-$->{0..3}.t_order_$->{0..15}
            # ... 分片策略同 3.26.4
```

**方案 B：ShardingSphere Proxy 模式（独立代理）**

```
应用服务 → ShardingSphere Proxy(3307) → MySQL 主/从
```

- 优点：应用无感知，语言无关，可独立升级
- 缺点：多一跳网络延迟，Proxy 本身是单点（需集群化）
- 适用场景：多语言微服务、数据库迁移

**方案 C：AbstractRoutingDataSource 动态数据源（轻量级）**

```java
// 自定义注解，标记方法走主库还是从库
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Master {
}

// AOP 切面，根据注解切换数据源
@Aspect
@Component
public class DataSourceAspect {
    @Around("@annotation(master)")
    public Object routeToMaster(ProceedingJoinPoint pjp, Master master) throws Throwable {
        DynamicDataSourceContextHolder.push("master");
        try {
            return pjp.proceed();
        } finally {
            DynamicDataSourceContextHolder.poll();
        }
    }
}

// 使用示例
@Master  // 强制走主库（写操作或需要强一致性的读操作）
public Order getOrderById(Long orderId) { ... }
```

**my-xhs 推荐方案**：方案 A（ShardingSphere JDBC 模式），原因：
1. 已引入 ShardingSphere 依赖，零额外组件
2. 读写分离 + 分库分表一体化配置，无需维护独立 Proxy
3. HintManager 已有使用基础，扩展成本低

##### （3）MySQL 高可用方案对比（含容器化场景）

> Stage-3 课程偏向传统 VM 部署的高可用方案，容器化场景需要不同的思路。

| 方案 | 架构 | 故障切换时间 | 容器化适配性 | 适用场景 |
|------|------|------------|------------|---------|
| **MHA** | 主从 + Manager节点监控 + VIP漂移 | 10-30秒 | ❌ 不推荐（依赖SSH/VIP，与K8s网络模型冲突） | 传统 VM 部署 |
| **Orchestrator** | 拓扑感知 + HTTP API + 自动故障恢复 | 30秒-2分钟 | ❌ 不推荐（同上） | 传统 VM，大型MySQL集群 |
| **MySQL InnoDB Cluster** | MySQL Group Replication + MySQL Shell + Router | <10秒 | ⚠️ 可用但复杂（有状态应用在K8s中运维困难） | 中等规模，需要原生方案 |
| **Vitess** | 分片+代理+自动故障恢复，Cloud Native设计 | 秒级 | ✅ 推荐（CNCF项目，原生支持K8s） | 大规模分库分表+高可用 |
| **MySQL Operator** | K8s Operator管理MySQL生命周期 | 秒级 | ✅✅ 最推荐（声明式管理，自动备份/恢复/扩缩容） | K8s 原生部署 |
| **云厂商 RDS** | 托管高可用（主从+自动切换+备份） | 秒级 | ✅✅ 最推荐（无需运维，SLA保障） | 云上部署，my-xhs 推荐 |

**批判性思考**：
- 小马哥课程重点讲 MHA/Orchestrator，但这些是 **VM 时代的方案**，在 K8s 中运行有诸多问题（SSH依赖、VIP管理、有状态应用编排）
- my-xhs 项目部署在 K8s 上，推荐 **云厂商 RDS**（省运维成本）或 **MySQL Operator**（自建）
- 如果是开发/测试环境，单个 MySQL 实例 + 定期备份即可，无需复杂的高可用架构

##### （4）容器化 MySQL 部署建议

| 场景 | 推荐方案 | 理由 |
|------|---------|------|
| 开发环境 | Docker 单实例 MySQL | 简单快速，数据量小 |
| 测试环境 | Docker Compose 主从 | 验证读写分离路由逻辑 |
| 预发环境 | 云厂商 RDS 或 MySQL Operator | 接近生产配置 |
| 生产环境 | 云厂商 RDS（推荐）或 Vitess/Operator | 高可用+自动运维 |

**开发环境 Docker Compose 示例**（主从复制）：
```yaml
# docker-compose-mysql-ha.yml
version: '3.8'
services:
  mysql-master:
    image: mysql:8.0
    ports:
      - "13306:3306"   # 非标准端口避免冲突
    environment:
      MYSQL_ROOT_PASSWORD: root
    command: >
      --server-id=1
      --log-bin=mysql-bin
      --binlog-format=ROW
      --gtid-mode=ON
      --enforce-gtid-consistency=ON
      --rpl_semi_sync_master_enabled=1
    volumes:
      - mysql-master-data:/var/lib/mysql

  mysql-slave:
    image: mysql:8.0
    ports:
      - "13307:3306"   # 非标准端口
    environment:
      MYSQL_ROOT_PASSWORD: root
    command: >
      --server-id=2
      --relay-log=relay-bin
      --gtid-mode=ON
      --enforce-gtid-consistency=ON
      --rpl_semi_sync_slave_enabled=1
      --slave_parallel_type=LOGICAL_CLOCK
      --slave_parallel_workers=4
      --read-only=ON
    depends_on:
      - mysql-master
    volumes:
      - mysql-slave-data:/var/lib/mysql

volumes:
  mysql-master-data:
  mysql-slave-data:
```

##### （5）MyBatis 核心组件深度（P1 补充）

> Stage-3 课程 014 讲了数据存储优化，缺少 MyBatis 核心组件交互原理。以下是补充。

```
SqlSessionFactoryBuilder
    → 读取 mybatis-config.xml / application.yml
    → 构建 SqlSessionFactory（全局唯一，Spring容器管理）

SqlSessionFactory
    → openSession() → 创建 SqlSession（非线程安全，每次请求创建一个）

SqlSession
    → getMapper() → 获取 Mapper 代理对象
    → selectOne()/selectList()/insert()/update()/delete()

Executor（SqlSession 内部委托给 Executor 执行）
    ├── SimpleExecutor    — 每次创建新 Statement
    ├── ReuseExecutor     — 复用 Statement（同一SQL）
    └── BatchExecutor     — 批量执行（rewriteBatchedStatements=true 时MySQL支持）

Plugin 拦截链（责任链模式）
    → 拦截 Executor/BoundSql/StatementHandler/ResultSetHandler
    → 典型应用：PageHelper分页、SQL打印、SQL注入防护、慢SQL告警
```

**my-xhs 性能优化建议**：
1. **批量操作**：订单批量插入用 `BatchExecutor` + `rewriteBatchedStatements=true`，单次插入1000条可提速10倍
2. **分页优化**：PageHelper 对深分页（OFFSET > 10000）性能差，推荐游标分页（`WHERE id > last_id LIMIT 100`）
3. **SQL 注入防护**：始终用 `#{}` 而非 `${}`，MyBatis 的 `#{}` 使用 PreparedStatement 参数化

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

### 专题 27.5：I/O 模型与异步编程（P0 新增）

> Stage-3 课程 019 讲了 Reactive 异步服务，但缺少 I/O 模型底层原理的系统讲解。理解 BIO/NIO/AIO/epoll 是理解高并发架构的基础，也是面试高频考点。

#### 3.27.5.1 I/O 模型全景对比

```
用户空间（User Space）              内核空间（Kernel Space）
+-------------------+              +-------------------+
| 应用程序           |              |                   |
|  read()/write()   | ← 数据拷贝 → |  Socket缓冲区      | ← 网卡 → 网络
|  recv()/send()    |              |  文件系统缓存       | ← 磁盘
+-------------------+              +-------------------+

I/O 模型 = 内核等待数据就绪的方式 + 数据从内核空间拷贝到用户空间的方式
```

| I/O 模型 | 等待数据就绪 | 数据拷贝 | 线程阻塞 | 典型应用 |
|---------|------------|---------|---------|---------|
| **BIO**（阻塞I/O） | 阻塞等待 | 阻塞拷贝 | 全程阻塞 | 传统 Servlet（一个请求一个线程） |
| **NIO**（非阻塞I/O） | 轮询（非阻塞） | 阻塞拷贝 | 拷贝时阻塞 | Java NIO（Channel/Buffer/Selector） |
| **I/O 多路复用**（epoll） | 事件通知 | 阻塞拷贝 | 仅拷贝时阻塞 | Netty、Redis、Nginx |
| **AIO**（异步I/O） | 不等待 | 内核自动拷贝 | 不阻塞 | Windows IOCP、Linux io_uring |

**批判性思考**：
- 小马哥课程把 Java NIO 等同于 I/O 多路复用，这是不准确的——**Java NIO 是非阻塞I/O + I/O 多路复用的组合**
- Linux 的 AIO（libaio）并不真正异步，Windows 的 IOCP 才是真正的 AIO
- Linux 5.1+ 引入的 **io_uring** 才是 Linux 真正的异步 I/O，但 Java 标准库尚未原生支持（Netty 有 io_uring 实验性支持）

#### 3.27.5.2 epoll 原理深度解析

> 为什么 epoll 比 select/poll 快？这是理解 Nginx/Netty/Redis 高性能的基础。

```
select/poll 的痛点：
1. 每次调用都需要把 fd 集合从用户态→内核态拷贝（O(n) 开销）
2. 内核线性扫描所有 fd，O(n) 复杂度
3. 返回后用户态仍需线性扫描找出就绪的 fd
4. fd 数量限制（select 默认1024，poll 无限制但仍慢）

epoll 的解法：
1. epoll_create() → 创建 epoll 实例（红黑树 + 就绪链表）
2. epoll_ctl() → 注册 fd + 事件（增删改，只在变化时调用）
3. epoll_wait() → 只返回就绪的 fd（O(1) 获取就绪事件）
```

| 特性 | select | poll | epoll |
|------|--------|------|-------|
| fd 数量限制 | 1024（FD_SETSIZE） | 无限制 | 无限制 |
| 每次调用开销 | O(n) 全量拷贝 | O(n) 全量拷贝 | O(1) 只返回就绪 |
| 内核扫描复杂度 | O(n) | O(n) | O(1) 事件回调 |
| 触发模式 | LT（水平触发） | LT | LT + ET（边缘触发，更高效） |
| 适用场景 | 少量连接 | 中等连接 | 大量连接（C10K+） |

**ET（边缘触发）vs LT（水平触发）**：
- LT：只要缓冲区有数据就持续通知，直到数据被读完 → 编程简单，但可能重复通知
- ET：只在缓冲区从空→非空时通知一次 → 编程复杂（必须一次性读完），但效率更高
- Netty 使用 **LT 模式**（Java NIO Selector 不支持 ET）
- Nginx 使用 **ET 模式**（C 直接调用 epoll_ctl）

#### 3.27.5.3 零拷贝技术

> 传统数据传输需要 4 次拷贝 + 4 次上下文切换，零拷贝技术可以减少到 2 次。

```
传统方式（4次拷贝）：
  磁盘 → 内核Page Cache → 用户Buffer → Socket内核缓冲区 → 网卡
  (DMA拷贝)  (CPU拷贝)     (CPU拷贝)     (DMA拷贝)

sendfile 零拷贝（2次拷贝）：
  磁盘 → 内核Page Cache → 网卡（SG-DMA直接从Page Cache到网卡）
  (DMA拷贝)  (DMA拷贝，CPU不参与)

mmap 零拷贝（2次拷贝）：
  用户空间和内核空间共享同一块物理内存，避免 CPU 拷贝
  Kafka 使用 mmap 实现零拷贝（Log Segment 的 MappedByteBuffer）
```

| 技术 | 拷贝次数 | 上下文切换 | 适用场景 |
|------|---------|-----------|---------|
| 传统 read+write | 4 | 4 | — |
| sendfile | 2（或3） | 2 | 静态文件传输（Nginx）、Kafka Replication |
| mmap | 2 | 4 | Kafka 日志读写、RocketMQ CommitLog |
| splice | 0 | 2 | 管道传输（两个 fd 之间） |

**my-xhs 中的零拷贝应用**：
- RocketMQ 消息存储使用 mmap（CommitLog → MappedByteBuffer）
- 静态资源（图片/视频）CDN 回源使用 sendfile（Nginx `sendfile on`）

#### 3.27.5.4 Netty Reactor 线程模型

> Stage-3 课程 019 提了 Reactive 异步，但缺少 Netty 底层线程模型。以下是深度补充。

```
主从 Reactor 多线程模型（Netty 默认）：

+-------------------------------------------------------+
|                    Reactor Main Group（Boss）            |
|  [EventLoop 1]  [EventLoop 2]  ...  [EventLoop N]      |
|   ↑ Accept新连接                                       |
|   ↓ 注册到Worker Group                                 |
+-------------------------------------------------------+
                        |
                        ↓
+-------------------------------------------------------+
|                   Reactor Worker Group（Worker）         |
|  [EventLoop 1]  [EventLoop 2]  ...  [EventLoop M]      |
|   ↑ 读/写事件处理                                      |
|   ↓ Pipeline处理链                                     |
+-------------------------------------------------------+
                        |
                        ↓
+-------------------------------------------------------+
|                   业务线程池（自定义Handler）              |
|  [Thread 1]  [Thread 2]  ...  [Thread K]               |
|   ↑ 耗时业务逻辑（DB/RPC/MQ）                           |
+-------------------------------------------------------+

关键规则：
1. 一个 EventLoop = 一个 Thread + 一个 Selector（epoll）
2. 一个 Channel 只注册到一个 EventLoop（线程绑定，无并发问题）
3. Boss Group 线程数 = 1（通常只需1个Accept线程，除非是百万连接）
4. Worker Group 线程数 = CPU核数 * 2
5. Pipeline 中的 Handler 不要做阻塞操作，否则拖慢整个 EventLoop
6. 耗时业务逻辑必须丢到业务线程池，不能在 I/O 线程执行
```

**Netty 关键组件**：

| 组件 | 职责 | 注意事项 |
|------|------|---------|
| **EventLoopGroup** | 线程池 + Selector 集合 | Boss 1线程，Worker CPU*2线程 |
| **EventLoop** | 单线程事件循环 | 串行处理Channel事件，无锁并发 |
| **Channel** | 网络连接抽象 | 绑定到唯一 EventLoop |
| **Pipeline** | Handler 处理链 | Inbound 入站 + Outbound 出站 |
| **ByteBuf** | 字节缓冲区 | 池化（PooledByteBufAllocator）优于非池化 |
| **ByteToMessageDecoder** | 粘包/半包处理 | LengthFieldBasedFrameDecoder 最常用 |

**my-xhs 中的 Netty 应用**：
- Spring Cloud Gateway 底层使用 Reactor Netty（WebFlux → Netty）
- my-xhs-im 即时通讯服务如果使用 WebSocket，底层也是 Netty
- mini-rpc 网络层如果用 Netty 重构，需遵循上述线程模型

#### 3.27.5.5 Tomcat NIO vs APR 模式对比

> Stage-3 课程 011 讲了 HTTP 服务架构升级，缺少 Tomcat 线程模型对比。以下是补充。

| 模式 | 实现原理 | 性能 | 容器化适配性 |
|------|---------|------|------------|
| **NIO**（默认） | Java NIO Selector + 线程池 | 良好 | ✅ 推荐（纯Java，跨平台，容器友好） |
| **NIO2** | Java AsynchronousFileChannel + 线程池 | 略好于NIO | ✅ 可用（JDK7+） |
| **APR** | Apache Portable Runtime（C库） | 最高（原生Socket） | ❌ 不推荐容器化（需安装libapr，镜像膨胀） |

**批判性思考**：
- 小马哥课程推荐 APR 模式提升性能，但在容器化场景下**APR 的优势被削弱**：
  - 1 Pod 1 进程，并发连接数有限，NIO 足够
  - APR 需要安装 native 库（`libapr1-dev`），增加镜像体积和构建复杂度
  - K8s 中水平扩展（HPA）比单机调优更有效
- **my-xhs 推荐 NIO 模式**，配合 Spring Boot 默认配置即可

#### 3.27.5.6 Spring Web Reactive 与 Servlet 对比（P1 补充）

> Stage-3 课程 015 讲了 Spring Web Reactive，以下是批判性分析。

| 维度 | Servlet（Spring MVC） | Reactive（Spring WebFlux） |
|------|----------------------|--------------------------|
| 线程模型 | 请求→Tomcat线程→阻塞I/O→响应 | 请求→EventLoop线程→非阻塞I/O→回调 |
| 并发能力 | 受线程池大小限制（200-400线程） | 少量线程处理大量连接（CPU核数*2） |
| 编程复杂度 | 同步阻塞，直观易懂 | 异步回调/Mono/Flux，学习曲线陡 |
| 数据库访问 | JDBC（阻塞） | R2DBC（非阻塞，但生态不成熟） |
| 适用场景 | CRUD为主、I/O不密集 | I/O密集型、流式处理、高并发网关 |
| 错误排查 | 堆栈清晰 | 回调地狱，堆栈难以追踪 |

**批判性思考**：
- WebFlux 的性能优势**被过度宣传**——在典型的 CRUD 场景下，Spring MVC + JDBC 的吞吐量并不比 WebFlux + R2DBC 差多少
- WebFlux 的真正价值在于**I/O 密集型网关**（如 Spring Cloud Gateway）和**流式处理**（如 SSE/WebSocket）
- my-xhs 的 BFF 层使用 CompletableFuture 并行聚合已经是务实的方案，无需全面切换 WebFlux
- **Spring Cloud Gateway 天然使用 WebFlux**，这是不可避免的——但内部服务仍用 Spring MVC 即可

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

#### 3.28.9 分布式事件设计（P1 补充）

> Stage-3 课程 016-018 讲了分布式事件设计，但缺少 Spring ApplicationEvent + Redis Pub/Sub 跨 JVM 事件传播的实战方案。以下是补充。

##### （1）为什么需要分布式事件？

```
单体应用：Spring ApplicationEvent（进程内事件总线）
  → 事件发布者和监听者在同一JVM，直接回调

微服务应用：服务A发布事件 → 服务B（不同JVM）需要感知
  → 方案1：MQ（RocketMQ/Kafka）— 重型，适合跨服务最终一致性
  → 方案2：Redis Pub/Sub — 轻量，适合同服务多实例间事件同步
  → 方案3：Spring Cloud Stream — MQ的抽象层，与具体MQ解耦
```

**选型建议**：

| 场景 | 推荐方案 | 理由 |
|------|---------|------|
| 同服务多实例间事件同步（如缓存失效广播） | Redis Pub/Sub | 轻量、实时、无需额外中间件 |
| 跨服务最终一致性事件（如下单→扣库存） | RocketMQ | 可靠投递、事务消息、死信队列 |
| 配置变更通知 | Nacos Config 长轮询 | 已有方案，无需额外引入 |
| 业务事件通知（如用户关注→推送） | Spring ApplicationEvent（本地）+ MQ（跨服务） | 组合方案 |

##### （2）Spring ApplicationEvent + Redis Pub/Sub 组合设计

> 核心思路：本地事件 + Redis广播 = 跨JVM事件传播

```java
// Step 1: 定义事件
public class CacheInvalidationEvent extends ApplicationEvent {
    private final String cacheKey;
    private final String sourceService;  // 事件来源服务实例标识
    // ...
}

// Step 2: 本地事件发布（服务内）
@Service
public class ProductService {
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private StringRedisTemplate redisTemplate;

    public void updateProduct(Product product) {
        productMapper.update(product);
        // 1. 发布本地事件（同JVM的监听器立即响应）
        eventPublisher.publishEvent(new CacheInvalidationEvent(this, "product:" + product.getId(), getInstanceId()));
        // 2. 通过Redis广播到其他实例
        redisTemplate.convertAndSend("cache:invalidation", "product:" + product.getId());
    }
}

// Step 3: 本地监听器（同JVM）
@Component
public class LocalCacheListener {
    @EventListener
    public void onCacheInvalidation(CacheInvalidationEvent event) {
        if (!event.getSourceService().equals(getInstanceId())) {
            return; // 忽略来自其他实例的事件（避免重复处理）
        }
        localCache.invalidate(event.getCacheKey());
    }
}

// Step 4: Redis消息监听器（跨JVM）
@Component
public class RedisCacheInvalidationListener implements MessageListener {
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String cacheKey = new String(message.getBody());
        // 将Redis消息转为本地ApplicationEvent，统一处理
        eventPublisher.publishEvent(new CacheInvalidationEvent(this, cacheKey, getInstanceId()));
    }
}

// Step 5: Redis订阅配置
@Configuration
public class RedisPubSubConfig {
    @Bean
    public RedisMessageListenerContainer container(RedisConnectionFactory factory,
                                                   RedisCacheInvalidationListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(listener, new ChannelTopic("cache:invalidation"));
        return container;
    }
}
```

**关键设计点**：

| 设计点 | 方案 | 原因 |
|--------|------|------|
| 消息去重 | sourceService 字段标识来源 | 避免发布者自己又消费自己发出的Redis消息 |
| 事件统一处理 | Redis消息 → 转为ApplicationEvent | 本地和远程事件统一入口，减少重复代码 |
| 可靠性 | Redis Pub/Sub 不保证消息持久化 | 只用于缓存失效等可丢失场景；重要业务事件仍用MQ |
| 事件序列化 | JSON字符串 | 简单可读，跨语言兼容 |

**批判性思考**：
- Redis Pub/Sub 是**fire-and-forget**模式，订阅者不在线则消息丢失——不适合重要业务事件
- my-xhs 已有 RocketMQ 事务消息体系，**重要业务事件（下单/支付/库存变更）应继续用MQ**
- Redis Pub/Sub 适合**轻量级实时通知**（缓存失效、配置热更新广播、WebSocket集群消息广播）
- 更高级的方案可用 **Redis Streams**（类似Kafka，支持消费组+持久化），但增加复杂度

##### （3）事件驱动架构模式简介

| 模式 | 说明 | 适用场景 | my-xhs 现状 |
|------|------|---------|------------|
| **Event Sourcing** | 所有状态变更以事件序列持久化，可重放恢复状态 | 金融/审计系统 | ❌ 过于复杂，不适用 |
| **CQRS** | 读写分离模型，写走Command→Event→WriteDB，读走Query→ReadDB | 复杂查询场景 | ⚠️ ES搜索已类似CQRS（MySQL写→Canal→ES读） |
| **Saga** | 长事务拆分为多个本地事务+补偿操作 | 分布式事务 | ✅ RocketMQ事务消息已实现 |
| **Outbox Pattern** | 业务表+事件表同事务写入，后台线程轮询发送 | 保证事件不丢失 | ✅ my-xhs 已有本地消息表 |

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

---

## 附录：Stage-3 课程对比分析

> 来源：[Java训练营第三期 - 分布式高并发、高性能、高可用架构](https://www.yuque.com/mercyblitz/java-training-camp/stage-3)
>
> 目的：对照 Stage-3 课程大纲，识别 my-xhs 项目在"三高"（高并发、高性能、高可用）维度的知识缺口，指导后续专题补充。

### A.1 Stage-3 课程目录（6次公开课 + 22节正课 + 8次加餐 = 36个视频）

| # | 课程标题 | 核心主题 |
|---|---------|---------|
| 001 | [公开课] 高并发、高性能与高可用 课程规划 | 课程全景 + 学习路线图 |
| 002 | [公开课] 高并发、高性能与高可用 课程开营 | 开营介绍 |
| 003 | [公开课] 电商项目 Shopizer 介绍 | 项目代码结构与架构分析 |
| 004 | [公开课] Shopizer 项目"三高"优化计划 | 优化路线图、瓶颈定位 |
| 005 | [公开课] 第一节：架构优化准备（一） | 优化前的基线评估、工具链准备 |
| 006 | [公开课] 第二节：架构优化准备（二） | 优化前的基线评估、工具链准备 |
| 007 | 第三节：高并发、高性能服务容器调优 | JVM 内存管理、Tomcat/Undertow 调优 |
| 008 | 第四节：高可用微服务架构升级 | 微服务拆分、注册发现升级 |
| 009 | 第五节："高可用" Eureka 服务注册与发现 | 服务注册中心高可用设计 |
| 010 | 第六节："高性能" Eureka Server 架构 | Eureka Server 性能优化 |
| 011 | 第七节："高并发、高性能" HTTP 服务架构升级 | Tomcat 集群、NIO、连接池 |
| 012 | 第八节："高并发、高可用" RPC 架构升级 | 高并发RPC设计 |
| 013 | 第九节："高可用" MySQL 数据库 | 数据源架构、主从、读写分离 |
| 014 | 第十节："高并发、高性能" 数据存储 | MyBatis 核心组件、分库分表 |
| 015 | [加餐一] Spring Web Reactive | 响应式编程模型 |
| 016 | [加餐二] 分布式事件设计 | 事件驱动架构 |
| 017 | [加餐三] 小伙伴定制 | 社区定制内容 |
| 018 | 第十一节："高性能、高可用" 分布式事件 | Spring Redis 分布式事件 |
| 019 | 第十二节："高并发" Reactive 异步服务 | I/O 模型、异步非阻塞 |
| 020 | 第十三节："高并发、高性能与高可用" API 网关 | API 网关架构设计 |
| 021 | 第十四节："高并发、高性能与高可用" RPC 网关 | RPC 网关架构设计 |
| 022 | 第十五节："高并发、高性能与高可用" Istio | 服务网格入门 |
| 023 | [加餐四] "高并发、高性能与高可用" Istio（续） | 服务网格深入 |
| 024 | [加餐五] Dubbo 内核设计与实现 | Dubbo SPI、架构深度解析 |
| 025 | 第十六节："高并发、高性能与高可用" Dubbo Mesh | Dubbo 整合 xDS、Envoy |
| 026 | 第十七节："高并发、高性能与高可用" 配置中心 - Nacos | Nacos Config 原理与实践 |
| 027 | 第十八节："高并发、高性能与高可用" 配置中心 - etcd | etcd Watch/Lease 与配置中心对比 |
| 028 | [加餐六] "高并发、高性能与高可用" 分布式配置客户端实现 | 手写配置客户端（长轮询/Watch/本地缓存） |
| 029 | [加餐七] GraalVM 基础 | AOT编译、Native Image 基础 |
| 030 | 第十九节："高并发、高性能与高可用" 日志平台 | 日志采集、聚合、检索 |
| 031 | 第二十节："高并发、高性能与高可用" 监控平台 | 指标监控、告警 |
| 032 | 第二十一节："高并发、高性能与高可用" Spring Native 应用 | Spring Native 编译与部署 |
| 033 | 第二十二节："高并发、高性能与高可用" Java Native 应用 | Java Native Image 实战 |
| 036 | [加餐八] 现代 Java 发展与变化 | Java 新特性（Virtual Threads/Record/Sealed Class） |

### A.2 知识点逐课拆解与项目映射

> 核心思路：不是"课程编号对应哪个Phase"，而是"这节课到底讲了哪些知识点，my-xhs有没有覆盖，没覆盖能不能补"。

#### 007 服务容器调优（JVM）

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| JVM 内存模型（堆/非堆/元空间/直接内存） | ⚠️ 仅提到G1 GC + 4G堆 | Phase-5 #24 性能优化清单 | ✅ 可补充 | Phase-5 #24 扩充JVM调优章节 |
| 垃圾收集器选型（G1 vs ZGC vs Shenandoah） | ❌ 只用了G1，未对比 | — | ✅ 可补充 | Phase-5 #24，容器场景ZGC优势明显 |
| GC 参数调优（MaxGCPauseMillis/InitiatingHeapOccupancyPercent/G1HeapRegionSize） | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 #24 |
| 容器内存感知（cgroup + MaxRAMPercentage） | ⚠️ 仅在踩坑指南提了"JVM堆=容器60%" | Phase-6 #40 | ✅ 可补充 | Phase-5 #24，需系统性讲解 |
| Tomcat/Undertow 线程模型与参数调优 | ❌ 未涉及 | — | ⚠️ 部分可补 | Phase-5 #24，容器化下优先级降低（1Pod1进程），但maxThreads/acceptCount仍是面试高频 |
| JFR/JMC 生产诊断实战 | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 #24 |
| Arthas 线上诊断全流程 | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 #24 |

#### 008 高可用微服务架构升级

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| 微服务拆分策略（按业务域 vs 子域 vs 能力） | ⚠️ 已有模块划分但缺理论总结 | Phase-1~7 实际拆分 | ✅ 可补充 | Phase-4 #20 前置知识文档 |
| 服务边界划定（防腐层/上下文映射） | ❌ 未涉及 | — | ⚠️ 可补充 | Phase-4 #20 文档 |
| Spring Cloud 服务注册发现升级路径 | ✅ Nacos 已落地 | Phase-4 #20 | — | 已覆盖 |

#### 009-010 Eureka 注册与发现 + Server架构

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| 注册中心高可用设计（集群部署/数据同步/脑裂防护） | ✅ Nacos双集群方案已详细设计 | Phase-7 #43 | — | 已覆盖，且比Eureka更先进（AP+CP双模式） |
| 注册中心性能优化（读写分离/缓存/批量操作） | ⚠️ mini-nacos覆盖了Raft但缺性能优化专题 | sca-demo mini-nacos | ✅ 可补充 | mini-nacos 文档扩充 |
| 服务发现原理（推/拉模型/心跳/本地缓存） | ✅ 已覆盖 | Phase-4 #20 + mini-nacos | — | 已覆盖 |
| Eureka vs Nacos vs ZooKeeper 对比 | ❌ 未涉及 | — | ✅ 可补充 | Phase-4 #20 前置知识文档 |

#### 011 HTTP 服务架构升级

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| 连接池原理与调优（HikariCP/HTTP连接池） | ⚠️ HikariCP有参数配置，缺原理讲解 | Phase-5 #24 | ✅ 可补充 | Phase-5 #24 |
| Tomcat NIO vs NIO2 vs APR 模式对比 | ❌ 未涉及 | — | ⚠️ 可补充 | Phase-5 #24 前置知识（容器化下NIO为主，APR意义降低） |
| Ingress Controller 连接管理 | ❌ 未涉及 | — | ✅ 可补充 | Phase-6 #32 K8s部署专题 |
| Servlet 3.0 异步处理 | ❌ 未涉及 | — | ⚠️ 可补充 | Phase-5 #24，与WebFlux对比学习 |

#### 012 RPC 架构升级

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| RPC 框架核心设计（序列化/网络层/协议栈） | ✅ mini-rpc 已实现 | sca-demo mini-rpc | — | 已覆盖 |
| RPC 连接池复用与长连接管理 | ❌ mini-rpc缺连接池 | — | ✅ 可补充 | mini-rpc 迭代升级 |
| 序列化协议对比（Hessian/Protobuf/JSON/Kryo） | ❌ 未涉及 | — | ✅ 可补充 | mini-rpc 前置知识文档 |
| RPC 负载均衡策略对比 | ⚠️ 简单实现 | mini-rpc | ✅ 可补充 | mini-rpc 迭代升级 |

#### 013 MySQL 数据库高可用

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| MySQL 主从复制原理（binlog/relaylog/GTID） | ⚠️ 仅在踩坑指南提了主从延迟 | Phase-6 #40 | ✅ 可补充 | Phase-5 #26 扩充或新增专题 |
| 读写分离路由策略（ShardingSphere proxy/jdbc模式） | ⚠️ 仅提了HintManager强制走主库 | Phase-6 #40 | ✅ 可补充 | Phase-5 #26 扩充 |
| 数据源架构（多数据源 + AbstractRoutingDataSource + 动态切换） | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 #26 扩充 |
| 主从延迟处理（半同步复制/并行复制/强制走主库） | ⚠️ 仅提了HintManager | Phase-6 #40 | ✅ 可补充 | Phase-5 #26 扩充 |
| MySQL 高可用方案对比（MHA/Orchestrator/MySQL InnoDB Cluster/Vitess） | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 #26 扩充 |
| 容器化 MySQL（Operator/RDS托管 vs 自建） | ❌ 未涉及 | — | ✅ 可补充 | Phase-6 #32 扩充 |

#### 014 数据存储高并发高性能

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| MyBatis 核心组件交互（SqlSessionFactory→Executor→Plugin拦截链） | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 #26 前置知识文档 |
| MyBatis 批量操作优化（BatchExecutor vs ReuseExecutor） | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 #26 |
| 分库分表实战 | ✅ 已有 ShardingSphere 分表实践 | Phase-5 #26 | — | 已覆盖 |
| 分页插件原理（PageHelper拦截机制） | ❌ 未涉及 | — | ⚠️ 可补充 | Phase-5 #26 前置知识文档 |

#### 015 Spring Web Reactive

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| Reactive 编程模型（Mono/Flux/操作符/Backpressure） | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 新增专题或前置知识文档 |
| WebFlux vs Servlet 对比（线程模型/适用场景/性能差异） | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 新增专题 |
| WebClient 非阻塞调用 | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 #24 异步优化章节 |
| Spring WebFlux + Netty 运行原理 | ❌ 未涉及 | — | ⚠️ 可补充 | Phase-5 新增专题 |

#### 016-017 分布式事件设计 + 小伙伴定制

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| Spring ApplicationEvent 本地事件机制 | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 扩充，为分布式事件打基础 |
| Redis Pub/Sub 跨JVM事件传播 | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 扩充 |
| 事件驱动架构模式（Event Sourcing/CQRS简介） | ❌ 未涉及 | — | ⚠️ 可补充 | Phase-5 前置知识文档 |
| Spring Redis 分布式事件组合设计（ApplicationEvent + Pub/Sub + 本地监听） | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 扩充 |

#### 018 分布式事件高性能高可用

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| 分布式事件的可靠性保证（至少一次投递/幂等消费） | ✅ MQ消息可靠性已覆盖 | Phase-6 #38 | — | 已覆盖 |
| Spring Redis 分布式事件实战 | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 扩充 |
| 事件订阅的动态管理 | ❌ 未涉及 | — | ⚠️ 可补充 | Phase-5 扩充 |

#### 019 Reactive 异步服务

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| I/O 模型原理（BIO/NIO/AIO/epoll/select/poll） | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 新增专题（理解高并发底层的基础） |
| 内核态/用户态数据拷贝与零拷贝 | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 新增专题 |
| Netty Reactor 线程模型（Boss/Worker Group） | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 新增专题 |
| CompletableFuture 异步编排 | ✅ Feed流并行聚合已用 | Phase-3 #14 | — | 已覆盖 |
| Reactor 异步编程实战 | ❌ 未涉及 | — | ⚠️ 可补充 | Phase-5 新增专题 |

#### 020-021 API 网关 + RPC 网关

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| API 网关架构设计（路由/鉴权/限流/熔断/协议转换） | ✅ Spring Cloud Gateway已落地 | Phase-4 #18 + Phase-7 #44 | — | 已覆盖 |
| RPC 网关设计（非HTTP网关，Dubbo/Triple协议） | ❌ 未涉及 | — | ⚠️ 可补充 | Phase-4 #20 扩充（与mini-rpc对照） |
| 网关与多活路由联动 | ✅ 已设计 | Phase-7 #44 | — | 已覆盖 |

#### 022-023 Istio 服务网格

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| 服务网格概念与价值（Sidecar/数据面/控制面） | ❌ 未涉及 | — | ✅ 可补充 | Phase-7 扩充（与多活天然关联） |
| Istio 流量管理（VirtualService/DestinationRule/Gateway） | ❌ 未涉及 | — | ✅ 可补充 | Phase-7 扩充 |
| Envoy 代理与 xDS 协议 | ❌ 未涉及 | — | ✅ 可补充 | Phase-7 扩充 |
| Istio 可观测性（Kiali/Jaeger/Prometheus集成） | ❌ 未涉及 | — | ⚠️ 可补充 | Phase-7 扩充 |

#### 024-025 Dubbo 内核 + Dubbo Mesh

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| Dubbo SPI 扩展机制（@SPI/@Adaptive/@Activate） | ❌ 未涉及 | — | ✅ 可补充 | mini-rpc 前置知识文档（对照学习） |
| Dubbo 架构（Router/Filter/LoadBalance/Cluster扩展点） | ❌ 未涉及 | — | ✅ 可补充 | mini-rpc 前置知识文档 |
| Dubbo 整合 xDS/Envoy（Dubbo Mesh） | ❌ 未涉及 | — | ⚠️ 可补充 | Phase-7 扩充（与Istio形成对照） |
| Triple 协议（基于HTTP/2的RPC协议） | ❌ 未涉及 | — | ⚠️ 可补充 | mini-rpc 迭代升级 |

#### 026-028 配置中心（Nacos + etcd + 手写客户端）

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| Nacos Config 原理（长轮询/MD5比对/灰度发布） | ⚠️ 有配置管理实践，缺原理 | Phase-6 #35 | ✅ 可补充 | Phase-6 #35 扩充 |
| etcd Watch/Lease 机制 | ❌ 未涉及 | — | ✅ 可补充 | Phase-6 #35 扩充（与Nacos对比） |
| 分布式配置客户端手写实现（长轮询/Watch/本地缓存/版本对比） | ❌ 未涉及 | — | ✅ 可补充 | Phase-6 #35 前置知识文档（与mini-nacos结合） |
| 配置中心选型对比（Nacos vs Apollo vs etcd vs Spring Cloud Config） | ❌ 未涉及 | — | ✅ 可补充 | Phase-6 #35 前置知识文档 |

#### 029 GraalVM 基础

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| GraalVM 架构（JIT编译器/SubstrateVM/Truffle） | ❌ 未涉及 | — | ⚠️ 暂不补 | 优先级低，云原生方向，my-xhs当前阶段用不上 |
| AOT 编译原理 | ❌ 未涉及 | — | ⚠️ 暂不补 | 同上 |
| Native Image 构建与限制（反射/动态代理/资源文件需配置） | ❌ 未涉及 | — | ⚠️ 暂不补 | 同上 |

#### 030 日志平台

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| 日志采集（Filebeat/Fluentd） | ✅ ELK体系已设计 | Phase-6 #31 | — | 已覆盖 |
| 日志聚合与检索（Elasticsearch/Kibana） | ✅ 已设计 | Phase-6 #31 | — | 已覆盖 |
| 结构化日志（JSON格式/MDC链路ID） | ✅ 已涉及 | Phase-6 #31 | — | 已覆盖 |

#### 031 监控平台

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| 指标监控（Micrometer + Prometheus） | ✅ 已设计 | Phase-5 #25 + Phase-6 #31 | — | 已覆盖 |
| 告警规则与通知（Prometheus AlertManager） | ✅ 已设计 | Phase-5 #25 | — | 已覆盖 |
| Grafana 可视化大屏 | ✅ 已设计 | Phase-5 #25 | — | 已覆盖 |

#### 032-033 Spring Native + Java Native 应用

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| Spring Native / Spring Boot 3 AOT 编译 | ❌ 未涉及 | — | ⚠️ 暂不补 | 优先级低，Spring Native 生态尚不成熟 |
| Java Native Image 实战（编译/部署/调试） | ❌ 未涉及 | — | ⚠️ 暂不补 | 同上 |
| Native Image 兼容性分析（反射/动态代理/JNI限制） | ❌ 未涉及 | — | ⚠️ 暂不补 | 同上 |

#### 036 现代 Java 发展与变化

| 知识点 | my-xhs 是否已有 | 已有位置 | 能否补充 | 建议补充位置 |
|--------|----------------|---------|---------|------------|
| Virtual Threads（Project Loom） | ❌ 未涉及 | — | ✅ 可补充 | Phase-5 #24 扩充（对高并发模型有革命性影响，Spring Boot 3.2+已支持） |
| Record Class | ❌ 未涉及 | — | ✅ 可补充 | 项目代码可直接使用，简化DTO |
| Sealed Class | ❌ 未涉及 | — | ⚠️ 可补充 | 优先级低 |
| Pattern Matching / Switch表达式 | ❌ 未涉及 | — | ⚠️ 可补充 | 代码风格优化，优先级低 |

### A.3 知识点覆盖汇总

> 按优先级分类：✅已覆盖 → ⚠️浅覆盖→可补充 → ❌未覆盖→能补 → 🚫暂不补

| 优先级 | 知识点 | 来源课程 | my-xhs现状 | 补充位置 |
|--------|--------|---------|-----------|------------|
| **P0** | JVM 内存模型与GC调优（G1/ZGC参数/日志分析） | 007 | ✅ §3.24.7(1)(2) | Phase-5 #24 已补充 |
| **P0** | JVM 生产故障排查（OOM/CPU飙升/死锁→jmap/jstack/Arthas） | 007 | ✅ §3.24.7(4) | Phase-5 #24 已补充 |
| **P0** | 容器化JVM调优（cgroup感知/MaxRAMPercentage/容器OOMKilled vs JVM OOM） | 007 | ✅ §3.24.7(3) | Phase-5 #24 已补充 |
| **P0** | MySQL主从复制（binlog/GTID/级联复制/并行复制） | 013 | ✅ §3.26.7(1) | Phase-5 #26 已补充 |
| **P0** | 读写分离路由（ShardingSphere proxy/jdbc + 动态数据源切换） | 013 | ✅ §3.26.7(2) | Phase-5 #26 已补充 |
| **P0** | I/O 模型原理（BIO/NIO/AIO/epoll/零拷贝） | 019 | ✅ §3.27.5.1-.3 | Phase-5 新增专题已补充 |
| **P0** | Netty Reactor 线程模型 | 019 | ✅ §3.27.5.4 | Phase-5 新增专题已补充 |
| **P1** | MySQL 高可用方案对比（MHA/Orchestrator/InnoDB Cluster/Vitess/Operator） | 013 | ✅ §3.26.7(3) | Phase-5 #26 已补充 |
| **P1** | MyBatis 核心组件交互（SqlSessionFactory/Executor/Plugin拦截链） | 014 | ✅ §3.26.7(5) | Phase-5 #26 已补充 |
| **P1** | 服务网格（Istio概念/流量管理/Envoy/xDS） | 022-023 | ✅ §3.42.8 | Phase-7 已补充 |
| **P1** | Dubbo SPI扩展机制与架构深度 | 024 | ✅ §3.20.8 | Phase-4 #20 已补充 |
| **P1** | Spring Web Reactive（Mono/Flux/WebFlux vs Servlet对比） | 015 | ✅ §3.27.5.6 | Phase-5 新增专题已补充 |
| **P1** | 分布式事件设计（ApplicationEvent + Redis Pub/Sub跨JVM） | 016-018 | ✅ §3.28.9 | Phase-5 #28 已补充 |
| **P1** | etcd Watch/Lease机制 + 配置中心选型对比 | 026-027 | ✅ §3.35.7(1)(2) | Phase-6 #35 已补充 |
| **P1** | 分布式配置客户端手写实现 | 028 | ✅ §3.35.7(3) | Phase-6 #35 已补充 |
| **P1** | Virtual Threads（Project Loom） | 036 | ✅ §3.24.7(6) | Phase-5 #24 已补充 |
| **P1** | RPC 连接池复用 + 序列化协议对比 | 012 | ✅ §3.20.8(4)(5) | Phase-4 #20 已补充 |
| **P2** | Tomcat NIO vs APR模式对比 | 011 | ✅ §3.27.5.5 | Phase-5 新增专题已补充 |
| **P2** | 微服务拆分策略理论 | 008 | ✅ §3.20.6 | Phase-4 #20 已补充 |
| **P2** | Eureka vs Nacos vs ZK 注册中心对比 | 009 | ✅ §3.20.5 | Phase-4 #20 已补充 |
| **P2** | Dubbo Mesh（xDS整合） | 025 | ⚠️ xDS在§3.42.8有涉及 | Phase-7 已部分补充 |
| **P2** | RPC 网关设计 | 021 | ⚠️ Gateway已落地 | 需补充RPC网关与HTTP网关对比 |
| **P2** | 事件驱动架构模式（Event Sourcing/CQRS） | 016 | ✅ §3.28.9(3) | Phase-5 #28 已补充 |
| **P2** | Record Class + Pattern Matching 等Java新特性 | 036 | ❌ 未涉及 | 项目代码直接使用 |
| 🚫 | GraalVM AOT / Native Image | 029, 032-033 | ❌ 未涉及 | 云原生方向，当前优先级低 |
| 🚫 | Spring Native / Java Native 实战 | 032-033 | ❌ 未涉及 | 生态尚不成熟，暂不补 |

### A.4 my-xhs 项目差异化优势

> 以下知识点是 my-xhs 项目已有但 Stage-3 课程未深入涉及的领域。

| my-xhs 知识点 | Phase | 价值 |
|---|---|---|
| Feed 流推拉混合架构 | Phase-3 #14 | 实战型内容，Stage-3 偏理论 |
| 搜索引擎 ES 双索引 + Canal 同步 | Phase-3 #15 | ES 实战深度高于课程 |
| 推荐系统 5 路召回 | Phase-3 #17 | Stage-3 未涉及推荐系统 |
| 热搜榜滑动窗口 | Phase-3 #16 | 实时计算实践 |
| 分库分表实战（ShardingSphere） | Phase-5 #26 | Stage-3 偏 MySQL 架构，my-xhs 有分表实践 |
| 全链路压测 | Phase-6 #41 | Stage-3 缺少压测方法论 |
| 混沌工程 & 故障演练 | Phase-6 #29 | Stage-3 未涉及 |
| 多活架构（同城双活/区域路由/数据双向同步） | Phase-7 | Stage-4 内容，Stage-3 未涉及 |
| 消息可靠性全链路 | Phase-6 #38 | 事务消息+幂等+死信队列完整覆盖 |
| 安全合规 | Phase-6 #30 | Stage-3 未涉及安全 |
| K8s 容器化部署 | Phase-6 #31 #32 | Stage-3 缺少容器化视角 |

### A.5 核心行动建议

> ✅ 表示已完成，⬜ 表示待完成。

1. ✅ **最紧急**：已补充 **JVM 调优与线上故障排查** 到 Phase-5 §3.24.7，覆盖：(1) 内存模型+GC选型（G1/ZGC/Shenandoah对比） (2) OOM/CPU飙升/死锁/GC线上排查全流程（jmap/jstack/Arthas/JFR） (3) 容器化场景（cgroup感知/MaxRAMPercentage/容器OOMKilled vs JVM OOM/内存规划公式）
2. ✅ **最核心**：已补充 **MySQL 高可用架构** 到 Phase-5 §3.26.7，覆盖：(1) 主从复制原理（binlog/GTID/半同步/并行复制/主从延迟根因） (2) 读写分离路由3方案（ShardingSphere JDBC/Proxy/AbstractRoutingDataSource） (3) 高可用6方案对比（含容器化场景选型，MHA/Orchestrator/InnoDB Cluster/Vitess/Operator/云RDS）
3. ✅ **最底层**：已补充 **I/O 模型与异步编程** 作为 Phase-5 §3.27.5 新增专题，覆盖：(1) BIO/NIO/AIO/epoll原理+select/poll对比 (2) 零拷贝技术（sendfile/mmap/splice） (3) Netty Reactor线程模型（Boss/Worker Group） (4) Tomcat NIO vs APR对比 (5) Spring WebFlux vs Servlet批判性分析
4. ✅ **最演进**：已补充 **服务网格** 到 Phase-7 §3.42.8，覆盖 Istio 核心概念、VirtualService/DestinationRule YAML、SDK vs 服务网格对比、my-xhs选型建议、xDS协议、演进路径
5. ✅ **最拓展**：已补充 **Dubbo SPI + 架构深度** 到 Phase-4 §3.20.8，覆盖 SPI vs Java SPI、@SPI/@Adaptive/@Activate注解、Dubbo架构扩展点全景、与mini-rpc对照学习、序列化协议对比
6. ✅ **最实用**：已补充 **分布式事件设计** 到 Phase-5 §3.28.9，覆盖 3种方案选型、Spring ApplicationEvent + Redis Pub/Sub 跨JVM组合设计（完整代码示例）、Event Sourcing/CQRS/Saga/Outbox模式
7. ✅ **最前沿**：已补充 **Virtual Threads** 到 Phase-5 §3.24.7(6)，覆盖 Platform vs Virtual Thread对比、Spring Boot 3.2启用方式、批判性思考（synchronized/ThreadLocal不兼容）、my-xhs迁移路径
8. ✅ **额外补充**：注册中心选型对比（Phase-4 §3.20.5）、微服务拆分策略理论（Phase-4 §3.20.6）、配置中心选型对比+etcd Watch/Lease/CAS（Phase-6 §3.35.7）、手写配置客户端5大要点（Phase-6 §3.35.7(3)）
9. ⬜ **待补充**：Dubbo Mesh xDS整合深度（§3.42.8已有xDS基础）、RPC网关与HTTP网关对比、Java新特性（Record Class/Pattern Matching/Sealed Classes）在项目中的应用