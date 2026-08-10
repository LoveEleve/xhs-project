# 04 MQ 可靠性

> 复审维度 04 | 每个模块必查 | 9 透镜全覆盖，分布式消息链路的正确性为本维度核心
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。每个检查项标注了透镜类型，覆盖
> 业务逻辑/工程正确性/分布式正确性/生产级/可扩展性/并发/微服务/性能/盲区 全部 9 个维度。
> 注：MQ 乱序防护（版本号/时间戳原子检查）的详细规则见 03.3，本维度只提供入口引用。

---


**执行本维度后，必须在审查报告中输出 `[04] 04 MQ 可靠性：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [04]）。**
## 检查项

### 4.1 Consumer 幂等与去重 | 透镜：并发/工程

**必须检查**：Consumer 的去重 key 是否包含全部业务维度；去重操作是否原子。

**怎么查**：
```bash
grep -rn '@RocketMQMessageListener\|implements.*Consumer' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/
# 逐 Consumer 检查：去重 key 维度（是否含 userId+bizType+bizId）；去重实现是否原子 Lua
```

**判定**：
- 去重 key 用 `bizType:bizId` 缺 userId → 用户 A 的操作覆盖用户 B 的去重记录
- 去重用 SETNX 非原子 Lua → 并发双过
- 失败路径的 removeMark → 见 4.2

**案例**：`InventoryDeductConsumer` 失败不 `removeMark` 致 MQ 重投被吞（修复加 removeMark）；`OrderTransactionConsumer` 同样问题。

---

### 4.2 Consumer 失败处理与死信 | 透镜：工程/生产级/盲区

**必须检查**：Consumer 失败路径是否完整——removeMark、RECONSUME_LATER、死信队列监控。

**怎么查**：
```bash
grep -rn 'RECONSUME_LATER\|CONSUME_SUCCESS\|removeMark' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/
```

**判定**：

| 检查点 | 问题模式 |
|--------|---------|
| 失败不 removeMark | 消费失败标记仍在→MQ 认为已成功→永不重投 |
| 部分成功无回滚 | 操作 A 成功 + 操作 B 失败→A 不回滚（03.2 规则在此的MQ特化） |
| 死信无监控 | 消息在重试队列过期进入死信→无告警→永久丢失 |
| Consumer NPE/异常 | `getBody()`/`getTags()` null → NPE → 被 catch 吞掉→详见 4.6 |

**修复标准**：失败路径先 `removeMark` 再 `RECONSUME_LATER`；所有 Consumer 加死信计数 metric。

**案例**：`InventoryDeductConsumer` + `OrderTransactionConsumer` 双失败不 removeMark 吞消息；`CounterEventConsumer.getTags()` null→switch(null)→NPE 被 catch 吞掉。

---

### 4.3 Producer-Consumer 契约一致 | 透镜：分布式/工程/盲区

**必须检查**：Producer 发送的事件字段名和 Consumer 解析的字段名是否一致（camelCase 统一）；跨模块用同一业务 ID 时派生算法是否一致。

**怎么查**：
```bash
# Producer 发送的字段——找出所有 MessageBuilder / Event 构造
grep -rn 'new.*Event\|MessageBuilder\|build\(\)' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/ my-xhs-*/src/main/java/com/myxhs/*/service/ | head -30

# Consumer 解析的字段——逐 Consumer 对照
grep -rn 'getBody\|JSON.parseObject\|JSONObject' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/

# 跨模块 ID 派生——orderId/pseudoOrderId/derivePseudoOrderId
grep -rn 'derivePseudoOrderId\|pseudoOrderId\|translateOrderId\|fold.*hash' my-xhs-<module>/src/main/java/
```

**判定**：

| 检查点 | 问题模式 |
|--------|---------|
| 字段名不一致 | Producer 发 `event_time`(snake) / Consumer 读 `eventTime`(camel) → 字段为 null |
| ID 派生不一致 | order 用真实 orderId 释放库存，inventory 用 fold-hash pseudoOrderId 查询预扣→永不匹配 |
| 版本字段缺失 | Producer 不发 `eventTime`→Consumer 无版本比较→乱序零防护 |

**案例**：`InventoryOutboxSenderJob` payload 用 snake_case `event_time`，Consumer 读 `eventTime`（camelCase）→ null（修复统一 camelCase）；order `releaseInventory` 用真实 orderId 查不到 inventory 的 fold-hash pseudoOrderId→释放链路断裂。

---

### 4.4 Producer 发送可靠性 | 透镜：工程/业务

**必须检查**：每次 MQ 发送是否检查 `SendResult`/`SendStatus`；发送失败的地路径是否完整。

**怎么查**：
```bash
grep -rn 'syncSend\|asyncSend\|send(' my-xhs-<module>/src/main/java/com/myxhs/*/service/ -A3 | grep -B3 'SendStatus\|SendResult\|SEND_OK'
```

**判定**：

| 检查点 | 问题模式 |
|--------|---------|
| 不检查 SendStatus | `rocketMQTemplate.syncSend(...)` 返回值不检查→失败静默吞消息 |
| asyncSend 无回调 | 异步发送不设 SendCallback→失败无感知 |
| 同步发送在事务中 | `@Transactional` 内 syncSend→事务回滚但 MQ 已发出→幽灵消息 |
| compensateIncompletePush 无统一 | 每个 Consumer 自己写补偿→重复/不一致 |

**案例**：`sendFollowCounterEvent` 不检查 `SendStatus`→MQ 发送失败静默丢失；`compensateIncompletePush` 多处散落未统一→各模块独立补偿逻辑不一致。

---

### 4.5 Consumer 链路完整性 | 透镜：分布式/业务/盲区

**必须检查**：每个 MQ topic-tag 的 Producer 和 Consumer 是否一一对应——有没有 Producer 发出但无 Consumer 的消息、有没有 Consumer 监听但无 Producer 的 topic。

**怎么查**：
```bash
# 所有 Producer 发出的 topic+tag
grep -rn 'syncSend\|asyncSend\|send(' my-xhs-<module>/src/main/java/ -B2 | grep -E 'topic|tag|destination'
grep -rn 'send.*Event\|send.*Message' my-xhs-<module>/src/main/java/

