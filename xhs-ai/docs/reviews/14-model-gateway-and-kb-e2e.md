# RV14：D01 模型网关韧性 + M3 知识问答端到端打通

> 日期：2026-09-13 ｜ 范围：D01（模型网关）+ M3 知识层联调
> 结论：**模型网关（重试/熔断/降级/指标）落地并通过单测；知识问答 Agent 端到端打通（目录→检索→读卡→带引用回答）。**

---

## 1. D01 模型网关（已交付）

实现：`com.myxhs.ai.model.ModelGateway`（`Model` 装饰器）

| 能力 | 实现要点 |
|------|---------|
| 传输重试 | 仅对传输类错误重试（连接失败/reset/超时）；**已出流后不重试**（防重复增量），转由备用通道接管 |
| 熔断 | 主通道连续失败 ≥3 → 60s 冷却期内直接走备用并快速失败 |
| 降级 | 主不可用 → 备用模型（deepseek-v4-flash）；主备皆失败 → `ModelUnavailableException` 向上传递 |
| 指标 | `ai_model_calls_total{channel,result,model}`、`ai_model_latency`（Prometheus 已可见） |
| 测试 | `ModelGatewayTest` 5 例：重试成功/主耗尽降级/熔断开启/全失败传导/部分流不重试 —— **17/17 全绿** |

配置：`ai.model.retry.max-attempts/backoff-ms`、`ai.model.breaker.threshold/cooldown-ms`（可调）。

**双通道模型选型**（实测依据）：
- 聊天（`ChatController`）：`deepseek-v4-pro`（质量优先）；
- Agent 工具循环：`qwen3.8-flash`（`ai.model.agent-name`）——三个 flash 候选均原生返回 `tool_calls` 且推理极短；pro 在知识类问题上长推理易导致空答复（直测推理 2.1k，但 Agent 多工具上下文下不稳定）。

## 2. M3 知识问答端到端（打通）

```
knowledge_catalog（54 卡，每层 Top10）
  → knowledge_search（BM25："home BFF 事务中心" → bff-role 46.5 分；"order 编排中心" → order-orchestration-role 52.9 分）
  → card_read（整卡：结构化要点/反例/来源）
  → 带引用回答（`architecture/bff-role.yaml`、`architecture/order-orchestration-role`）
```
- 工具审计已接入：`knowledge.catalog / knowledge.search / card_read` 落 `ai_audit`；
- SSE 事件：tool ×3 + delta + final；回答含结论/证据/来源/反混淆。

## 3. 联调中修复的缺陷（含 1 个关键回归）

| # | 问题 | 修复 |
|---|------|------|
| 1 | **知识工具未注册**（此前二分实验中注释后未恢复，导致 Agent 无法调用，表现为"只说不做/空答复"） | 恢复 3 个 `registerAgentTool` 并加入启动日志校验 |
| 2 | `card_read` 传相对路径（`architecture/xxx`）不命中 | `byId` 归一化路径（去 `.yaml` 后缀 + 前缀匹配） |
| 3 | Prometheus `docs_*` 工具误导模型（架构问题去查监控文档） | MCP 白名单剔除，System 提示显式禁止 |
| 4 | 长推理耗尽输出配额 → 空答复 | `maxTokens 8192`；空最终答复改为显式 `error`（不再静默 `done`） |
| 5 | 模型网关瞬态失败 | 本次 D01 重试/熔断/降级 + 指标 |

## 4. 下一步

1. **KB EVAL 30 条**（真实用例 + 引用校验门禁；当前网关稳定时段可跑量）；
2. `code_locate`（v1 轻量解析）+ 代码类问题闭环；
3. 工具预算护栏（当前 Agent 侧 26 个，白名单纪律保持）；
4. 卡片引用校验 CI（类/方法/表/topic 存在性）。
