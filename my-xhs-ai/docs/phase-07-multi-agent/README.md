# Phase 7: 多 Agent 协作 + A2A

## 前置依赖

- **Phase 5 (Agent架构)**：控制平面→调度多个 Agent
- **Phase 6 (MCP)**：多个 Agent 共享 10 个 MCP Server

## 与 my-xhs 的关联

| 本 Phase 产出 | my-xhs 集成点 | 回答什么业务问题 |
|-------------|-------------|----------------|
| Meta Planner | 控制平面拆解任务 | "分析上周订单下降原因并出报告" |
| 并行 Agent 执行 | 3 个子 Agent 分别查 order/payment/log MCP | 并行缩短分析时间 |
| HITL Gate | 高风险操作需人工确认 | "建议修改库存阈值"→暂停等确认 |

## 学什么

| 模块 | 内容 |
|------|------|
| 多 Agent 模式 | Meta Planner / Peer-to-Peer / Handoff + 决策准则 |
| A2A 协议 | Agent Card + 通信模式 + Nacos 注册发现 |
| Meta Planner | 任务拆解→依赖分析→并行/串行判断→结果汇总→失败恢复 |
| 并行执行 | 独立子 Agent 并行调不同 MCP Server + 超时控制 + 结果合并 |

## 生产工程问题

| 问题 | 本 Phase 如何解决 | 验证方式 |
|------|-----------------|---------|
| **部分失败** | 子 Agent A 超时→Meta Planner 用 B+C 结果继续，标注 "A 数据暂不可用" | 1/3 子 Agent 失败→返回包含标注的部分分析 |
| **Agent 漂移** | Meta Planner 每步检查子 Agent 输出是否与原任务相关 | 子 Agent 偏离任务→Meta Planner 重新分配 |

## 文档清单（5 篇）

| # | 文档 | 内容要点 |
|---|------|---------|
| 01 | multi-agent-patterns.md | 三种模式+决策准则+对比表 |
| 02 | a2a-protocol.md | A2A 协议深度+Agent Card+通信流 |
| 03 | meta-planner.md | 任务拆解→分配→汇总→失败恢复 |
| 04 | parallel-execution.md | 并行架构+超时+结果合并 |
| 05 | hitl.md | 高风险暂停+人工确认+恢复/拒绝 |

## 代码结构

```
src/main/java/com/myxhs/ai/multiagent/
├── MetaPlanner.java             # 任务拆解+分配+汇总
├── SubAgentManager.java         # 子 Agent 生命周期
├── A2AConnector.java            # A2A 通信+Agent Card
├── ParallelExecutor.java        # 并行执行引擎
└── HITLGate.java                # 人工确认门
```

## 验证标准

1. Meta Planner 拆解 "分析订单下降原因"→"查订单量+查支付成功率+查错误日志" 3 个子任务
2. 子 Agent 并行执行（3 个 MCP Server 同时调）→汇总时间 < 单次串行时间
3. 汇总：每个子 Agent 结论合并为完整分析
4. 高风险操作暂停+人工确认后继续
5. 1/3 子 Agent 超时→返回 "订单量和错误日志分析完成，支付数据暂不可用"

## 对后续的影响

- **Phase 12 (部署)**：子 Agent 集群独立扩缩容
