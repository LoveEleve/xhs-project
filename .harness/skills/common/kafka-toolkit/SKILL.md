---
name: kafka-toolkit
stage: 组件封装
description: Kafka 工具封装——消费者封装（幂等消费/重试/DLQ）、生产者封装（确认/重试）、监控指标
---

# Kafka 工具封装（kafka-toolkit）

## 1. 职责

为项目生成 Kafka 消息中间件基础设施代码，包括消费者封装（幂等消费/重试/DLQ）、生产者封装（确认/重试）、Lag 监控。**禁止手动消费 Kafka 消息而不经过封装层**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/kafka-toolkit [consumer\|producer\|dlq\|monitor\|all]` |
| 自动加载 | coding-skill 阶段检测到 `Kafka` / `Consumer` / `Producer` / `@KafkaListener` 时 |

## 3. 前置条件

- 项目已识别技术栈（Java Spring Kafka / Go sarama-cluster / Python kafka-python 等）
- 已确定目标包路径（按语言规范）
- 已确定 Topic 名称和消息格式
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `consumer` | ConsumerWrapper + 幂等过滤 | 出现 `@KafkaListener` / `consumer.poll()` |
| `producer` | ProducerWrapper | 出现 `kafkaTemplate.send()` / `producer.send()` |
| `dlq` | 死信队列 + 重试 Topic | 消费者无异常处理或 DLQ |
| `monitor` | Lag 监控 + 告警 | 无消费者监控指标 |
| `all`（默认） | 全部组件 | 首次引入 Kafka |

### Step 2: 生成代码文件

```
# Java 示例
{basePackage}/kafka/
├── consumer/
│   ├── ConsumerWrapper.java         — 消费者封装（幂等过滤 + 重试 + DLQ）
│   ├── IdempotentConsumer.java      — 幂等消费处理器接口
│   └── RetryableConsumer.java       — 可重试消费者（重试 Topic 路由）
├── producer/
│   ├── ProducerWrapper.java         — 生产者封装（acks=all + 重试 + 回调）
│   └── TransactionalProducer.java   — 事务生产者（批量发送原子性）
├── dlq/
│   ├── DeadLetterQueue.java         — 死信队列处理
│   └── DqlMessage.java              — DLQ 消息结构
└── monitor/
    ├── LagMonitor.java              — Lag 监控（指标暴露）
    └── KafkaHealthIndicator.java    — 健康检查

# 其他语言按对应命名规范生成
```

**每个组件的关键实现约束**：

- `ConsumerWrapper`：手动提交 offset（处理完再提交），幂等过滤（去重表/布隆过滤器/业务幂等三选一），重试三次后入 DLQ
- 重试策略：首次失败→retry-1 Topic（延迟 10s），再次失败→retry-2 Topic（延迟 60s），第三次→DLQ
- `ProducerWrapper`：acks=all，retries=3，异步回调记录发送结果，发送失败记录到本地文件 + 定时重扫
- `DeadLetterQueue`：DLQ 消息结构包含 originalTopic/originalKey/originalValue/error/retryCount/failedAt/traceId
- `LagMonitor`：按 Topic×Partition 暴露 Lag 指标，对接 Prometheus/Grafana，告警规则：Lag>10000→Warning，持续增长5分钟→Critical

### Step 3: 生成测试

```
{basePackage}/kafka/
├── consumer/ConsumerWrapperTest.java
├── producer/ProducerWrapperTest.java
├── dlq/DeadLetterQueueTest.java
└── monitor/LagMonitorTest.java
```

测试要求：
- 消费者：验证幂等过滤（重复消息被跳过）、重试三次后入 DLQ、手动 offset 提交
- 生产者：验证发送确认、重试机制、回调记录
- DLQ：验证消息结构、重新投递到原 Topic
- 监控：验证 Lag 指标暴露格式
- 使用 Mock/Embedded Kafka 测试

### Step 4: 验证

1. 编译通过 + 测试通过
2. 检查清单：
   - [ ] 所有消费者经过 `ConsumerWrapper` 封装
   - [ ] 所有生产者经过 `ProducerWrapper` 封装
   - [ ] 手动提交 offset（非自动提交）
   - [ ] 重试有上限（非无限重试）
   - [ ] DLQ 消息结构完整
   - [ ] Lag 监控已暴露

## 5. 输出文件清单

```
{targetPackage}/kafka/
├── consumer/ConsumerWrapper.{java|py|go}
├── consumer/IdempotentConsumer.{java|py|go}
├── consumer/RetryableConsumer.{java|py|go}
├── producer/ProducerWrapper.{java|py|go}
├── producer/TransactionalProducer.{java|py|go}
├── dlq/DeadLetterQueue.{java|py|go}
├── dlq/DqlMessage.{java|py|go}
├── monitor/LagMonitor.{java|py|go}
├── monitor/KafkaHealthIndicator.{java|py|go}
├── consumer/ConsumerWrapperTest.{java|py|go}
├── producer/ProducerWrapperTest.{java|py|go}
├── dlq/DeadLetterQueueTest.{java|py|go}
└── monitor/LagMonitorTest.{java|py|go}
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ 所有消费者经过封装层（非裸 `@KafkaListener`）
- ✅ 手动提交 offset（非 `enable.auto.commit=true`）
- ✅ 重试有上限 + DLQ 兜底
- ✅ 生产者 acks=all
- ✅ Lag 监控已对接指标系统
- ❌ 禁止自动提交 offset
- ❌ 禁止无限重试（无 DLQ）
- ❌ 禁止生产者 acks=0 或 acks=1

## 7. 约束

- ❌ 不生成 Topic 创建脚本（Topic 由运维或 IaC 管理）
- ❌ 不修改已有的消费者/生产者代码（只新增封装层，原代码重构到封装层）
- ❌ 不引入未确认的 Kafka 客户端版本（项目中已有的优先复用）
- ❌ 不擅自决定反序列化方式（AVRO/JSON/Protobuf 由用户指定）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
