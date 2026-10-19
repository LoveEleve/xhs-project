---
iteration: ITER-010
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
---

# 测试用例: 内容模块生产化改造

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | — | 基线 SQL ×3 | 含 t_note_event DDL（与 init-all 对齐） | 手工 |
| TC-2 | AC-2 | deleteComment 正常 | 删除评论 | 同事务本地消息行（SOCIAL_TOPIC:UNCOMMENT，带 count）+ afterCommit 发送 + markSent | 是 |
| TC-3 | AC-2 | deleteNote 正常 | 删除笔记 | 两行（UNCOMMENT 带 count + NOTE_DELETE）+ 发送 | 是 |
| TC-4 | AC-2 | 发送失败（callback onException） | 同上 | 行保留 status=0（补发接管） | 是 |
| TC-5 | AC-3 | publishNote/publishDraft | 发布 | helper 入队（body 含 localMsgId）+ 发送 + markSent | 是 |
| TC-6 | AC-4 | 非 FEED 行（body=Map JSON） | 补发扫描 | 按原始 body 重投（不强制 NotePublishEvent） | 是 |
| TC-7 | AC-4 | FEED legacy 行（localMsgId null） | 补发扫描 | 补丁 localMsgId 后重投 | 是 |
| TC-8 | AC-5 | 笔记 PUBLISHED+REJECTED | createComment | 拒绝（文案对齐 share） | 是 |
| TC-9 | AC-5 | auditNote 通过/驳回 | 审核 | 记录 AUDIT 事件（同事务）+ 状态更新 + 缓存失效 | 是 |
| TC-10 | AC-6 | 补发达上限（两条路径） | incrementRetry 达上限 | recordFeedPushTerminal("send_retry_exceeded") | 是 |
| TC-11 | AC-6 | — | 缓存/发布/序列化打点 | recordAction("content", ...)（feed.push.total 不再混入） | 是 |
| TC-12 | AC-6 | — | JPA 探针 grep | 零残留 + 全仓编译 | 手工 |
| TC-13 | AC-7 | 全仓 | 十一模块重编测试 | 全绿（content 首次纳入） | 是 |
