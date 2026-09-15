# 第01题 | Agent 架构与 ReAct 工具循环

> 难度：★★★★☆｜频率：★★★★★｜区分度：高
> 关键词：AgentScope 2.0 Java、ReAct、Toolkit、RuntimeContext、Redis 状态、workflow vs agent、单 Agent

## 问题
问题：你这个 Agent 的架构讲一下。为什么用 Agent 而不是确定性工作流？为什么选 AgentScope（Java）而不是 LangChain/LangGraph？

## 面试可讲版（五段式）

**① 业界背景**
Anthropic《Building effective agents》把"工作流"和"Agent"做了清晰区分：能用确定性编排解决的别用 Agent。围绕这条线，业界一直在争论：① 什么时候该上 Agent（Anthropic 的答案是"路径不可预知、需要动态决策"）；② 用什么框架——LangChain/LangGraph 生态大但以 Python 为主，Spring AI/LangChain4j 在 Java 侧 Harness（HITL/状态/评测）能力弱，AgentScope 2.x 则是 Java 原生 + 内置 Harness。再叠加 MCP 协议兴起，工具接入标准化成为共识。

**② 项目选择**
栈：AgentScope 2.0 Java（HarnessAgent/ReActAgent）+ Spring Boot 3.2.5 + JDK 17；架构是"单 Agent + 工具循环"——ReAct（思考→调工具→观察→再思考），maxIters=12；工具 16 个全自研（DLQ/日志/指标/知识/代码五类），ES DSL 与 PromQL 收在服务端；状态存 Redis（`xhs-ai:state:`，CAS 冲突重试 ≤3），MySQL 只放业务表；危险工具（重投）挂起走 HITL 审批；模型双通道（chat=deepseek-v4-pro / Agent=qwen3.8-flash / 降级 flash）。为什么是 Agent：诊断路径不可预知——同一个"为什么报错"，可能查日志、可能查指标、可能查代码，预先编排覆盖不了分支；反过来，单点查询类问题（如"up=0 实例"）用确定性工具就够，这也是我们的设计原则。

**③ 坑（主动承认）**
- 框架不支持工具级热替换：运行时重挂 MCP client 后已注册工具不重绑（实测 `MCP client not initialized`）→ 架构解法是**去 MCP 化**（16 工具全自研，MCP 仅运维直连）；
- `RuntimeContext` 必须每次传 userId/sessionId/traceId，否则落到 defaultSessionId 串会话；
- "工具注册成功 ≠ 可用"：框架权限默认 ASK 让无只读注解的 MCP 工具永久挂起（表现为空答复 503），是实测出来的隐蔽 P0；
- 多副本下并发护栏是 JVM 内存态（每实例各放 8）；跨实例审批的"业务续跑订阅"未接线，当前是事件广播 + 指标观测。

**④ 兜底**
- 状态恢复：进程重启续聊；Redis 状态丢失从 ai_message 重建最近对话 + 滚动摘要注入；
- 变更安全：审批状态独立持久化（超时 fail-closed、崩溃补执行、executing 回收）+ 三段式 settlement + 队列级位点核验；
- 成本/防滥用：按用户 token 预算三段（软限 80% 切轻量、硬限 429）、工具 schema 预算、并发护栏、请求幂等（X-Request-Id）；
- 行为边界：提示词 13 条硬约束（工具 ≤4 次、参数报错禁重调等）、工具 never-throw、空答复显式报错；
- MCP 进程级自愈（探测+三连击+限流+systemd 拉起；默认仅告警）。

**⑤ 话术**
> "路径不可知才上 Agent；Agent 的价值在跨系统取证与多跳推理，不在单点查询。我们选 AgentScope 是因为 Java 栈契合且内置 Harness；框架限制不硬扛——比如不支持工具热替换，我们就去 MCP 化把依赖拿掉。"

## 追问与参考回答
**追问1：Agent 和 workflow 的边界？** 看路径是否可预知、是否跨系统取证；Anthropic 的观点我也认同——能用确定性的别用 Agent。
**追问2：为什么不用 LangGraph？** 栈不匹配（跨进程桥接成本高）、HITL/状态要多写一层；AgentScope Java 原生内置 Harness。图编排优势在单 Agent 工具循环场景用不上。
**追问3：工具数量怎么控？** 双护栏——工具数软 36/硬 40（超硬拒绝启动）+ schema token 软 12k（历史实测 3428 tokens，当时 32 工具含 MCP 口径）；再涨上 tool_search。当前工具选择评测 12/12。
**追问4：Agent 崩了怎么办？** 状态在 Redis 可续聊；状态丢从消息表+摘要恢复；审批卡死有回收 Job；变更有 settlement+核验，不假设"发出去就成功"。
**追问5：为什么单 Agent 不搞多智能体？** 问题域不需要，多 Agent 成本×3 而收益不显著；我们按用户计量预算，多 Agent 会成倍消耗。当前 10 个真实案例已验证单 Agent 够用。

## 面试官评分点
**高级开发级**：能讲清 ReAct 循环与状态恢复；能给出工作流/Agent 判定标准；工具治理（白名单/预算/never-throw）。
**架构师加分**：框架选型对比与代价、单/多 Agent 取舍、状态分层（Redis 热/MySQL 冷）、水平扩展与预算全局一致性、主动列边界。
**危险信号**：把 Agent 当万能编排器；没有状态恢复/预算/审批；把框架限制说成"没问题"。

## 本项目真实证据
- `agent/AgentService:71-182`：16 工具装配、13 条提示词、maxIters=12、温度 0.2、`PermissionMode.BYPASS`；`RuntimeContext` 每次传。
- `api/AgentController:46,90`：同步 300s / SSE（delta/tool/approval_required/final）+ X-Request-Id 幂等。
- 状态：`RedisAgentStateStore`；F7 重建 + 滚动摘要实测（删状态仍能答历史事实）。
- 预算/并发：50 万/软 80%/硬 429；护栏 2/8；ToolBudget 软 36/硬 40。
- 去 MCP 化实测（live-drill）：ES MCP 死后问"文档数 top3 索引" **19s** 命中 `es_index_list`；Prom MCP 死后问"up=0 实例" **39s** 命中 `metric_query`。

## 版本与来源
Anthropic《Building effective agents》；AgentScope 2.0 Java 文档与源码核验（R02）；本项目 `docs/02-architecture.md`（23 条 ADR）、RV19/RV27。

## 真实性说明
工具数、maxIters、双通道、Redis 状态、预算/护栏为代码事实；"单机 ~10 并发"为 D07 估算（Agent 通道未单独压测）；"跨实例续跑未接线"见 RV17/RV18。
