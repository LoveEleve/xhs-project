# M10 详细设计：会话与记忆（Conversation + Session Memory）

> 日期：2026-08-15 | 前置：roadmap-m10-m14.md（决策 D-B：会话级先做）
> 定位：设计到可开工粒度（DDL/接口契约/时序/上下文注入方案/摘要方案），符合 de-risk 纪律——写码前定死。

---

## 1. 目标与范围

- **多轮会话**：`convId` 贯穿，消息持久化，多轮上下文注入（第二问能引用第一问结论/证据）
- **会话级摘要记忆**：每轮结束生成会话摘要，下一轮注入（长会话上下文压缩）
- 明确不做（本期）：用户级长期记忆（D-B 决策）、会话列表 UI 管理（薄壳只加输入框+convId 显示）

## 2. 数据模型（DDL，my_xhs_ai 库）

```sql
-- 会话（与 Run 分离，四态分离原则）
CREATE TABLE IF NOT EXISTS ai_conversation (
  conv_id           VARCHAR(32)  PRIMARY KEY,
  user_id           VARCHAR(64)  NOT NULL DEFAULT 'anonymous',
  title             VARCHAR(200) DEFAULT NULL COMMENT '首问截断',
  summary           TEXT         DEFAULT NULL COMMENT '会话级摘要（每轮更新）',
  message_count     INT          DEFAULT 0,
  created_at        DATETIME(3),
  last_activity_at  DATETIME(3),
  KEY idx_user_time (user_id, last_activity_at)
) COMMENT 'AI 诊断会话（多轮上下文载体）';

-- 消息（含 run 关联，可追溯）
CREATE TABLE IF NOT EXISTS ai_message (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  conv_id       VARCHAR(32)  NOT NULL,
  role          VARCHAR(16)  NOT NULL COMMENT 'user/assistant',
  content       MEDIUMTEXT   NOT NULL,
  run_id        VARCHAR(32)  DEFAULT NULL COMMENT '产生该回答的 run（可追溯）',
  refs_json     TEXT         DEFAULT NULL COMMENT '证据引用（可选）',
  created_at    DATETIME(3),
  KEY idx_conv (conv_id, id)
) COMMENT '会话消息（user 提问 + assistant 回答）';
```

## 3. 接口契约

| 端点 | 说明 |
|------|------|
| `POST /api/runs` | body 增加可选 `conversationId`；无则新建会话（响应带 convId）|
| `GET /api/conversations/{convId}` | 会话详情：消息列表 + summary + 关联 runId 列表 |
| `GET /api/conversations?userId=` | 会话列表（按 last_activity 倒序，含 summary）|

响应扩展：POST /api/runs 返回加 `conversationId`（兼容旧字段）。

## 4. 多轮上下文注入（核心机制，深度 review 修订版）

```
用户提问 → ConversationService.load(convId)
  ├─ 恢复会话历史消息（最近 20 轮，只含 user/assistant 结论消息）
  ├─    ★ 不含工具结果原文（P0-1 方案 A）：模型无法引用上轮 ev_xxx，
  │      想用上轮数据必须重新调用工具（正确语义：数据可能已变化）
  ├─ 注入记忆摘要（SystemMessage：'历史会话摘要：…'）
  └─ Harness.run(query, messages=历史+当前)
回答完成 → 追加 user/assistant 消息到 ai_message（★ 会话消息=结论+证据 ID 列表，无工具原文）
  └─ 更新会话摘要（规则抽取）
```

注入优先级：**记忆摘要 < 会话历史 < 当前问题**（与 RAG 上下文同层原则）。
**职责分离**（P0-2）：ai_message 会话专用（注入/展示/追溯）；ai_step.messages_snapshot 恢复专用（执行态）——单向流：run 终态 → 写 ai_message，checkpoint 不反向读会话表。
**并发限制**：同会话同一时间仅一个活跃 run（V1 串行，复用 409 语义）。

## 5. 会话摘要生成方案

