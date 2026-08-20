# 为什么这个系统不是“模型随便调工具”，而是工具系统本身就是边界

> 对应目录：`vol-ai/02-tool-mcp-policy/`
> 目标问题：既然 `my-xhs-ai` 最终还是要去调 `queryOrderVolume`、`mqDlqQuery`、`logSearch` 这些工具，那它和普通的 Tool Calling Agent 到底有什么本质区别？为什么这里必须有 `ToolRegistry + AgentToolCatalog + AgentToolBinder + PolicyGuard` 这一整套东西？

## 一句话困惑

很多 AI 项目做到“能调工具”就会默认自己已经进入了 Agent 阶段。

最常见的想法是这样的：

1. 把若干工具定义给模型
2. 模型根据问题自己选工具
3. 工具返回结果
4. 模型继续总结

从功能上看，`my-xhs-ai` 表面上也在做这件事：

- `queryOrderVolume`
- `paymentSuccessRate`
- `httpErrors`
- `mqDlqQuery`
- `logSearch`
- `dlq.redeliver`

所以读者很容易觉得：

> 这不就是一个“模型能调工具”的系统吗？为什么还要额外搞 `ToolRegistry`、`PolicyGuard`、MCP、profile 工具子集这些看起来很“工程”的东西？

如果只是做 demo，这个问题很容易被一句“为了安全”带过。但在 `my-xhs-ai` 里，这一层如果不讲透，整个系统都会被误解成一个普通 tool-calling app，而不是一个真正有执行边界的诊断平台。

## 一句话答案

`my-xhs-ai` 里的工具系统不是为了“让模型更方便调工具”，而是为了把“模型能调什么、在什么场景调、调完算不算有效、哪些动作必须被拦住”全部系统化；也就是说，这里的工具层首先是**边界层**，其次才是能力层。

## 先建立最小心智模型

先不要把工具看成“一组函数”，要把它看成四层：

```text
AgentToolCatalog     -> 定义工具元数据与参数 schema
ToolRegistry         -> 统一登记工具事实源
AgentToolBinder      -> 把元数据绑定到真实执行器
PolicyGuard          -> 决定这次 run 能不能调用、要不要审批、参数合不合法
```

也就是说，一次工具调用不是：

```text
模型说调 -> 直接执行
```

而是：

```text
模型说调
  -> ToolRegistry 里有没有
  -> 当前 Agent 画像允许不允许
  -> 参数合不合法
  -> 是 L1/L2 还是 L3
  -> 是否进入审批门
  -> 才能真正执行
```

如果没有这层结构，所谓的“工具调用”本质上就只是把大模型输出接到一个函数调度器上，系统边界并没有被真正定义。

## 先推演第一个最直觉、也最容易误导人的失败方案：把工具直接暴露给模型

这是最容易出现的做法。

### 为什么这个方案看起来合理

因为从表面上看，模型调用工具似乎只需要两步：

1. 把工具描述给模型
2. 模型返回 `toolName + args`

然后系统直接根据这个名字找到实现并执行。

写成最朴素的结构，大概就是：

```text
prompt 里列出工具
  ↓
模型返回 toolName / args
  ↓
根据名称反射执行
```

这在 demo 项目里很常见，而且一开始看起来几乎没有问题：

- 快
- 简单
- 工具加起来也不费劲

### 它会先坏在哪里

它会先坏在：**工具名和权限边界没有被系统化。**

在 `my-xhs-ai` 里，工具并不是一个“只要存在就能调”的东西。它至少有三层约束：

1. 工具是否在官方工具清单里注册
2. 当前 Agent 画像是否允许调用它
3. 这个工具属于 L1/L2 还是 L3

如果没有 `ToolRegistry + PolicyGuard`，这些约束都只能靠：
- prompt 里提醒
- if/else 写死
- 或者人肉记忆

这会立刻产生两个问题：

#### 第一层失败：系统不知道“这是不是一把合法的工具”

