# 为什么这个项目真正的中心不是模型，而是 Harness

> 对应目录：`vol-ai/00-overview-architecture/`
> 目标问题：很多 AI 项目都会把“模型”“工具”“Langfuse”“Memory”当成最显眼的亮点，那为什么在 `my-xhs-ai` 里，真正决定系统形状和边界的，反而是 `AgentHarness`？

## 一句话困惑

如果只看功能清单，`my-xhs-ai` 很容易被理解成下面这种结构：

- 一个大模型
- 一组工具
- 一套 trace
- 一点记忆
- 再补一个 Temporal PoC

从展示角度看，模型、MCP、Langfuse、Memory 都比 `Harness` 更“像成果”。

所以读者很自然会产生一个直觉：**系统中心应该是模型，至少也应该是工具层。**

这个直觉非常常见，而且表面上也并不荒唐。但只要沿着一条真实链路走一遍——例如 `mqDlqQuery -> WAITING_APPROVAL -> dlq.redeliver -> CR_SUCCESS`——你就会发现系统真正的关键矛盾不是“模型会不会答”，而是：

- 谁决定这一步该不该查？
- 谁决定查到什么时候该停？
- 谁决定哪些工具允许调用？
- 谁决定什么算有效证据？
- 谁决定高危动作必须进入审批？
- 谁决定失败时返回 partial，还是直接拒答？

这些问题，模型不负责，工具也不负责，trace 更不负责。真正负责的是中间那层执行控制系统：`AgentHarness`。

## 一句话答案

`my-xhs-ai` 的中心不是模型，因为模型在这里只是一个被约束的决策器；真正把路由、工具、证据、审批、预算、恢复和观测压成一套可运行系统的，是 `AgentHarness` 这层执行控制面。模型只是它调度的一部分，工具只是它调用的一部分，trace 只是它留下的痕迹。

## 先建立最小心智模型

先不要把这个系统看成“LLM + tools”的组合，而要把它看成三层：

```text
用户问题
   ↓
Intent Router
   ↓
Agent Harness
   ↓
Model / Tool / Approval / Store / Trace / Memory
```

这张图里最容易被忽略的是中间那层。

因为很多 AI 项目默认会把“模型”放在图中央，工具和缓存围在周围，看起来像是：

```text
用户问题 -> LLM -> Tool Calling -> Answer
```

这张图足够支撑一个 demo，但不够支撑一个企业级诊断系统。

原因在于：它隐含假设“模型可以天然承担系统控制”。可在 `my-xhs-ai` 这种项目里，真正需要被系统化解决的恰恰是：

- 模型什么时候有资格行动
- 模型怎么被限制
- 工具如何被授权
- 证据如何进入答案
- 高危动作怎么被阻断
- 失败如何被系统化收束

所以这里真正的最小心智模型应该是：

> **模型不是控制器，Harness 才是控制器。**

或者再说得更直白一点：

> 模型负责“想”，Harness 负责“准不准你这么想、准不准你这么做、做到哪里必须停”。

## 先推演第一个最直觉、也最容易误导人的失败方案：把模型当中心

这是大多数 AI 项目都会先掉进去的直觉方案。

### 为什么这个方案看起来合理

它足够顺：

1. 用户提问
2. 模型理解问题
3. 模型决定调哪个工具
4. 模型总结结果

如果只是做演示，这套流程甚至已经够了。

而且它还有一种非常强的心理诱惑：

> 既然大模型已经会推理，那控制权自然也该在模型手里。

这个直觉在产品演示里很常见，但在 `my-xhs-ai` 里会迅速失败。

### 它会先坏在哪里

它会先坏在：**模型不知道什么叫“不能做”。**

在真实诊断任务里，系统不是要求模型“尽可能多答一点”，而是要求它：

- 不能编造数字
- 不能跳过基线工具自己算对比窗口
- 不能无限循环查工具
- 不能在没有证据时硬下结论
- 不能绕开审批直接执行 `dlq.redeliver`
- 不能在预算耗尽后继续跑

