# 为什么这个系统不是“模型随便调工具”，而是工具系统本身就是边界

> 对应目录：`vol-ai/02-tool-mcp-policy/`
> 目标问题：既然 `my-xhs-ai` 最终还是要去调 `queryOrderVolume`、`mqDlqQuery`、`logSearch` 这些工具，那它和普通 Tool Calling Agent 到底有什么本质区别？为什么这里必须有 `ToolRegistry + AgentToolCatalog + AgentToolBinder + PolicyGuard` 这一整套东西？

## 一句话困惑

很多 AI 项目做到“能调工具”就会默认自己已经进入了 Agent 阶段。

最常见的理解方式是：

1. 把工具描述给模型
2. 模型自己选工具
3. 工具返回结果
4. 模型继续总结

从功能层面看，`my-xhs-ai` 似乎也在做这件事：

- `queryOrderVolume`
- `paymentSuccessRate`
- `httpErrors`
- `mqDlqQuery`
- `logSearch`
- `dlq.redeliver`

于是读者会非常自然地觉得：

> 这不就是一个“模型会调工具”的系统吗？为什么还要额外搞 ToolRegistry、MCP、PolicyGuard、profile 子集、L1/L2/L3 分级这些看起来很重的工程结构？

如果只是做 demo，这个问题很容易被一句“为了安全”带过去。但在 `my-xhs-ai` 里，这一层如果讲不透，系统就会被误解成一个普通 tool-calling app，而不是一个真正有执行边界的诊断平台。

## 一句话答案

`my-xhs-ai` 里的工具系统，不是为了“让模型更方便调工具”，而是为了把“模型能调什么、在什么场景调、调完算不算有效、哪些动作必须被拦住、哪些动作必须挂审批”全部系统化；也就是说，这里的工具层首先是**边界层**，其次才是能力层。

## 先建立最小心智模型

先不要把工具看成“一组函数”，要把它看成五层收紧的边界链：

```text
AgentToolCatalog   -> 定义工具元数据与参数 schema
ToolRegistry       -> 统一登记工具事实源
AgentToolBinder    -> 把元数据绑定到真实执行器
PolicyGuard        -> 决定这次 run 能不能调用、要不要审批、参数合不合法
AgentHarness       -> 把工具调用纳入任务状态机（证据 / budget / partial / answer）
```

所以一次工具调用不是：

```text
模型说调 -> 直接执行
```

而是：

```text
模型说调
  -> 这个工具是否存在于系统事实源里
  -> 当前 Agent 画像是否允许用它
  -> 参数是否合法
  -> 是 L1/L2 还是 L3
  -> 是否要进入审批门
  -> 执行结果如何进入证据链
  -> 这一步之后任务还能不能继续
```

如果没有这条链，所谓“工具调用”本质上就只是把 LLM 输出接到一个方法调度器上，边界并没有被真正建立。

## 先推演第一个最直觉、也最容易误导人的失败方案：把工具直接暴露给模型

这是最常见的做法。

### 为什么这个方案看起来合理

它足够顺：

1. prompt 里列出工具
2. 模型返回 `toolName + args`
3. 系统根据名字找到执行器
4. 得到结果

写成最朴素的结构，就是：

```text
prompt 里列工具
  ↓
模型返回 toolName / args
  ↓
反射或 map 查找执行器
```

这在 demo 项目里极其常见，因为它快、简单、接一把新工具也不贵。

### 它会先坏在哪里

它会先坏在：**系统不知道“这是不是一把合法的工具”。**

如果没有注册表，任何工具名都只是一个字符串：

- `queryOrderVolume`
- `mqDlqQuery`
- `dropDatabase`
- `refundAllOrders`

系统并没有一个统一事实源来回答：

- 这把工具是否存在
- 是否真的开放
- 参数 schema 是什么
- 风险级别是什么
- 它是不是还在 PoC 阶段

而 `ToolRegistry` 正在解决这件事。

见：
- `my-xhs-ai-tools/src/main/java/com/myxhs/ai/tools/ToolRegistry.java`

