---
iteration: ITER-006
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 上游依赖：solution 未确认前，本文件不得进入 coding（见 .harness/rules/开发流程规范.md「阶段间接口」）
# 阶段间接口：REQ 编号一旦确认，禁止在不更新下游产物的情况下改写
---

# PRD: 购物车订单联动（下单清车 + 取消回补）

> 机器可读的迭代协议产物之一。本文件定义**业务需求与验收标准**，是 solution 的唯一事实来源。

## 背景与目标

**现状缺口**（证据：模块对照 `02-购物车` §7-1 + 99 总表"最大缺口"）：

- cart 不消费订单事件、order 不调 cart——**下单不清车**（真实电商会移除）；
- 用户需求：未支付订单终结（取消/超时/库存失败/券失败/支付失败）后，**商品应回购物车**（可改数量重下单）。

**基建已就绪**（已核实）：
- `ORDER_TRANSACTION_TOPIC`（创建，inventory 在消费）载荷已带 `orderNo/userId/skuItems`；
- `ORDER_TERMINATION_TOPIC`（终结，inventory/coupon/notification/payment 在消费）载荷带 `orderId/orderNo/userId/reason`（**缺 items**）；
- 前端 OrderCreatePage 已有 `source:'cart'` 导航状态（只需透传到 API）。

**方案经 Owner 深度 review 修订**：三标记状态机 + 定序握手协议（修复孤儿窗口/重复投递两漏洞）。

## 非目标（Out of Scope）

- 匿名车服务端化；失效项自动清理；优惠试算；退款回补购物车（记录）
- 联动对账兜底 Job（演进）；批量 merge Lua（记录理由）；权限类

## 需求（REQ）

| ID | 需求描述 | 优先级 |
|----|---------|--------|
| REQ-1 | **下单清车**：订单创建事件（ORDER_TRANSACTION_TOPIC，新消费组 `cart-order-created-group`）→ 仅当 `source=CART` 时，按订单 skuItems **批量原子移除**（新 Lua `cart_remove_batch`）；重复投递去重（`cleared:{orderNo}` 存在则跳过——防误删用户清车后的新加购）；对 MySQL 同步复用逐 sku **DELETE** 事件 | P0 |
| REQ-2 | **取消回补**：订单终结事件（ORDER_TERMINATION_TOPIC，新消费组 `cart-order-termination-group`）→ 按 items **merge 回补**（复用 `cart_merge_item` Lua：max 数量语义 + checked=true）；**三标记状态机 + 定序握手协议**（见 solution）；`restored` 整批成功后 SETNX；五类终结原因全覆盖 | P0 |
| REQ-3 | **事件载荷扩展**（order 侧）：① 创建载荷 +`source`（来自请求，Map 5→6 项）② 终结载荷 +`items[{skuId,quantity}]`（监听器本地事务内查 t_order_item 填充；`eventVersion=2`，向后兼容——现有订阅方 JsonNode 按字段读） | P0 |
| REQ-4 | **前端透传**：OrderCreatePage 的 `createOrder` 调用补 `source`（`state.source==='cart' ? 'CART' : 'DIRECT'`） | P0 |
| REQ-5 | **可观测**：`myxhs_cart_*` 指标族（联动 `order_linkage_total{op,result}` / 竞态 `linkage_handshake_total` / 基础操作计数）；日志规格（联动 INFO、竞态 WARN、跳过 DEBUG、trace 还原） | P1 |
| REQ-6 | **工程质量**：`cart_remove_batch.lua` + RedisScriptConfig bean + `removeBySkus` 服务方法；cron 注释漂移修复（javadoc 4am → 实际 hourly） | P1 |
| REQ-7 | **收口**：cart 纳入回归集（**八模块**——基线已核 15 测全绿）；e2e 脚本补 source 场景；对照 §7 回写（裁决 + 修订记录） | P0 |

## 验收标准（AC）

