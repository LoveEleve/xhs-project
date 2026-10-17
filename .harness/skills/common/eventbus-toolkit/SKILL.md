---
name: eventbus-toolkit
stage: 组件封装
description: 事件总线工具封装——同步/异步事件、事务事件（事务提交后发）、事件追踪（发布/消费记录）、事件总线配置
---

# 事件总线工具封装（eventbus-toolkit）

## 1. 职责

为项目生成事件总线基础设施代码，包括同步/异步事件发布、事务事件（事务提交后发布）、事件追踪（发布/消费记录 + 监控）。**禁止在业务代码中直接使用 `ApplicationEventPublisher` 而不经过事件总线封装层**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/eventbus-toolkit [sync|async|transactional|trace|all]` |
| 自动加载 | coding-skill 阶段检测到 `ApplicationEventPublisher` / `@EventListener` / `EventBus` / `go-eventbus` 时 |

## 3. 前置条件

- 项目已识别技术栈+事件框架（Spring Events / Guava EventBus / Go eventbus / Python PyPubSub）
- 已确定目标包路径（按语言规范）
- 已确定已定义的事件类型列表
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `sync` | 同步事件总线 | 存在事件发布但无异步需求 |
| `async` | 异步事件总线 | 事件处理耗时，需要异步 |
| `transactional` | 事务事件 | 事件需要在事务提交后才发布 |
| `trace` | 事件追踪 | 事件发布/消费无记录 |
| `all`（默认） | 全部组件 | 首次引入事件驱动架构 |

### Step 2: 生成代码文件

```
# Java 示例
{basePackage}/eventbus/
├── core/
│   ├── EventBus.java                   — 事件总线接口（publish）
│   ├── DomainEvent.java                — 领域事件基类（事件ID/聚合根ID/时间戳/来源）
│   └── EventPriority.java              — 事件优先级（HIGH/NORMAL/LOW）
├── bus/
│   ├── SyncEventBus.java               — 同步事件总线（同一线程发布和消费）
│   ├── AsyncEventBus.java              — 异步事件总线（线程池消费）
│   └── TransactionalEventBus.java      — 事务事件总线（事务提交后发布）
├── annotation/
│   ├── EventHandler.java               — 事件处理器注解（异步/同步/优先级/重试）
│   └── TransactionalEvent.java         — 事务事件注解
├── store/
│   ├── EventRecord.java                — 事件记录实体（事件ID/类型/状态/发布时间/消费时间）
│   ├── EventRecordRepository.java      — 事件记录存储
│   └── EventTracer.java                — 事件追踪器（发布/消费/失败记录）
├── retry/
│   ├── EventRetryStrategy.java         — 事件重试策略（最大次数/间隔）
│   └── EventRetryProcessor.java        — 事件重试处理器（定时扫描失败事件重新发布）
└── config/
    └── EventBusConfig.java             — 配置（线程池大小/重试次数/事件记录开关）

# 其他语言按对应命名规范生成
```

**每个组件的关键实现约束**：

- `SyncEventBus`：同一线程同步发布和消费，适用于同一事务内的事件处理（如更新库存后同步扣减），消费异常抛出终止当前事务
- `AsyncEventBus`：线程池异步消费，线程池大小可配置，使用 `@Async` 或自定义线程池，消费异常记录日志并触发重试
- `TransactionalEventBus`：在 @Transactional 事务方法中发布事件，事务提交后统一消费，事务回滚则事件不发布（使用 Spring `TransactionSynchronizationManager` 注册回调）
- `DomainEvent`：基类字段 eventId（UUID）、aggregateRootId（聚合根ID）、occurredAt（时间戳）、source（发布来源）
- 事件追踪：记录事件发布（事件ID/类型/状态/发布时间）、消费（事件ID/消费者/消费时间/结果）、失败（失败原因/重试次数），事件记录持久化到数据库

### Step 3: 生成测试

```
{basePackage}/eventbus/
├── core/SyncEventBusTest.java
├── core/AsyncEventBusTest.java
├── bus/TransactionalEventBusTest.java
├── store/EventTracerTest.java
└── retry/EventRetryProcessorTest.java
```

测试要求：
- 同步事件：验证同一线程发布消费、消费异常终止
- 异步事件：验证线程池消费、异常日志记录
- 事务事件：验证事务提交后发布、事务回滚不发布
- 事件追踪：验证发布/消费/失败记录完整性

### Step 4: 验证

1. 编译通过 + 测试通过
2. 检查清单：
   - [ ] 所有事件发布经过 `EventBus` 封装（非直接 `applicationEventPublisher.publishEvent()`）
   - [ ] 事务事件在事务提交后发布（非事务中发布）
   - [ ] 异步事件有线程池配置（非无界线程池）
   - [ ] 事件记录已持久化
   - [ ] 失败事件有重试机制

## 5. 输出文件清单

```
{targetPackage}/eventbus/
├── core/EventBus.{java|py|go}
├── core/DomainEvent.{java|py|go}
├── core/EventPriority.{java|py|go}
├── bus/SyncEventBus.{java|py|go}
├── bus/AsyncEventBus.{java|py|go}
├── bus/TransactionalEventBus.{java|py|go}
├── annotation/EventHandler.{java|py|go}
├── annotation/TransactionalEvent.{java|py|go}
├── store/EventRecord.{java|py|go}
├── store/EventRecordRepository.{java|py|go}
├── store/EventTracer.{java|py|go}
├── retry/EventRetryStrategy.{java|py|go}
├── retry/EventRetryProcessor.{java|py|go}
├── config/EventBusConfig.{java|py|go}
├── core/SyncEventBusTest.{java|py|go}
├── core/AsyncEventBusTest.{java|py|go}
├── bus/TransactionalEventBusTest.{java|py|go}
├── store/EventTracerTest.{java|py|go}
└── retry/EventRetryProcessorTest.{java|py|go}
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ 所有事件经过 EventBus 封装
- ✅ 事务事件在事务提交后发布
- ✅ 异步事件有线程池配置
- ✅ 事件记录已持久化
- ✅ 失败事件有重试机制
- ❌ 禁止直接使用 `ApplicationEventPublisher.publishEvent()`（应经过 EventBus 封装）
- ❌ 禁止事务事件在事务中直接发布（会因事务回滚导致事件误发）
- ❌ 禁止异步事件无界线程池（会耗尽系统资源）
- ❌ 禁止事件失败无重试（会丢失事件处理）

## 7. 约束

- ❌ 不生成事件定义的具体业务逻辑（只生成事件基础设施）
- ❌ 不修改已有的事件处理代码（只新增封装层，建议用户逐步迁移）
- ❌ 不引入未确认的事件框架版本
- ❌ 不生成消息队列集成（Kafka/RocketMQ 由对应 toolkit 处理）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
