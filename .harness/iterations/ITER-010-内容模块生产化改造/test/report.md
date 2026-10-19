---
iteration: ITER-010
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
---

# 测试报告: 内容模块生产化改造

## 执行摘要

- 执行时间: 2026-10-18（三阶段：① 基线与一致性 → ② 事件可靠性 → ③ 工程；收口并入）
- 环境 profile: JDK 17 + Maven 3.9 离线；无运行时（单测口径；删 target/test-classes 强制全量重编）
- 命令: 十一模块 `mvn -o ... -am test` + 全仓 `mvn -o -q compile`
- 结果: ✅ **十一模块 526 全绿**（common 100 / product 35 / coupon 24 / order 124 / inventory 33 / payment 73 / search 43 / cart 33 / counter 15 / analytics 29 / **content 17**——content 首次纳入回归集）

## 用例执行结果（要点）

| TC | 归属 AC | 结果 | 断言/证据 |
|----|---------|------|----------|
| TC-1 | AC-1 | 🟢 | 基线 SQL ×3 含 t_note_event（grep 核验） |
| TC-2 | AC-2 | 🟢 | `CommentServiceTest#deleteCommentSuccess`：UNCOMMENT 经 helper 入队 |
| TC-3 | AC-2 | 🟢 | `NoteServiceTest#deleteNoteSuccess`：UNCOMMENT + NOTE_DELETE 双入队 |
| TC-4 | AC-2 | 🟢 | `LocalMessageHelperTest#enqueueSendFailureKeepsRow`：发送异常不抛 + 行保留 |
| TC-5 | AC-3 | 🟢 | `NoteServiceTest#publishNoteSuccess`：FEED_TOPIC 经 helper 入队 |
| TC-6 | AC-4 | 🟢 | `FeedMessageRetryJobTest#retryNonFeedRowSendsRawBody`：非 FEED 按原始 body 重投 |
| TC-7 | AC-4 | 🟢 | `#retryFeedLegacyRowPatchesLocalMsgId`：FEED legacy 补丁 localMsgId |
| TC-8 | AC-5 | 🟢 | `CommentServiceTest#addComment_rejectedNote_forbidden`：已驳回笔记拒绝评论 |
| TC-9 | AC-5 | 🟢 | `NoteServiceTest#auditNoteRecordsEvent`：AUDIT 事件 + 缓存失效 |
| TC-10 | AC-6 | 🟢 | `FeedMessageRetryJobTest#retryDeadLetterRecordsMetric`：死信指标 |
| TC-11 | AC-6 | 🟢 | 指标口径修正（recordAction("content",...)——grep 零 recordFeedPush 残留） |
| TC-12 | AC-6 | 🟢 | JPA 探针零残留（grep + 全仓编译 + 网关无引用） |
| TC-13 | AC-7 | 🟢 | 十一模块 526 全绿 |

## 阶段落地（提交记录）

| 阶段 | 提交 | 内容 |
|------|------|------|
| 规划 | `docs(harness): C-010/ITER-010 ...` | 五件套（6 线索裁决含 §7-3 翻案） |
| ①②③ | 见提交链 | 基线修复 + LocalMessageHelper + 删除链路迁移 + 补发泛化 + FEED 过滤防御 + 死信指标 + 指标口径 + JPA 移除 |

## 深审查（实施期要点）

- **实施期关键防御（代码审查发现）**：推送补偿扫描必须加 `topic='FEED_TOPIC'` 过滤——否则计数事件行（push_status 恒 0）会被误扫入 Feed 推送补偿（readValue NotePublishEvent 失败 → 永久重扫 → 误置终态）；已在 SQL 落位。
- 测试构建修正 ×4（构造序/asyncSend 重载歧义/Message 捕获/MP lambda 缓存）——测试锁定后全绿。

## 收口清单

- [x] 十一模块全量回归 526 全绿 + 全仓编译
- [x] §8 回写（05-内容）
- [x] change 卡 → done
- [x] 运行时验证按项目规则后置
