---
id: C-010
slug: 内容模块生产化改造
status: done
created: 2026-10-18
updated: 2026-10-18
iteration: ITER-010     # 机器可读迭代协议：.harness/iterations/ITER-010-内容模块生产化改造/
relations:
  relates_to:
    - slug: docs/学习/模块对照/05-内容
      reason: §7 六条线索的收口（含 §7-3 翻案：t_local_message 无缺口）
    - slug: docs/学习/模块对照/00-执行方案
      reason: Phase 2 内容域第二站（主战场：笔记/评论/Feed/DFA）
  depends_on: []
---

# C-010 内容模块生产化改造

> **完成记录（2026-10-18）**：三阶段全部落地（基线修复 + 删除链路事件本地消息化 + 工程收口）；
> 证据：**十一模块 526 全绿**（content 17 首次纳入回归集）+ 全仓编译；
> 详见 `.harness/iterations/ITER-010-内容模块生产化改造/test/report.md`。§8 回写：05-内容.md。
> 运行时验证（真实 MQ 补发/补偿）按项目规则后置。

> **立项（2026-10-18）**：内容域主战场。**深度探索（代码优先）**：§7 六条线索逐条核实 + 同型比对（C-007/8/9 家族）：
> 核心 = **t_note_event 基线缺口（真）+ 计数事件无可靠性层（N1：UNCOMMENT/NOTE_DELETE 丢失即静默）**；
> 翻案：**§7-3 t_local_message 无缺口**（init-all 527 行 `USE my_xhs_content` 正确；"17" 为分片表前缀匹配误读）。
> Feed 双段兜底（本地消息同事务 + 补发 30s + 推送补偿 60s）核实**设计完整**（home 侧 ZADD 幂等兜底）。
> 规划详见 `.harness/iterations/ITER-010-内容模块生产化改造/`。

## 用户故事

作为内容域开发者，我想要把笔记/评论/Feed 链路的"基线缺口 + 事件静默丢失 + 工程遗留"清理到生产标准，
并让内容域与已改造模块（交易域 + 计数域）的水准对齐，以便学习的是最终形态。

## 非目标（Out of Scope）

- 审核队列的完整实现（应然差距——讲：发布前审核队列/申诉恢复）；
- 设备/行为风控（简化——讲）；
- home 侧 FeedPushConsumer/feedCleanupJob 的深挖（Phase 4 支撑域）；
- search 域（C-011 另立）。

## 验收标准

- AC-1 ~ AC-7 见 `.harness/iterations/ITER-010-内容模块生产化改造/prd.md`（本变更走迭代协议）

## 边界情况

- 基线初始化环境 → t_note_event 补齐后事件流水不再静默丢失（catch-log 语义保留：不阻塞发布）
- 删除链路（deleteNote/deleteComment）的 UNCOMMENT/NOTE_DELETE → 同事务本地消息 + 补发（at-least-once）
- VIEW/SHARE → 保持 fire-and-forget（近似计数可接受——讲）
- 已驳回笔记（PUBLISHED+REJECTED）→ 评论入口补齐双条件
- JPA 探针移除 → 默认装配零变化（本就 conditional 关闭）

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 本地消息复用现有表/补发 Job（无新机制）；评论创建双条件校验为单字段判断 |
| 可靠性 | 删除链路事件 at-least-once；发送侧死信指标可见 |
| 安全 | 无 |
| 可观测 | 指标口径修正（feed.push.total 不再混入缓存事件）；死信指标补齐 |

## 设计约束

- 复用模块内已验证机制（t_local_message + FeedMessageRetryJob 泛化），不引入新表；
- 与 C-007/8/9 口径对齐：部分失败窗口闭合、注释零漂移、测试矩阵补强；
- 不改文章正文（走 §8 回写）。

## 契约影响

- REST: 无新增
- MQ: 无新 topic（本地消息承载既有事件）
- 数据模型: 无 DDL（t_note_event 补基线 SQL 文件；t_local_message 复用）

## 影响面

- 模块/服务: my-xhs-content（主体）；my-xhs-common（如需泛化工具）
- wiki: 无（归档时回流）

## 测试策略

- 先写失败测试：本地消息入队/发送/标记（删除链路）；评论审核双条件；补发 Job 泛化（非 FEED body）；死信指标
- 回归：**content 首次纳入回归集（十模块 → 十一模块）**
