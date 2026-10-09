# DLQ 死信重放 Runbook（v1，2026-09-27）

> 背景：全项目 26 个消费者组均有 `maxReconsumeTimes=3/5` → 重试耗尽进 `%DLQ%{group}`；
> 现有监控只有**告警**（`DlqMessageDetected` / `RocketmqDlqBacklog`），**没有重放路径**。
> 本 runbook 定义"收到 DLQ 告警后怎么查、怎么幂等重放、怎么限速"。
> 关联：`distributed-semantics-grilling-20260927.md` §7.6-P3；`40-RocketMQ深度拷打.md`。

## 一、原则（四先四不）

1. **先看后动**：先只读定位（哪个组/什么消息/为什么失败），不直接批量重放；
2. **先修后放**：根因未修复前重放 = 再进一次 DLQ（如反序列化失败/Schema 变更类）；
3. **幂等为准**：重放依赖消费者幂等（msgId 标记/版本 Lua/唯一键/状态机）——已核对全项目消费者具备；
4. **限速回放**：禁止无控速全量回放（尼恩案例 3：追积压打爆 DB → 二次事故）。**单组 ≤ 100 msg/s**（首轮建议 20/s 观察）。

## 二、定位（只读，不改动）

```bash
# 1. 查看所有死信队列（组名见 DlqMetrics 动态发现，或告警标签 consumerGroup）
docker exec my-xhs-mq-broker sh /home/rocketmq/rocketmq-5.1.4/bin/mqadmin \
  topicList -n 127.0.0.1:9876 | grep '%DLQ%'

# 2. 看某组 DLQ 堆积量与位置（min/max offset）
docker exec my-xhs-mq-broker sh /home/rocketmq/rocketmq-5.1.4/bin/mqadmin \
  topicStatus -n 127.0.0.1:9876 -t '%DLQ%inventory-deduct-consumer-group'

# 3. 抽样读取消息内容（只读打印，不消费不删除）
docker exec my-xhs-mq-broker sh /home/rocketmq/rocketmq-5.1.4/bin/mqadmin \
  consumeMessage -n 127.0.0.1:9876 -t '%DLQ%inventory-deduct-consumer-group' -c 3
```

**判定失败类型**：
| 类型 | 特征 | 处理 |
|---|---|---|
| 数据类（毒消息） | 反序列化失败/字段非法 | 修数据 or 丢弃（记录归档） |
| 依赖类 | 下游宕机/DB 抖动期间的存量 | 依赖恢复后**限速重放** |
| 代码类 | NPE/逻辑异常 | **先修代码/发版**，再重放 |
| Schema 演进 | 新增字段反序列化失败（12 册 112 场景） | 兼容性修复（忽略未知字段）后重放 |

## 三、重放（方案 A：重新发送，推荐）

> 原理：DLQ 消息不能改位点回原 topic；正确做法是**读取 → 校验 → 重发原 topic → 依赖幂等**。
> 也可用 RocketMQ Dashboard 手工重发（同样限速 + 幂等前提）。

1. **准备**：确认根因已修复；确认目标组消费者幂等（见下"幂等核对表"）；通知相关值班；
2. **重放脚本**（伪代码，执行时按组填参）：
   - 以 `%DLQ%{group}` 为源、`{原topic}` 为目标；
   - 逐批（≤50 条）读取 → 解析/校验 body → 重新发送（携带原 keys，header 加 `replayFrom=dlq`）；
   - 每批 sleep（100 msg/s 上限；首轮 20/s）；
   - 记录：总数/成功/失败/时间窗（供复盘与审计）。
3. **验证**：目标组消费 TPS 正常、DLQ 不再新增（`rocketmq_dlq_backlog` 归 0）、业务指标（订单/库存/支付）无异常；
4. **清理**：确认无消费需求后，按业务归档策略导出并清理 DLQ（记录归档位置）。

**幂等核对表（重放前逐一确认）**：
| 组 | 幂等机制 |
|---|---|
| inventory-deduct | msgId 标记 + (orderId,skuId) 版本 Lua + 对账 |
| order-pay-result / refund-result | 状态机 ALLOWED_FROM + 事件序号唯一键 |
| coupon-claim | 乐观锁 WHERE status=0 + outbox 唯一键 |
| cart 双消费者 | 时间戳 CAS + 事件表唯一键 |
| feed-push | ZADD 天然幂等 + 断点游标 |
| notification/搜索/计数 | 键去重 / INSERT IGNORE / 版本刷新 |
| 其余 | 默认按 msgId 标记（MessageIdempotentHelper） |

## 四、方案 B：弃用（毒消息）

数据类毒消息（永不可能成功）：导出 body 到 `docs/reports/dlq-archive-{date}.log` → 值班与业务确认 → 从 DLQ 清理（`mqadmin deleteTopic` 是删整个 topic，慎用；单条清理需控制台/脚本）。**清理也必须留审计记录**。

## 五、演练建议（季度）

1. 造一条毒消息（注入非法字段的测试 topic）→ 走完"告警→定位→判定→限速重放/弃用"全流程；
2. 记录 RTO（从告警到清零时间），目标 < 30 分钟（单组 ≤1 万条）；
3. 演练报告归档到 `per-service-review` 台账。

## 六、已知局限与改进方向

- [ ] 重放脚本尚未工程化（当前为 runbook 手工步骤）；
- [ ] DLQ 清理无自动化工具（依赖控制台/脚本）；
- [ ] 相关告警已有（DLQ 新增 P1 / 积压 >0 5min），但**无"值日手册链接"**——建议把本 runbook 链到告警 description。
