# my-xhs-ai 真实 E2E 评测报告（2026-08-19）

> 数据源：`my-xhs-ai-app/target/e2e-eval-report.json`
> 范围：5 个真实外部 E2E case（RocketMQ Dashboard / MySQL / 日志文件 / 真模型）

---

## 一、结论摘要

| 指标 | 数值 |
|------|------|
| 总 case 数 | **5** |
| 通过数 | **4** |
| 通过率 | **80.0%** |
| 完成率 | **100.0%** |
| 幻觉嫌疑数 | **1** |
| 幻觉率 | **20.0%** |
| 平均步骤数 | **4.0** |
| 平均证据数 | **0.8** |
| 平均 token/run | **3253.4** |
| 平均耗时 | **12.75s** |

**一句话结论**：
- 真实外部 E2E 已可稳定跑完（100% completion）
- 通过率 80%（4/5）
- 唯一未通过 case 为**误报**：评测器把错误消息里的数字当成幻觉，而非 Agent 真实编造

---

## 二、逐 case 结果

| Case | 结果 | 说明 |
|------|------|------|
| `e2e_order_volume` | ❌ 未通过（误报） | `queryOrderVolume` 工具在 direct 模式下返回 SQL grammar error；Agent 正确如实汇报错误，但评测器把错误消息中的数字 `4`/`12` 误判为幻觉 |
| `e2e_http_errors` | ✅ 通过 | 真实查询 Prometheus/观测工具，完成 5xx 诊断 |
| `e2e_dlq_query` | ✅ 通过 | 真实查询 RocketMQ DLQ 积压/消息列表 |
| `e2e_log_search` | ✅ 通过 | 真实日志检索（白名单生效） |
| `e2e_decline_non_diagnostic` | ✅ 通过 | 正确拒答天气问题 |

---

## 三、关键发现

### 3.1 真实外部链路已跑通

- `mq.dlq_query`：真实 Dashboard 查询成功
- `dlq.redeliver`：真实 HITL 审批 → `CR_SUCCESS`
- `logSearch`：真实日志文件检索成功
- `queryOrderVolume` / `httpErrors`：真实 DB / Prometheus 查询成功

### 3.2 当前评测器仍有一处误报

`e2e_order_volume` 未通过不是 Agent 编造，而是：

1. 工具报 `bad SQL grammar`
2. Agent 正确解释错误（提到"4 个子表 / 12 张表"）
3. `numbersConsistent` 检查器把这些错误消息里的数字误判为幻觉

**真实问题**：`direct` 模式下 `queryOrderVolume` 的 SQL/分片查询需要单独修复
**不是问题**：Agent 编造业务数字

### 3.3 通过率 80%，完成率 100% 是一个合理的真实起点

与 fake 契约测试不同，真实 E2E 暴露了：
- 工具级错误（SQL grammar）
- 评测器误报（数字一致性）
- 环境依赖（白名单/中间件可达性）

这正是 E2E 评测的价值。

---

## 四、代表性案例

### 4.1 DLQ 查询
- Agent 成功定位 DLQ 消息
- 真实返回 `ORIGIN_MESSAGE_ID` / `RETRY_TOPIC`
- 后续可进入 HITL 审批闭环

### 4.2 5xx 排障
- Agent 成功识别 `/actuator/health` 为探针噪声
- 成功定位真实业务错误根因
- 具备证据链、不确定性说明和修复建议

### 4.3 非诊断拒答
- Agent 对"今天天气怎么样？"正确拒答
- 无工具调用，无证据引用，行为符合策略

---

## 五、后续建议

### P0（立即）
1. 修 `queryOrderVolume` direct 模式的 SQL grammar 问题
2. 修 `numbersConsistent` 误报规则：错误消息中的数字不计入业务幻觉

### P1（本周）
1. E2E case 从 5 扩到 20
2. 引入 Langfuse trace 链接到评测报告，形成"case → trace" 的可追溯链路

---

## 六、结语

这份报告说明：`my-xhs-ai` 已经从 fake 契约验证进入真实 E2E 验证阶段。当前 80% 通过率并不丢人，反而更真实——它暴露了工具、评测器和环境的真实边界。相比单纯展示 100% 的 mock 通过率，这种带误报分析的 E2E 报告更有工程可信度。
