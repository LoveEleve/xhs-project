# 第03题 | HITL 审批状态机

> 难度：★★★★☆｜频率：★★★★★｜区分度：高
> 关键词：human-in-the-loop、危险工具挂起、参数指纹、CAS 抢占、崩溃补执行、超时 fail-closed、级联拒绝

## 问题
问题：Agent 能操作生产（重投 DLQ），你怎么保证它不闯祸？提示词拦截不够吗？

## 面试可讲版（五段式）

**① 业界背景**
HITL（human-in-the-loop）是 Agent 安全共识：高风险动作必须人工确认。LangGraph 的 `interrupt`、Anthropic 的 human-in-the-loop 模式都是这个思路。关键争论：**提示词能不能当安全边界？** 不能——提示注入可以绕过任何提示词约束，安全边界必须在**执行层**（工具网关/状态机）。另一个争论是审批放哪：对话层拦截（弱）还是工具执行前拦截（强）。

**② 项目选择**
执行层拦截 + 数据库状态机。危险工具（`dlq.redeliver`）不直接执行——Agent 调用时返回 `approval_required` + approvalId（SSE 推送）；审批落库 `ai_approval`，**状态机（小写字符串）**：`pending` → `approved` / `rejected` / `expired`（超时 10 分钟 fail-closed）。决策用 **CAS**：`UPDATE ... WHERE id=? AND status='pending'`；审批参数算 **`raw_input_hash` 指纹**（防"批 A 执行 B"），同会话同指纹已有 pending 时**复用**（不重复弹窗）；拒绝时**级联拒绝**同会话其他 pending（避免残留隐患）。执行器只支持白名单工具（当前仅 `dlq.redeliver`），执行后写 settlement = `pending_verification`，交给核验器裁决（见 04 题）。

**③ 坑**
- 审批通过后进程崩溃（approved 但无执行结果）→ `ApprovalExecutionRecoveryJob` 按 id 补执行；
- 执行器**卡死在 executing**：实现上 executing 是 result 里的标记，回收 Job 扫描 >600s 的记录**标 failed 交人工**——"不自动重放副作用"，因为重投可能已发出；
- 超时未审批：`ApprovalExpiryJob` 置 `expired`（fail-closed，绝不默认放行）；
- 重复批准/并发决策：CAS 只有一个赢家；指纹防止参数被偷换；
- 提示词里写"不要重投"没有强制力——所有强制都在执行器与状态机。

**④ 兜底**
- 执行器只认审批状态（不认对话上下文），工具白名单硬编码；
- 核验器给"已重投"一个可证伪断言（队列位点/再入检测，见 04 题）；
- 全过程审计（谁批、何时、参数指纹、执行结果、核验结论）+ 哈希链防篡改；
- `ApprovalEventBus` 广播状态变化，UI/指标实时可见；级联拒绝清理同会话 pending。

**⑤ 话术**
> "安全边界不靠提示词，靠执行层状态机：危险操作只能提案，批准走 CAS、参数有指纹、超时 fail-closed、崩溃能补执行、卡死不得自动重放；每一步都有审计。"

## 追问与参考回答
**追问1：为什么提示词拦截不够？** 提示注入可以把"不要重投"改成"帮我重投"；提示词是策略不是强制，执行层才可强制。
**追问2：指纹怎么算、防什么？** 对工具名 + 规范化参数算 `raw_input_hash`；防"审批后参数被改"和"拿旧审批执行新请求"；同会话同指纹复用 pending 也顺带防了重复弹窗。
**追问3：为什么超时 fail-closed？** 生产变更默认可放行是灾难；宁可让用户重新发起，也不给未确认操作留窗口（10 分钟阈值可配）。
**追问4：跨实例怎么保证只执行一次？** 状态在 DB，CAS `WHERE id=? AND status='pending'` 抢占；多副本下同一审批只有一个赢家。
**追问5：执行结果如何确认？** 不是看接口 200：核验器做两级判定——再入检测 + 队列级位点核验（verified_consumed / verified_no_reentry / reentered_dlq）。
**追问6：为什么拒绝要级联？** 用户拒绝一个危险操作，往往意味着不信任同类操作；残留 pending 容易被误点批准。级联拒绝是保守策略。

## 面试官评分点
**高级开发级**：能说清为什么安全边界在执行层；能描述状态机与指纹的作用；知道超时 fail-closed。
**架构师加分**：崩溃补执行/卡死回收/重复执行三类故障的处置、"不自动重放副作用"的取舍、核验的可证伪设计、审批粒度与体验（复用/级联）。
**危险信号**：靠提示词/正则拦危险操作；审批状态只在内存；批准后参数可改；失败后静默重试。

## 本项目真实证据
- `approval/` 7 个类：`ApprovalService`（落库/CAS/级联拒绝/复用指纹）、`ApprovalExecutor`（白名单+effect+settlement）、`ApprovalFingerprint`（raw_input_hash）、`ApprovalExpiryJob`（10min→expired）、`ApprovalExecutionRecoveryJob`（approved 补执行 + executing>600s 回收标 failed）、`ApprovalEventBus`、`RedeliverVerifier`。
- `ApprovalService.java:45,133-135,150-151`：status='pending' 查询与 CAS、级联拒绝 SQL；`ApprovalExpiryJob.java:37-55`（超时 expired + fail-closed 注释）；`ApprovalExecutionRecoveryJob.java:46,68-76`（补执行/回收）。
- 实测：SSE `approval_required` → 批准 → 重投 → 核验全链路跑通；重复提交被幂等拦截。

## 版本与来源
LangGraph interrupt / Anthropic HITL 模式公开资料；本项目 approval 模块代码与生产化报告。

## 真实性说明
状态值与 SQL、指纹、CAS、补执行、回收、级联拒绝均为代码事实；"跨实例业务续跑订阅未接线"为主动披露边界（事件广播已有，订阅执行未接）；审批状态为小写字符串（非大写枚举）。
