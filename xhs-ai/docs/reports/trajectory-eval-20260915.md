# 轨迹评测与稳定性度量（RV28）· 2026-09-15

> 评测升级：从"首个工具是否选对"→ **轨迹评测（有序子序列 + 部分分 + 探索冗余计数）**；并做**多次运行稳定性**与生产化补差
> 结论：稳定性样本 12 次运行平均得分 **0.917**（11/12 满分，1 次为客户端 240s 超时的基础设施抖动）；评测集 14 例覆盖 7 类能力

## 1. 方法

| 项 | 说明 |
|----|------|
| 用例 | `eval/tool-cases.yaml` 14 例：`expected` 为集合匹配（任一命中按比例给分）、`sequence` 为**有序子序列**（轨迹，允许中间探索但顺序必须对） |
| 脚本 | `scripts/tool-eval.sh [cases] [repeat]`；从 Redis `agent_state` 提取工具调用序列；输出 分数/命中/冗余/耗时 与 JSON 报告 |
| 隔离 | `EVAL_UID=1001` 评测专用用户 + 运行前清零额度（避免消耗真实用户预算/被硬限拦截） |
| 指标 | 每次运行同时产出：`ai_agent_runs_total{result}`、`ai_agent_duration_seconds`、`ai_tool_calls_total{result=error}`、`ai_tool_errors_total{tool}` |

## 2. 结果（修正后）

单轮全量（14 例）与稳定性（4 例 × 3 次，代表四类能力）：

| 用例 | 类型 | 轨迹/结果 | 冗余调用 | 备注 |
|------|------|----------|---------|------|
| TS-02 死信根因 | 集合 | ✅ 2/2（dlq_topic_list→dlq_message_detail） | 2 | 附加日志/知识佐证 |
| TS-05 5xx Top | 集合 | ✅ 1/1（metric_top） | 1 | |
| TS-11 up=0 | 集合（修正） | ✅ | 2 | 原期望含已下线的 MCP 工具，已修正为 metric_query |
| TS-13 死信诊断链 | **轨迹** | ✅ 2/2（topic→detail） | 1~2 | 3 次运行均满分 |
| TS-14 日志→QPS 趋势 | **轨迹** | ✅ 2/2（log_top_services→metric_trend） | 1 | 原用例问"日志趋势"与 metric_trend 语义不符，已修正 |
| TS-03 日志 Top 服务 | 集合 | ✅✅✅ 3/3 | 1 | |
| TS-05 5xx Top | 集合 | ✅⛔✅ 2/3 | 0~2 | 1 次客户端 240s 超时（上游慢），非选择错误 |
| TS-08 消费积压 | 集合 | ✅✅✅ 3/3 | 0~1 | |
| TS-13 死信链 | 轨迹 | ✅✅✅ 3/3 | 1~2 | |

**稳定性均值 0.917**；排除基础设施超时为 **11/12=91.7%**；平均探索冗余 1~2 个调用。

## 3. 评测基础设施问题与修复

1. **预算拦截评测**：首轮全量 14 例被当日 50 万硬限拦截（TS-12/13/14 毫秒级 429），一度误判为"轨迹失败"——已改为**评测专用用户 + 运行前清零额度**，真实用户预算与评测互不干扰。
2. **期望值腐化**：去 MCP 化后 TS-11 期望仍是 `query/list_targets`，已修正为 `metric_query`——评测集需要随工具目录演进维护（已加注释）。
3. **用例语义**：TS-14 原问"日志趋势"却期望 `metric_trend`，属用例错误，改为"QPS 趋势"后通过。
4. **超时口径**：单次 240s 客户端超时计为失败样本并如实记录（不隐藏）。

## 4. 本轮生产化补差（同批交付）

| 缺口 | 修复 | 验证 |
|------|------|------|
| 批准后执行崩溃 → 永久无结果 | `ApprovalExecutionRecoveryJob`：approved 且无 executionStatus 的记录自动补执行（CAS 防重） | 造记录 → 60s 内补执行，`ai_approval_recovered_total=1` |
| 执行中进程中断 → 卡在 executing 永久卡死 | 回收 Job：executing 超时（默认 600s）标记 failed 待人工重试，**不自动重放副作用** | 造记录 → 60s 内标记 failed，`ai_approval_reclaimed_total=1` |
| 无运行/工具级指标 | `ai_agent_runs_total/duration`、`ai_tool_calls_total{result=error}`、`ai_tool_errors_total{tool}` | 实测计数正确（含预算拒绝计入 ON_ERROR、白名单拦截计入 tool error） |
