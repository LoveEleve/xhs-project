# HANDOFF-AI v6（my-xhs-ai 收官交接文档）

> 日期：2026-08-18
> 用途：交给下一位 AI/会话继续执行，避免重复探索、误报状态或遗漏用户要求。
> 仓库根目录：`/data/workspace/my-xhs`
> 当前分支：`main`
> 当前阶段：**核心研发完成，技术/业务/理论/面试资产收官中**。

---

## 0. 当前一句话结论

`my-xhs-ai` 已经完成核心企业级诊断 Agent 研发，并完成 MiMo V2.5 Pro 切换、关键门禁验证和 nightly 100 条全量首跑。

当前不应继续无边界扩展功能，下一阶段重点是：

1. 理论专题深度 review 与提交；
2. 收官资产索引完善；
3. T14 外部方案对照；
4. 面试/作品集最终封版。

当前没有 Docker 部署任务，用户明确要求重点放在**技术、业务、面试、理论知识、论文式专题**。

---

## 1. 仓库与安全

- Git 仓库：`/data/workspace/my-xhs`
- Remote：`LoveEleve/xhs-project.git`
- 分支：`main`
- 凭据文件：`/data/workspace/my-xhs/.env.local`
- `.env.local` 必须保持 gitignored，不得读取后输出，不得写入代码/文档/commit。
- 评测使用环境变量：

```bash
set -a; source .env.local; set +a
export MYXHS_LLM_API_KEY="<环境变量中的值>"
```

- 用户曾在对话中提供过一枚 OpenCode key；不要把它写入文件、命令日志或文档。当前代码只通过 `MYXHS_LLM_API_KEY` 读取。

---

## 2. 当前模型与真实验证

当前配置：

```yaml
myxhs:
  ai:
    llm:
      base-url: https://opencode.ai/zen/go/v1
      model: mimo-v2.5-pro
      api-key: ${MYXHS_LLM_API_KEY:}
```

配置位置：

- `my-xhs-ai-app/src/main/resources/application.yml`
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/config/LlmGatewayConfig.java`
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/config/HarnessConfig.java`

重要事实：

- `opencode.ai/zen/v1` 的模型目录和 `opencode.ai/zen/go/v1` 不同。
- `mimo-v2.5-pro` 是在 **OpenCode Go** 通道模型目录中实测确认的。
- 不要再把 go 通道与普通 zen 通道混淆。
- MiMo V2.5 Pro 已通过：
  - `EvalSmokeRunTest`
  - `SampledRegressionTest`
  - `EvalGateRunTest`
  - `EvalSmokeFullRunTest`
  - `EvalRegressionFullRunTest`

模型切换原则：

> 任何模型切换都必须重新跑关键门禁和 nightly，不能只改配置后宣称质量等价。

---

## 3. 代码模块与职责

| 模块 | 职责 | 关键事实 |
|---|---|---|
| `my-xhs-ai-tools` | 业务/观测工具、工具访问桥、DLQ 工具 | 工具大部分只读，L3 工具受审批控制 |
| `my-xhs-ai-app` | Agent 核心、Run、Conversation、HITL、评测 | 主服务 19020 |
| `my-xhs-ai-mcp` | MCP 服务与工具导出 | MCP 服务 19021 |
| `frontend/` | React AI 诊断台薄壳 | `/ai` 页面，前端只消费 API/SSE |

实测规模（此前 handoff 口径）：

- tools：约 `1818/897` main/test 行
- app：约 `6444/4391` main/test 行
- mcp：约 `305/306` main/test 行
- MCP 工具：14 个

---

## 4. 已完成研发主线

### 4.1 基础 D/M 阶段

- D1-D4：真实指标工具、MCP、RAG、Agent Harness 基础能力。
- M5：Run/Step 持久化、异步执行、取消、checkpoint/resume。
- M6：评测集、EvalRunner、EvalGate、数字一致性、指标统计。
- M7：PolicyGuard、deny-by-default、参数白名单、MCP 认证/审计、安全测试。
- M8：前端薄壳、SSE、Gateway 方案。
- M9：受控日志检索、`dlq.redeliver` 受控执行方向。

### 4.2 M10-M14

