---
iteration: ITER-006
status: confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 用途：C-006 深度代码审查（重点：部分成功/部分失败、分布式、工程问题）
---

# 深度审查: C-006（购物车订单联动）

## 审查范围

`CartOrderLinkageService` / `CartService`（removeBySkus/mergeItem/executeMergeItem）/ 两个消费者 / Lua / order 载荷扩展 / 前端 / e2e 脚本 / CartReconcileJob 交互。

## 审查清单（逐项校验结论）

> 结论标记：✅ 通过 / 🟡 发现并修复 / 📝 记录（边界）

### A. 部分成功/部分失败（重点）

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| F1 | **竞态回补部分失败重入**：clear 侧握手回补 merge 失败 → 清车消费者重试 → `cleared` 去重**直接 return** → 握手回补永不重试 → **商品被清后永久丢失**（终结消息已 ack） | 逐路径推演 + 读码 | 🔴 **真 bug，已修复**：重入路径补查 pending——`cleared 存在 && pending 存在 && restored 不存在` → **补做握手回补**（幂等收敛）+ 专项测试 |
| F2 | restore 侧部分失败重入 | 推演 | ✅ 无去重阻塞（restored 未置位才重入）→ merge max 收敛 |
| F3 | clear 原子性 | 读码 | ✅ `cart_remove_batch.lua` 单脚本原子（批内无部分成功）；同步事件缺失由对账兜底（既有机制） |
| F4 | SET cleared 后崩溃（pending 检查前） | 推演 | ✅ F1 修复后重入收敛（pending 在则补做） |
| F5 | restored 置位后 delete(pending) 失败 | 推演 | ✅ 重入 skip（restored 存在）；pending 自然过期（1h）无害 |
| F6 | 双向永久失败（DLQ） | 推演 | 📝 clear 失败 → 商品从未被清（自愈）；restore 失败 → order 侧 DLQ 扫描（小时级）兜底——残余记录 |

### B. 分布式问题

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| D1 | 跨 topic 乱序（竞态两到达序） | 协议推演 | ✅ 定序操作序（SETNX pending 先于查 cleared；SET cleared 先于查 pending）恰好一方 merge |
| D2 | 跨槽位可见性（markers 无 hash tag，不同 slot） | 一致性推演 | ✅ 协议仅需"写 X 后读 Y"的实时可见性（Redis 单 key 线性一致）——集群下成立 |
| D3 | 重复投递（Outbox 重发/DLQ 扫描）并发 | 推演 | ✅ 双向 skip-if-marker + merge max + SETNX restored（并发最坏双 merge：微秒级/幂等/用户无法插入） |
| D4 | 与用户并发操作 | 推演 | ✅ 清车按订单 sku 批量（不误伤其他）；📝 用户"下单后立刻重新加购同款"在清车到达前会被清（ms~s 窗口，淘宝同行为）；📝 重试窗口内用户删除的项可能被重新加回（秒级窗口） |
| D5 | 用户清空购物车（CLEAR 屏障）与联动 | 读码 | ✅ 批量清车复用逐 sku DELETE（不涉 CLEAR 屏障）；用户 CLEAR 与联动各自有序（userId 分区） |
| D6 | 对账扫描与 marker 碰撞 | 读码（CartReconcileJob SCAN 模式） | ✅ 模式 `myxhs:cart:{*:items`（含 `{`）——marker `myxhs:cart:linkage:*` 不匹配，**无碰撞** |

### C. 工程问题

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| E1 | **"回补挂起"日志噪声**：直接购买单/竞态前半段每次终结都打 INFO | 读码 | 🟡 **已修复**：INFO → DEBUG（竞态由 clear 侧 WARN + handshake 指标覆盖；skip 计数已有） |
| E2 | **e2e 脚本异步容忍**：cartCleared 检查紧跟下单（清车为 MQ 异步）→ 竞态误报 | 读码 | 🟡 **已修复**：检查前 sleep 3s（MQ 消费窗口） |
| E3 | `executeMergeItem` 提取等价性 | 对照 + 既有测试 | ✅ Lua 参数/事件语义一致；CartServiceTest 全绿 |
| E4 | 消费者校验一致性（创建=严格抛错重试 / 终结=items 缺省安全跳过） | 读码 | ✅ 有意差异（旧版事件兼容） |
| E5 | 前端类型/透传 | 读码 | ✅ `source?: string` + 两路径分支 |
| E6 | cron 注释对齐 | grep | ✅ 零残留（"0 0 4" 无匹配） |
| E7 | mergeItem 逐项 Feign（restore 批量优化空间） | 读码 | 📝 记录（联动低频、项数少，不值得引入批量） |
| E8 | 重入路径指标（clear ok 少计一次） | 读码 | 📝 记录（restore ok 正常计数，影响可忽略） |

## 修复清单（本轮）

1. 🔴 **F1**：`handleOrderCreated` 重入路径——`cleared 存在 && pending 存在 && restored 不存在` → 补做握手回补（`restoreItems`）；否则才 skip。+ 专项测试（竞态→回补失败→重试补做→收敛）
2. 🟡 **E1**：回补挂起日志 INFO → DEBUG
3. 🟡 **E2**：e2e 脚本清车核验前 sleep（异步容忍）

## 总结

- 🔴 严重问题：**1**（F1，已修复——正是"部分成功部分失败"类：原子步骤成功 + 后续步骤失败 + 去重标记导致重试被短路）
- 🟡 工程修复：2（日志噪声 / 脚本异步容忍）
- 📝 记录：5（残余 DLQ 兜底 / 用户并发窗口×2 / Feign 逐项 / 指标少计）
- 🟢 通过：其余 16 项
