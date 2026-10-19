---
iteration: ITER-010
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 用途：C-010 深审查（清单先行 + 逐步思考）——事件可靠性链 × 跨模块波及面
---

# 深度审查: C-010（内容模块生产化改造）

## 审查清单（5 维 17 项，逐项结论）

> ✅ 通过 / 🟡 发现并修复 / 📝 记录

### A. 事件可靠性链正确性（本轮核心）
- **A1 helper 全链路** ✅ —— 占位插入→回填→afterCommit 发送→markSent；**回滚语义**：占位行随事务回滚消失 + afterCommit 不执行（Spring 仅 commit 时回调）+ `isSynchronizationActive` 门禁（无事务抛错）——单测锁定
- **A2 发送 wire format 一致性** ✅ —— 无自定义 MessageConverter（默认转换链：String payload → UTF-8 bytes）；home 消费端 `new String(body, UTF_8)` + fastjson 解析（对 object/string 两种发送路径均兼容）；**NotePublishEvent 全字段为 Long/String**（publishTime=毫秒时间戳 Long——无 JavaTime 序列化风险）
- **A3 补发序列化一致性** ✅ —— FEED legacy 补丁路径用注入 objectMapper（含 JavaTimeModule）；首发/补发 body 均为 app-mapper JSON（比原"表内 toJson + 发送走转换器"更一致）
- **A4 补发覆盖面** ✅ —— retryFailedMessages 扫全部 topic（正确：所有本地消息都应补发）；compensateIncompletePush 加 FEED_TOPIC 过滤（实施期防御，防计数行误扫）
- **A5 删除链路双事件顺序** ✅ —— afterCommit 按注册序执行（UNCOMMENT→NOTE_DELETE）；home 侧 T-126 已删标记（`myxhs:note:deleted:`）处理乱序/重复
- **A6 载荷逐字段对照** ✅ —— deleteNote UNCOMMENT {noteId,userId,count} / deleteComment UNCOMMENT {noteId,commentId?,actionTime,count} / COMMENT 同构——与 counter handleCommentEvent 读取字段逐一对齐

### B. 跨模块波及面（C-009 教训）
- **B1 home FeedPushConsumer 字段期望** ✅ —— noteId/authorId/publishTime/localMsgId 与 helper body（NotePublishEvent JSON）一致；断点续推依赖 localMsgId ✓（builder 回填）
- **B2 通知事件口径** 📝 —— NOTIFICATION_TOPIC（评论通知）保持 fire-and-forget（通知丢失可接受——记录）
- **B3 DDL 一致性（🟡 R2）** —— 基线 DDL 注释含 /AUDIT 但 init-all 未同步 → 已修正 ×2

### C. 语义保全
- **C1 发布链路迁移等价** ✅ —— 指标保留（recordAction("content","publish")）；helper 三段式与原实现逐段等价；重复块消除
- **C2 delayDoubleDelete 语义** ✅ —— 非 tx-aware（即时删 + 500ms 延迟删 + MQ 兜底）——auditNote 事务化后由延迟删除覆盖"失效→提交"竞态 ✓
- **C3 构造序/静态 mock** ✅ —— helper 注入位置与测试构造序对齐；publish 测试的 TS 静态 mock 变 no-op（无害）

### D/E. 工程与证据
- **D1 对称性（🟡 R1）** —— 删除链路已本地消息化但**创建侧 COMMENT 未做**（同类"丢失即永久漂移"属性）→ 已补齐（enqueue）+ 死方法 sendCommentCounterEvent 删除；VIEW/SHARE 保持 fire-and-forget（无事务/高频/近似计数——口径）
- **E1 陈旧报告** ✅ —— 全新鲜
- **E2 十一模块复跑** ✅ —— **526 全绿**（修复后）

## 发现汇总（🟡 2 处，均已修复）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| **R1** | 创建侧 COMMENT 事件未随删除链路本地消息化（不对称） | 可靠性缺口（对称性） | createComment enqueue + 死方法清理 + 测试断言 |
| **R2** | t_note_event 注释 /AUDIT 未同步 init-all ×2 | 注释漂移 | 修正 |

## 结论

17 项全部闭环：事件可靠性链（helper/格式/补发/载荷）全通过；跨模块波及面扫描无新问题（B1 home 字段逐一对齐）；**R1 对称性缺口已补齐**（COMMENT/UNCOMMENT/NOTE_DELETE 三类事务内写事件全部 at-least-once）。证据：十一模块 **526 全绿**（复跑）。
