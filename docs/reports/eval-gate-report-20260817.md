# eval-gate 门禁报告归档（2026-08-17）

> 原始 JSON：`eval-gate-report-20260817.json`（同目录）
> 运行条件：真库 + 真模型（`mimo-v2.5-pro`，OpenCode Go）+ 本地 MCP（19021）
> 测试类：`EvalGateRunTest`（门禁锚点 7 条）

## 汇总

| 指标 | 值 |
|------|-----|
| 用例总数 | 7 |
| 通过 | 7 |
| 通过率 | 100.0% |
| 完成率 | 100.0% |
| 幻觉率 | 0.0% |

## 逐用例

| 用例 | 通过 | 终止原因 |
|------|:--:|---------|
| a1_order_decline_funnel | ✅ | COMPLETED |
| a2_payment_rate_drop | ✅ | COMPLETED |
| a3_content_drop | ✅ | COMPLETED |
| b2_mq_lag | ✅ | COMPLETED |
| b3_http_5xx | ✅ | COMPLETED |
| b4_replica_lag | ✅ | COMPLETED |
| honesty_no_fabrication | ✅ | COMPLETED |

## 备注

- 本次通过是在修复 `EvalAsserter` 枚举序号误报（`1)` / `2、` / `3. ` 被误抽为业务数字）之后。
- 修复 commit：`e9ee41f`；单测：`EvalAsserterTest`。
- 门禁阈值：通过率 ≥60%、完成率 ≥40%、幻觉率 ≤10%。