| 方案 | 成本 | 质量 | 选型 |
|------|:--:|:--:|:--:|
| 规则抽取（结论段/证据行截断拼接）| 0 | 中 | 先用（零成本，够用）|
| LLM 摘要（每轮终态后调一次模型）| 每轮 ~500 token | 高 | 后续增量 |

- **V1 规则抽取**：从 finalAnswer 提取"结论/证据链/反证/不确定性"段落 + 首问标题 → 拼接为摘要
- 每轮更新 summary 字段（覆盖式，长度上限 2000 字符）
- 注入时若 summary 为空则跳过

## 6. 时序流（多轮归因）

```
用户: "为什么订单量下降了？"            conv=A
  → POST /api/runs {message, conversationId:A}
  → ConversationService: 历史=[], summary=null
  → Harness 调查 → COMPLETED（结论含证据链）
  → 存消息[user,assistant] + 规则摘要 → summary="订单量46→8…"
用户: "那支付呢？"                      conv=A（第二问）
  → 恢复历史[Q1,A1] + 注入 summary
  → Harness 带上下文调查 → 回答能引用"如上所述订单量下降…"
```

## 7. 组件改动清单

| 模块 | 改动 |
|------|------|
| tools | 无 |
| app | `ConversationStore`（JdbcConversationStore：ai_conversation/ai_message CRUD + 摘要更新）|
| app | `ConversationService`（load 历史+摘要注入组装；save 消息+更新摘要）|
| app | `AgentHarness` 多轮入口：`run(query, budget, listener, userId, cancelToken, initialMessages)`（复用现有消息恢复逻辑）|
| app | `RunManager.submit` 支持 convId：提交前组装消息上下文（注入历史+摘要）|
| app | `RunController`：POST body 解析 conversationId；`/api/conversations/**` 端点 |
| app | 事件流：RUN_STARTED message 带 convId（前端显示）|
| mcp | 无 |
| frontend | 输入框上方显示当前会话标识（可新建会话）；?conv= 参数恢复会话 |

## 8. 验收门禁（深度 review 修订）

- [ ] **前置债**：eval-gate 跑绿（7 个失败用例修复，门禁真实通过）
- [ ] **多轮归因**：Q1"订单量下降"→ Q2"那支付呢"能承接 Q1 结论（且**重新调用工具验证**，不是抄上轮答案）
- [ ] **跨轮证据校验**：模型不可引用上轮 ev_xxx（历史注入无工具原文）；引用即校验失败→重想→重新查
- [ ] **摘要注入生效**：长会话（>5 轮）后新问题注入 summary（日志/快照可查）
- [ ] **并发限制**：同会话第二个活跃 run 被拒（409 语义）
- [ ] **崩溃兼容**：多轮消息恢复与 M5-4 恢复机制共存（回归）
- [ ] 单轮行为不变：无 convId 提交 = 现状（192 测试回归）
- [ ] 全量回归绿 + 真实 E2E（两轮连续提问，第二问承接第一问）

## 9. 风险与对策（深度 review 修订）

| 风险 | 对策 |
|------|------|
| 历史消息撑爆上下文 | 最近 20 轮截断 + 规则摘要（V1 不做压缩状态机——诊断会话短，场景适配）|
| 多轮引用编造（引用不存在的上轮证据）| **历史注入无工具原文**（P0-1 方案 A）——模型看不到旧 ev，编造无源可依 |
| 数据随时间变化（上轮结论已过期）| 重新调用工具的正确语义（而非跨轮复用旧证据）|
| checkpoint 与会话双写不一致 | 职责分离 + 单向流（P0-2）|
| 会话归属/越权 | V1 按 userId 关联（gateway L1/L2 角色已有）；跨用户会话访问属 M8-3 gateway 职责 |
| 摘要规则抽取质量 | 零成本先用，bad-case 回流（M14）后评估是否换 LLM 摘要 |

---

> 下一步：修订后 M10 动工（前置：eval-gate 跑绿）——先 ConversationStore/Service + RunManager 集成 + 测试，再前端。
