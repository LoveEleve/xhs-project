---
iteration: ITER-011
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
---

# PRD: 搜索模块生产化改造

## 背景与目标

**深探索结论**（两轮补课，代码级证据：IndexRebuildJob 582 / IncrementalIndexSyncJob 376 / NoteIndexSyncConsumer 363 / ProductIndexSyncConsumer 392 / 三控制器 / 5 消费者 / HotSearchService / RecommendComputeJob 全读 + 全量 echo 比对）：

- **🔴 R1 补偿不收敛**：增量补偿的 bulk 处理**未豁免 version_conflict**（rebuild 已豁免并注释"关键配套"）→ "ES 已是更新版本"的文档计为失败 → 失败集**永久循环**（每分钟重投 + re-SADD 续期 1h 永不过期）；且 `ids.contains` 回填判断恒真（成功项也回填）；
- **🔴 R3 flat 版本域不一致**：note flat 路径 `es`-优先（小整数）+ 严格 `External`（product flat 已用 timestamp+Gte 正确）——P1-4 修复不彻底；
- **🔴 别名切换（§7-1）**：原地重建（重建期"半新半旧"+死文档延迟清理）；
- 🟡 R2：ProductIndexSyncConsumer 缺 `currentSpuId.remove()`（note 侧有）→ 线程复用残留污染失败集；
- 🟡 R4：note 物理删无墓碑 vs product 走墓碑——策略不一致（泄漏/复活两头顾）；
- 🟡 R5：确定性失败（商品文档构建失败）回填失败集 → 同 R1 永久循环；
- 🟡 R6：注释漂移 ×3（增量补偿"每 5 分钟"×2 / 推荐权重 / BehaviorReport"攒批"）；
- 🟡 R7：DLQ 重放缺失 ×5 消费组；R8：三控制器零限流；
- ❌ §7-4 勘误：推荐实为四层管线（仅权重硬编码 + javadoc 权重过时）。

## 非目标

推荐算法升级；ES 集群化；跨库边界重构。

## 需求（REQ）

| ID | 需求描述 | 优先级 |
|----|---------|--------|
| REQ-1 | **补偿收敛（R1/R5）**：增量补偿 bulk 豁免 version_conflict（对齐 rebuild，视为成功）+ **逐项判定失败集回填**（真失败才回填）；确定性失败（文档构建失败）丢弃 + 告警（不回填） | P0 |
| REQ-2 | **flat 版本域对齐（R3）**：note flat 路径 ts 域优先 + ExternalGte（对齐 product flat/canal） | P0 |
| REQ-3 | **别名切换重建（旗舰）**：`IndexAliasManager`（resolve 当前/创建下一版本/原子切换/删旧/legacy 回退）+ 重建写新索引 → 原子切换 → **窗口重放**（updated_at >= rebuildStart）→ 删旧；断点续传携带 targetIndex | P0 |
| REQ-4 | **墓碑一致性（R4）**：note/product 物理删 → 版本化墓碑（status=-1 + deletedAt）→ 重建时超龄墓碑清理（delete_by_query）——防复活 + 防泄漏两全 | P1 |
| REQ-5 | **兜底（R7/R2）**：DLQ 重放 ×5 消费组（保留 tag）；ProductIndexSyncConsumer 补 ThreadLocal remove | P1 |
| REQ-6 | **限流与注释（R8/R6）**：三控制器公开端点 @RateLimit；注释漂移 ×3 修正 | P1 |
| REQ-7 | **收口**：测试补强 + §8 回写（含 §7-4 勘误）+ 面试材料同步 + 十一模块回归 | P0 |

## 验收标准（AC）

| ID | 归属 | 验收标准 | 来源 |
|----|------|---------|------|
| AC-1 | REQ-1 | version_conflict 计为成功；仅真失败回填（逐项）；确定性失败丢弃+告警——补偿多轮收敛（单测） | 深探 R1/R5 |
| AC-2 | REQ-2 | note flat 版本 ts 域 + ExternalGte（单测断言） | 深探 R3 |
| AC-3 | REQ-3 | 别名管理全矩阵（resolve/next/switch/legacy 回退/删旧）；重建流程 = 建 v2 → 写 v2 → 切 → 重放 → 删 v1（单测） | 深探旗舰 |
| AC-4 | REQ-4 | 物理删墓碑（版本+deletedAt）；超龄清理（delete_by_query 断言） | 深探 R4 |
| AC-5 | REQ-5 | DLQ 重投 ×5（tag 保留）；ThreadLocal 清理 | 深探 R7/R2 |
| AC-6 | REQ-6 | 限流落位；注释零漂移 | 深探 R8/R6 |
| AC-7 | REQ-7 | 十一模块全绿 + §8/材料回写 | 回归 |

## 边界情况

- legacy（索引名=配置名，无别名）→ 重建回退原地模式 + 告警（部署说明迁移）
- 重建失败 → 别名不切换；下次续传同一 target
- 窗口重放与并发增量 → ExternalGte 幂等
- DLQ 重放 × 幂等（external version / INSERT IGNORE）→ 安全

## 非功能需求

| 维度 | 指标 |
|------|------|
| 可靠性 | 补偿收敛；重建零中断；物理删防复活 |
| 可观测 | 切换/重放/清理日志与指标 |
| 安全 | 公开端点限流 |

## 依赖与影响面

- 模块: my-xhs-search（主体）
- ES: 别名模型（legacy 迁移说明）；MQ: DLQ ×5；DDL: 无
