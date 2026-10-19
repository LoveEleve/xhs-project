---
iteration: ITER-012
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# 测试设计: 通知模块生产化改造

## 验证策略

- **单测为主**（项目规则：无运行时环境；编译 + 单测为验证口径；Redis/MQ/JDBC 全 mock；删 target/test-classes 强制全量重编）；
- **测试集从零建立**（模块当前零测试）：目标 ≥30 用例，覆盖消费者/聚合/未读/对账/SSE/票据/DLQ/限流；
- 集成/E2E：不做（无运行时）。

## AC → 测试方法映射

| AC | 测试方法 | 层级 | 观测方式 |
|----|---------|------|---------|
| AC-1 | `NotificationDlqReplayConsumerTest`：两监听器重投目的地/KEYS+originalMsgId 保留/attempt≤3/失败不吞 | 单测 | mock RocketMQTemplate + ArgumentCaptor |
| AC-2 | `NotificationEventConsumerTest`：originalMsgId 优先去重；失败 removeMark + 抛出。`NotificationServiceTest`：post-commit 失败吞（incrementUnread 抛 → processEvent 不抛 + 消息可 ACK） | 单测 | mock helper/redis |
| AC-3 | `NotificationAggregatorTest`：INSERT 失败 → delete 键；PENDING 超时 → delete + 独立插入；affected≤0 → delete + 独立插入；新建/聚合/订单刷新正常路径 | 单测 | mock mapper + redis script 返回 |
| AC-4 | `OrderNotificationPublisherTest`（order 模块）：落库（operation 类型/载荷）+ 发送；`LocalMessageRetryJobTest`：ORDER_STATUS_NOTIFICATION → NOTIFICATION_TOPIC 映射 | 单测 | mock mapper/template |
| AC-5 | `NotificationControllerRateLimitTest`（或注解核验）：三端点 @RateLimit 存在；/sse 无（口径） | 单测/核验 | 反射/注解断言 |
| AC-6 | grep 核验：死物零残留、注释回正 ×2、TTL、静态脚本；`UnreadCountServiceTest`：forceSetUnread TTL 断言 | 单测 + 核验 | grep/断言 |
| AC-7 | 十二模块 `mvn test` 全绿 + §8/材料回写 | 回归 | 构建输出 |

## 关键边界用例

- 幂等键：无 originalMsgId 属性（正常消息）→ msgId；有（重放）→ 原值；
- 窗口自愈后重试：delete 键 → 重试 SETNX 成功 → 新窗口 count=1（无双计）；
- 聚合 PENDING 自旋：3 次后仍 PENDING → 独立插入 + 删键；
- Redis 脏值：聚合键非数字/未读值非法 → 安全降级（既有路径回归）；
- SSE：连接替换（旧 emitter complete）/清理（双参 remove）/跨实例消息格式（Long→String）。
