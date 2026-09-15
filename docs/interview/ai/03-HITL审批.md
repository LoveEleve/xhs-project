# 第03题 | HITL 审批状态机

> 难度：★★★★☆｜频率：★★★★★｜区分度：高
> 关键词：human-in-the-loop、危险工具挂起、参数指纹、CAS 抢占、崩溃补执行、超时 fail-closed

## 问题
问题：Agent 能操作生产（重投 DLQ），你怎么保证它不闯祸？提示词拦截不够吗？

## 面试可讲版（五段式）

**① 业界背景**
HITL（human-in-the-loop）是 Agent 安全共识：高风险动作必须人工确认。LangGraph 的 `interrupt`、Anthropic 的 human-in-the-loop 模式都是这个思路。关键争论：**提示词能不能当安全边界？** 不能——提示注入可以绕过任何提示词约束，安全边界必须在**执行层**（工具网关/状态机）。另一个争论是审批放哪：对话层拦截（弱）还是工具执行前拦截（强）。

**② 项目选择**
执行层拦截：危险工具（如 `dlq_redeliver`）不直接执行——Agent 调用时返回 `approval_required` + approvalId（SSE 事件同步给前端）；审批独立落库，状态机 **PENDING → APPROVED/REJECTED → EXECUTING → EXECUTED/FAILED**；审批绑定**参数指纹**（`ApprovalFingerprint`），批准 A 参数不能执行为 B（防偷换）；批准后 `ApprovalExecutor` 用 CAS 抢占状态再执行；执行后跑**核验**（`RedeliverVerifier` 断言消息确实离开 DLQ/落回目标队列）才算完成。

**③ 坑**
- 审批通过后进程崩溃：审批停在 APPROVED 无人执行 → `ApprovalExecutionRecoveryJob` 补执行；
- 执行器卡死在 EXECUTING：必须有回收（超时释放回 APPROVED/FAILED，防永久卡死）；
- 重复批准/重复执行：CAS + 幂等键扛；
- 超时未审批：`ApprovalExpiryJob` 到期置失败（fail-closed），不能"默认放行"。

**④ 兜底**
- 执行器只认真实状态机（不认对话上下文）；指纹保证"批什么执行什么"；
- 核验器给"已重投"一个可证伪断言（reentered_dlq），失败标 FAILED 人工介入；
- 全过程审计（谁批、何时、参数指纹、执行结果）+ 哈希链防篡改；
- 事件广播（`ApprovalEventBus`）让 UI/指标实时看到状态。

**⑤ 话术**
> "安全边界不靠提示词，靠执行层状态机：危险操作只能提案，批准后经指纹校验、CAS 抢占、执行、核验四步才闭环；任何一步失败都是 fail-closed。"

## 追问与参考回答
**追问1：为什么提示词拦截不够？** 提示注入可以把"不要重投"改成"帮我重投"；提示词是策略不是强制，执行层才可强制。
**追问2：指纹怎么算、防什么？** 对工具名 + 规范化参数（排序/归一）做哈希；防"审批后参数被改"和"拿旧审批执行新请求"。
**追问3：为什么超时 fail-closed？** 生产变更默认可放行是灾难；宁可让用户重新发起，也不给未确认操作留窗口。
**追问4：跨实例怎么保证只执行一次？** 状态在 DB，CAS `UPDATE ... AND status=APPROVED` 抢占，抢占成功才执行；多实例下同一审批只有一个赢家。
**追问5：执行结果如何确认？** 断言式核验（消息不再在 DLQ/进入目标），不是只看 API 返回 200。
**追问6：审批粒度？** 单工具单请求单审批；批量操作要拆条，避免"一次审批放大成批操作"。

## 面试官评分点
**高级开发级**：能说清为什么安全边界在执行层；能描述状态机与指纹的作用；知道超时 fail-closed。
**架构师加分**：崩溃补执行/卡死回收/重复执行三类故障的处置、核验的可证伪设计、审计合规、审批粒度与体验平衡。
**危险信号**：靠提示词/正则拦危险操作；审批状态只在内存；批准后参数可改。

## 本项目真实证据
- `approval/`：`ApprovalService`、`ApprovalExecutor`、`ApprovalFingerprint`、`ApprovalExpiryJob`、`ApprovalExecutionRecoveryJob`、`ApprovalEventBus`、`RedeliverVerifier`（7 个类，状态机 + 指纹 + 补执行 + 回收 + 核验 + 事件）。
- 实测：SSE 返回 `approval_required` → 批准 → 重投 → 核验（reentered_dlq）全链路跑通；重复提交被幂等拦截。

## 版本与来源
LangGraph interrupt / Anthropic HITL 模式公开资料；本项目 approval 模块代码与生产化缺口报告（生产化缺口 14 项中"续跑订阅未接线"为已知边界）。

## 真实性说明
七个类、指纹、CAS、补执行、回收、核验均为代码事实；"跨实例业务续跑订阅未接线"为主动披露的边界（事件广播已有，订阅执行未接）。