如果没有注册表，任何工具名都只是字符串。

模型输出：
- `queryOrderVolume`
- `dropDatabase`
- `refundAllOrders`
- `mqDlqQuery`

系统如果只是“按名字找执行器”，那它根本没有一个统一事实源来回答：

- 这个工具是否存在
- 这个工具是否被允许开放
- 这个工具有没有参数 schema
- 这个工具是不是还处于 PoC / 预留状态

而 `my-xhs-ai` 的 `ToolRegistry` 正是在回答这个问题。

见：
- `my-xhs-ai-tools/src/main/java/com/myxhs/ai/tools/ToolRegistry.java`

它做的不是“保存几个工具对象”，而是定义一套单一事实源：
- 名称
- `mcpName`
- 描述
- schema
- validator
- invoker

这意味着在这个系统里，工具首先是**被治理的资源**，不是模型随手能调的函数。

#### 第二层失败：系统不知道“这次 run 能不能调它”

就算某个工具是合法注册工具，也不等于当前这次任务就能用。

`my-xhs-ai` 不是单一 Agent，而是有：
- `BUSINESS`
- `OPS`
- `FULL`

不同画像看到的是不同工具子集。

例如：
- 订单、支付、内容指标偏业务侧
- MQ、HTTP、日志、数据库偏运维侧

这就要求系统必须回答另一个问题：

> **这个工具在系统里存在，但在当前 Agent 画像里是不是被允许？**

这件事不是工具本身决定的，也不是模型决定的，而是 `PolicyGuard.evaluate(tool, args, allowedTools)` 决定的。

