# 深度 Review：M11 实现（HITL 审批闭环）

> 日期：2026-08-15 | 对象：M11 HITL（design-m11-hitl.md）| 视角：状态机正确性/审批并发/审计完整性/场景适配
> 结论：**2 个 P0 + 2 个 P1（全部修复）**——216 测试全绿 + 真库冒烟

---

## P0-1：审批挂起视图缺工具名（pendingApproval 只有 args）

**问题链**：挂起时 `AgentRun.pendingApproval` 只存 args（msgId/consumerGroup），视图（GET /api/runs/{id}）展示 WAITING_APPROVAL 但**没有工具名**——前端审批卡片无法显示"审批哪个工具"。

**修复**：`AgentRun.flagWaitingApproval(tool, args)` 增加 pendingTool 字段，视图输出 pendingTool + pendingApproval。RunControllerTest 的挂起断言捕获。

## P1-1（二轮 review 发现）：挂起时会话锁提前释放 + 会话消息丢失

**问题链**：submit 的 `whenComplete` 在挂起 run 的 future 完成时照常执行——① `activeByConv` 锁被释放（挂起期间同会话可提交新 run，与挂起 run 并行）；② `appendAssistantMessage` 写 finalAnswer=null 落库失败（脏日志）；③ `updateSessionId` 被跳过 → 审批恢复 resumeEntry 读不到 session_id → **恢复完成后不写会话消息**（messageCount 停在 1）。

**修复**：
1. whenComplete 区分"终态/挂起"：挂起不写消息、不释放锁（try-finally 保证终态必释放）
2. `updateSessionId` 无条件执行（挂起也写——resumeEntry 依赖它续接会话）
3. resumeEntry 从 store 读 session_id → 重新登记锁（putIfAbsent，冲突 WARN）+ 终态写消息/摘要/释放（与 submit 同语义）

**回归测试**：`HITL_挂起期间同会话并发仍409`（挂起时锁保留）+ 审批恢复后 messageCount=2（消息补写）。

## P1-2（二轮 review 发现）：前端挂起流不关闭、事件被忽略

**问题链**：WAITING_APPROVAL 不在 TERMINAL_TYPES → ① watchRun 不关闭 EventSource（挂起后无更多事件，流悬挂）；② applyEvent 无 WAITING_APPROVAL 分支（事件被忽略，用户看不到审批卡片）。

**修复**：watchRun 流关闭条件加 WAITING_APPROVAL（TERMINAL_TYPES 语义不动——别处用它判终态视图）；applyEvent 加 WAITING_APPROVAL 分支 → loadView 拉视图渲染审批卡片。

## 实现中自发现并修复（非 review 阶段）

1. **H2 测试建表缺 approval_json 列** → 3 处测试建表 SQL 补列
2. **loadApproval 对 null 列 NPE**：`rs.getString` 返回 null → `findFirst` 对 null 元素炸 `Optional.of(null)` → 先 filter 再 findFirst
3. **"重投死信"被意图路由当问候直答**：mock 分类失败→默认引导；测试 query 改用 L0 强信号
4. **SYSTEM_PROMPT 未告知模型 dlq.redeliver 存在**：真库 E2E 模型直接 DECLINE → prompt 补说明 → 模型改为"先调查再决策"（行为正确）

## 注意点（记录在案）

1. **真库挂起-审批 E2E 不可控**：模型是否请求 dlq.redeliver 取决于数据——完整链路由 fake 单测锁定（确定性）。
2. **dlq.redeliver 管理通道未配置**（远端 MQ 不可达）：工具返回 ERROR 如实；生产配置 `myxhs.ai.hitl.dlq-redeliver.url` 即接真实执行。
3. **审批拒绝复用 CANCELLED**（TerminationReason.APPROVAL_REJECTED 区分）：少加枚举。
4. **挂起无超时**（V1 人工管理）：无线程/事件占用；重启后 approve 走 resumeEntry 重建。
5. **P2：挂起时 RunMetrics 提前记录**（onRunFinished 把挂起当完成，时长偏低）——指标轻微失真，记录在案。
6. **P2：approve 审计字段用字符串拼接**（lastIndexOf 定位最外层 }，args 值白名单保证安全）——规范上可后续换 JSON 序列化。

