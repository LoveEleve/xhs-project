# 第22题 | 组件深度拷打：AgentScope 2.0 Java

> 难度：★★★★★｜频率：★★★★☆｜区分度：高
> 关键词：HarnessAgent/ReActAgent、RuntimeContext、AgentStateStore、PermissionEngine、事件流、无状态单例

## 问题
为什么选 AgentScope？它的核心机制是什么？框架权限和状态怎么用的？踩过哪些框架级的坑？

## 面试可讲版（五段式）

**① 原理层（框架组成，源码级验证过）**
- **入口与调用**：`HarnessAgent.builder()`（harness 自动带 core）；`agent.call(msg, RuntimeContext)` / `streamEvents(...)` 返回**类型化事件流**（30 种 `AgentEventType`：TEXT_BLOCK_DELTA / TOOL_CALL_START / REQUIRE_USER_CONFIRM / USER_CONFIRM_RESULT…）；
- **无状态单例**：一个 Agent 实例并发服务多 `(userId, sessionId)`，**同 session 自动串行、不同 session 并行**——多副本部署的前提；
- **状态**：`AgentStateStore` 按 `(userId, sessionId)` 寻址，承载对话/摘要/权限规则/tool state；Redis 实现带 **versioning CAS**；
- **权限（HITL）**：`PermissionEngine` 三态（allow/ask/deny）+ 挂起/恢复事件链，框架原生就能做人工确认；
- **上下文治理**：`CompactionConfig(triggerMessages, keepMessages)` 压缩 + 工具结果驱逐；人格/记忆用 `AGENTS.md` + memory 文件自动注入；
- **技能与观测**：技能四层合成 + `skillRepository()`；`OtelTracingMiddleware` 接 OTel。

**② 项目用法（真实装配）**
- 版本 **2.0.1**（`agentscope-harness` + extensions：openai 模型 / redis 状态 / git 技能）；装配代码（`AgentService`）：
  `ReActAgent.builder()` + sysPrompt + model + toolkit + `RedisAgentStateStore(keyPrefix="xhs-ai:state:")` + **maxIters=12** + `checkRunning(true)` + `generateOptions(temperature=0.2, maxTokens=8192)`；
- **权限模式 = `PermissionMode.BYPASS`**：非交互 API 场景没有人在线应答权限询问；`RV19 实测`——ES MCP 工具无只读注解时，默认 **ASK 会把工具卡在 asking（答复为空）**，`DONT_ASK` 仍被默认规则拒绝，所以框架级检查放开；
- **补偿设计**（把框架安全网关掉就必须自己兜住）：工具**白名单**（16 个自研）+ **应用层 HITL 审批状态机**（危险工具挂起→审批→执行→核验→审计）+ 全部审计留痕；
- **状态存 Redis**（Sentinel，CAS 重试 ≤3）：MySQL 里**没有 `agentscope_*` 表**——框架表边界（Flyway 只管网表）是设计规则，实际状态不走 DB；
- **技能**：`GitSkillRepository`（技能变更走 git PR 治理）；**v1 不启用沙箱**（工具全 HTTP，无不可信代码执行）。

**③ 框架级坑（全部实测，很值钱）**
1. **工具注册 ≠ 可用**：PermissionEngine 默认 ASK + 无只读注解 → 工具永久挂起（表现为"空答复 503"）；根因定位到事件流里停在 `asking`；解法 = BYPASS + 应用层白名单/审批（**代价：框架不做最后一道拦截，应用层必须严格**——主动承认的边界）；
2. **不支持工具热替换**：运行时重挂 MCP client 后已注册工具不重绑（调用报 `MCP client not initialized`）→ 架构解法"去 MCP 化"（工具全自研，MCP 只留运维直连）；
3. **`RuntimeContext` 必须每次传**：不传 `sessionId` 会落 `defaultSessionId` 串会话——多租户场景是 P0；
4. **`tools.json` 白名单连内置工具一起过滤**（read_file/memory_search 等要显式保留）；
5. **本地 stateStore + 远程 filesystem 组合直接 `IllegalStateException`**（框架设计使然：防状态留本地盘）；
6. **依赖治理**：harness 附带 kubernetes-client/aliyun-sdk-oss 等重依赖需排除；okhttp 版本与 BOM 对齐（降级）——Enforcer/依赖收敛的实战来源。