见：
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/harness/PolicyGuard.java:52-88`

如果没有这层，所谓的多画像就会退化成：
- prompt 里写“你最好别用某些工具”

这在企业系统里是站不住的。

#### 第三层失败：系统不知道“这是查还是执行”

`my-xhs-ai` 的工具不是同一种风险等级。

- L1/L2：查询类工具
- L3：执行类工具

最典型的就是：
- `mqDlqQuery` 只是查
- `dlq.redeliver` 是真正执行

如果没有 access level 的系统建模，那它们在模型眼里只是两个“可调用方法”。

这会导致一个非常危险的结果：

> 系统无法在“查”和“执行”之间建立制度性边界。

而 `AgentToolCatalog` 明确把这种差别写进了元数据：
- `AccessLevel.L1`
- `AccessLevel.L2`
- `AccessLevel.L3`

见：
- `my-xhs-ai-tools/src/main/java/com/myxhs/ai/tools/AgentToolCatalog.java`

这就说明，在当前系统里：

> 工具不是平等函数集合，而是按风险等级被组织和约束的能力目录。

### 第一种失败方案为什么彻底站不住

所以“把工具直接暴露给模型”这个方案，最终会在三个地方一起崩掉：

1. 工具存在性无统一事实源
2. Agent 画像边界无法落地
3. 查询动作和执行动作无法制度性区分

这就意味着，工具层如果没有被注册、分类、校验和守卫，系统根本没有边界，所谓的 tool calling 只是一个功能，而不是一套治理结构。

## 再推演第二个看起来更稳的失败方案：把 MCP 当成工具边界的全部答案

如果承认工具不能直接裸露给模型，第二个常见想法就是：

> 既然有 MCP，那工具边界自然就解决了。

这个想法也很接近真相，但仍然不够。

### 为什么这个方案很诱人

因为 MCP 确实已经解决了很多问题：

- 工具协议统一
- 输入 schema 统一
- host / client / server 关系清晰
- 工具可被 catalog 化导出

在 `my-xhs-ai` 里，`my-xhs-ai-mcp` 的作用也非常明确：
- 暴露 MCP 工具目录
- 承担只读业务查询和日志检索
- 把 MCP 当成“工具网关”而不是 Agent runtime

所以很容易顺着得出一个结论：

> 工具边界其实已经被 MCP 解决了，剩下的只是让模型去调用它。

### 它为什么还是站不住

因为 MCP 解决的是**协议问题**，不是**任务控制问题**。

换句话说：

- MCP 定义“工具怎样被看见、怎样被调用”
- 但不定义“当前任务该不该调用、什么时候停、参数是否合法、是否需要审批”

这些仍然必须由上层系统来回答。

#### 第一层失败：MCP 不是执行控制器

`my-xhs-ai-mcp` 只负责导出工具，不负责：
- budget
- loop detection
- evidence validity
- partial / failed / completed
- HITL 审批挂起

所以 MCP 只能是工具协议层，不可能替代 Harness。

#### 第二层失败：MCP 不是权限裁决器

MCP server 可以暴露工具，但“本次 run 到底能不能调这个工具”，还要看：
- 当前 Agent 是 `BUSINESS` 还是 `OPS`
- 当前工具是 L2 还是 L3
- 参数是否合法

这些都不是 MCP 协议负责的，而是 `PolicyGuard` 负责的。

#### 第三层失败：MCP 不负责答案合法性

MCP 能返回工具结果，
但它不负责：
- 这个结果有没有被正确引用进最终答案
- 模型有没有乱编数字
- 证据够不够支撑结论

这件事依旧要回到 Harness。

### 所以第二种失败方案的本质问题是什么

它的问题不是 MCP 不重要，而是：

> **MCP 是工具协议和导出层，不是系统执行边界本身。**

这和上一种失败方案刚好形成补足：

- 工具不能裸露给模型
- 但就算有 MCP，也不代表系统控制已经成立

真正让边界成立的，是：
- `AgentToolCatalog`
- `ToolRegistry`
- `AgentToolBinder`
- `PolicyGuard`
- 以及最后接在 Harness 里的执行路径

## 真正的源码证明链：边界是怎样被系统化的

到这里，必须把“工具系统就是边界”变成代码上的证明链。

### 证明链 1：`AgentToolCatalog` 定义的不是工具函数，而是治理元数据
见：
- `my-xhs-ai-tools/src/main/java/com/myxhs/ai/tools/AgentToolCatalog.java`

这里最关键的不是列出多少工具，而是每个工具都带着：
- `name`
- `mcpName`
- `description`
- `accessLevel`
- `schemaJson`
- `validator`

这说明工具在系统里首先不是“代码入口”，而是“被治理的能力单元”。

### 证明链 2：`ToolRegistry` 把这些元数据变成唯一事实源
见：
- `my-xhs-ai-tools/src/main/java/com/myxhs/ai/tools/ToolRegistry.java`

它真正解决的是：
- 名称唯一性
- MCP 名称映射
- 可用工具计数
- 注册顺序稳定性

这意味着后续所有系统动作——
- PolicyGuard
- Harness
- MCP

都不再各自维护一套工具真相，而是统一读这一个注册表。

这条链是工具边界成立的前提。

### 证明链 3：`AgentToolBinder` 证明“元数据”和“真实执行器”是两层
见：
- `my-xhs-ai-tools/src/main/java/com/myxhs/ai/tools/AgentToolBinder.java`

这里最重要的意义是：

- `AgentToolCatalog` 只定义元数据
- `AgentToolBinder` 才把元数据绑定到真实执行器

这意味着：

> 工具的“存在”与“能执行”是分开的。

这正好支撑了系统对工具分级的控制：
- 有的工具存在，但 invoker 为空（预留）
- 有的工具存在，也可执行，但必须审批
- 有的工具存在、可执行、可直接查

这就是边界被结构化的方式。

### 证明链 4：`PolicyGuard` 才是“这次 run 能不能调”的最终裁决者
见：
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/harness/PolicyGuard.java`

这里真正起作用的不是“有白名单”，而是裁决顺序：

1. 工具是否注册
2. 当前画像是否允许
3. L3 且无执行器 → deny
4. L3 且有执行器 → requiresApproval
5. L1/L2 参数校验
6. allow

这说明：

