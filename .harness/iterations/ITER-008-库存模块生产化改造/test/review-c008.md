---
iteration: ITER-008
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 用途：C-008 深审查（实施期查漏补缺）——键模型重构的语义保全与一致性核验
---

# 深度审查: C-008（库存模块生产化改造）

## 审查范围

C-008 全部落地改动：4 消费者（Deduct/OrderTransaction/OrderTermination/DlqReplay 新）、5 Lua（4 重写 + refund_restore 新 + reconcile_buckets 删）、InventoryService（全量重写）、5 Job、Mapper、DTO ×6、order 侧 2 方法、product 侧 1 DTO、DDL、yml、测试 ×7 文件。

## 审查清单（逐项校验结论）

> 结论标记：✅ 通过 / 🟡 发现并修复 / 📝 记录（边界）

### A. 部分失败/幂等（三场景矩阵）

- **补发（新 msgId）** ✅ —— L2 逻辑事件幂等（inbox PK=(order,sku,action)）拦截；单测 `duplicateApplicationSkipped`
- **重投（同 msgId）** ✅ —— 完成态标记（isProcessed 只读拦截）/ inbox 兜底；单测覆盖
- **崩溃（kill -9）** ✅ —— L1 完成后置标记（重投 → Lua 逐 SKU 幂等补扣）；L2 事务回滚无记录（重投重新应用）；退款回补同槽原子脚本（delta=0 幂等）；终止释放幂等（墓碑+无记录即返回）
- **F12 标记窗口** ✅ —— 任一预期 SKU 缺失即写标记；单测 `confirmDeduct_partialMissing_writesConfirmedMarker`
- **items 缺失防御** ✅ —— confirm 写标记（保守不释放）/ release 仅告警；单测 `releaseStock_missingItems_conservativeSkip`

### B. 键模型一致性（集群正确性）

- **全模块键格式一致** ✅ —— grep 核验：Service/TimeoutJob/CompensationJob/ReconcileJob/CacheEvict 五处键构造逐一对齐（`inventory:{skuId}:stock` / `:prededuct:{orderId}` / `:terminated:{orderId}` / `:prededuct:index` / `:refund:{orderId}`）
- **每脚本单 slot** ✅ —— 单测 `preDeductAllKeysSameSlot`（KEYS 全部 `inventory:{skuId}` 前缀）+ 各脚本 KEYS 注释与 Java 传参逐一对过
- **索引键一致性（🟡 真 bug 抓到）** —— R1：TimeoutJob 索引键曾用 `inventory:prededuct:index:{skuId}`（tag 在尾），与 Service 写入 `inventory:{skuId}:prededuct:index` **不一致** → 扫描永远匹配不到（超时释放整体失效）→ 测试 `expiredRecord_withoutConfirmedMarker_releases` 抓到 → 统一 tag-first

### C. 语义保全（历史修复的等价性）

- **F-037（来源桶释放）** ✅ —— 随分桶消亡（单键无"扣错桶"）；补偿测试断言按 (sku,order) 回退
- **N10（扩容暂停窗口）** ✅ —— 随 resize 消亡（无 pause/resize 路径）；无测试残留
- **T-073（雪花取模精度）** ✅ —— 随路由消亡（无桶路由）
- **T-124（bucket:count tag）** ✅ —— 键整体收敛（bucket:count 删除）
- **04B 墓碑** ✅ —— per-(sku,order) 原子「检查+总是写墓碑」语义保留（mark_terminated.lua 重写）；`preDeduct_terminatedTombstone_skip` + `releaseStock_noRecord_writesTombstone` 锁定
- **04B 确认标记** ✅ —— 保留 + F12 强化（任一缺失）
- **N7（不盲目回填）** ✅ —— getStock 保留（未命中读 MySQL 不回填）

### D. 跨模块契约

- **items 贯通** ✅ —— order `doConfirmInventoryDeduct`/`doReleaseInventory`（Map 载荷）→ inventory DTO；终止载荷 items 解析（兼容字符串形态 Long）
- **product 字段清理** ✅ —— InitRequest.bucketCount 删（原本就未 set）
- **兼容性** 📝 —— 单部署同发（无灰度需求）；旧格式消息（无 items）走防御路径

### E. 测试与编译证据

- **八模块 457 全绿**（common 93 / product 35 / coupon 24 / order 124 / **inventory 32**（基线 20，+12）/ cart 33 / search 43 / payment 73）+ 全仓编译零错误
- **实施期抓到的真实缺陷**：R1（索引键不一致，见 B）——测试先行策略生效

## 发现汇总（🟡 5 处，均已修复）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| R1 | TimeoutJob 索引键格式与 Service 不一致（扫描失效） | **真 bug** | 统一 tag-first（测试抓到） |
| R2 | reconcileLockedStock 仍按 Hash 扫描（旧模型） | 重构遗漏 | 单键化重写（String GET + skuId 解析） |
| R3 | HotSkuDetector.java 未删（计划删除项遗漏） | 清理遗漏 | 删除 |
| R4 | 注释残留旧格式 ×4（PseudoOrderIdUtil/TimeoutJob×2/ReconcileJob/CacheEvict） | 注释漂移 | 修正 |
| R5 | ReconcileJob 缺 businessMetrics 字段（指标接线编译错误） | 接线遗漏 | 补字段 |

## 结论

幂等三场景 + 键模型一致性 + 语义保全 + 跨模块契约全部闭环；实施期抓到 1 个真 bug（R1）+ 4 处遗漏（R2-R5）已全部修复。证据：八模块 457 全绿 + 全仓编译。