它不是单纯的“存几个对象”，而是在定义：
- name
- `mcpName`
- description
- schema
- validator
- invoker

所以在当前系统里，工具首先是**被治理的能力单元**，不是模型可任意碰触的函数。

### 它第二次坏在哪里

它会坏在：**系统不知道“这次 run 能不能调它”。**

`my-xhs-ai` 不是单一 Agent，它至少有：
- `BUSINESS`
- `OPS`
- `FULL`

不同画像看到的工具子集不同：

- 业务 Agent 偏订单/支付/内容/漏斗
- 运维 Agent 偏 MQ/HTTP/日志/数据库

如果没有系统级裁决层，所谓多画像就会退化成：
- prompt 里写一句“你最好不要调用某些工具”

这在企业系统里没有实际约束力。

真正起作用的是：
- `PolicyGuard.evaluate(tool, args, allowedTools)`

见：
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/harness/PolicyGuard.java:52-88`

也就是说，工具能不能在这次任务里出场，不由模型决定，而由系统裁决。

### 它第三次坏在哪里

它会坏在：**系统分不清“查询”与“执行”。**

在 `my-xhs-ai` 里，工具不是同一种风险级别：

- L1/L2：读能力
- L3：高危执行能力

最典型的一对就是：
- `mqDlqQuery`：只是查
- `dlq.redeliver`：会真的重投消息

如果没有系统级别的 access level，模型眼里它们都只是“可调用方法”。

这会导致一个最危险的问题：

> 系统无法制度性地区分“看见世界”和“改动世界”。

而 `AgentToolCatalog` 明确把这种差异写进了元数据：
- `AccessLevel.L1`
- `AccessLevel.L2`
- `AccessLevel.L3`

见：
- `my-xhs-ai-tools/src/main/java/com/myxhs/ai/tools/AgentToolCatalog.java`

### 这条失败方案为什么彻底站不住

所以“把工具直接暴露给模型”最终不是“有点粗糙”，而是会在三层同时崩掉：

1. 工具存在性无统一事实源
2. 画像边界无法落地
3. 查询动作和执行动作无法制度性区分

这意味着，工具如果没有 catalog / registry / guard 这一整层，就只是“功能”，还不是“边界”。

## 再推演第二个看起来更稳的失败方案：把 MCP 当成工具边界的全部答案

如果承认工具不能裸露给模型，第二个更高级一点的直觉就是：

> 既然有 MCP，工具边界自然就解决了。

这个直觉比第一个更接近真相，但仍然会失败。

### 为什么这个方案看起来合理

因为 MCP 确实已经解决了很多问题：

- 工具协议统一
- input schema 标准化
- host / client / server 角色清晰
- 工具目录可导出

在 `my-xhs-ai` 里，`my-xhs-ai-mcp` 也确实承担着工具网关的职责：
- 暴露 MCP 工具目录
- 暴露只读业务查询
- 暴露日志检索

所以很容易顺着得出一个结论：

> 工具边界其实已经被 MCP 解决了，剩下的只是让模型去调用它。

### 它会先坏在哪里

它会先坏在一个非常具体、非常真实的场景里：

- `mqDlqQuery` 和 `dlq.redeliver` 都通过工具系统暴露了
- 为什么前者可以直接调用，后者却必须先挂 `WAITING_APPROVAL`？

如果 MCP 本身已经等于边界，这件事就解释不通。

因为从协议视角看，它们都只是一个工具调用接口。

但从系统视角看，它们的语义完全不同：

- `mqDlqQuery` 只是观测
- `dlq.redeliver` 是执行

真正决定这条边界的，不是 MCP，而是：
- `AgentToolCatalog` 里的 `AccessLevel`
- `PolicyGuard` 的审批裁决
- `AgentHarness` 的挂起/恢复语义

所以 MCP 在这里最多只是：
> **把工具暴露出来**

而不是：
> **定义这把工具在当前任务里有没有资格执行**

### 它第二次坏在哪里

它会坏在：**MCP 不负责任务状态。**

MCP 能回答的是：
- 这个工具怎么调用
- 输入 schema 是什么
- 返回结构是什么

但它回答不了：
- 这是第几步
- 当前 run 的 budget 还剩多少
- 证据是否已经足够
- 是继续查，还是该回答
- 是直接失败，还是 partial
- 是挂审批，还是继续推进

这些全都属于 Harness / RunManager / RunStore 的职责，不属于 MCP。

### 它第三次坏在哪里

它会坏在：**MCP 不负责答案合法性。**

MCP 可以把工具结果返回上来，但不能决定：
- 这个结果有没有被正确引用进最终答案
- 证据引用是否真实存在
- 这个结论是否有反证
- 现在是不是已经可以 ANSWER

这些最后都回到 Harness 的答案裁决逻辑。

### 所以第二种失败方案的本质问题是什么

它的问题不是 MCP 不重要，而是：

> **MCP 是工具协议层，不是任务执行边界。**

这和第一种失败方案一起，刚好把这个系统的真实结构逼出来了：

- 工具不能裸露给模型
- 但有了 MCP 也不代表系统边界自动成立
- 真正让边界成立的是：**Catalog -> Registry -> Binder -> PolicyGuard -> Harness** 这条链

## 真正的源码证明链：工具边界怎样被系统化

这一篇不能只停留在概念判断，必须把“工具系统本身就是边界”变成代码上的收束链。

### 第一级：`AgentToolCatalog` 定义工具不是函数，而是元数据对象
见：
- `my-xhs-ai-tools/src/main/java/com/myxhs/ai/tools/AgentToolCatalog.java`

这里真正重要的不是列了多少工具，而是每个工具都有：
- `name`
- `mcpName`
- `description`
- `schemaJson`
- `accessLevel`
- `validator`

这意味着工具在系统里首先不是“代码入口”，而是“带治理信息的能力单元”。

### 第二级：`ToolRegistry` 把元数据变成唯一事实源
见：
- `my-xhs-ai-tools/src/main/java/com/myxhs/ai/tools/ToolRegistry.java`

它解决的不是“存起来”这么简单，而是：
- 名称唯一性
- MCP 名称映射
- 顺序稳定性
- 可用工具统计

这意味着：

> 后续所有人——Harness、PolicyGuard、MCP——都不再各自维持一套工具真相，而是统一读一个事实源。

### 第三级：`AgentToolBinder` 证明“存在”和“可执行”是两层
见：
- `my-xhs-ai-tools/src/main/java/com/myxhs/ai/tools/AgentToolBinder.java`

这里最值钱的一点是：
- `Catalog` 只定义元数据
- `Binder` 才绑定执行器

这让系统可以表达非常重要的状态：
- 工具存在，但 invoker 为空（预留）
- 工具存在，可执行，但属于 L3
- 工具存在，可执行，可直接查

也就是说：
> **工具存在 ≠ 工具当前可执行。**

这一层正是边界能被系统化的前提。

### 第四级：`PolicyGuard` 才是“这次 run 能不能调”的裁决者
见：
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/harness/PolicyGuard.java`

