---
name: rocketmq-toolkit
stage: 组件封装
description: RocketMQ 工具封装——事务消息（半消息+反查）、顺序消息、延迟消息、重试/DLQ、生产者/消费者封装
---

# RocketMQ 工具封装（rocketmq-toolkit）

## 1. 职责

为项目生成 RocketMQ 消息中间件基础设施代码，包括生产者封装、消费者封装（幂等/重试/DLQ）、事务消息（半消息+反查）、顺序消息、延迟消息。**禁止直接操作 RocketMQ 客户端而不经过封装层**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/rocketmq-toolkit [producer\|consumer\|transaction\|order\|delay\|all]` |
| 自动加载 | coding-skill 阶段检测到 `RocketMQ` / `MQProducer` / `MQPushConsumer` / `@RocketMQListener` 时 |

## 3. 前置条件

- 项目已识别技术栈（Java RocketMQ-Client / Python rocketmq-client / Go rocketmq-client）
- 已确定目标包路径（按语言规范）
- 已确定 NameServer 地址或 ACK 地址
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `producer` | ProducerWrapper | 检测到 `MQProducer` / `DefaultMQProducer` |
| `consumer` | ConsumerWrapper + 幂等 + DLQ | 检测到 `MQPushConsumer` / `DefaultMQPushConsumer` |
| `transaction` | 事务消息 | 检测到 `TransactionMQProducer` / 事务相关逻辑 |
| `order` | 顺序消息 | 检测到顺序消费需求（队列选择器） |
| `delay` | 延迟消息 | 检测到延迟消息需求 |
| `all`（默认） | 全部组件 | 首次引入 RocketMQ |

### Step 2: 生成代码文件

```
# Java 示例
{basePackage}/rocketmq/
├── producer/
│   ├── ProducerWrapper.java          — 生产者封装（同步/异步/单向）
│   ├── TransactionProducer.java      — 事务消息生产者（半消息+反查）
│   └── OrderProducer.java            — 顺序消息生产者（队列选择器）
├── consumer/
│   ├── ConsumerWrapper.java          — 消费者封装（幂等过滤 + 重试 + DLQ）
│   ├── OrderConsumer.java            — 顺序消息消费者（单队列消费）
│   └── BroadcastConsumer.java        — 广播模式消费者
├── transaction/
│   ├── TransactionListener.java      — 事务反查监听器接口
│   └── TransactionChecker.java       — 反查实现（检查本地事务状态）
├── dlq/
│   ├── DeadLetterQueue.java          — 死信队列处理
│   └── DqlRepublish.java             — DLQ 重新投递（定时重扫）
├── config/
│   ├── RocketMQConfig.java           — 配置（NameServer/线程数/超时）
│   └── RocketMQConstant.java         — 常量（Topic/Tag/消费者组）
└── monitor/
    └── RocketMQMonitor.java          — 消息积压监控

# 其他语言按对应命名规范生成
```

**每个组件的关键实现约束**：

- `ProducerWrapper`：支持同步（send）、异步（asyncSend）、单向（sendOneway）三种模式，发送失败自动重试（retries=3），发送结果回调日志
- 事务消息：发送半消息 → 执行本地事务 → 根据本地事务结果提交/回滚消息 → 反查机制（transactionCheckListener），反查间隔 60s，最大反查次数 15
- 顺序消息：生产者使用 `MessageQueueSelector`（按业务 ID 取模），消费者使用 `MessageListenerOrderly`（单队列顺序消费），消费失败本地重试（suspendCurrentQueueTimeMillis=1000）
- 延迟消息：延迟级别（1s/5s/10s/30s/1m/2m/3m/4m/5m/6m/7m/8m/9m/10m/20m/30m/1h/2h），封装为枚举，禁止直接使用 `message.setDelayTimeLevel(int)` 的魔法数字
- `ConsumerWrapper`：手动提交消费进度，幂等过滤（去重表/布隆过滤器），消费失败重试次数 16 次后入 DLQ，DLQ Topic 命名 `%DLQ%{consumerGroup}`

### Step 3: 生成测试

```
{basePackage}/rocketmq/
├── producer/ProducerWrapperTest.java
├── consumer/ConsumerWrapperTest.java
├── transaction/TransactionProducerTest.java
├── dlq/DeadLetterQueueTest.java
└── monitor/RocketMQMonitorTest.java
```

测试要求：
- 生产者：验证同步/异步/单向三种发送模式，验证重试机制
- 消费者：验证幂等过滤、消息重试、DLQ 入队
- 事务消息：验证半消息发送、本地事务执行回调、反查机制
- 使用 Mock/Embedded RocketMQ 测试

### Step 4: 验证

1. 编译通过 + 测试通过
2. 检查清单：
   - [ ] 所有生产者经过 `ProducerWrapper` 封装
   - [ ] 所有消费者经过 `ConsumerWrapper` 封装
   - [ ] 事务消息有反查实现（非空 `TransactionListener`）
   - [ ] 顺序消息使用 `MessageQueueSelector`（非随机选择队列）
   - [ ] 延迟消息使用枚举（非 `setDelayTimeLevel` 魔法数字）
   - [ ] 幂等过滤已实现（去重表/布隆过滤器）

## 5. 输出文件清单

```
{targetPackage}/rocketmq/
├── producer/ProducerWrapper.{java|py|go}
├── producer/TransactionProducer.{java|py|go}
├── producer/OrderProducer.{java|py|go}
├── consumer/ConsumerWrapper.{java|py|go}
├── consumer/OrderConsumer.{java|py|go}
├── consumer/BroadcastConsumer.{java|py|go}
├── transaction/TransactionListener.{java|py|go}
├── transaction/TransactionChecker.{java|py|go}
├── dlq/DeadLetterQueue.{java|py|go}
├── dlq/DqlRepublish.{java|py|go}
├── config/RocketMQConfig.{java|py|go}
├── config/RocketMQConstant.{java|py|go}
├── monitor/RocketMQMonitor.{java|py|go}
├── producer/ProducerWrapperTest.{java|py|go}
├── consumer/ConsumerWrapperTest.{java|py|go}
├── transaction/TransactionProducerTest.{java|py|go}
├── dlq/DeadLetterQueueTest.{java|py|go}
└── monitor/RocketMQMonitorTest.{java|py|go}
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ 生产者经过封装层
- ✅ 消费者经过封装层 + 幂等过滤
- ✅ 事务消息有反查实现
- ✅ 顺序消息使用队列选择器
- ✅ 延迟消息使用枚举封装
- ❌ 禁止直接使用 `setDelayTimeLevel` 魔法数字
- ❌ 禁止事务消息无反查（半消息无反查会超时回滚）
- ❌ 禁止顺序消息使用随机队列选择（会乱序）
- ❌ 禁止自动提交消费进度（应手动提交）

## 7. 约束

- ❌ 不生成 Topic/Group 创建脚本（由运维或控制台管理）
- ❌ 不修改已有 RocketMQ 客户端代码（只新增封装层）
- ❌ 不引入未确认的 RocketMQ 客户端版本
- ❌ 不擅自决定消息序列化方式（JSON/Protobuf 由用户指定）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