| ID | 归属 | 验收标准 | 来源 |
|----|------|---------|------|
| AC-1 | REQ-1 | source=CART 创建事件 → 批量移除订单 sku（原子 Lua）；source=DIRECT/缺省 → 不碰购物车；`cleared` 已存在 → 跳过（重复投递不误删新加购） | 02-购物车 §7-1 + review |
| AC-2 | REQ-2 | 五类终结 → items merge 回补（max + checked=true）；`restored` 已存在 → 跳过（重复投递不抬回数量）；失效 SKU 降级/超限 warn | review 修订 |
| AC-3 | REQ-2 | **定序握手协议**：restore=SETNX pending → 查 cleared；clear=删批 → SET cleared → 查 pending；任意到达序恰好一方 merge；clear 永久失败时商品保留（自愈） | review 修订（真漏洞 2） |
| AC-4 | REQ-3 | 创建载荷含 source；终结载荷含 items 且 eventVersion=2；现有订阅方兼容（字段按需读） | 契约核验 |
| AC-5 | REQ-4 | 前端 createOrder 传 source（cart/direct 两路径） | 前端核验 |
| AC-6 | REQ-5 | 指标族落位（命名 `myxhs_cart_*`）；日志级别规格 | 代码核验 |
| AC-7 | REQ-6 | Lua/bean/服务方法就位；cron 注释与部署一致 | 代码核验 |
| AC-8 | REQ-7 | cart 基线保持 + 八模块全量回归全绿 + 新增用例通过；§7 回写 | 回归基线 |

## 边界情况

- 秒取消竞态（回补先到）：定序协议处理；最坏窗口双方 merge（微秒级、max 幂等、用户无法插入）——记录
- 重复投递（Outbox 重发）：双向 skip-if-marker；`restored` 在整批成功后置位
- 直接购买单：清车永不发生（source≠CART）→ restore 置 pending 后过期（零副作用）
- 恢复数量边界：merge=max（用户调小后重复投递不会抬回——靠 restored 去重）
- marker TTL：cleared/restored=7 天（覆盖订单全周期+重投窗口）、pending=1h
- 残余（记录）：clear 成功但 restore 永久丢失 → 靠 order 侧 DLQ 扫描（小时级）兜底

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 联动低频（1 订单 1 清 1 补）；marker 小键 TTL；无新增热路径开销 |
| 可靠性 | 至少一次 + 标记去重 + merge 幂等 + 定序协议；无 DDL/无新服务 |
| 可观测 | 指标族 + 日志规格 + trace 还原 |

## 依赖与影响面

- 模块/服务: my-xhs-cart（主体）、my-xhs-order（载荷 2 处）、frontend（1 处）
- 外部 API: `POST /api/order/create` +`source`（可选）
- 消息: 2 个新消费组；2 处载荷扩展；无新 topic
- 数据模型: 无 DDL；Redis 新标记键
- 涉及 wiki: 无（归档时回流）

## 人工决策点（已决策，2026-10-17——Owner 授权 AI 决策；握手协议经 Owner review 修订）

- **决策 1（联动方式）** = 事件订阅式（对齐 04B；零新服务/零 Feign 新增/零 DDL）
- **决策 2（source 语义）** = 仅 CART 清车；缺省 DIRECT 保守（脚本/旧客户端不受影响）；source 不落库（靠握手推断）
- **决策 3（握手协议）** = 三标记状态机 + 定序操作序（SETNX pending 先于查 cleared；SET cleared 先于查 pending）——修复孤儿窗口与重复投递两漏洞
- **决策 4（恢复语义）** = merge max + checked=true（保留购买意图）；边界（调小需手动）记录
- **决策 5（同步事件）** = 批量清车复用逐 sku DELETE 动作（CartSyncEvent 实为 DELETE——对照口径勘误）
- **决策 6（记录不做）** = 联动对账 Job（演进）/批量 merge Lua（逐项幂等收敛够用）/退款回补购物车（非标准行为）
