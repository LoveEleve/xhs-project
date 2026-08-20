# my-xhs-ai 深度架构与业务解读（2026-08-19）

> 目标：像 `vol-xhs` 一样，把 `my-xhs-ai` 当前的系统边界、模块职责、数据流、业务价值和真实能力层次讲清楚。

---

## 一、项目定位

`my-xhs-ai` 不是通用聊天机器人，也不是“让大模型直接查数据库”的自然语言 BI。

它的真实定位是：

> **一个面向电商运营/运维排障场景的受限诊断 Agent 平台。**

它主要解决的是：
- “最近订单量为什么变化？”
- “支付成功率为什么下降？”
- “为什么会有 5xx / MQ / DLQ 问题？”
- “是否应该重投某条死信消息？”

所以它的关键词不是“聊天”，而是：
- **受限**
- **有证据**
- **可审批**
- **可恢复**
- **可评测**
- **可观测**

---

## 二、系统边界

当前系统边界非常清楚，核心只有两个新增服务：

### 2.1 `my-xhs-ai-app`（19020）
职责：
- API 入口
- 意图路由
- Agent Harness
- 会话/记忆
- Run 状态机
- HITL 审批
- SSE 事件流
- Langfuse trace
- Temporal PoC

一句话：
> 这是大脑和控制平面。

### 2.2 `my-xhs-ai-mcp`（19021）
职责：
- 对外暴露 MCP 工具
- 受控只读查询
- 日志检索
- 指标/观测工具桥接

一句话：
> 这是工具网关和只读数据平面。

### 2.3 外部依赖
- MySQL（业务库 + `my_xhs_ai` 自有库）
- RocketMQ Dashboard（DLQ 查询/重投）
- Prometheus（HTTP 错误、延迟、MQ backlog）
- 日志文件（logSearch）
- Langfuse Cloud（trace）
- 豆包 embedding（语义记忆）
- Temporal dev server（PoC）

所以整体不是“AI 一体机”，而是一个以 `app + mcp` 为核心、对接既有业务基础设施的诊断系统。

---

## 三、架构主线

系统的主架构主线可以概括为：

```text
用户问题
  -> API / Gateway
  -> Intent Router
      -> 确定性工具路径（指标/观测/日志）
      -> 复杂归因路径（Bounded Agent）
  -> Agent Harness
      -> think
      -> tool
      -> observe
      -> answer
  -> 证据链 / 审批 / 记忆 / trace
  -> 最终回答 / 挂起 / 恢复
```

这个架构里，最重要的不是“大模型”，而是 **Harness**。

---

## 四、核心模块拆解

## 4.1 Intent Router

作用：
先决定用户问题**应该走哪条执行路径**。

不是所有问题都交给 Agent。

### 典型分流
- “2026-08-01 到 2026-08-07 的下单量是多少？”
  - 直接走 `queryOrderVolume`
- “为什么最近订单量下降了？”
  - 进入 Agent
- “今天天气怎么样？”
  - 直接拒答

### 本质价值
它是在解决：
> **什么时候需要 Agent，什么时候不需要。**

这是企业场景里比“模型效果”更重要的问题，因为它直接决定：
- 成本
- 时延
- 可重复性
- 风险边界

---

## 4.2 Agent Harness

这是系统最核心的模块。

### 它不是什么
它不是普通 ReAct demo，不是无限 while-loop，也不是框架默认 agent executor。

### 它是什么
它是一个**有边界的状态机**，负责：
- 结构化解析模型输出
- 工具调度
- 预算控制（step/token/cost）
- 循环检测
- 证据链记录
- 错误兜底
- 审批挂起 / 恢复
- 终态校验

### 运行形态
```text
THINK -> VALIDATE -> TOOL -> OBSERVE -> LOOPCHECK -> ANSWER
```

### 关键工程价值
它把“模型会不会乱来”这个不确定问题，转成了**可以被约束、被测试、被观测**的问题。

---

## 4.3 ToolRegistry + PolicyGuard

这是系统的第二个关键支柱。

### ToolRegistry
把所有工具统一描述成：
- name
- mcpName
- description
- schema
- accessLevel
- invoker

### PolicyGuard
负责：
- 未注册工具直接拒绝
- 参数校验
- 画像（BUSINESS/OPS/FULL）子集过滤
- L3 工具审批门

### 这两个模块组合后的意义
不是“模型自由调用工具”，而是：
> **模型只能在被明确定义好的工具边界内行动。**

这就是为什么项目能讲“安全边界”，而不是只讲“工具调用”。

---

## 4.4 HITL 审批闭环

这是系统里最像“企业级”的部分之一。

### 当前已实现
- `WAITING_APPROVAL`
- `approve/reject`
- `resume`
- 审计落库
- 前端审批卡片
- 真链路已验证：`dlq.redeliver`

### 实际意义
高风险动作不是“模型建议一下”，而是：
- 先调查
- 再挂起
- 由人批准
- 再继续执行

这保证了：
- 模型不直接做危险动作
- 人在回路中
- 所有操作有审计

### 典型代表
`dlq.redeliver`

