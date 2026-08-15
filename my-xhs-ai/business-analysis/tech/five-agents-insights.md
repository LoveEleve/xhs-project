# 五 Agent 项目探索启示（pi / opencode / deepseek-harness / hermes / reasonix）

> 日期：2026-08-15 | 方法：本地源码 + 已有域发现/闭环笔记交叉验证（两路并行深挖）
> 用途：为 M10-M14 规划提供业界实证参考——**不是照搬，是提炼可借鉴的模式**。

---

## 一、五项目一句话画像

| 项目 | 语言 | 灵魂 | 一句话 |
|------|:--:|------|--------|
| **pi** | TS | 事件溯源 + 会话状态机 | 双层循环 + 事件归约（reducer），压缩/恢复/记忆完整，评测 harness 最成熟 |
| **opencode** | TS | 循环状态在 DB 不在内存 | V2 事件溯源引擎，收件箱双游标 + Context Epoch 基线，权限系统最完整 |
| **deepseek-harness** | TS | 框架即产品（一切皆插件）| 能力缝三角色（Def/Provider/Consumer），会话日志=上下文源，profile-bundle 版本化 |
| **hermes-agent** | Python | 单体大循环 + 学习循环 | 运行时最完整（预算/中断/fallback/记忆/技能治理），防 thrash 压缩状态机 |
| **reasonix** | Go | 单控制器 + 契约驱动 | 传输无关内核 + TaskSpec 规格数据化 + 缓存优先 + 检查点事务化 |

## 二、启示矩阵（按我们的规划能力域）

### M10 会话与记忆（启示最密集）

| # | 模式 | 来源 | 怎么用 |
|---|------|------|--------|
| 1 | **持久化收件箱双游标**（admitted_seq/promoted_seq，admit 幂等 + promote 原子）| opencode | 多轮输入先落库再进模型；"用户只存不问"=admit-only。我们的 ai_message 加 admitted/promoted 游标 |
| 2 | **压缩 = 摘要重建 + 保留尾巴**（pi retainedTail 20000 / opencode head-recent 8000）+ **防 thrash 状态机**（阈值+冷却+ineffective 记账+恢复试探）+ **提交栅栏**（取消要么提交前赢要么等完整）| pi + opencode + hermes | 会话摘要升级为"摘要+保留尾+新增"三段式压缩，替代 M10 设计的简单规则摘要 |
| 3 | **Context Epoch 不可变基线**（系统提示变化不改基线只追加，保 prompt cache 前缀稳定）| opencode | 店铺画像/诊断规则作为不可变 Baseline；参数变化以时间序 System 消息追加 |
| 4 | **Session Facts 表**（session_id, seq, kind, key, value + label 书签）| pi | 记忆蓝图："结论"=facts(kind=conclusion)；custom 条目"持久化但不进 LLM 上下文"——记忆不污染上下文的分离原则 |
| 5 | **记忆双态快照**（冻结快照注入 system prompt vs 活态写盘）+ **subject 冲突模型**（同一知识点更新旧结论不并存）| hermes + reasonix | 用户级记忆：快照注入缓存稳定；结论冲突时更新旧 ID 而非并存 |

### M11 HITL 与执行工具

| # | 模式 | 来源 | 怎么用 |
|---|------|------|--------|
| 6 | **五事件工具管线**（pre-execute 门 → execute → post-execute 接受/替换/阻断 → 日志 → result）| deepseek-harness | dlq.redeliver 的审批挂在 pre-execute 缝事件；post-execute 审计与 result 分离 |
| 7 | **审批 fail-closed**（缺审批支持时 ask 变 deny）+ 决策收据可审计 | deepseek-harness + reasonix | HITL：无审批端点 = 拒绝，审批决策落审计收据 |
| 8 | **工具组合预设 = 权限分级**（只读预设 vs 可写预设，集合切换）| pi | "诊断模式（14 只读）vs 执行模式（+dlq.redeliver）"预设切换，比细粒度权限系统简单可靠 |

### M12 工具注册表

| # | 模式 | 来源 | 怎么用 |
|---|------|------|--------|
| 9 | **Capability Seams 三角色**（Service Definition / Provider / Consumer 三包）| deepseek-harness | 我们的工具按缝重组：只读诊断 provider vs 运营写 provider = provider 切换，Consumer（MCP）不动 |
| 10 | **definition-first 注册表**（AgentTool 契约 + 双向转换 + setActiveToolsByName 重建提示词）| pi | ToolRegistry 直接照搬：切换工具集同步重建 system prompt |
| 11 | **九阶段门控链 + 变异屏障**（第一个持久写失败 → 后续变异全跳）| reasonix | 工具调用统一走门控链；dlq.redeliver 失败后阻止后续写操作 |

### M13 多 Agent

