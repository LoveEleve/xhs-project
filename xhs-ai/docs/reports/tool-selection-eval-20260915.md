# 工具选择评测（Tool-Selection Eval）· 2026-09-15

> 目的：验证 32 个工具在真实提问下"是否被模型选对"，量化 RV21/RV22 工具抽象（T′/A5）的效果
> 方法：`eval/tool-cases.yaml` 12 题 → `scripts/tool-eval.sh` 逐题打 Agent → 从 Redis `agent_state` 提取实际 `tool_use` → 期望任一命中即通过；失败自动重试一次
> 结论：**12/12（100%）**；同时暴露并按实测校准了 token 预算默认值

## 1. 结果（2026-09-15 16:02 复跑）

| ID | 期望工具 | 实际首选用 | 耗时 |
|----|---------|-----------|------|
| TS-01 DLQ 积压清单 | dlq_topic_list | ✅ dlq_topic_list | 41.2s |
| TS-02 死信失败原因 | dlq_message_detail | ✅ dlq_topic_list→dlq_message_detail（+日志/知识佐证） | 58.8s |
| TS-03 ERROR 日志 Top 服务 | log_top_services | ✅ log_top_services | 12.8s |
| TS-04 指定服务日志检索 | log_search | ✅ log_search（+ES 元数据/其他查询，探索性冗余） | 38.6s |
| TS-05 5xx 错误率 Top3 | metric_top | ✅ metric_top | 14.7s |
| TS-06 P95 最慢接口 | metric_top | ✅ metric_top（+query 复核） | 50.4s |
| TS-07 QPS 趋势 | metric_trend | ✅ metric_trend | 18.2s |
| TS-08 消费积压最多 | consumer_lag_top | ✅ consumer_lag_top | 31.2s |
| TS-09 业务知识问答 | knowledge_* | ✅ catalog→search→card_read | 22.8s |
| TS-10 代码定位 | code_locate | ✅ code_locate（+知识佐证） | 25.3s |
| TS-11 up=0 实例 | query/list_targets | ✅ query | 7.2s |
| TS-12 闲聊（不应调工具） | none | ✅ none | 7.6s |

## 2. 与改造前对比（历史会话采集）

| 场景 | 改造前（裸 DSL/PromQL） | 改造后（业务级工具） |
|------|------------------------|---------------------|
| ERROR 日志 Top 服务 | `mttr-rerun-log-1`：list_indices→get_mappings→search×2（**答偏**，98s+） | `log_top_services` 命中，**12.8~43.5s** |
| P95 最慢接口 | `metric-baseline-2`：metric_metadata×2→query→label_values（**答非所问**） | `metric_top` 命中，14.7~50.4s |
| QPS 趋势 | 无对应能力（模型不会 range 聚合） | `metric_trend` 命中，18.2s |
| 消费积压 | 无对应工具 | `consumer_lag_top` 命中，31.2s |

## 3. 附带发现与修复

1. **预算校准（实测驱动）**：首轮评测跑到第 6 题被硬限拦截（今日用量 204,280 / 200,000，`hard_reject=14`）——单次重诊断（含工具循环与大结果）实测约 4 万 tokens，20 万/日只够约 5 次。已将默认值调至 **50 万/日**（≈12 次重诊断），保留 env 可调与软限切轻量。
2. **上游流中断**：qwen3.8-flash 偶发 `PartialStreamException`（已输出部分内容不降级重放），评测脚本按"失败重试一次"处理并在报告标注，属上游抖动而非选择错误。
3. **探索性冗余**：TS-04/TS-06 会额外调用 1-2 个工具做交叉验证（+20~35s），可接受，但提示后续可加"调用预算/收敛提示"。

## 4. 边界（诚实声明）

- 单轮 12 题、单次复跑；样本以本项目真实运维问题为主，覆盖 6 大工具族；
- "改造前"为历史会话回溯对比（非严格 A/B）；
- 评测消耗真实模型额度，已计入 token 预算（本轮约 35 万 tokens，含重试与首轮被拦）。
