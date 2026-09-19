# Review 第五轮（2026-09-19）：MQ Topic/订阅治理

> 换面：对 broker 做一次全量治理盘点（topic/消费组/积压/孤儿/代码对照），并把检查单固化为脚本。

## 一、盘点结果

| 项 | 结果 |
|---|---|
| 消费组 LAG（22 组，Diff 列求和） | **全部 0**（消费健康 ✓） |
| SOCIAL_TOPIC | 8 队列各 ~32.5k（合计 ~26 万）消息已全部消费，最新写入 23:00 |
| broker topic（含系统） | 36 行；业务 topic 23 个 |
| 代码 topic 常量 | 18 个；无"代码有 broker 无" |
| broker 磁盘 | store 1.7G；宿主 overlay 78%（37G/50G）→ 登记关注 |

## 二、发现与处置

### ① 静默丢消息陷阱：未知 operationType 投递到无消费者 topic（已修）
- `LocalMessageRetryJob.resolveTopic()` 的 `default` 分支返回 `DEFAULT_RETRY_TOPIC`（**无消费者**），且 `SEND_OK` 后执行 `markSuccess` → 消息静默丢失。
- 当前生产仅写入 `ORDER_CREATED`（唯一类型，default 不可达），属**防扩展的潜在缺陷**。
- 修复：未知类型抛 `IllegalStateException`，由 catch 走指数退避（5 次）→ 死信（status=3，deadLetterCount 告警），人工可查。
- 验证：order 重发；test-09 **16/16**、test-11 **12/12**、test-13 **9/9**。

### ② 空孤儿 topic 清理
- `TEST_TOPIC_VERIFY` 已删除 ✓；
- `BenchmarkTest` / `SELF_TEST_TOPIC` 被 broker 判定为**系统保留 topic**（`conflict with system topic`，拒绝删除），均 0 消息 → 保留；
- `DEFAULT_RETRY_TOPIC` / `RETRY_TOPIC` 空 topic，前者随 ① 修复已无生产者、后者仅 AI 工具提示文案引用 → 保留观察。

### ③ 注释漂移修正
- `PaymentService`："PAY_RESULT_TOPIC 当前无消费端" 已过期（order 侧 `PayResultConsumer` 兜底消费）→ 修正为实际口径。

### ④ 工具化：`scripts/mq-topic-audit.sh`
- 四段巡检：① 各消费组真实 LAG（Diff）；② 无消费组且 0 消息的孤儿候选；③ 代码-vs-broker topic 对照；④ broker store 磁盘。单次约 2 分钟。
- 开发中踩坑并规避：**变量名 `GROUPS` 是 bash 内置特殊变量**（=用户组 ID），赋值被忽略导致统计恒为 1 → 改名 `MQ_GROUPS`；同类还有 `UID`（此前直连测试脚本也踩过，已用 `USERID`）。

## 三、复盘口径（MQ 治理检查单）
1. **LAG 必须为 0**（Diff 列，不是 ConsumerOffset）；2. **孤儿 topic**（无消费组+0 消息）定期清；3. **代码常量 vs broker** 双向对照（漏建/漏删都能发现）；4. **兜底 topic 必须有消费者**（否则静默丢消息，本次 ① 即此类）；5. 系统保留 topic 不强行删。

## 四、验证
- 审计脚本：22 消费组检查完成、全 LAG=0；
- 回归：test-09 16/16、test-11 12/12、test-13 9/9（order 重发后）。