| # | 模式 | 来源 | 怎么用 |
|---|------|------|--------|
| 12 | **子 agent 权限继承**（继承 deny + 禁开子 agent 防递归）| opencode | 双 Agent PoC：子 Agent 继承父 deny 列表 |
| 13 | **背景审查 fork = 最小多 Agent 形态**（独立子 agent 只持白名单工具）| hermes | 比 orchestrator 更小的 PoC 起点：主 Agent + 审查 fork |
| 14 | **每 key 串行、跨 key 并行 + wake 合并 + interrupt 幂等** | opencode | 多 Agent 调度的并发控制模型 |

### M14 评测与验收

| # | 模式 | 来源 | 怎么用 |
|---|------|------|--------|
| 15 | **TaskSpec 三合一 + SuccessCriterion.evidence_ids**（goal/scope/non_goals/allowed_operations/success_criteria，每条判据绑定证据 ID，验收=证据计数+必选判据）| reasonix | **直接对接我们的证据链**：评测断言从"答案含关键词"升级为"成功判据×证据 ID"结构化验收 |
| 16 | **eval harness 真实会话**（transcript 事件 + 快照 artifact + stopReason 断言）| pi | 评测从"单轮答案断言"升级为"真实多轮会话 + 步骤断言" |
| 17 | **无人值守三件套**（evaluator fail-closed + token 预算 + 成本观测）| reasonix | 定时诊断（无人值守）必须有评估器，无评估器则暂停 |

### 架构通用

| # | 模式 | 来源 | 怎么用 |
|---|------|------|--------|
| 18 | **传输无关内核**（命令面 Submit 族 + typed 事件流 + 前端外壳）| reasonix | 三阶路由/AgentHarness 已是内核；SSE/REST 是壳——保持 |
| 19 | **迭代预算 consume/refund + grace call + 中断占位**（预算耗尽给最后一次收尾轮；被跳过工具写取消占位）| hermes | AgentHarness 预算增强：收尾轮（诊断结论不截断）+ 取消占位（模型知道哪些工具没跑）|
| 20 | **profile-bundle 版本化**（patch 最后写赢 + 整行替换 + dump-config）| deepseek-harness | 诊断模板/话术/策略按 bundle 分层版本化（替代散落的常量）|

## 三、对我们 M10-M14 的规划修订（融入点）

1. **M10 会话升级**：设计从"规则摘要"升级为 **三段式压缩（摘要+保留尾）+ 收件箱双游标 + 防 thrash**——直接参考 pi/opencode/hermes 三项目实现（见 design-m10 修订）
2. **M10 记忆升级**：Session Facts 表 + 双态快照 + subject 冲突模型（用户级记忆的完整方案已有实证参考）
3. **M11 HITL**：审批挂 pre-execute 缝事件 + fail-closed + 决策收据（deepseek-harness 五事件管线）
4. **M12 工具注册表**：Capability Seams 三角色 + definition-first + 组合预设权限（deepseek-harness + pi）
5. **M13 多 Agent**：PoC 起点改为 **主 Agent + 审查 fork**（hermes 最小形态），再评估 orchestrator；权限继承 + 防递归（opencode）
6. **M14 评测**：验收模型升级为 **TaskSpec×evidence_ids**（reasonix）——这是对我们证据链理念的强化验证：**业界大项目也用证据 ID 绑定验收**
7. **横切**：迭代预算 grace call + 中断占位（hermes）；能力缝策略挂点（deepseek-harness）

## 四、我们已优/持平的部分（不照搬）

- **三阶意图路由**：pi/opencode 靠运行时动态分派（steer 队列/agent 选择），我们入口静态路由对电商诊断更可预测、可测——保留
- **存在性校验 + DECLINE**：五个项目没有等价物（它们靠"测试即行为契约"兜底，我们是机制级兜底）——已验证是差异化
- **事件溯源正确性**：opencode/pi 的重放校验/损坏检测比我们 RunStore 强——**这是我们要补的**（单调 seq + 分叉检测）
- **SSE 单消费者 + 断开释放**：opencode 的 durable tail 无窗口订阅值得借鉴（后续）

## 五、结论

五个项目证实了我们的规划方向，并提供了**可直接落地的实现参考**（不是概念，是模式名 + 文件位置）：
- 会话/记忆：pi（retainedTail/Facts）、opencode（双游标/Epoch）、hermes（防 thrash/双态）
- 工具治理：deepseek-harness（能力缝/五事件管线）、pi（组合预设）
- 多 Agent：hermes（审查 fork）、opencode（权限继承）
- 评测：reasonix（TaskSpec×evidence_ids）——**与我们的证据链理念互相印证**

我们的差异化（机制级防编造/确定性路由/受控工具）在这些通用 Agent 项目中不存在——组合起来就是"企业级 Agent 平台"的完整拼图。