- M10：Conversation、消息持久化、规则摘要、多轮上下文、会话锁。
- M11：`WAITING_APPROVAL`、approve/reject、resume、审计、HITL 前端卡片。
- M12：ToolRegistry、ToolSpec、工具目录、PolicyGuard 注册表驱动、MCP 工具导出。
- M13：BUSINESS/OPS/FULL profile、AgentDispatcher、共享 Harness、多 Agent 对比。
- M14：100 条评测集、EvalJudge 装配、BadCaseCollector、门禁阈值校准。

---

## 5. 最终评测结果

### 5.1 关键门禁

归档报告：

- `docs/reports/eval-gate-report-20260817.json`
- `docs/reports/eval-gate-report-20260817.md`

MiMo V2.5 Pro 下：

- `EvalGateRunTest`：7/7 通过
- completion：100%
- hallucination：0%

### 5.2 Nightly 100 条

评测集入口：

- `my-xhs-ai-app/src/main/resources/eval/cases.yaml`：20 条 smoke
- `my-xhs-ai-app/src/main/resources/eval/cases-regression.yaml`：80 条 regression
- `EvalM14Test` 固定合计为 100 条

正式 runner：

- `EvalSmokeFullRunTest`
- `EvalRegressionFullRunTest`

原始报告：

- `my-xhs-ai-app/docs/reports/nightly-smoke-full-report.json`
- `my-xhs-ai-app/docs/reports/nightly-regression-full-report.json`

归档报告：

- `docs/reports/nightly-100-report-20260817.md`
- `docs/reports/nightly-100-quality-summary-20260817.md`

最终口径：

| 集合 | 条数 | 通过率 | 完成率 | 幻觉率 | 平均耗时 |
|---|:--:|:--:|:--:|:--:|:--:|
| smoke | 20 | 100% | 100% | 0% | 26.1s/条 |
| regression | 80 | 100% | 95% | 0% | 22.6s/条 |
| 合计 | 100 | 100% | 96% | 0% | 23.3s/条 |

综合观测：

- 平均 Token：约 10,324/条
- 综合发散率：约 35%
- 发散率不是失败率，表示工具调用中出现探索性重复/换窗口行为。
- 5% 未以 SUCCEEDED 终止的样本属于允许的 `PARTIAL/BUDGET_STEPS` 复杂归因观察点。
- 不要把本轮结果宣传为所有生产流量永久 100% 正确。

---

## 6. 近期评测器修复记录

当前 `EvalAsserter` / `EvalRunner` 已修复并有测试：

1. 列表序号：`1)`、`2）`、`3、`、`4. `。
2. Markdown 表格排名和 `Top 3`/`排名 1` 等非业务数字。
3. `最近 7 天`、`31 天`、日期上下文数字。
4. `0.553s` 与 `553ms` 单位换算。
5. `DECLINE` 零证据路径不再被强制进行数字一致性检查。
6. 退款能力缺失时，regression 用例允许诚实拒答，不强制要求证据。

关键代码/测试：

- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/eval/EvalAsserter.java`
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/eval/EvalRunner.java`
- `my-xhs-ai-app/src/test/java/com/myxhs/ai/app/eval/EvalAsserterTest.java`
- `my-xhs-ai-app/src/test/java/com/myxhs/ai/app/eval/EvalM14Test.java`

---

## 7. 真实数据与外部边界

已执行造数并归档：

- 订单：8 条，覆盖 4 个分片
- 支付：6 条
- 加购：18 条
- 笔记事件：20 条
- 浏览：60 条，落在 `my_xhs_product.t_product_behavior`
- 漏斗口径：browse/cart/order = 60:18:8

重要数据边界：

- 真实基础设施链路是真实的；业务数据包含 seed 与历史残留。
- 订单查询是 16 节点分片扫描。
- 漏斗浏览使用 `t_product_behavior`，不是 `t_counter`。
- 内容互动部分场景存在 0 数据/采集口径限制。
- `dlq.redeliver` 真实 RocketMQ E2E 仍受 `ORIGIN_MESSAGE_ID` 查询接口 NPE 阻塞。
- HITL 控制流、审批、resume、审计由 fake/单测验证，但真实死信重投未完全闭环。
- Docker 部署不在当前任务范围。
- 远端 CI 未真实验收，不要宣称 CI 已跑绿。

