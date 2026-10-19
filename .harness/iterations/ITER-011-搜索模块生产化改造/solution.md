---
iteration: ITER-011
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
---

# 技术方案: 搜索模块生产化改造

## 方案概述

```
别名切换重建（旗舰）：
  IndexAliasManager：
    resolveWriteIndex(alias)  → 别名当前指向的实体索引；legacy（实体=别名名）→ 返回其名（回退标记）
    createNextIndex(alias, mapping) → {alias}_v{n+1}（mapping 复用 IndexInitializer 常量）
    switchAlias(alias, from, to) → ES updateAliases 原子（remove from + add to）
    deleteIndex(name)
  重建流程（别名模式）：
    建 v2 → 扫描写 v2（断点续传 status 记 target）→ v2 内死文档/超龄墓碑清理
    → 原子切换 alias→v2 → 窗口重放（DB updated_at >= rebuildStart → bulk 写 alias）
    → 删 v1；失败：别名不动（线上仍 v1），下次续传同一 target
  补偿收敛：
    bulk 逐项：version_conflict → 成功豁免；真失败 → 收集 id；仅失败 id 回填失败集
    文档构建失败（确定性）→ 丢弃 + 告警（不回填）
```

三阶段：

| 阶段 | 内容 | 独立价值 |
|---|---|---|
| ① 收敛与版本 | REQ-1（补偿收敛）+ REQ-2（flat 版本域）+ R2 | 消除永久循环/域不一致 |
| ② 别名切换 | REQ-3（旗舰） | 零中断重建 |
| ③ 兜底与工程 | REQ-4 墓碑 + REQ-5 DLQ/ThreadLocal + REQ-6 限流/注释 | 水准对齐 |
| ④ 收口 | REQ-7 测试/回归/回写 | 闭环 |

## REQ → 任务映射

### 阶段① 收敛与版本（REQ-1/2 / AC-1/2）

| REQ/AC | 实现任务 | 涉及文件 | 观测点 |
|--------|---------|---------|--------|
| AC-1 | T1.1 `IncrementalIndexSyncJob.bulkIndexNotes/bulkIndexProducts` 重构：返回逐项失败 id（version_conflict 豁免）；调用方仅回填失败 id；构建失败（商品）丢弃+告警不回填 | `job/IncrementalIndexSyncJob.java` | 单测：豁免/逐项/收敛 |
| AC-2 | T1.2 `NoteIndexSyncConsumer.indexNoteFromFlat`：extractVersion ts 域优先 + ExternalGte | `consumer/NoteIndexSyncConsumer.java` | 单测断言 |
| AC-1 | T1.3 `ProductIndexSyncConsumer` finally 补 `currentSpuId.remove()`（R2） | `consumer/ProductIndexSyncConsumer.java` | 单测/代码核验 |

### 阶段② 别名切换（REQ-3 / AC-3）

| REQ/AC | 实现任务 | 涉及文件 | 观测点 |
|--------|---------|---------|--------|
| AC-3 | T2.1 `IndexAliasManager`（新）：resolve/next/switch/delete/legacy 判定；mapping 复用（IndexInitializer 常量提为包内可见或抽 `IndexMappings`） | `config/IndexAliasManager.java`（新） | 单测矩阵 |
| AC-3 | T2.2 `IndexRebuildJob`：别名模式全流程（建 v2 → 写 v2 → 清理 → 切换 → 窗口重放 → 删 v1）；legacy 回退原地；断点续传 status 加 target；窗口重放方法（updated_at >= start → bulk alias） | `job/IndexRebuildJob.java` | 单测：流程/回退 |
| AC-3 | T2.3 `IndexInitializer`：新部署直接建别名模型（{alias}_v1 + alias） | `config/IndexInitializer.java` | 单测/代码核验 |

### 阶段③ 兜底与工程（REQ-4/5/6 / AC-4/5/6）

| REQ/AC | 实现任务 | 涉及文件 | 观测点 |
|--------|---------|---------|--------|
| AC-4 | T3.1 物理删墓碑：note `physicallyDeleteNote(noteId, version)` → 墓碑（status=-1 + deletedAt）；product DELETE 事件墓碑加 deletedAt；rebuild 增加 `pruneOldTombstones`（delete_by_query status=-1 && deletedAt < now-7d） | 两消费者 + rebuild | 单测/代码核验 |
| AC-5 | T3.2 `SearchDlqReplayConsumer`（新）：5 组 `%DLQ%`（note-index-sync / product-index-sync / counter-es-sync / recommend-behavior / seller-sub-order-index-sync）→ 源 topic（保留 tag）；attempt≤3 | `consumer/SearchDlqReplayConsumer.java`（新） | 单测 |
| AC-6 | T3.3 限流：SearchController（note/product/suggest/hot）+ RecommendController（feed/similar）+ SellerOrderSearchController；注释漂移 ×3 修正 | 三控制器 + 两 Job + RecommendService | 单测/核验 |

### 阶段④ 收口（REQ-7 / AC-7）

| REQ/AC | 实现任务 | 涉及文件 | 观测点 |
|--------|---------|---------|--------|
| AC-7 | T4.1 测试补强 + 十一模块回归 | src/test | 报告 |
| AC-7 | T4.2 §8 回写（06-搜索，含 §7-4 勘误）+ 面试材料（50-Canal 深度拷打）同步 + 部署说明（legacy 迁移）+ change 卡 | docs | 文档 |

## 关键设计决策

| 决策 | 结论 | 理由 |
|------|------|------|
| 补偿收敛 | version_conflict=成功豁免 + 逐项回填 | 对齐 rebuild 的既有豁免；根除永久循环 |
| 版本域 | 全链路 ts/updated_at 毫秒域 | P1-4/T-088 已定域，flat 路径补齐 |
| 重建模型 | 别名切换（alias=配置名，实体 v{n}） | 读路径透明；零中断；失败不动线上 |
| 窗口重放 | updated_at >= rebuildStart → bulk alias | 覆盖"重建期间更新"；ExternalGte 幂等 |
| 物理删 | 墓碑（deletedAt）+ 超龄清理 | 防复活（版本墓碑）+ 防泄漏（清理）两全 |
| legacy | 回退原地 + 部署说明 | 无运行时；兼容旧部署 |

## 风险与降级

| 风险 | 影响 | 缓解 |
|------|------|------|
| 别名切换逻辑回归 | 搜索不可用 | 单测矩阵 + 失败不动线上（切换前任何失败都不影响 v1） |
| 窗口重放漏 | 个别更新滞后 | updated_at 索引扫描 + 次日全量重建兜底 |
| 墓碑清理误删 | 数据丢失 | 仅删 status=-1 且 deletedAt 超 7 天 |
