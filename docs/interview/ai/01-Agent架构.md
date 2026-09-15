# 第01题 | Agent 架构与 ReAct 工具循环

> 难度：★★★★☆｜频率：★★★★★｜区分度：高
> 关键词：AgentScope 2.0 Java、ReAct、Toolkit、工具循环、RuntimeContext、Redis 状态、SSE 事件、workflow vs agent

## 问题
问题：你这个 Agent 的架构讲一下。为什么用 Agent 而不是确定性工作流？为什么选 AgentScope（Java）而不是 LangChain/LangGraph？

## 30 秒简洁回答
结论：这是一个"单 Agent + 工具循环"的架构——ReAct（思考→调工具→观察→再思考），上限 12 轮；工具 16 个全自研，分 DLQ/日志/指标/知识/代码五类；状态存 Redis，会话可恢复；危险工具（重投）挂起走 HITL 审批。选 Agent 而不是工作流，是因为诊断路径**不可预知**：同一个"为什么报错"的问题，可能查日志、可能查指标、可能查代码，预先编排的流水线覆盖不了分支；而"能确定性编排的绝不上 Agent"恰恰是我们的原则——单点查询类问题（如"up=0 实例"）其实用工作流更稳，Agent 的价值在跨系统取证与多跳推理。选 AgentScope 而不是 LangChain 系：项目是 Java 17/Spring 生态，AgentScope 2.0 原生 Java、内置 Harness（HITL/状态/技能/subagent），避免跨语言进程桥接；LangGraph 那套图编排留给 Python 侧对照。

## 展开回答（高级开发级）
**1）组件分层。** API 层（对话/Agent/审批/知识/MCP/会话/评测 7 组接口）→ Agent 运行时（ReActAgent：工具循环、事件流、状态存储）→ 工具层（16 个自研工具 + Toolkit 注册），横切：鉴权（内部令牌/JWT/ADMIN）、审计、模型网关、预算/并发护栏、可观测（17 指标）。

**2）工具循环的实现。** AgentScope 的 `ReActAgent.stream(messages, options, RuntimeContext)` 输出事件流（REASONING/TOOL_RESULT/AGENT_RESULT 等），我们映射成 SSE（delta/tool/approval_required/final/hint）。`RuntimeContext` 必须每次传（userId/sessionId/traceId），否则会落到 defaultSessionId 串会话；状态用 `RedisAgentStateStore`（前缀 `xhs-ai:state:`，CAS 冲突重试 ≤3），MySQL 只放业务表（session/message/approval/audit），避免热路径打 DB。

**3）"为什么不是工作流"的判定标准。** 三类：① 确定性单点查询（PromQL/索引列表）→ 直接工具/工作流；② 需要跨系统取证、路径依赖上一步结果（DLQ→查日志→查代码）→ Agent；③ 变更类（重投）→ Agent 提案 + HITL 执行。我们把"能用确定性解决"作为设计原则写进了评审（RV09），避免为 Agent 而 Agent。

**4）框架选型对比。** LangChain/LangGraph：生态大、Python 为主、图编排强；Spring AI/LangChain4j：Java 生态但 Harness 能力弱、HITL/状态需自建；AgentScope 2.0：Java 原生、Harness 完整、支持多副本状态与 HITL，正好贴合我们"Java 微服务 + 企业治理"的场景。代价：社区与生态相对小，遇到框架限制只能绕（比如工具级热替换不支持 → 我们做了去 MCP 化）。

**5）行为边界。** 提示词 13 条硬约束（同一工具最多 1 次、参数报错禁止重调、总工具调用 ≤4、系统本体问题必须走知识检索、锚点事实两关键词各查一次+读卡、引用只允许卡片 id）；`maxIters=12` 是循环上限兜底；空答复显式报错（不静默）；工具 never-throw，错误以结构化结果返回给模型自行纠正。

## 进一步回答（架构师层级）
**可演进性**：工具注册表 + 白名单 + 预算护栏（软 36/硬 40、schema token 软 12k）保证工具扩张可控；MCP 作为外部能力入口（只桥接 Tools，双白名单，未知工具快速失败）；扩展框架设计为两代（SPI → 出进程 Sidecar，禁止 in-process full-trust）。
**状态与恢复**：Redis 状态 + MySQL 消息备份 + 会话重建 + 滚动摘要；进程重启续聊、状态丢失可从消息表恢复；审批状态独立持久化，跨实例恢复走事件总线（当前为事件通知，业务续跑订阅未接线——主动边界）。
**成本与性能**：模型网关双通道（chat=pro/agent=qwen3.8-flash/降级 flash）、按用户 token 预算三段、并发护栏、请求幂等；容量估算单机 ~10 并发诊断（瓶颈是 LLM 配额，不是服务本身），水平扩展靠无状态多副本。
**为什么不搞多 Agent**：当前问题域单 Agent 足够，多 Agent 会放大成本与不确定性（业界数据：多智能体在本任务上成本×3 而收益不显著）；子代理/subagent 能力留给后续复杂场景。

## 理解与复述提示
本质：用"路径是否可预知"回答为什么 Agent；用"生态契合/内置 Harness"回答为什么 AgentScope；用"约束+预算+状态恢复"回答工程化。
顺序：① 架构分层 → ② ReAct 循环与事件流 → ③ Agent vs 工作流判定 → ④ 框架对比与代价 → ⑤ 行为边界与恢复 → ⑥ 边界（单 Agent、多副本待验）。
记忆钩子："路径不可知才上 Agent；Agent 的价值在取证与多跳，不在单点查询。"

## 必记关键词
ReAct、Toolkit、RuntimeContext、AgentStateStore、SSE 事件映射、maxIters、工具预算、HITL 挂起/恢复、workflow vs agent、AgentScope vs LangGraph。