如果没有中间这层执行控制，这些约束只能靠 prompt 提示。

而 `AgentHarness` 的类注释从一开始就把问题定义成系统问题，而不是提示词问题：

- `THINK -> VALIDATE -> TOOL -> OBSERVE -> LOOPCHECK -> ANSWER`
- 存在性校验
- deny-by-default 工具边界
- partial 终止
- 模型不可用时显式失败

见：
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/harness/AgentHarness.java:28-35`

这段代码真正证明的不是“有个状态机”，而是：

> **在 `my-xhs-ai` 里，模型输出不直接等于系统动作，中间永远隔着一层系统裁决。**

### 它第二次坏在哪里

它会坏在：**模型不知道什么算真证据。**

大模型很容易把“看起来合理”组织成一段漂亮答案，但在诊断系统里，漂亮答案毫无意义，必须可追溯。

`my-xhs-ai` 的硬约束不是“请引用证据”，而是：
- `evidenceRefs` 必须命中真实记录
- 引用不存在的证据直接无效
- 零证据答案不通过

这件事不是 prompt 约束，而是 Harness 的答案裁决逻辑。

从代码上看，`validateAnswer()` 才是真正的最后审判者：
- 结论不能为空
- `evidenceRefs` 非空
- 每个 ref 必须出现在当前 run 的 registry 里

见：
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/harness/AgentHarness.java:763-776`

这说明：

> 模型负责生成答案形状，Harness 负责决定这个答案是不是合法答案。

### 它第三次坏在哪里

它会坏在：**模型不知道什么时候必须让位给人。**

`dlq.redeliver` 是这个系统最典型的高危动作。现实里，这种操作最怕的不是模型算错，而是模型越权：

- 找到死信
- 拿到 `ORIGIN_MESSAGE_ID`
- 直接重投

如果控制权真在模型手里，这条链就很危险。

但在 `my-xhs-ai` 里，这件事不是“提示模型谨慎一点”，而是直接进入系统状态：

- 先挂 `WAITING_APPROVAL`
- 人工 approve/reject
- 再 resume

而且这已经拿到真实运行态证据：

- `mqDlqQuery`
- `WAITING_APPROVAL`
- `approve`
- `dlq.redeliver`
- `CR_SUCCESS`

也就是说，这个项目已经用真链路证明了：

> **模型在这里没有最终执行权。**

它最多只能提出执行意图，执行权在 Harness + PolicyGuard + RunManager 这一套控制面里。

### 第一种失败方案为什么彻底站不住

所以“模型是中心”这个方案，并不是“有点不够”，而是会在三个关键层面同时失效：

1. 停止条件失效
2. 证据裁决失效
3. 审批边界失效

一旦这三件事失效，这个系统就会退化成一个“会说话、会调工具、但谁也不敢完全信”的大模型应用。

而 `my-xhs-ai` 恰恰不是要做这个。

## 再推演第二个看起来更稳的失败方案：把工具层当中心

如果承认模型不能做中心，第二个自然想法就是：

> 那真正的中心应该是工具层。毕竟所有数字、日志、DLQ、Prometheus、数据库事实都来自工具。

这个方案比“模型是中心”更接近真相，但还是会失败。

### 为什么这个方案也看起来合理

因为 `my-xhs-ai` 的可信度确实大量建立在工具上：

- `queryOrderVolume`
- `paymentSuccessRate`
- `httpErrors`
- `mqDlqQuery`
- `logSearch`
- `dlq.redeliver`

而且系统已经把工具做成了非常像样的一层：

- `AgentToolCatalog`
- `ToolRegistry`
- `AgentToolBinder`
- `PolicyGuard`

这会让人产生第二个直觉：

> 既然所有真相都来自工具，那系统中心应该是 Tool / MCP 层。

### 它先坏在哪里

它先坏在：**工具只定义能力，不定义任务。**

工具能回答：
- 可以查什么
- 参数是什么
- 返回什么结构
- 权限级是什么

