# 系统边界：控制面、工具面和外部世界如何分层

> 对应目录：`vol-ai/00-overview-architecture/`
> 目标问题：`my-xhs-ai` 为什么不是一个“大而全的 AI 单体”，而是一个控制面 + 工具面 + 外部依赖拼起来的诊断系统？

## 一句话困惑

很多人看到 `my-xhs-ai` 的第一反应，是把它想成“一个 Spring Boot 服务，外面接个大模型就完了”。但真实结构并不是这样：系统真正的复杂度来自**控制面和工具面分离、同步调查链和外部可观测依赖交织、以及状态/审批/trace 的横切能力**。

## 一句话答案

`my-xhs-ai` 不是一个靠“大模型直接吞掉一切”成立的系统，而是**`ai-app` 做控制面、`ai-mcp` 做工具面、外部中间件提供事实和观测、Langfuse/Temporal 提供横切能力**共同组成的多层结构；真正抬高复杂度的不是模型，而是不同层的职责边界完全不同。

## 最小拓扑

```text
用户 / 调用方
   ↓
my-xhs-ai-app (19020)
   ↓
my-xhs-ai-mcp (19021) / direct tools
   ↓
MySQL / RocketMQ Dashboard / Prometheus / 日志文件 / Embedding / Langfuse / Temporal
```

## 分层解释

### 1. `my-xhs-ai-app`：控制面
职责：
- API
- 意图路由
- Agent Harness
- Run 状态机
- 会话与记忆
- HITL 审批
- SSE
- Langfuse trace
- Temporal PoC

这层不直接“拥有业务事实”，但决定了系统怎么调查、什么时候停、什么时候审批、怎么回溯。

### 2. `my-xhs-ai-mcp`：工具面
职责：
- MCP 暴露工具
- 受控只读查询
- 日志检索
- 观测查询

这层本质上是“模型与事实系统之间的闸门”。

### 3. 外部事实与观测层
包括：
- MySQL（业务事实、AI 自有状态）
- RocketMQ Dashboard（DLQ）
- Prometheus（观测）
- 日志文件（根因）
- Embedding 模型（记忆语义检索）
- Langfuse（可视化 trace）
- Temporal（durable execution PoC）

这一层不是“工具附件”，而是 Agent 调查成立的事实地基。

## 最重要的边界

### 业务事实不属于模型
订单量、支付成功率、库存状态、DLQ 消息，这些都不属于模型。模型只能通过固定工具接触它们。

### 执行能力不属于模型
`dlq.redeliver` 这种 L3 高危动作，必须经过审批，不因为模型“觉得该做”就直接做。

### 长任务控制不属于模型
Temporal PoC 证明了 durable execution 不是“模型多想两步”，而是 workflow / worker / signal / state 的系统能力。

## 当前架构的真实层次

从控制面角度看，`my-xhs-ai` 已经不是轻量 demo，而是一套完整的 Agent 控制系统。

从工具与部署角度看，它还不是完全平台化的最终产品。

所以最准确的判断是：
> **控制面已经很强，平台化还在继续。**