## 必须明确的边界
- 单机 ~10 并发诊断是估算值；压测覆盖的是对话通道，Agent 通道未单独压。
- 多副本部署下并发护栏是 JVM 内存态（每实例各放 8），预算才是全局的（Redis）。
- 跨实例审批"业务续跑订阅未接线"，当前是事件广播 + 指标观测。
- 向量检索/沙箱/长期记忆等按止损规则未启用（非欠债，有意不做）。

## 常见错误
- 把 Agent 说成"什么都能干"：不区分确定性工作流与 Agent，导致成本和稳定性都失控。
- 不提状态与恢复：Agent 跑 3 分钟后崩溃，会话从头再来。
- 不提边界：工具无预算、无白名单、无审批，模型可以随便调危险操作。
- 用 Python 框架硬塞 Java 项目（跨进程桥接）却说"架构一致"。

## 本项目真实证据
- `agent/AgentService.java:71-182`：装配 16 工具、提示词 13 条、maxIters=12、温度 0.2、maxTokens 8192、`PermissionMode.BYPASS`（非交互 API）；`RuntimeContext` 每次传 userId/sessionId/traceId。
- `api/AgentController.java:46,90`：同步 300s / SSE（delta/tool/approval_required/final），`X-Request-Id` 幂等（409/回放）。
- 状态：`RedisAgentStateStore` 前缀 `xhs-ai:state:`；F7 重建 + 滚动摘要（production-gaps 报告实测：删状态后仍能答出历史事实）。
- 预算/并发：`TokenBudgetService`（50 万/软 80%/硬 429）、`AgentConcurrencyGuard`（2/8）、`ToolBudget`（软 36/硬 40）。
- 框架限制实例：工具级热替换不支持 → 去 MCP 化（16 自研，kill 两个 MCP 仍可诊断 19s/39s）。

## 自测要求
- 30 秒：架构分层 + ReAct + 为什么不是工作流。
- 3 分钟：状态存储与恢复、工具治理、框架选型代价、单 Agent 理由。
- 能回答：如果让你调度多个子任务并行，你的架构要怎么改？

## 追问与参考回答
**追问1：Agent 和 workflow 的边界怎么定？**
回答：看路径是否可预知、是否需要跨系统取证。像"查 up=0 实例"直接工具即可；"订单报错为什么"要日志→指标→代码多跳，才用 Agent。Anthropic《Building effective agents》的观点也是"能用确定性的别用 Agent"，我认同。

**追问2：为什么不用 LangGraph？**
回答：栈不匹配（Java 微服务）、跨进程桥接成本高、HITL/状态要多写一层。AgentScope 2.0 Java 原生并内置 Harness；LangGraph 图编排的优势在我们这个"单 Agent 工具循环"场景用不上。

**追问3：工具数量怎么控制？**
回答：双重护栏——工具数（软 36/硬 40，超硬拒绝启动）+ schema token 预算（软 12k，实测 3428）；再涨就上 tool_search 渐进加载。事实是工具选择评测 12/12，当前不是瓶颈。

**追问4：Agent 崩了怎么办？**
回答：状态在 Redis，重启续聊；Redis 状态丢了从 ai_message 重建最近对话 + 摘要；审批卡死有回收 Job；变更有 settlement + 核验，不假设"发出去就成功"。

**追问5：为什么单 Agent 不搞多智能体？**
回答：问题域不需要，多智能体会放大成本与不确定性；我们的预算是按用户计量的，多 Agent 会成倍消耗。当前用工具编排 + 提示词约束已经覆盖 10 个真实故障案例。

## 示例与使用说明
```java
RuntimeContext ctx = RuntimeContext.builder()
    .userId(String.valueOf(userId))
    .sessionId(userId + ":" + sessionId)   // 防串会话
    .put("traceId", traceId)
    .build();
return agent.stream(List.of(new UserMessage(message)), options, ctx)
    .contextWrite(c -> c.put(TokenBudget.USER_ID_KEY, userId)); // 预算计量
```
```text
SSE 事件映射：delta(增量文本) / tool(工具过程) / approval_required(挂起审批)
              / final(最终答复) / hint(提示)
```

## 面试官评分点
**高级开发级**：能讲清 ReAct 循环与状态恢复；能给出"工作流 vs Agent"的判定；能说清工具治理（白名单/预算/never-throw）。
**架构师加分**：框架选型对比与代价、单/多 Agent 取舍、状态存储分层（Redis 热/MySQL 冷）、水平扩展与预算全局一致性、主动列边界。
**危险信号**：把 Agent 当万能编排器；没有状态恢复与预算；不做危险操作审批；把框架限制说成"没问题"。

## 实战练习
1. 用 3 个不同路径的问题（单点查询/多跳诊断/变更操作）验证 Agent 的工具调用路径与耗时。
2. kill -9 服务后重启，验证同会话续聊与审批状态保留。
3. 把提示词的"总工具调用 ≤4"去掉，观察成本与路径变化。

## 版本与来源
- AgentScope 2.0 Java（Harness/ReActAgent/McpServerRegistrar）官方文档与源码核验（R02 深读）。
- Anthropic《Building effective agents》（workflow vs agent）。
- 本项目：`agent/AgentService`、`api/AgentController`、`docs/02-architecture.md`（23 条 ADR）、`docs/research/02-agentscope-production-deepdive.md`。
- 框架限制与规避：RV19/RV27（去 MCP 化）。

## 真实性说明
16 工具、maxIters=12、双通道模型、Redis 状态、预算/并发/工具护栏均为代码事实；"单机 ~10 并发" 来自容量设计估算（D07），Agent 通道压测未做属边界；"跨实例续跑未接线"来自 RV17/RV18 记录。