- 工具不是“存在就能调”
- 当前任务也不是“模型说调就能调”
- 真正的执行边界必须经过系统裁决

### 证明链 5：Harness 最终把工具边界接进任务语义
见：
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/harness/AgentHarness.java:385-431`

在这里：
- 先 `PolicyGuard.evaluate(...)`
- 再决定是否审批
- 再执行工具
- 再记证据
- 再做 loop check

这说明工具系统并不是孤立存在，而是被 Harness 吃进去，变成任务状态机的一部分。

所以真正完整的链不是：

```text
MCP -> tool
```

而是：

```text
Catalog -> Registry -> Binder -> PolicyGuard -> Harness -> Tool Execution
```

这条链才是 `my-xhs-ai` 真正的工具边界。

## 再补一层运行态证据：这不是纸上工具系统

### 运行态证据 1：`dlq.redeliver` 真链路
真实跑通过：
- `mqDlqQuery`
- 提取 `ORIGIN_MESSAGE_ID`
- 进入 `WAITING_APPROVAL`
- approve
- `dlq.redeliver`
- `CR_SUCCESS`

这里最值钱的不是“能查也能重投”，而是：

- 查询类工具和执行类工具被系统制度性区分
- L3 工具不会被模型直接越权执行

这正是 ToolRegistry / PolicyGuard / Harness 这条边界链在运行态的证明。

### 运行态证据 2：`logSearch` 白名单
当前系统已经真实验证过：
- 白名单为空时，日志检索会全部拒绝
- 加入 `my-xhs-order` / `my-xhs-inventory` 等后，才能真实查询业务日志

这说明“工具边界”不是抽象概念，而是会直接决定系统能不能看见真实世界的边界。

### 运行态证据 3：Langfuse trace
在 Langfuse trace 里，tool span 已经能看到：
- tool name
- window
- evidence refs
- output

这等于从另一个角度证明：工具不是黑盒函数，而是系统可观测边界的一部分。

## 这层设计的代价

如果说工具系统是边界层，那代价也必须讲清楚。

### 1. 工具接入复杂度上升
现在接一个工具，不只是写个函数，还要补：
- catalog
- schema
- validator
- binder
- profile 可见性
- 可能还要考虑 MCP 暴露

这比 demo 级 tool calling 明显更重。

### 2. 工具元数据必须长期维护
一旦：
- 名称漂移
- schema 变动
- accessLevel 写错
- binder 漏接

系统边界就会出现错位。

### 3. 工具系统会把“功能增量”变成“治理增量”
这其实是代价，也是价值。

代价在于：
- 新工具不再是低成本乱接

价值在于：
- 新工具进入系统时，就天然经过边界治理

这是企业系统必须接受的代价。

## 收束结论

到这里，问题可以彻底收回来了。

`my-xhs-ai` 不是“模型随便调几个工具”的系统。

它真正成立的条件是：
- 工具被 catalog 化
- 工具被 registry 化
- 元数据和执行器分离
- 权限和参数有系统裁决
- 最后再被 Harness 纳入任务状态机

所以这一层真正的意义不是“工具很多”，而是：

> **工具系统本身就是边界。**

也正因为它是边界，系统才能在真实世界里做到：
- 不乱查
- 不乱调
- 不乱执行
- 不乱答

## 篇末桥接

这篇解决了三件事：

1. 为什么不能把工具直接裸露给模型
2. 为什么 MCP 也不能替代执行边界
3. 为什么 ToolRegistry / PolicyGuard / Harness 这条链才是真正的工具边界

这篇还没彻底展开的是：

- 当工具边界成立后，历史上下文、长期记忆和 RAG 又该怎么分层，才不会重新污染系统边界？

所以下一篇最自然的桥接应该是：

- `03-memory-conversation-rag/01-memory-is-for-dev-not-end-user.md`

也就是去回答：
> 为什么 `my-xhs-ai` 的 Memory 不是给电商终端用户做画像，而是给研发诊断用户保存历史调查语义。