---
iteration: ITER-010
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
---

# 测试设计: 内容模块生产化改造

## 验证策略

- **单测为主**（无运行时；删 target/test-classes 强制全量重编）：
  - **helper 矩阵**：入队（同事务行 + body 回填）/afterCommit 发送/markSent/发送失败行保留
  - **删除链路**：deleteNote/deleteComment 入队断言（topic/body/count）+ 不再 fire-and-forget
  - **补发泛化**：非 FEED 行原始 body 重投；FEED legacy 行 localMsgId 补丁
  - **一致性**：createComment 拒绝非 APPROVED；auditNote 记录 AUDIT 事件
  - **指标**：死信指标（两条路径）；口径修正核验
  - **JPA 移除**：grep 零残留 + 编译
- 集成/E2E：不做。

## AC → 测试方法映射

| AC | 测试方法 | 层级 | 观测方式 |
|----|---------|------|---------|
| AC-1 | SQL 文件核验 | 手工 | grep |
| AC-2 | 删除链路入队单测 | 单测 | insertOutbox 断言 + afterCommit 发送 |
| AC-3 | helper 单测 + 迁移等价 | 单测 | 四调用点断言 |
| AC-4 | 补发泛化单测 | 单测 | 重投 body 断言 |
| AC-5 | 双条件/审核事件单测 | 单测 | 异常/insert 断言 |
| AC-6 | 指标/JPA 核验 | 单测+手工 | 指标调用 + grep |
| AC-7 | 十一模块回归 | 单测 | 全绿 |

## 动态事实接入

| 事实 | 来源 | 用途 |
|------|------|------|
| 本地消息模式（"{}"占位/回填/afterCommit/markSent） | NoteService:107-156（已核） | helper 基准 |
| 补发 Job 强类型读 NotePublishEvent | FeedMessageRetryJob:92（已核） | 泛化点 |
| t_note_event DDL | init-all:544（已核） | 基线补齐来源 |
| JPA 三件套 conditional | ContentJpaConfig（已核） | 移除范围 |
