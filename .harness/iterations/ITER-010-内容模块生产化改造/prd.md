---
iteration: ITER-010
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
---

# PRD: 内容模块生产化改造

## 背景与目标

**深探索结论**（代码级证据：NoteService 727 / CommentService 509 / FeedMessageRetryJob 240 / LocalMessageMapper / CacheEvictConsumer / JPA 三件套全读）：

- **🔴 t_note_event 基线缺口**：init-all ✓（544 行 `USE my_xhs_content`）但 mysql-content-init ✗ / migration ✗ / deploy 副本 ✗ → 基线初始化环境**笔记事件静默丢失**（catch-log）；
- **🔴 N1 计数事件无可靠性层**：`sendCounterEvent`/`sendCommentCounterEvent` = asyncSend + 日志回调（无 outbox/重试）——UNCOMMENT（删除补偿）/NOTE_DELETE（Feed 清理）丢失即静默且无对账可愈（counter 无评论数对账）；
- **🟡 N2**：createComment 不校验 audit_status（详情/分享双条件）→ 已驳回笔记仍可评论；
- **🟡 N3**：发送侧死信（status=3）无指标/告警（补偿侧有）；
- **🟡 N4**：指标混用（publish/cache_hit/cache_miss/json_deser_fail → feed.push.total）；
- **🟡 N5**：测试 9 测 / 3400 行（Feed 链/事件/DFA/消费者全无覆盖）；
- **🟡 N6**：publishNote/publishDraft 本地消息块重复 ×2；
- **🟡 N7**：auditNote 无审核事件记录；
- **📝 N8**：CacheEvictConsumer DLQ 无重放（脏缓存到 TTL，可接受）；
- **翻案**：§7-3 t_local_message **无缺口**（分片表前缀匹配误读）。

## 非目标

审核队列完整实现（讲）；风控；home 侧 Feed 消费者（Phase 4）；search（C-011）。

## 需求（REQ）

| ID | 需求描述 | 优先级 |
|----|---------|--------|
| REQ-1 | **基线修复**：`t_note_event` 补入 mysql-content-init ×2 + migration V5 | P0 |
| REQ-2 | **删除链路事件可靠性（N1）**：`deleteNote`/`deleteComment` 的 UNCOMMENT/NOTE_DELETE 改**同事务本地消息 + afterCommit 发送 + markSent**（复用 t_local_message；at-least-once）；VIEW/SHARE 保持 fire-and-forget（口径：近似计数） | P0 |
| REQ-3 | **本地消息发送统一（N6）**：提取入队助手（insert 占位 → 回填 body → afterCommit 发送 → markSent），publishNote/publishDraft/删除链路共用 | P1 |
| REQ-4 | **补发 Job 泛化（N1 配套）**：FeedMessageRetryJob 补发按**原始 body** 发送（非 NotePublishEvent 强类型）；FEED_TOPIC 保留 localMsgId 补丁（legacy 行） | P0 |
| REQ-5 | **一致性小修（N2/N7）**：createComment 补 audit_status 双条件；auditNote 补审核事件记录（+@Transactional） | P1 |
| REQ-6 | **可观测（N3/N4）**：发送侧死信指标；feed.push.total 口径拆分（缓存/序列化事件改 recordAction("content",...)） | P1 |
| REQ-7 | **工程收口**：JPA 探针评估后移除（三件套+controller+pom 依赖+yml）；注释/死代码扫描 | P1 |
| REQ-8 | **收口**：测试补强（N5）+ **content 纳入回归（十一模块）** + §8 回写 | P0 |

## 验收标准（AC）

| ID | 归属 | 验收标准 | 来源 |
|----|------|---------|------|
| AC-1 | REQ-1 | 基线 SQL（×2 + migration）含 t_note_event；按基线初始化环境事件可落库 | 深探 |
| AC-2 | REQ-2 | 删除链路事件走本地消息（同事务入队 + 发送成功标记）；发送失败行保留 → 补发 Job 重投 | 深探 N1 |
| AC-3 | REQ-3 | 入队助手覆盖 4 个调用点（publish/publishDraft/deleteNote×2/deleteComment）；行为与现状等价 | 深探 N6 |
| AC-4 | REQ-4 | 补发 Job 对非 FEED_TOPIC 行按原始 body 重投；FEED legacy 行 localMsgId 补丁保留 | 深探 N1 |
| AC-5 | REQ-5 | createComment 拒绝"PUBLISHED+非 APPROVED"；auditNote 记录 AUDIT 事件（同事务） | 深探 N2/N7 |
| AC-6 | REQ-6/7 | 死信指标落位；指标口径修正；JPA 探针零残留（编译+回归） | 深探 N3/N4 |
| AC-7 | REQ-8 | 十一模块全绿 + §8 回写 | 回归 |

## 边界情况

- 删除链路事件丢失风险：本地消息行保留 → 补发（3 次 → 死信 + 指标）✓
- VIEW/SHARE 丢失：可接受（讲：近似计数）
- 本地消息与 Feed 共用表/Job：topic 字段区分 ✓
- JPA 移除后 content.jpa.enabled 配置失效（yml 注释清理）

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 删除链路新增 1-2 行本地消息（同事务，与 Feed 同量级） |
| 可靠性 | 删除链路事件 at-least-once；死信可见 |
| 可观测 | 指标口径零混用 |

## 依赖与影响面

- 模块: my-xhs-content（主体）
- MQ: 无新 topic；补发 Job 泛化（既有表/Job）
- DDL: 无（SQL 基线文件补齐）