---

## 8. 已完成作品集/面试/理论资产

### 8.1 面试与作品集

- `my-xhs-ai/business-analysis/tech/pitch-3tier-v1.md`：30 秒/3 分钟/10 分钟讲稿。
- `my-xhs-ai/business-analysis/tech/nine-questions-v1.md`：九问与高频追问。
- `my-xhs-ai/business-analysis/tech/jd-hit-matrix-v1.md`：JD1-JD18 命中矩阵。
- `my-xhs-ai/business-analysis/tech/business-cases-v1.md`：订单/支付/内容/MQ/HTTP 五张案例卡。
- `my-xhs-ai/business-analysis/tech/retrospective-v1.md`：项目总复盘。

### 8.2 理论/专题

已完成首版并经过深度修订：

- `theory-agent-v1.md`：Chatbot/Workflow/Agent/企业级边界。
- `theory-enterprise-agent-engineering-v1.md`：四态、Durable、HITL、Policy、评测。
- `theory-evaluation-v1.md`：评测指标、断言、Judge、bad case。
- `theory-anti-hallucination-v1.md`：证据链、数字一致性、评测器边界。
- `theory-harness-vs-framework-v1.md`：成熟框架 vs 最小自研控制面。
- `theory-multiagent-v1.md`：M13 多 Agent PoC 与取舍。

索引：

- `my-xhs-ai/business-analysis/tech/README-收官资产.md`

---

## 9. 当前 Git 状态与最近工作

已知最近提交：

- `e511e26`：理论与评测收官资产落盘。
- 更早的 nightly/model 相关提交：`ed08cdf`、`06e362d`。

交接时必须先运行：

```bash
git status --short
git log --oneline -10
```

如果发现理论文档、评测器或 nightly 报告存在未提交修改，先确认是否属于本交接范围，不要覆盖用户已有改动。

---

## 10. 下一步执行顺序

### P0：先处理

1. 检查当前 git status，确认 T13 理论文档修改是否已提交。
2. 对 `theory-multiagent-v1.md` 做最后一轮深度修订/事实核对：
   - `FULL` 是基线/回退，不是协作 Agent；
   - 当前是分派式，不是 Agent 接力；
   - 分派准确率尚未单独产出；
   - A2A 当前不需要；
   - 加入 M13 真实失败反例与出处。
3. 修订完成后运行文档相关代码回归，提交并推送。

### P1：收官资产

1. 更新 `README-收官资产.md`，加入 `theory-multiagent-v1.md`。
2. 更新 `HANDOFF-AI-v5.md` 或建立 v6 后替代旧 handoff。
3. 把 T9/T10/T11/T13/T14 资产使用顺序整理成最终阅读路径。
4. 输出项目最终状态页：研发完成、评测完成、知识资产完成、剩余外部边界。

### P2：可选专题

1. T14 外部方案对照：Anthropic、OWASP、Promptfoo、Temporal、Langfuse。
2. MiMo 与其他模型的对照研究，但任何切换都必须重跑 eval-gate/nightly。
3. Python/LangGraph 对照 PoC，只有在求职目标明确需要时才做。
4. Langfuse/OTel、长期 Memory、A2A、Temporal 只作为后续增量，不阻塞当前收官。

---

## 11. 下一位 AI 的工作纪律

- 先读本文件和 `README-收官资产.md`，不要重复查已经确认的模型/评测/数据事实。
- 用户要求“深度 review”时，必须检查事实边界，不要只润色文字。
- 区分：已实现、自动化测试、真模型验证、nightly 验证、真实外部 E2E、规划中。
- 不要把 100% 通过率说成 100% 生产正确率。
- 不要把 `PARTIAL/BUDGET_STEPS` 说成系统失败，也不要把它隐藏。
- 不要把 OpenCode `zen/v1` 与 `zen/go/v1` 混淆。
- 不要在文档或命令中暴露 API key。
- 不做 Docker 部署，除非用户明确改变范围。
- 不要擅自提交与当前任务无关的用户修改。
- 修改前先读文件，修改后跑相关测试，提交前检查 diff 和 status。
