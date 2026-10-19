---
iteration: ITER-010
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
---

# 技术方案: 内容模块生产化改造

## 方案概述

```
本地消息统一（C-010 核心）：
  LocalMessageHelper.enqueue(topic, payloadBuilder)：
    事务内：insert 占位("{}") → 回填 body（payloadBuilder(localMsgId)）→ updateById
    afterCommit：asyncSend(topic, body) → onSuccess markSent；onException 仅日志（行保留 → 补发）
  调用点：publishNote / publishDraft（FEED_TOPIC，payload 带 localMsgId）
          deleteNote ×2（SOCIAL_TOPIC:UNCOMMENT / :NOTE_DELETE）
          deleteComment ×1（SOCIAL_TOPIC:UNCOMMENT）
  FeedMessageRetryJob 泛化：补发按原始 body 发送（FEED_TOPIC 行保留 localMsgId 补丁——legacy）
```

四阶段：

| 阶段 | 内容 | 独立价值 |
|---|---|---|
| ① 基线与一致性 | REQ-1 基线修复 + REQ-5 N2/N7 小修 | 真缺口闭合 |
| ② 事件可靠性 | REQ-2/3/4 本地消息统一 + 补发泛化 | 删除链路 at-least-once |
| ③ 工程 | REQ-6 指标 + REQ-7 JPA 移除/扫描 | 水准对齐 |
| ④ 收口 | REQ-8 测试 + 十一模块回归 + §8 | 闭环 |

## REQ → 任务映射

### 阶段① 基线与一致性（REQ-1/5 / AC-1/5）

| REQ/AC | 实现任务 | 涉及文件 | 观测点 |
|--------|---------|---------|--------|
| AC-1 | T1.1 `t_note_event` DDL 补入 `sql/mysql-content-init.sql` + deploy 副本 + `sql/migration/content/V5__add_note_event.sql`（DDL 与 init-all:544 对齐） | SQL ×3 | 文件核验 |
| AC-5 | T1.2 `createComment` 补 `auditStatus == APPROVED` 双条件（错误文案对齐 share） | CommentService | 单测 |
| AC-5 | T1.3 `auditNote` 加 @Transactional + `recordNoteEvent(noteId, note.getUserId(), "AUDIT", note.getStatus(), status)` | NoteService | 单测 |

### 阶段② 事件可靠性（REQ-2/3/4 / AC-2/3/4）

| REQ/AC | 实现任务 | 涉及文件 | 观测点 |
|--------|---------|---------|--------|
| AC-3 | T2.1 新增 `LocalMessageHelper`（@Component）：`enqueue(topic, Function<Long,Object> builder)` 同事务入队 + afterCommit 发送 + markSent；`sendCounterEvent` 的删除链路调用点迁移 | `service/LocalMessageHelper.java`（新） | 单测：入队/发送/标记 |
| AC-2 | T2.2 `deleteNote`：UNCOMMENT/NOTE_DELETE 改 enqueue（替换 fire-and-forget）；`deleteComment`：UNCOMMENT 改 enqueue | NoteService/CommentService | 单测：同事务行 + 发送 |
| AC-3 | T2.3 `publishNote`/`publishDraft` 迁移到 helper（消除重复块 N6） | NoteService | 单测：等价 |
| AC-4 | T2.4 `FeedMessageRetryJob.retryFailedMessages` 泛化：body 按 JsonNode 读；`topic==FEED_TOPIC && localMsgId==null` → 补丁+重序列化，否则**原始 body 重投** | FeedMessageRetryJob | 单测：非 FEED 行重投 |

### 阶段③ 工程（REQ-6/7 / AC-6）

| REQ/AC | 实现任务 | 涉及文件 | 观测点 |
|--------|---------|---------|--------|
| AC-6 | T3.1 死信指标：retryFailedMessages 的 incrementRetry 达上限路径 + asyncSend 回调路径 → `recordFeedPushTerminal("send_retry_exceeded")` | FeedMessageRetryJob | 单测 |
| AC-6 | T3.2 指标口径：`recordFeedPush("publish"/"cache_hit"/"cache_miss"/"json_deser_fail")` → `recordAction("content", ...)`（readNoteDetail/fromJsonList/publishNote） | NoteService | 核验 |
| AC-6 | T3.3 JPA 探针移除：ContentJpaConfig/NoteJpaEntity/NoteJpaRepository/ContentJpaProbeController + pom 依赖（spring-boot-starter-data-jpa）+ yml autoconfig 排除与注释 | 多文件 | grep 零残留 + 编译 |
| AC-6 | T3.4 注释/死代码扫描（content 模块） | 全模块 | grep |

### 阶段④ 收口（REQ-8 / AC-7）

| REQ/AC | 实现任务 | 涉及文件 | 观测点 |
|--------|---------|---------|--------|
| AC-7 | T4.1 测试补强：helper 矩阵/删除链路/补发泛化/审核双条件/audit 事件 | src/test | 报告 |
| AC-7 | T4.2 **content 纳入回归（十一模块）** + 全仓编译 | 全仓 | 报告 |
| AC-7 | T4.3 §8 回写（05-内容）+ change 卡 | docs | 文档 |

## 关键设计决策

| 决策 | 结论 | 理由 |
|------|------|------|
| 事件可靠性范围 | 仅删除链路（UNCOMMENT/NOTE_DELETE）；VIEW/SHARE 保持 fire-and-forget | 删除影响一致性且无对账可愈；浏览/分享近似计数可接受（讲） |
| 机制复用 | t_local_message + 补发 Job 泛化（不新表） | 模块内已验证机制；"{}"占位+回填模式沿用 |
| helper 形态 | 入队助手统一 4+ 调用点 | N6 去重 + N1 可靠性一并解决 |
| JPA 探针 | 评估后移除 | 实验遗留（conditional 默认关，零运行价值）——"不要为了技术而技术" |

## 风险与降级

| 风险 | 影响 | 缓解 |
|------|------|------|
| 补发 Job 泛化回归 | Feed 补发 | FEED 路径单测锁定（localMsgId 补丁保留） |
| 删除链路新增行量 | 表增长 | 与 Feed 同量级；既有清理策略覆盖 |
| JPA 移除遗漏 | 启动/编译 | grep + 全仓编译 |
