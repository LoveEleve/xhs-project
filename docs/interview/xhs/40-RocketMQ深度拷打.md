# 第40题 | 组件深度拷打：RocketMQ

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：CommitLog/ConsumeQueue、顺序/事务/延迟消息、重试与 DLQ、幂等、rebalance、消息堆积

## 问题
讲讲 RocketMQ 的架构和存储模型？顺序消息怎么保证？事务消息回查机制？消息堆积怎么办？重试与死信怎么设计？

## 面试可讲版（五段式）

**① 架构与存储（原理层）**
- **四角色**：NameServer（无状态路由，AP）、Broker（Master/Slave + CommitLog）、Producer（group 选队列）、Consumer（group + offset）。
- **存储三件套**：所有消息顺序写 **CommitLog**（单文件 1G，刷盘策略 SYNC_FLUSH/ASYNC_FLUSH）→ 异步分发构建 **ConsumeQueue**（逻辑队列，定长 20 字节：offset+size+tagHash）→ **IndexFile**（按 key/时间查消息）。
- **为什么快**：顺序写 + 页缓存 + 零拷贝（mmap/sendfile）；ConsumeQueue 是索引不是数据，读放大很小。
- **刷盘与复制**：同步刷盘保不丢（性能降）；主从复制 ASYNC/SYNC 决定 RPO。**本项目 broker 加固：SYNC_FLUSH + autoCreateTopicEnable=false**（防断电丢消息/防拼错 topic 静默创建）。

**② 消息类型与本项目用法**
| 类型 | 原理 | 本项目 |
|---|---|---|
| 普通消息 | 并发消费 | 27 个消费者（计数/Feed/IM/同步等） |
| 顺序消息 | 同队列 FIFO + 消费端单线程 | 未用 ORDERED（业务用**版本号**抗乱序：ExternalGte/24h 窗口——顺序依赖队列会牺牲并发，业务层解决更灵活） |
| 延迟消息 | 固定 18 档（delayLevel=1~18） | 关单 **delay-level 16（30min）**；注意不是任意延迟（5.x 有定时消息） |
| 事务消息 | half 消息 + 本地事务 + 回查 | 下单：`OrderTransactionListener`（execute→check，查询异常返回 **UNKNOWN 让 Broker 再回查**） |
| 死信 | 重试超限进 %DLQ% 组 | 26 组对齐 + DLQ 监控（第 15 题） |

**③ 可靠性与幂等（项目口径）**
- **重试策略**：消费者 `maxReconsumeTimes` **18 组×3 / 8 组×5**（按业务容忍度分级）；重试间隔由 Broker 退避（1s/5s/10s/30s/1m...）；
- **幂等**：`MessageIdempotentHelper`——Redis `SETNX(bizType:bizId)` + TTL，失败时 `removeMark` 允许重投；配合 DB 唯一键（四层防线第 3 层）；
- **本地消息表**：事务消息之外还有 Outbox（先落库后发送，Job 补发）——防 Broker 不可用；
- **顺序 vs 幂等**：不追队列顺序，靠**版本号**（搜索 ExternalGte、社交 24h 窗口）让乱序无害——这是架构选择；
- **堆积治理**：消费 lag 指标 + DLQ 积压规则；业务按"可丢/可重放/必须处理"分类（DLQ 三级治理）。

**④ 拷打追问（面试官视角）**
1. **"为什么 RocketMQ 不用 ZooKeeper？"** NameServer 无状态、节点间不通信（去中心化），设计更简单；代价是路由信息可能短暂不一致（AP 取舍）。
2. **"CommitLog 单文件顺序写，那消费读是怎么定位的？"** 每个 ConsumeQueue 条目指向 CommitLog 物理偏移；消费按逻辑队列顺序读索引，再按偏移读 CommitLog。
3. **"同步刷盘 + 主从同步复制才不丢，你们用了哪个？"** 本项目 SYNC_FLUSH（单机）保断电不丢；主从复制是 ASYNC（RPO>0，故障演练验证过）；生产要严格不丢需 SYNC_MASTER 复制（性能代价）。
4. **"事务消息的 half 消息对消费者可见吗？"** 不可见——Commit 后才进真实队列；Rollback 直接删；UNKNOWN 触发回查。
5. **"回查为什么可能查不到？"** 本地事务未提交/超时/DB 不可用；回查返回 UNKNOWN 继续等（有限次后丢弃或告警）——本项目查询异常返回 UNKNOWN。
6. **"消费失败重试会不会放大？"** 会——重试 3/5 次 + 退避 + 进 DLQ；毒丸消息要能识别（配额/内容校验）避免无限重试打爆。
7. **"同一个 group 加机器会怎样？"** rebalance：队列重新分配（默认平均分），期间可能重复消费（offset 提交窗口）→ 幂等必须覆盖。
8. **"怎么保证消费不丢？"** offset 提交时机（消费成功后提交）+ 业务幂等 + 对账；极端靠重放（MQ 保留期）。