**④ 兜底**
- 框架级权限放开 → 应用层白名单 + HITL + 审计链三件套补偿；
- 状态丢失 → 从 `ai_message` 重建对话 + 摘要注入（14 题）；
- 框架表边界：Flyway 只管 `ai_*`，框架自管表（如未来切 MySQL 状态）不纳管；
- 版本升级策略：锁定 2.0.1 + 契约测试护航（升级前跑 UT/契约/评测）。

**⑤ 拷打追问**
1. **"为什么 AgentScope 不是 Spring AI/LangChain4j？"** Harness 能力（分布式状态/权限/技能/事件流/压缩）是内置的；Spring AI 偏模型抽象、Agent 编排弱；LangChain4j 同；Python 生态与平台 Java 不符。
2. **"ReAct 循环怎么跑的？"** 思考→工具调用→观察→再思考，`maxIters=12` 上限；事件流把每一步暴露给 SSE 前端。
3. **"框架怎么保证同会话不并发写状态？"** 无状态单例 + 同 session 串行调度 + stateStore CAS（版本冲突重试 ≤3）。
4. **"为什么敢 BYPASS 框架权限？"** 这是权衡不是偷懒：非交互 API 无人应答 + 框架 ASK 有实测缺陷；补偿是应用层白名单+HITL+审计——并承认"框架不再兜底"的风险。
5. **"读过框架源码吗？"** 做过生产化深读（R02）：源码级验证了自定义 endpoint、挂起/恢复事件链、30 种事件枚举、Redis Sentinel 客户端支持、技能格式、MySQL DDL 归属。
6. **"升级框架怎么办？"** 锁定版本 + 契约/评测门禁；框架表不纳管所以升级不动业务表；BYPASS 这类"绕过点"升级后必须复测（RV19 就是复测发现的）。
7. **"框架能力边界在哪？"** 不做工具热替换、不做进程管理（MCP 进程自愈要自建）、事件流的会话续跑订阅要自己接线——这些是我们架构选择的依据。

**⑥ 话术**
> "选 AgentScope 是因为它内置了 Harness 能力——分布式状态、权限、事件流、上下文压缩、技能治理，这些要是自己写等于重造框架。用它最值钱的经历是两个：一是实测出权限引擎默认 ASK 会把没有只读注解的工具卡死在 asking，表现为空答复，最后我们 BYPASS 框架权限、在应用层做白名单+HITL+审计来补偿，并且明说'框架不再兜底'这个风险；二是框架不支持工具热替换，我们就干脆去 MCP 化，把风险从架构上消除。状态走 Redis、MySQL 只放业务表，框架表边界用 Flyway 划清。"

## 发散追问地图（横向）
- 框架对比：AgentScope vs Spring AI vs LangChain4j vs Python 生态。
- 框架机制：事件流类型、压缩策略、技能仓库、OTel 中间件。
- 多租户：RuntimeContext/IsolationScope、状态隔离、并发语义。
- 扩展：Model 抽象、自定义中间件、HITL 钩子。
- 工程：依赖收敛、版本锁定、升级回归。

## 面试官评分点
**高级开发级**：能讲框架组成、ReAct 循环、状态存储、权限三态。
**架构师加分**：BYPASS 的权衡与补偿闭环；工具热替换缺失的架构级解法（去 MCP 化）；RuntimeContext 多租户坑；框架表边界；源码级验证清单。
**危险信号**：只会调 API 不懂机制；不知道同会话串行；把框架权限当安全保证（BYPASS 了还不补偿）；框架表当业务表管理。

## 本项目真实证据
- `AgentService`（ReActAgent 装配/BYPASS 注释/maxIters/温度/Redis stateStore keyPrefix）；
- `xhs-ai/docs/research/02-agentscope-production-deepdive.md`（源码级 6 项验证 + 6 条生产坑位）；
- `xhs-ai/docs/02-architecture.md` ADR 1/2/5/6/7/12；MySQL `my_xhs_ai` 库表清单（无 agentscope_*）；
- RV19（ASK 挂起）实测记录。

## 版本与来源
AgentScope 2.0.1 官方文档 + 源码级验证（R02）；本项目装配代码。

## 真实性说明
版本/装配参数/BYPASS 决策/表清单均为代码与运行态事实；"框架不再兜底"的风险、沙箱/热替换缺失等边界主动披露。
