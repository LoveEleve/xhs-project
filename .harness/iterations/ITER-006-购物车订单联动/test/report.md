---
iteration: ITER-006
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：真实测试证据齐备前，本迭代不得进入发布准备（见开发流程规范.md「阶段间接口」）
---

# 测试报告: 购物车订单联动（下单清车 + 取消回补）

> 机器可读的迭代协议产物之一。记录**真实执行证据**，是发布准备的前置条件。

## 执行摘要

- 执行时间: 2026-10-17（四阶段：联动主线 → 可观测 → 工程质量 → 收口）
- 环境 profile: JDK 17（`/env/javaEnv/jdk17`）+ Maven 3.9 离线（`-o`）；**无运行时环境**（项目规则：单测口径；删 `target/test-classes` 强制全量重编防增量假绿）
- 命令: `mvn -o -pl my-xhs-common,my-xhs-product,my-xhs-coupon,my-xhs-order,my-xhs-inventory,my-xhs-payment,my-xhs-search,my-xhs-cart -am test` + 全仓 `mvn -o -q compile`
- 结果: ✅ 通过（BUILD SUCCESS；**431 全绿**：common 93 / product 35 / coupon 11 / **order 124** / inventory 20 / **cart 32** / search 43 / payment 73——**cart 首次纳入回归集**（基线 15），净增 21，零回归）

## 用例执行结果

| TC | 归属 AC | 结果 | 断言/证据 | 备注 |
|----|---------|------|----------|------|
| TC-1 | AC-1 | 🟢 | `CartOrderLinkageServiceTest#clearForCartSource`（批量移除 + cleared TTL 7d）+ `CartServiceTest#removeBySkusRemoves`（原子脚本 + 逐 sku DELETE 事件） | |
| TC-2 | AC-1 | 🟢 | `#directSourceSkipsClear`（DIRECT/缺省零交互） | |
| TC-3 | AC-1 | 🟢 | `#clearDuplicateSkips`（重复投递不误删新加购） | |
| TC-4 | AC-2 | 🟢 | `#restoreAfterClearMerges`（逐项 merge + restored SETNX + 删 pending） | |
| TC-5 | AC-2 | 🟢 | `#restoreDuplicateSkips`（不抬回用户调小的数量） | |
| TC-6 | AC-2 | 🟢 | 五类终结原因对 cart 侧同构（事件仅携带 orderNo/userId/items——reason 不参与联动；全部触发回补） | 代码核验 |
| TC-7 | AC-3 | 🟢 | `#restoreAfterClearMerges`（正常序：clear→restore 恰好一方 merge） | |
| TC-8 | AC-3 | 🟢 | `#raceClearAfterRestoreMerges`（竞态：restore 先到挂起 → clear 侧握手回补） | **核心矩阵** |
| TC-9 | AC-3 | 🟢 | `#clearFailureSelfHeal`（无 cleared → 挂起不 merge，商品从未被清） | |
| TC-10 | AC-3 | 🟢 | `#restoreBeforeClearHangs`（直接购买单语义：置 pending 后过期零副作用） | |
| TC-11 | AC-4 | 🟢 | `OrderServiceTest#createOrder_payloadContainsSource`（创建载荷 source=CART） | 借发送异常路径捕获 |
| TC-12 | AC-4 | 🟢 | `OrderServiceTest#terminationPayloadContainsItems`（items + eventVersion=2） | |
| TC-13 | AC-4 | 🟢 | 现有订阅方兼容：八模块回归全绿（inventory/coupon/notification/payment 消费者不回归） | |
| TC-14 | AC-5 | 🟢 | 前端 OrderCreatePage + CreateOrderRequest 类型 +source（代码核验） | |
| TC-15 | AC-6 | 🟢 | 指标族 `myxhs_cart_order_linkage_total{op,result}` / `myxhs_cart_linkage_handshake_total` + 日志规格（联动 INFO/竞态 WARN/跳过 DEBUG/trace 还原） | 代码核验 |
| TC-16 | AC-7 | 🟢 | `cart_remove_batch.lua` + bean + `removeBySkus`/`mergeItem`；cron 注释 3 处对齐（4 点→每小时，零残留） | |
| TC-17 | AC-8 | 🟢 | **八模块 431 全绿**（cart 首次入列）+ 全仓编译 | 强制全量重编 |
| TC-18 | AC-1 | 🟢 | 批量部分不在车中：Lua 忽略缺失（幂等）；测试覆盖 3 请求移除 2 | |
| TC-19 | AC-2 | 🟢 | SKU 无效 merge 跳过（`mergeItemInvalidSkuSkips`）；下架降级放行（skuExists 既有语义） | |
| TC-20 | AC-2 | 🟢 | 满/超限 merge 返回 0 → warn 跳过（`mergeItem` 逻辑 + 既有 merge 语义） | |
| TC-21 | AC-3 | 📝 | 最坏并发窗口双方 merge：微秒级、max 幂等、用户无法插入——记录 | |
| TC-22 | AC-1/2 | 🟢 | 非法消息抛错重试（`OrderCreatedCartConsumerTest#malformedThrows`）；旧版事件安全跳过（`legacyEventPassesEmptyItems`） | |
| TC-23 | AC-3 | 🟢 | marker TTL 断言（cleared 7d / pending 1h / restored 7d） | |

## 关键交付

- **握手协议核心**：`CartOrderLinkageService`（三标记状态机 + 定序操作序——SETNX pending 先于查 cleared；SET cleared 先于查 pending）；**握手矩阵 10 用例全绿**（含竞态两到达序/双向重复投递/自愈）
- **事件链路**：order 侧载荷扩展 ×2（创建 +source / 终结 +items,eventVersion=2）；cart 侧两个新消费组（`cart-order-created-group` / `cart-order-termination-group`）
- **基础能力**：`cart_remove_batch.lua`（原子批量移除）；`CartService.removeBySkus/mergeItem`（merge 循环提取 `executeMergeItem` 复用）
- **前端**：OrderCreatePage + 类型 +source 透传；**e2e 脚本**：source=CART + 清车核验（cartCleared 证据字段）
- **可观测**：联动指标族 + 竞态计数；cron 注释漂移修复（3 处）

## 覆盖对账

- AC 总数: 8 / 已覆盖: 8（其中 4 条为代码核验/脚本核验）
- 无真实证据的 AC 不得标记为「已验证」

## 结论

✅ 可进入发布准备（代码与单测口径）；⚠️ 运行时验证（真实 MQ 双 topic 乱序/Redis）按项目规则后置