## 方法论复盘

- **写前 review 3 处修正全部兑现**：L3 先校验再挂起/无执行器 L3 直接 deny/approve 兼容内存 miss
- **二轮 review 的增量价值**：第一轮 review 验证了"链路正确"，二轮转向"状态机 × 横切机制的组合"（会话锁/指标/SSE）——挂起不是终态，但第一轮实现把它当终态处理（whenComplete 语义）——**状态枚举变化必须审计所有"终态判断点"**
- **测试先于真库暴露问题**：`HITL_挂起期间同会话并发仍409` 这类"状态组合"断言，单测确定性 > 真库依赖模型行为

## 结论

- M11 完成（含二轮 review 的 P1 修复）：审批闭环 + dlq.redeliver 受控执行 + 前端审批卡片
- 216 测试全绿（app 155 + tools 50 + mcp 11）
- 下一步：M13 多智能体 PoC / M14 评测闭环


---

## 补充 review（2026-08-16）：dlq.redeliver 对接真实 Dashboard 契约

**对象**：DlqRedeliverTool 两段式改造（csrf 会话 + consumeMessageDirectly.do）+ 真实环境实测。

### P1-1：ORIGIN_MESSAGE_ID 语义断链（端到端不可用，如实记录）
- 契约要求 msgId = **ORIGIN_MESSAGE_ID**（DLQ 消息的原始消息 ID），而获取它的查询接口
  `queryDlqMessageByConsumerGroup` 实测 NPE（中间件团队提示 + 本机复现）——**数据获取路径断裂**。
- 对策：执行器按契约实现（可测可用性=输入必须为 ORIGIN_MESSAGE_ID）；工具/prompt 语义明确标注；
  端到端可用依赖接口修复或 mqadmin；未假装可用（HANDOFF 已知问题如实记录）。

### P1-2：测试 error 路径缺失
- 原测试只覆盖成功/参数非法/未配置——补：csrf 失败（HTTP 500）、重投响应非 0（消息不存在）→ 均如实 error。

### P2（记录）
- HTTP 明文传输 CSRF token/cookie（内网 iptables 白名单缓解，非公网暴露）
- baseUrl 尾斜杠约定（配置规范）
- FULL prompt 不动（评测基线稳定）；OPS 变体描述更新（ORIGIN_MESSAGE_ID）→ prompt 版本 v2

### 方法论
- **契约对接必须验证"数据闭环"**：执行器对接只是半程——输入从哪来（DLQ 查询接口）是另一半，
  NPE 使端到端不可用——"能用"与"接口对上"是两回事
- **错误路径与成功路径同等测试**（csrf/重投失败均如实报错，不吞）

---

## 补充 review（2026-08-16 二轮）：中间件口径核实落地 + 契约细节

### P1-3：mqDlqBacklog 聚合哨兵负值误导 Agent（中间件口径落地）
- 中间件核实：`-1` = 无 DLQ/查询失败哨兵，`-283` 是 -1 行求和无意义——**正确口径只计 >0 的组**。
- 原工具 `total += v` 把 17 个 -1 累加成 -283，Agent 回答需自行兜底解释（E2E 见过"哨兵值的聚合"）。
- 修复：聚合排除 v<=0（哨兵组数单列 sentinelGroups + note 说明口径）；单测覆盖混合场景（-1/5/-1 → total=5, sentinel=2）。

### P2-2：重投响应解析改 JSON 精确判断
- 原 `contains("\"status\":0")` 字符串匹配可能误匹配嵌套字段 → `parseStatus()==0`（JSON path 精确）。

### P2-3：契约端到端验证留待真实场景（记录）
- 重投端点不经审批不实际调用（红队纪律，含"无效 ID 探测"）——端到端契约验证留待真实死信场景
  （审批后执行时自然验证）；当前可用性 = 会话/查询类端点实测 + 执行器 fake 单测。
