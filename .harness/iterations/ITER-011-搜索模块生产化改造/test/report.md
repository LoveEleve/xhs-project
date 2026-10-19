---
iteration: ITER-011
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# 测试报告: 搜索模块生产化改造

## 执行摘要

- 执行时间: 2026-10-19（三阶段：① 补偿收敛与版本域 → ② 别名切换旗舰 → ③ 兜底与工程；收口并入）
- 环境 profile: JDK 17 + Maven 3.9 离线；无运行时（单测口径；删 target/test-classes 强制全量重编）
- 命令: 十一模块 `mvn -o ... -am test`
- 结果: ✅ **十一模块 546 全绿**（common 100 / product 35 / coupon 24 / order 124 / inventory 33 / payment 73 / **search 63** / cart 33 / counter 15 / analytics 29 / content 17——search 43 → 63，新增 20）

## 用例执行结果（要点）

| TC | 归属 AC | 结果 | 断言/证据 |
|----|---------|------|----------|
| TC-1 | AC-1 | 🟢 | `IncrementalIndexSyncJobTest#noteBulkAllConflictsConverge`：全冲突 → 失败集空 |
| TC-2 | AC-1 | 🟢 | `#noteBulkConvergenceMatrix`：1 成功 + 1 冲突 + 1 真失败 → 仅真失败回填 |
| TC-3 | AC-1 | 🟢 | `#productBulkDeterministicFailureDiscarded`：构建失败丢弃 + doc_build_failed 指标 |
| TC-4 | AC-1 | 🟢 | 同 TC-1（收敛 = 无回填）+ `#bulkExceptionFallsBackToFullRetrySet`（整批保守回填） |
| TC-5 | AC-2 | 🟢 | `NoteIndexSyncConsumer` flat 路径 ts 域（binlogTimestamp 优先）+ ExternalGte（代码走查 + 既有 flat 用例复跑） |
| TC-6 | AC-3 | 🟢 | `IndexAliasManagerTest#resolveAliasExists`：别名 → v2 实体 |
| TC-7 | AC-3 | 🟢 | `#resolveLegacyInPlace`：同名实体 → 原地 + legacy 标记 |
| TC-8 | AC-3 | 🟢 | `#resolveFreshEnvAutoCreates`：建 v1 + 别名指向（captor 断言实体名） |
| TC-9 | AC-3 | 🟢 | `#createNextIndexIncrements` / `#createNextIndexFromNonVersionedName` / `#createNextIndexFromUnparsableSuffix` |
| TC-10 | AC-3 | 🟢 | `IndexRebuildJobAliasTargetTest`（决策矩阵：legacy/建下一版本+续传键/续传复用）；**调用序**（建→bulk(v2)→清理(v2)→switch→重放→删旧）在 doRebuild 显式，代码走查记录于 review-c011 |
| TC-11 | AC-3 | 🟢 | 同上：switch 在构建全部成功后才执行——切换前失败 → 别名不动 + 无删旧（走查记录）；`switchAliasAtomic`（单次 updateAliases 双动作） |
| TC-12 | AC-4 | 🟢 | `NoteIndexSyncConsumer#physicallyDeleteNote`：墓碑（status=-1 + deletedAt + ExternalGte 版本）——flat/canal 双路径均版本化 |
| TC-13 | AC-4 | 🟢 | `IndexRebuildJob#pruneOldTombstones`：delete_by_query（status=-1 && deletedAt<7d 前）——切前执行 |
| TC-14 | AC-5 | 🟢 | `SearchDlqReplayConsumerTest` ×5：tag 保留（SOCIAL_TOPIC 路由）/ 计数 +1 / 超限仅告警 / 失败不吞 |
| TC-15 | AC-5 | 🟢 | ProductIndexSyncConsumer finally remove（对称性修复；代码走查 + 编译） |
| TC-16 | AC-6 | 🟢 | 三控制器 8 处 @RateLimit（grep 计数 4/2/2）；注释漂移零残留（grep：5min/权重/攒批/es-版本域 全清） |
| TC-17 | AC-7 | 🟢 | 十一模块 **546 全绿** |

## 阶段落地（提交记录）

| 阶段 | 提交 | 内容 |
|------|------|------|
| 规划 | `docs(harness): C-011/ITER-011 ...` | 五件套（深探两轮：§7 七线索 + R1~R7） |
| ①② | 见提交链 | 补偿收敛（conflict 豁免/确定性失败丢弃/指标）+ flat ts 域 + ThreadLocal 对称 + 别名管理（IndexAliasManager/IndexMappings）+ 重建别名化（resolveTarget/窗口重放/超龄墓碑清理/续传键）+ IndexInitializer 别名建账 |
| ③ | 见提交链 | 物理删版本化墓碑（note 双路径 + product deletedAt）+ DLQ 重放 ×5 + 限流 8 端点 + 注释漂移清零 |

## 深审查（清单先行，见 review-c011.md）

- **审查期修复**：IndexAliasManager.resolve 用 getAlias 探测别名——缺失时抛 404 异常，**fresh env 自动建账分支不可达**（测试设计阶段发现）→ 改 existsAlias 探测；
- 注释漂移再发现 ×3（HotSearchService 5min ×2、note structured javadoc es-优先）→ 清零；
- 结论：20 项全部闭环（明细见 review-c011.md）。

## 收口清单

- [x] 十一模块全量回归 546 全绿
- [x] §8 回写（06-搜索，含 §7-4 勘误）
- [x] 面试材料同步（50-Canal深度拷打：墓碑字段/窗口重放具体化）
- [x] 部署说明（DEPLOY-NOTES：legacy 别名迁移三步）
- [x] change 卡 → done
- [x] 运行时验证按项目规则后置