**⑤ 话术**
> "RocketMQ 是 NameServer 加 Broker 加生产和消费四角色，存储是 CommitLog 顺序写、ConsumeQueue 异步构建索引、IndexFile 支持按 key 查询，顺序写加页缓存加零拷贝是性能来源。消息类型我们用了普通、延迟、事务三类：关单用固定档位延迟 16 也就是 30 分钟，下单用事务消息，回查查询异常时返回 UNKNOWN 让 Broker 再来。顺序消息我们没用 ORDERED，因为会牺牲并发，改用业务版本号抗乱序。可靠性上：broker 开了 SYNC_FLUSH 和禁用自动建 topic；消费重试按 3 次和 5 次两档；幂等靠 Redis SETNX 加 DB 唯一键。堆积和死信有 26 组监控和三级治理。"

## 追问与参考回答（延伸）
**Q：延迟消息有 18 个档位，具体是哪些？** 1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h（delayLevel 16 = 30m）。
**Q：消息体序列化选型？** JSON 可读、跨语言；性能敏感用 Avro/Protobuf；本项目 JSON（统一 ObjectMapper）。
**Q：ConsumeQueue 和 Kafka 分区区别？** 概念类似（逻辑队列=分区），但 RocketMQ 索引和数据分离（CommitLog 共享），Kafka 每分区独立日志。
**Q：MQ 的顺序、事务、延迟三个特性只能选一个？** 早版本不能组合（如事务+顺序）；选型时按主诉求设计。
**Q：RocketMQ 5.x 有什么变化？** 存算分离（Proxy + Store）、任意延迟、gRPC 协议、消息队列 for Kafka 兼容——本项目用 5.1.4 但仍按 4.x 经典模型用。

## 发散追问地图（横向）
- 对比：Kafka（吞吐/分区/ISR）、RabbitMQ（路由/ACK）、Pulsar（存算分离）；选型维度。
- 存储：CommitLog 文件回收、过期清理（48h 默认）、磁盘水位保护。
- 高可用：DLedger/Raft（自动主从切换）、主从切换丢消息窗口。
- 消费：push vs pull（长轮询）、rebalance 策略、广播模式。
- 治理：消息轨迹、死信/重试 topic、消息查询（按 msgId/key）。

## 面试官评分点
**高级开发级**：能讲清存储模型与三类消息用法；知道自己项目的重试/幂等/延迟档位。
**架构师加分**：UNKNOWN 回查语义；不追队列顺序而用版本号的架构取舍；SYNC_FLUSH/复制策略与 RPO 的关系；rebalance 重复消费与幂等的关系。
**危险信号**：说不清 CommitLog/ConsumeQueue；以为延迟消息任意时长；顺序消息与并发不取舍；重试无上限/无 DLQ。

## 本项目真实证据
- 27 个 `@RocketMQMessageListener`；`maxReconsumeTimes` 18×3/8×5；`MessageIdempotentHelper:36-81`（SETNX/removeMark）；
- `OrderTransactionListener:23-132`（execute/check/UNKNOWN）；`OrderService:90,307,947`（delay-level 16）；broker 加固（SYNC_FLUSH/autoCreateTopicEnable=false，DEPLOY-NOTES §二）；
- DLQ 26 组与三级治理（第 15 题 + `docs/reports/dlq-cleanup-20260917.md`）。

## 版本与来源
RocketMQ 官方文档（存储/事务/延迟/重试）；本项目 MQ 使用代码与部署加固笔记。

## 真实性说明
架构原理为标准知识；项目用法（27 消费者/档位/重试次数/UNKNOWN/加固）均为代码与部署笔记事实。
