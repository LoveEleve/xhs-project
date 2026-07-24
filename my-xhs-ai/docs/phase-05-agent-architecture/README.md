# Phase 5: Agent 架构全貌

## 前置依赖

- **Phase 2 (LLM原理)**：理解模型能力边界
- **Phase 4 (记忆)**：记忆基础设施

## 为什么第五

Phase 5 是 14 个 Phase 的**核心枢纽**——前面全是基础，Phase 5 把 2026 年 Agent 架构三个新范式讲透。分两个轨道：

**5-A**：理论——控制平面+分布式运行时+Skills 范式（含 Loop/Graph 定位）
**5-B**：AgentScope 2.0 源码深度——生产级 Agent 框架怎么设计的

## 与 my-xhs 的关联

| Agent 运行时需求 | my-xhs 已有组件 | 本 Phase 如何使用 |
|----------------|----------------|-----------------|
| Agent 服务发现 | Nacos | Agent 实例注册+发现 |
| 异步任务编排 | RocketMQ | Agent 任务消息+延时重试（`TOPIC_AGENT_TASK`） |
| 分布式会话恢复 | Redis | Agent 状态快照存储（`agent:{agentId}:session:{sessionId}`） |
| 定时触发 | XXL-Job | 周期性巡检 Agent 任务 |
| 限流熔断 | Sentinel | Agent 推理请求保护 |
| 全链路追踪 | SkyWalking | Agent 执行链追踪 |
| 配置热更新 | Nacos Config | Prompt/Model 配置热加载 |

## 学什么

### 5-A：理论基础

| 模块 | 内容 |
|------|------|
| 控制平面 | OpenAI Symphony：看板=Agent 调度器、任务树+依赖、HITL |
| 分布式运行时 | Google Agent Executor：事件日志+快照+可恢复执行 |
| Loop 范式对比 | CoT→ToT→ReAct→Reflexion→Self-Refine 五种对比：适用场景+局限 |
| Graph 编排 | LangGraph StateGraph：条件路由+并行+检查点+HITL |
| Loop vs Graph | 什么时候用循环（简单任务）vs 图（复杂多步+分支+并行） |
| 论文 | ReAct (ICLR 2023)、Reflexion (NeurIPS 2023)、Harness 实证 (arxiv 2604.18071) |
| 架构公式 | 华为 Ping Guo 的 "Agent = Model + Harness" 公式 |

### 5-B：AgentScope 2.0 源码深度

| 模块 | 内容 |
|------|------|
| Harness 层 | HarnessAgent vs ReActAgent 双层设计、单例无状态引擎、Builder 模式 |
| 三层记忆实现 | Flush→Consolidation→Compaction 三步源码 |
| Skill 生命周期 | 自动保存 SKILL.md→draft→active→stale→archived |
| 权限三态 | 允许/审批/拒绝 + HITL 内生机制 |
| Plan Mode | 只读规划态→plan 文件持久化→驱动执行 |
| 事件流 | 28 种类型化事件+start→delta→end 三段式 |
| Middleware | 5 阶段：onSystemPrompt/onAgent/onModelCall/onReasoning/onActing |
| vs LangChain4j | 能力矩阵对比：哪些是 LangChain4j 没有的、哪些可自实现 |

## 生产工程问题

| 问题 | 本 Phase 如何解决 | 验证方式 |
|------|-----------------|---------|
| **Agent 漂移** | 控制平面定期检查子任务是否偏离主任务目标；ReAct 每步注入任务描述 | 10 轮分析后仍围绕原问题 |
| **无限循环** | Step limit + 语义相似度判重（连续 3 步产出相似结果→终止） | 注入循环场景→Agent 在步数限制内终止 |
| **工具选择错误** | Agent 调用工具前做参数校验；错误的工具结果不被后续步骤引用 | 100 次调用中选错工具率 < 5% |
| **部分失败** | 某 Tool 超时→返回"该数据源暂时不可用"→用其他 Tool 结果继续 | 3/5 成功→返回部分分析 |
| **成本爆炸** | Token 预算 + 步数硬限制 + 单次调用成本上限（$0.10） | 单次 Agent 调用成本 < $0.10 |
| **冷启动** | Phase 4 记忆系统提供默认上下文；Phase 3 RAG 提供业务知识 | 新会话首次回答问题可用 |
| **模型降级** | FallbackModel：DeepSeek→备选模型（本地 Ollama）自动切换 | 主模型故障→Agent 不中断 |

## 文档清单（10 篇）

| # | 文档 | 轨道 | 内容要点 |
|---|------|:---:|---------|
| 01 | control-plane.md | A | OpenAI Symphony+任务树+HITL |
| 02 | distributed-runtime.md | A | Google Agent Executor+事件日志+快照 |
| 03 | loop-comparison.md | A | CoT/ToT/ReAct/Reflexion/Self-Refine 五种对比+选择决策树 |
| 04 | graph-orchestration.md | A | LangGraph StateGraph+条件路由+并行+检查点 |
| 05 | myxhs-runtime.md | A | my-xhs 已有基础设施→Agent 分布式运行时（7 组件映射） |
| 06 | react-paper.md | A | ReAct 论文精读（ICLR 2023） |
| 07 | reflexion-paper.md | A | Reflexion 论文精读（NeurIPS 2023） |
| 08 | harness-architecture.md | B | AgentScope Harness 双层设计+无状态引擎+Builder 源码 |
| 09 | memory-skill-permission.md | B | 三层记忆+Skill 生命周期+权限三态源码分析 |
| 10 | agentscope-vs-langchain4j.md | B | 能力矩阵+架构差异+适用场景+自实现方案 |

## 代码结构

```
5-A（理论基础）：
src/main/java/com/myxhs/ai/
├── runtime/
│   ├── TaskScheduler.java      # XXL-Job+RocketMQ 任务调度
│   ├── AgentStateSnapshot.java # Redis 快照+恢复
│   ├── LoopDetector.java       # 语义相似度判重+循环检测
│   ├── DriftDetector.java      # 任务偏离度检测
│   └── FallbackRouter.java     # 主备模型切换

5-B（AgentScope 深学）：
src/main/java/com/myxhs/ai/harness/
├── MemoryCompactor.java        # 自实现结构化压缩
├── SkillRegistry.java          # Nacos 技能注册中心
├── PermissionEngine.java       # 三态权限引擎
└── MiddlewareChain.java        # 5 阶段中间件链
```

## 验证标准

1. Agent 任务通过 RocketMQ 异步编排，失败自动重试
2. 服务重启→Redis 恢复 Agent 会话（< 2s 恢复）
3. 循环检测：连续 3 步语义相似 > 0.95→自动终止
4. AgentScope vs LangChain4j 能力矩阵对比表（覆盖 8 模块）
5. 部分失败：3/5 Tool 成功→返回"部分数据不可用"的分析
6. 模型降级：DeepSeek 故障→备选模型自动接管

## 对后续的影响

- **Phase 6 (MCP)**：Skills 的底层执行技术
- **Phase 7 (多Agent)**：控制平面调度多个 Agent
- **Phase 8 (Skills)**：Skill 完整生命周期管理
- **Phase 9 (评测)**：Agent 存在了才能评测