这个案例已经完成了真实 E2E：
- 查询死信
- 拿到 `ORIGIN_MESSAGE_ID`
- 审批
- 重投
- `CR_SUCCESS`

---

## 4.5 证据链

这是项目和普通 AI Demo 拉开差距的地方。

### 核心逻辑
每次工具调用都会生成 evidence record。
最终答案必须引用 `evidenceRefs`。

### 约束意义
- 没有工具证据就不能乱答
- 数字必须可追溯
- 反证和不确定性必须显式说明

### 真实价值
它把“解释能力”从 prompt 层推到了系统层。

---

## 4.6 会话与记忆

这里要明确区分两层：

### 会话（Conversation）
- 当前轮次上下文
- 历史 user / assistant 结论
- 摘要注入
- 不直接注入旧工具原文

### 长期记忆（Memory）
当前已经实现了基础版：
- 存储位置：`my_xhs_ai.ai_memory`
- 向量模型：豆包 `doubao-embedding-vision-large`
- 语义检索：余弦相似度
- 多用户隔离：按研发 `userId`

### 这里的一个重要边界
它记住的不是电商终端用户偏好，
而是：
> **研发/运维这个“AI 诊断台使用者”的历史诊断记忆。**

比如：
- 上次查过订单量
- 最近关注 5xx
- 经常查支付失败

这和业务用户画像完全不同。

---

## 4.7 Langfuse Trace

当前已经不是“计划接入”，而是**真实接通**。

### 当前能展示
- `diagnosis-run_<runId>` 主 trace
- `userId`
- `sessionId`
- `modelName`
- `inputTokens / outputTokens`
- `cost`
- tool output

### 当前价值
它让 Agent 不再只是“跑完就完了”，而是能回答：
- 这次模型想了几步
- 调了哪些工具
- token 花了多少
- 每步工具结果是什么

这对调试、面试、评测都非常有价值。

---

## 4.8 Temporal PoC

这是项目目前最强的“下一阶段能力证明”。

### 当前主线
主线还是：
- Harness
- RunStore
- WAITING_APPROVAL
- resume

### Temporal PoC 在证明什么
不是替换主线，
而是证明：
> **如果以后要上 durable execution，当前团队已经知道怎么做。**

### 已验证
- 审批型 workflow
- `WAITING_APPROVAL`
- kill worker
- restart worker
- approve
- `COMPLETED`

### 架构意义
它让项目从“会做 Agent”上升到：
- 会做 Agent
- 也理解 durable workflow 的边界和价值

---

## 五、业务场景解读

## 5.1 订单归因

### 业务问题
“最近订单量下降了吗？如果下降了，断点在哪？”

### 系统做法
- 查当前订单量
- 查基线订单量
- 查 funnel
- 定位断点
- 给出结论 / 反证 / 不确定性

### 价值
这不是“问答”，是**数据驱动归因**。

当前 demo 中甚至能发现：
- 订单没降，反而涨了
- 但加购→下单转化率从 44% 掉到 3%

这已经是很有业务意义的分析结论。

---

## 5.2 5xx 排障

### 业务问题
“为什么最近有很多 5xx？”

### 系统做法
- 查 HTTP 错误
- 排除 `/actuator/health` 这类探针噪声
- 结合日志定位具体原因
- 输出修复建议

### 真实价值
系统已经在 demo 中自动发现两个真实问题：
1. `CouponFeignClient` 缺 `X-User-Id`
2. comment count 的 `ClassCastException`

这说明它不是只会“列指标”，而是真的能缩小到根因级别。

---

## 5.3 DLQ 死信重投

### 业务问题
“某条死信要不要重投？怎么安全重投？”

### 系统做法
- 查询 DLQ
- 提取 `ORIGIN_MESSAGE_ID`
- 让人审批
- 重投
- 看消费结果

### 价值
这条链路是系统“诊断 + 执行闭环”的代表。

而且已经拿到了真实 `CR_SUCCESS`，这使得系统不仅会“分析”，还会“在授权边界内执行”。

---

## 六、当前项目的真实层次

### 已经可以明确说“做完”的
- Harness 主干
- ToolRegistry / PolicyGuard
- HITL 闭环
- Langfuse trace
- 基础 Memory
- DLQ 真实 E2E
- Temporal PoC
- 3 个 demo

### 还不能夸大的
- 不是 300+ 评测全做完
- 不是生产级部署完成
- 不是真正多 Agent 协作平台
- 不是完整 MemoryOS

### 因此最佳口径
> 这是一个已经拿到关键真实证据、可以体面收官的企业级诊断 Agent 项目；它不是最终生产平台，但已经跨过了“概念 Demo”那条线。

---

## 七、为什么它现在值得收官

因为它已经同时具备：
- 核心代码主干
- 真实 E2E
- 真 trace
- 真记忆
- 真 durable PoC
- 真 demo
- 真测试矩阵
- 真讲稿与展示页

继续往下做当然还能更强，
但那已经属于“平台化增强”，不是“当前必须补的核心短板”。

所以现在的判断不是：
- “功能够不够多”

而是：
- **“核心证据是不是已经足够强”**

答案是：
**已经足够强。**
