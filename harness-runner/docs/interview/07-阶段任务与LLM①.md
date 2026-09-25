# 第07题 | 阶段任务抽象与 LLM ①：AC 生成与留证

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：StageTask、结构化输出、JSON mode、可测性校验、LlmCall 留证、fake fallback、不可信输入

## 问题

问题：LLM 阶段怎么接进流水线？模型输出格式漂移/幻觉/超时，你的流程怎么办？

## 面试可讲版（五段式）

**① 业界背景**
LLM 进生产流水线的核心矛盾：**模型输出是概率的，流程判定必须是确定的**。业界手段：约束解码/JSON mode（`response_format`）、schema 校验、失败重试与错误回填、以及"模型只当建议、判定权留给确定性代码"。另外 AI 系统强调可观测：prompt/响应/耗时/成本必须可回溯。

**② 项目选择**
抽象层：**StageTask 接口**统一"阶段任务"，两个实现——`ProcessGateTask`（跑进程门禁）与 `HarnessingTask`（LLM 任务）。① 阶段（需求分析）的链路：
```
prompt 模板（prompts/harnessing-ac.md，注入项目/需求）
  → OpenAI 兼容调用（temperature 0.2 + response_format=json_object）
  → JSON 解析（含 ```代码块剥离）
  → AcValidator 可测性校验（非空、id 唯一、描述非空）
  → GateResult(AC_TESTABLE)，AC 数进 metrics
  → 产物 = AC 文档 + 阶段报告；LlmCall 留证（prompt/响应原文 + jsonl 索引）
```
**③ 坑**
- **解析失败不重试**：当前策略是直接红 + 原文与 prompt 全量留档。理由：证据优先，先能复盘"模型到底吐了什么"；错误回填重试是设计（防无限重试烧钱）；
- **失败路径也要留证**：调用失败（网络/额度）时 `model=n/a`、`rawResponse=""` 也写一条 REJECTED 记录，不让失败变成"没有记录"；
- **演示的诚实**：无 API Key 走 fake provider，但 **prompt 是真实渲染的**、响应原文留档；真实端点用本地 HttpServer 做单测（请求体/鉴权/200/500/缺 content）；
- LLM 门禁的 `exitCode` 语义是约定的（0 合格 / 1 不合格），不混入进程退出码。

**④ 兜底**
- ② 编码闭环、多模型降级链、token 预算三段、知识库/上下文注入：设计未实现；
- prompt 注入无专门防护（缓解：AC 只是"草案"，且**下游门禁不信任模型判定**）；
- AC 数量阈值、覆盖度校验（主流程+边界）是 prompt 约束，不是机器强制。

**⑤ 话术**
> "LLM 阶段我的原则是：**把模型输出当不可信输入**——格式校验、内容校验、全量留证；判定权永远在确定性代码手里。演示环境没 Key 走 fake，但 prompt 和响应都是真留档的，真实端点也有 HTTP 层单测。"

## 追问与参考回答

**追问1：为什么不重试？** 两个原因：证据优先（先看原始输出才谈修复）；成本与幂等（重试要 attempt 语义和预算，② 阶段一起做）。当前"解析失败=红"更保守。
**追问2：怎么保证 AC 质量？** 机器只能保证"可测形式"（非空/唯一/有描述）；语义质量靠双轴评审与人工确认。**不吹"AI 自动保证需求质量"**。
**追问3：换成别的模型要改代码吗？** 不用：OpenAI 兼容协议 + `HARNESS_LLM_BASE_URL/MODEL/API_KEY` 环境变量；客户端与业务解耦（`LlmClient` 接口）。
**追问4：成本怎么控？** 当前记录 promptChars/outputChars 与 tokens 字段（OpenAI 响应 usage），真实成本=可算；预算三段（软限切轻量/硬限拒绝）是设计。
**追问5：LLM 和门禁的关系？** LLM 负责"生成/建议"（AC 草案、评审意见），门禁负责"判定"（机器证据）；两者用统一的 GateResult 表达结果，进同一条状态机与证据链。

## 面试官评分点

**高级开发级**：能说清 LLM 阶段的链路与两道校验；知道 fake/真实的边界。
**架构师加分**：StageTask 抽象的意义（LLM 任务与进程任务统一建模）；证据优先的失败策略；模型当不可信输入的安全观。
**危险信号**：把 LLM 说成"能自动保证质量"；解析失败静默兜底；不留 prompt/响应。

## 本项目真实证据

- `llm/.../OpenAiCompatibleClient.java`（HttpClient + JSON mode + 错误路径）、`AcGenerator.java`（模板/解析/剥离）、`AcValidator.java`
- `engine/.../HarnessingTask.java`（GateResult + AcReport + LlmCall）、`StageTask.java`
- 测试：llm 14 用例（含本地 HttpServer 的 3 个客户端用例）、engine 18（含 `llmFailureBlocksHarnessingAndKeepsPromptEvidence`）
- 真实演练：xhs ① 阶段 AC 3 条，`[PASS] AC_TESTABLE AC 可测性校验通过（3 条）`；留档 `evidence/d4-xhs/changes/chg-f76e814e/llm/llm-33da2901-{prompt.txt,response.json}`

## 发散追问地图（横向）

- 结构化输出：JSON schema、function calling、约束解码（grammar）
- 可靠性：超时/重试/降级链/熔断、prompt caching
- 安全：prompt 注入、输出越权（模型生成危险命令）、数据脱敏
- 评测：AC 质量评估、LLM-judge 与人工抽检
- 成本：token 预算、模型路由、批处理

## 版本与来源

本项目 llm/engine 模块源码与测试；OpenAI Chat Completions 兼容协议文档；xhs 演练证据包。

## 真实性说明

① 阶段链路、留证、校验、fake fallback、HttpServer 单测均为代码与运行事实；② 编码闭环/降级/预算/注入防护为设计未实现。
