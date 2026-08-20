# 项目定位：它到底是什么，不是什么

> 对应目录：`vol-ai/00-overview-architecture/`
> 目标问题：`my-xhs-ai` 到底是聊天机器人、自然语言 BI、还是企业级 Agent 平台？

## 一句话困惑

如果只看名字，`my-xhs-ai` 很容易被理解成“接了大模型的电商项目”。但只要顺着真实代码、测试和运行证据往下看，就会发现它的重点根本不在“聊天”，而在**如何把模型塞进真实业务边界里，并且让它可约束、可追踪、可审批、可恢复**。

## 一句话答案

`my-xhs-ai` 的本质不是聊天系统，而是一个**面向运营/运维诊断场景的受限 Agent 控制平面**：模型只负责理解与决策，真正决定系统边界的是 Harness、ToolRegistry、PolicyGuard、HITL、RunStore、Langfuse 与 Temporal PoC。

## 先建立最小心智模型

先不要把它看成“一个 AI 服务”，而要把它看成四层结构：

```text
用户问题
  ↓
API / Intent Router
  ↓
Bounded Agent Harness
  ↓
Tool / MCP / Observability / Memory / Approval / Trace
```

这四层里最重要的不是大模型，而是中间两层：

- **Intent Router** 决定“这是不是一个该交给 Agent 的问题”
- **Agent Harness** 决定“模型即使参与，也只能怎样参与”

如果这两层没有立住，后面的 MCP、Memory、Langfuse、Temporal 都会变成堆功能点，而不是一套可解释的系统。

## 它不是什么

先把最容易混淆的三个错误定位排掉：

### 1. 它不是通用聊天机器人
它不会和用户自由闲聊，也不以“回答自然”作为第一目标。真正的目标是：
- 数字有来源
- 工具有边界
- 高危动作要审批
- 任务过程可追踪

### 2. 它不是让 LLM 直接查数据库的自然语言 BI
代码里虽然有大量业务数据查询能力，但都被包成固定工具：
- `queryOrderVolume`
- `paymentSuccessRate`
- `httpErrors`
- `mqDlqQuery`
- `logSearch`

模型不能自由拼 SQL、PromQL、ES DSL。也就是说，它不是“让模型自由看数”，而是“让模型在固定工具边界里调查”。

### 3. 它也不是已经彻底平台化的成品
它现在已经非常强，但还不是：
- 300+ release 级评测全做完
- 红队测试全做完
- 部署/回滚/压测全做完
- 真正多 Agent 协作平台

所以它最准确的阶段定位是：
> **一个有关键真实证据支撑、已经可以体面收官的企业级 Agent 原型系统。**

## 当前最值得抓住的主线

如果只用一句话描述整个项目：

> `my-xhs-ai` 解决的不是“模型会不会回答”，而是“模型能否在真实业务边界里完成一次受限、可追踪、可审批、可恢复的诊断”。