# 所有 Consumer 监听的 topic+tag
grep -rn '@RocketMQMessageListener' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/ -A3

# 遍历链路：每个业务动作（confirm/release/cancel）的 Producer→Consumer 必须成对匹配
```

**判定**：
- Producer 有发送但无 Consumer 监听 → 消息发出无人消费→业务闭环断裂（如 confirm 有 Producer 无 Consumer）
- Consumer 有监听但无 Producer → 死代码或未来预留（标注即可，不强制删）
- topic-tag 名字一致但 Consumer Group 不同→同一条消息被重复消费（非幂等场景）→双扣/双计数

**案例**：`/api/inventory/confirm` 的 `InventoryDeductEvent` Producer 存在，但 confirm 对应的 Consumer 不存在→支付成功但库存从未确认扣减（confirm 链路断裂）。

---

### 4.6 Consumer NPE/异常防护 | 透镜：工程/盲区

**必须检查**：Consumer 对 MQ 消息字段的 null 防御——`getBody()`/`getTags()`/`getKeys()`/`orderNo`/`userId` 等可能为 null 的字段是否有检查。

**怎么查**：
```bash
grep -rn 'getBody()\|getTags()\|getKeys()\|getProperty' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/ -A5 | grep -B5 '\.equals\|switch\|Long.parse\|Integer.parseInt\|getBytes'
```

**判定**：

| 检查点 | 问题模式 |
|--------|---------|
| `msg.getTags()` null + `switch(tag)` | `switch(null)` → NPE → ack 前异常 → 消息被吞 |
| `msg.getBody()` null | `new String(null)` → NPE |
| `Long.parseLong(msg.getKeys())` null | NPE → 消息被吞 |
| `jsonObject.getString("orderNo")` 为 null 继续操作 | NPE 或拼出 `...:null` 的 Redis key |

**案例**：`CounterEventConsumer` 中 `msg.getTags()` 为 null 时 `switch(null)` → NPE→被外层 catch 吞掉（修复加 null 检查 + 非法 tag 默认分支）。

---

### 4.7 消息乱序防护 | 透镜：分布式/并发

> **此检查项的完整规则在 03 维度 "3.3 MQ 消费乱序防护"**——版本号维度/原子 Lua/时钟域/修复标准均为 03 的主规则。本维度仅提供入口交叉引用。

**必须检查**：Consumer 是否有乱序防护——或明确标注"本模块不要求严格顺序，无序消费可接受"。

**怎么查**：先在模块 Consumer 中找 versionKey/eventTime/actionTime 检查→如无，判断业务是否容忍乱序。

**判定**：无乱序防护 且 业务要求先 PRE_DEDUCT 再 CONFIRM→CONFIRM 先到操作失败 或 覆盖正确状态。

---

### 4.8 事务消息与 Outbox | 透镜：生产级/工程

> **Outbox 模式完整规则在 06 维度**——markSent 时机/SendResult 检查/cancelOutbox/Job 查 SendResult。本维度仅覆盖 MQ 侧的事务发送可靠性。

**必须检查**：如果有 `sendMessageInTransaction`（事务消息/半消息），check 回调是否正确查询本地事务状态；如果有 Outbox 表，Job 发送后是否正确更新状态。

**怎么查**：
```bash
grep -rn 'sendMessageInTransaction\|TransactionListener\|executeLocalTransaction\|checkLocalTransaction' my-xhs-<module>/src/main/java/
grep -rn 'outbox\|Outbox\|markSent\|cancelOutbox' my-xhs-<module>/src/main/java/
```

**判定**：
- `checkLocalTransaction` 返回 `UNKNOW` → 无限回查→Broker 压力
- Outbox Job 不检查 `SendResult`→发送失败仍 `markSent`→消息永久丢失
- Outbox Job payload 与 Consumer 解析格式不一致（见 4.3）

**案例**：07-inventory Outbox Job 不查 `SendResult`→发送失败仍标记成功（修复见 `InventoryOutboxSenderJob` 加 SendResult 检查）。

---

### 4.9 延迟消息正确性 | 透镜：工程/分布式

**必须检查**：所有延迟消息（`setDelayTimeLevel`）的延迟级别是否匹配业务需求；超时 Job 是否用了正确的延迟级别。

**怎么查**：
```bash
grep -rn 'setDelayTimeLevel\|delayLevel\|DELAY' my-xhs-<module>/src/main/java/
```

**判定**：
- delayLevel=3(10s) 但业务需要 30s→超时清理太早→并发乱序
- 延迟消息和 ZSet 索引双写写反→延迟到后再加索引→索引不清理
- RocketMQ 只支持 18 个预置级别（1s~2h）→超过 2h 的延迟需求不能用此机制

**案例**：`PreDeductTimeoutJob` 超时 60s 但用间隔执行代替延迟消息→旧架构（修复改 ZSet 索引 ZRANGEBYSCORE）。

---

### 4.10 Consumer Group 与消费模式 | 透镜：微服务/性能

**必须检查**：Consumer Group 的消费模式（CLUSTERING vs BROADCASTING）、消费线程数、消息拉取批量大小是否合理。

**怎么查**：
```bash
grep -rn 'consumeMode\|messageModel\|consumeThreadMax\|pullBatchSize' my-xhs-<module>/src/main/java/
grep -rn 'consumeMessageBatchMaxSize' my-xhs-<module>/src/main/resources/
```

**判定**：
- `BROADCASTING` 所有实例都消费全量→重复扣库存/重复发通知
- `consumeThreadMax=1` + 消息量大→消费堆积
- `pullBatchSize=1` → 逐条拉取浪费网络（默认 32 合理）
- CLUSTERING 下 rebalance 期间消息短暂重复→业务必须幂等（已由 4.1/4.7 覆盖）

**案例**：（全特性面预置检查项——my-xhs 02-07 未暴露 Group 配置问题，08-15 未审模块的 Group 配置需逐项核查。）

---

### 4.11 批量发送与消费性能 | 透镜：性能

**必须检查**：Producer 是否有逐条 `syncSend`（阻塞+1000 次网络 RTT）；Consumer 是否批量消费而非逐条处理。

**怎么查**：
```bash
# 循环内 send→逐条 RTT
grep -rn 'syncSend\|asyncSend' my-xhs-<module>/src/main/java/ -B5 | grep 'for\|forEach\|while'
```

**判定**：
- `for (items) { syncSend(...) }` → 1000 条 = 1000 次同步 RTT→秒级延迟
- 批量操作后逐条 send MQ（如删除笔记后为每个评论单独发 deleteCommentEvent）→MQ 风暴

**案例**：`deleteNote` 的 `afterCommit` 逐条 `syncSend` deleteCommentEvent→1000 条评论 = 1000 次 MQ 发送（已有 `compensateIncompletePush` 统一修复路径）。

---

### 4.12 MQ 中间件耦合度 | 透镜：可扩展性

**必须检查**：模块对 RocketMQ 的耦合深度——是否直接依赖 RocketMQ 特有 API（`RocketMQTemplate`/`@RocketMQMessageListener`）还是通过抽象层（Spring Cloud Stream）；切换 MQ 中间件的改动面。

**怎么查**：
```bash
grep -rn 'RocketMQTemplate\|@RocketMQMessageListener\|rocketMQTemplate' my-xhs-<module>/src/main/java/ | wc -l
grep -rn 'StreamBridge\|@Input\|@Output\|@EnableBinding' my-xhs-<module>/src/main/java/ | wc -l
```
前者远大于后者→绑定 RocketMQ API，切 Pulsar/Kafka 需改所有发送/消费代码。

**判定**：
- RocketMQ 特有 API 散落在 10+ 个 Service/Consumer→切换 MQ 为全模块重写
- 有统一的 `MessageSender` 封装层→切换只改适配层
- **记录即可，不强制修改。**

**案例**：（全特性面预置检查项——my-xhs 当前全量直接依赖 RocketMQTemplate，切 Pulsar 为全局改造。按模块实际 grep 结果填充。）

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| 消息乱序防护（版本号/时间戳） | 03.3 | 详见数据一致性维度 |
| Outbox 模式（markSent/SendResult/补偿） | 06 | 详见分布式事务维度 |
| 双写回滚（MQ 失败回滚 DB） | 03.2 | 详见数据一致性维度 |
| MQ Consumer 中的并发/锁 | 02.4 | 详见并发达维度 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl <module> -am
mvn test -pl <module>

# Consumer 列表
grep -rn '@RocketMQMessageListener' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/

# removeMark/RECONSUME_LATER 检查
grep -rn 'removeMark\|RECONSUME_LATER\|CONSUME_SUCCESS' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/

# SendStatus 检查
grep -rn 'syncSend\|asyncSend' my-xhs-<module>/src/main/java/com/myxhs/*/service/ -A3 | grep -B3 'SendStatus\|SEND_OK'

# 跨模块 ID 派生一致
grep -rn 'derivePseudoOrderId\|pseudoOrderId\|translateOrderId' my-xhs-<module>/src/main/java/

# Consumer NPE——getTags/getBody/getKeys null 防御
grep -rn 'getTags()\|getBody()\|getKeys()' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/ -A3 | grep -B3 'switch\|parse\|equals\|getBytes'

# 循环内逐条 send
grep -rn 'syncSend\|asyncSend' my-xhs-<module>/src/main/java/ -B5 | grep -e 'for' -e 'forEach' -e 'while'

# 延迟消息级别
grep -rn 'setDelayTimeLevel\|delayLevel' my-xhs-<module>/src/main/java/

# MQ 中间件绑定
grep -rn 'RocketMQTemplate\|@RocketMQMessageListener' my-xhs-<module>/src/main/java/ | wc -l

# topic-tag 全量审计（需逐 Consumer 核对）
grep -rn '@RocketMQMessageListener' my-xhs-*/src/main/java/com/myxhs/*/consumer/ -A3 | grep 'topic\|selectorExpression'
```