但工具回答不了：
- 这一步该不该查
- 应该先查当前窗口还是先查基线
- 结果够不够回答
- 查不到时要不要换路径
- budget 到底剩多少
- 失败该收敛成 partial 还是 reject

这些都不是工具的责任域。

### 它第二次坏在哪里

它会坏在：**工具没有任务级状态。**

工具本身不知道：
- 这是第几步
- 这次 run 的 `runId` 是什么
- 当前证据链有多少条
- 当前已经进入了什么状态
- 当前是不是在审批前挂起

这些状态都在 Harness / RunManager / RunStore 里，而不在工具层里。

也就是说，工具层即使非常完整，也只能解决“能做什么”，不能解决“现在这次任务到底怎么推进”。

### 它第三次坏在哪里

它会坏在：**工具没有答案裁决权。**

就算所有工具都返回了完美结构化结果，系统仍然需要有一层来判断：
- 现在是不是可以 ANSWER
- 这个答案是不是证据充分
- 需不需要补一个反证
- 是否必须声明不确定性

答案依然是：这不是工具说了算，是 Harness 说了算。

### 所以第二种失败方案的本质问题是什么

它的问题不是“工具不重要”，而是：

> **工具层是能力平面，不是控制平面。**

这句话非常关键。

`my-xhs-ai` 真正成立，是因为：
- 工具定义能力边界
- Harness 定义执行边界

把这两层混成一个中心，会让整个系统的解释失焦。

## 真正的源码证明链：Harness 为什么是裁决者

这篇不能只停在概念上，必须把“Harness 是中心”变成一条源码证明链。

### 证明链 1：`SYSTEM_PROMPT` 只是输入，不是边界本身
见：
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/harness/AgentHarness.java:41-85`

这里定义了：
- 工具清单
- 输出 JSON 格式
- 行为规则

但这层只是“模型看到的世界”。

它能做的是：
- 让模型知道有哪些工具
- 让模型知道该怎么输出
- 让模型知道基本纪律

它做不到的是：
- 真正禁止未授权工具
- 真正阻止参数越界
- 真正拒绝无证据答案

所以 `SYSTEM_PROMPT` 只能算输入约束，不是系统边界本身。

### 证明链 2：`handleToolCall()` 才是工具边界真正落地的地方
见：
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/harness/AgentHarness.java:385-431`

这里发生的事情才是关键：

1. `PolicyGuard.evaluate(...)`
2. 子集工具过滤（BUSINESS / OPS / FULL）
3. L3 工具走审批
4. 工具执行
5. 证据登记
6. loop 检查

也就是说，模型的一个 `TOOL_CALL` 输出，要经过 Harness 这一层才能真正变成系统动作。

这就是执行边界被系统化的关键点。

### 证明链 3：`callModel()` 只是部件，不是导演
见：
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/harness/AgentHarness.java:745-769`

`callModel()` 做的事情其实很朴素：
- 发请求
- 收响应
- 失败重试一次
- 记录 token / cost metadata

它并不决定：
- 什么时候开始调用
- 调完之后走哪条分支
- 这个输出算不算合法决策
- 决策失败后怎么恢复

换句话说，模型调用只是 Harness 里的一个动作点，不是总导演。

### 证明链 4：`validateAnswer()` 才是答案裁决者
见：
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/harness/AgentHarness.java:763-776`

它明确在做：
- 结论非空校验
- `evidenceRefs` 存在性校验
- 引用证据是否真实出现过的校验

这意味着系统的最后一关不是“模型已经输出了，所以就算完成”，而是：

> **模型必须交出一个被 Harness 接受的答案，才算真正完成。**

这条证明链把结论真正钉死了：

`SYSTEM_PROMPT`
→ 告诉模型怎么做

`handleToolCall()`
→ 决定模型能不能真这么做

`callModel()`
→ 只是调用模型

`validateAnswer()`
→ 决定模型最终说的话算不算数

所以中心只能是 Harness。

## 再补一层：为什么这不是纸上 Harness，而是运行态中心