它的裁决顺序很重要：

1. 工具是否注册
2. 当前画像是否允许
3. L3 且无执行器 → deny
4. L3 且有执行器 → requiresApproval
5. L1/L2 参数校验
6. allow

这条顺序证明了：
- 工具不是“存在就能调”
- 任务也不是“模型说调就能调”
- 边界是系统裁决结果，不是 prompt 偏好

### 第五级：Harness 把工具边界接进任务语义
见：
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/harness/AgentHarness.java:385-431`

这里真正发生的是：
- 先 `PolicyGuard.evaluate(...)`
- 需要审批则挂起
- 工具执行后写证据链
- loop check
- 再决定是否继续

所以最终完整链不是：

```text
MCP -> tool
```

而是：

```text
Catalog -> Registry -> Binder -> PolicyGuard -> Harness -> Tool Execution
```

这条链才是 `my-xhs-ai` 真正的工具边界。

## 再补一层运行态证据：这不是纸上边界

### 运行态证据 1：`dlq.redeliver` 真实审批闭环
真实链路已经跑通：
- `mqDlqQuery`
- 提取 `ORIGIN_MESSAGE_ID`
- 进入 `WAITING_APPROVAL`
- approve
- `dlq.redeliver`
- `CR_SUCCESS`

这里最强的证明不是“会查也会发”，而是：

- 工具存在 ≠ 直接执行
- L3 动作在运行态真的会被系统拦下并挂审批

这正是“工具系统是边界”的最佳运行态证据。

### 运行态证据 2：未开放工具不是“想调就调”
当前系统已经明确标注：
- `service.restart` 未实现
- `order.refund` 未实现

也就是说：
- 工具名可以存在于系统设计里
- 但只要执行器没落地，就不能假装它已经可用

这再次证明：
> 工具存在 ≠ 工具开放。

### 运行态证据 3：`logSearch` 白名单
实际已经验证过：
- 白名单为空时，日志检索会全部拒绝
- 加入 `my-xhs-order` / `my-xhs-inventory` 后，系统才能真实看见业务日志

这说明“边界”不是抽象说法，而是直接决定系统能不能接触某块现实世界数据。

### 运行态证据 4：Langfuse tool span
当前 Langfuse 里已经能看到 tool observation：
- tool name
- window
- evidence refs
- output

这说明工具边界不仅控制执行，还已经进入了系统可观测层。

## 这层设计真正的代价

如果说工具系统本身就是边界，那它的代价也必须讲清楚。

### 1. 接新工具不再便宜
以前你只要：
- 写一个函数
- 暴露给模型

现在你要补：
- catalog
- schema
- validator
- binder
- access level
- 画像可见性
- 可能还要考虑 MCP 暴露

这会让“新功能接入”明显变慢。

### 2. 元数据维护成本上升
一旦：
- 名称漂移
- schema 变动
- level 写错
- binder 漏接

边界就会出错。

### 3. 模型自由度被主动压低
这是最容易被忽略、但最值得写出来的代价。

当前系统为了安全和可审计，主动牺牲了：
- 模型的自由探索空间
- 任意尝试不同工具组合的灵活性
- 快速接新工具的轻便性

也就是说，这个系统不是在追求“模型尽可能自由”，而是在追求：

> **模型尽可能可信。**

这正是企业级 Agent 和玩具 Agent 的分界线。

## 收束结论

现在可以把开头的问题彻底收回来了。

`my-xhs-ai` 不是“模型随便调几把工具”的系统，
也不是“有 MCP 所以边界自动成立”的系统。

它真正成立的条件是：
- 工具被 catalog 化
- 工具被 registry 化
- 元数据和执行器分离
- 权限与参数被系统裁决
- 最后再被 Harness 纳入任务状态机

所以这篇真正要立住的结论是：

> **工具系统本身就是边界。**

不是“工具很多”，而是“工具被系统化治理之后，模型才有资格在真实业务边界内行动”。

## 篇末桥接

这篇解决了四件事：

1. 为什么不能把工具直接裸露给模型
2. 为什么 MCP 也不能替代执行边界
3. 为什么 ToolRegistry / PolicyGuard / Harness 这条链才是真边界
4. 为什么这种边界是有代价的，而且这种代价是主动接受的

这篇还没彻底展开的是：

- 当工具边界已经立住之后，历史上下文、长期记忆和 RAG 如果写不好，会怎样重新污染已经立住的边界？

换句话说，下一篇要处理的不是“记忆更聪明”，而是：

> **为什么 `my-xhs-ai` 的 Memory 必须服务研发诊断用户，而不能滑向终端用户画像，更不能把旧工具结果当成新事实。**

所以下一篇最自然的桥接就是：

- `03-memory-conversation-rag/01-memory-is-for-dev-not-end-user.md`