如果只是代码层证明，还不够。必须再给出运行态证据，证明它不是“设计图里的中心”，而是**真运行时的控制中心**。

### 运行态证据 1：DLQ 审批闭环
真实链路已经跑通过：

- Agent 调 `mqDlqQuery`
- 提取 `ORIGIN_MESSAGE_ID`
- 进入 `WAITING_APPROVAL`
- approve
- `dlq.redeliver`
- `CR_SUCCESS`

这里最关键的不是工具本身，而是：
- 什么时候停在审批前
- 什么情况下继续执行
- 继续后如何回到正常收敛链路

这些都证明：**Harness 才是在运行时真正持有任务控制权的层。**

### 运行态证据 2：Langfuse trace
Langfuse 里已经能看到：

- `diagnosis-run_<runId>` 主 trace
- `agent.run`
- `GENERATION`
- `TOOL`
- `agent.answer`
- `userId / sessionId / model / tokens / cost`

trace 结构本身就证明：
- 模型只是一个 generation 节点
- 工具只是一个 tool 节点
- 真正把整次任务串起来的是 run / harness 级控制面

### 运行态证据 3：Temporal PoC
Temporal PoC 跑通的是：
- `WAITING_APPROVAL`
- kill worker
- restart worker
- approve
- `COMPLETED`

这说明当前系统的“控制中心”概念还能自然投影到 durable execution 上。

换句话说：
- Harness 是当前主线中心
- Temporal 是未来承载更长任务的可选执行底座

这也再次证明，模型从头到尾都不是中心。

## 候选“中心”对照表

| 候选中心 | 为什么看起来像中心 | 为什么最终不是 |
|---|---|---|
| 模型 | 会推理、会决定工具调用 | 不知道边界、不知道审批、不知道证据裁决 |
| 工具层 | 所有真相都来自工具 | 只定义能力，不定义任务推进 |
| Langfuse | 能看到整次 trace | 它记录运行，不控制运行 |
| Memory | 能跨轮增强效果 | 它提供历史上下文，不裁决任务 |
| Temporal | 能做 durable execution | 当前只是 PoC，不是主线控制面 |
| **Harness** | 约束模型、调度工具、裁决答案、挂起恢复 | **这才是真正的执行控制中心** |

这张表其实就是整篇文章的压缩版结论。

## 这层设计的代价

既然 Harness 是中心，就必须承认代价。

### 1. 中心复杂度高
越多能力往上挂：
- 审批
- trace
- memory
- budget
- loop
- policy

Harness 就越容易变成复杂汇聚点。

### 2. 它的正确性比模型正确性更重要
模型换了可以重测。
Harness 出错会直接破坏：
- 权限边界
- 审批语义
- partial/complete 语义
- 证据链可信度

### 3. 它决定了系统后续演化难度
如果这一层抽象不好，后面想补：
- 真多 Agent
- 更复杂审批
- 更强恢复
- 更多 trace

都会越来越痛。

所以它是中心，也意味着它是最需要被持续审视的点。

## 收束结论

现在可以把开头的问题彻底收回来了。

`my-xhs-ai` 不是一个“以模型为中心”的项目，
也不是一个“以工具层为中心”的项目。

它真正最有价值的地方，是：

- 模型被约束
- 工具被授权
- 证据被绑定
- 高危动作被审批
- 任务能挂起、恢复、收束

而把这五件事压进同一套执行语义里的，就是 Harness。

所以：

> **这个项目真正的中心不是模型、不是 MCP、不是 Langfuse、不是 Memory，而是 Harness。**

## 篇末桥接

这篇解决了三件事：

1. 为什么模型不是中心
2. 为什么工具也不是中心
3. 为什么 Harness 才是真正的控制面

这篇还没有完全解决的是：

- Harness 作为控制面，到底怎样和 ToolRegistry、MCP、PolicyGuard 形成一个完整的执行边界？

所以下一篇必须接：

- `02-tool-mcp-policy/01-tool-registry-boundary.md`

因为只有把“能力平面”和“控制平面”的关系讲透，这卷的系统边界才算真正立住。
