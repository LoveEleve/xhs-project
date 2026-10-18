---
id: C-006
slug: 购物车订单联动
status: done
created: 2026-10-17
updated: 2026-10-17
iteration: ITER-006     # 机器可读迭代协议：.harness/iterations/ITER-006-购物车订单联动/
relations:
  relates_to:
    - slug: docs/学习/模块对照/02-购物车
      reason: §7-1"下单后不自动清车"的收口 + 用户需求（取消回补）
    - slug: docs/学习/模块对照/99-总表
      reason: "最大缺口"行（下单不清车）的修复
    - slug: docs/学习/订单-讲解/04B-订单终结
      reason: ORDER_TERMINATION_TOPIC 载荷扩展（+items，eventVersion=2）
  depends_on: []
---

# C-006 购物车订单联动（下单清车 + 取消回补）

> **完成记录（2026-10-17）**：四阶段全部落地（事件订阅式联动 + 三标记定序握手协议 + 可观测 + 收口）；
> 证据：**八模块全量 431 全绿**（cart 32，首次纳入回归集，+21）+ 全仓编译；详见 `.harness/iterations/ITER-006-购物车订单联动/test/report.md`。
> 握手矩阵 10 用例（竞态两到达序/双向重复投递/自愈）全绿。运行时验证（真实 MQ 乱序）按项目规则后置。

> **立项（2026-10-17）**：模块对照阶段二第二站。核心 = §7-1 缺口 + 用户需求：
> "下单后购物车被清，但未支付订单取消/超时后商品不回购物车——要联动回补"。
> **方案经 Owner 深度 review 修订**：三标记状态机（cleared/restore-pending/restored）+ **定序握手协议**
> （修复"孤儿窗口"与"重复投递抬回数量"两个真漏洞）；零新服务、零 DDL（事件订阅式，复用既有两条订单事件）。
> 规划详见 `.harness/iterations/ITER-006-购物车订单联动/`。

## 用户故事

作为购物车用户，我想要"下单即从购物车移除已购项"（对齐真实电商），并且在订单未支付终结（取消/超时/库存失败/券失败/支付失败）后
**商品自动回到购物车**（保留购买意图，方便改数量重新下单），以便不用重新搜索加购。

## 非目标（Out of Scope）

- 匿名车服务端化（现状：客户端带入——变体记录）；失效项自动清理（保持展示标记）；优惠试算（订单侧）；退款回补购物车（不属标准行为，记录）
- 联动对账兜底 Job（演进记录：靠 order 侧 Outbox+重试+DLQ）
- 权限类；批量 merge Lua（restore 逐项 merge 幂等收敛，记录理由）

## 验收标准

- AC-1 ~ AC-8 见 `.harness/iterations/ITER-006-购物车订单联动/prd.md`（本变更走迭代协议）

## 边界情况

- 直接购买（source=DIRECT/缺省）：**不碰购物车**（买同款不误删）
- 竞态"秒取消"（回补先于清车到达）：定序协议恰好一方 merge；clear 失败自愈（商品从未被清）
- 重复投递（Outbox 重发）：cleared/restored 双向去重（skip-if-marker）
- 恢复数量：merge=max（期间重加购不覆盖用户新选择）；checked=true（购买意图）
- 失效 SKU：merge 降级放行 + 列表 valid 兜底；50 上限超限项 warn 跳过

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 1 订单 → 1 清 + 1 回补（低频）；marker 小键 TTL 化 |
| 可靠性 | 至少一次 + 标记去重 + merge 幂等；残余（restore 永久丢失）靠 order 侧 DLQ 扫描兜底 |
| 可观测 | `myxhs_cart_*` 指标族（联动/竞态/操作）+ 日志级别规格 + trace 还原 |

## 设计约束

- 事件订阅式（对齐 04B"订阅方异步联动"哲学；零新服务、零 Feign 新增）
- 零 DDL：source 只存在于创建载荷（不落 t_order）；回补靠握手标记推断而非 source 持久化
- 生产标准：业务/工程/分布式/微服务/性能/可观测全维度；不改文章正文（对照 §7 回写）

## 契约影响

- REST: `POST /api/order/create` 请求体 +`source`（可选；缺省=DIRECT 保守）
- MQ: 创建载荷 +`source`；终结载荷 +`items[{skuId,quantity}]`（eventVersion=2）；cart 新增两个消费组（`cart-order-created-group` / `cart-order-termination-group`）
- 数据模型: 无 DDL；Redis 新标记键（linkage 三标记 + TTL）
- 前端: OrderCreatePage 的 createOrder 调用补 `source`

## 影响面

- 模块/服务: my-xhs-cart（主体：消费者/服务/Lua/指标）、my-xhs-order（载荷扩展 2 处）、frontend（1 处）
- 脚本: test-e2e-business-chain.py（可选补 source 场景）
- wiki: 无（归档时回流）

## 测试策略

- 先写失败测试：**握手协议矩阵**（正常序/竞态两种到达序/双向重复投递/直接购买单/clear 失败自愈）
- 边界测试：失效 SKU、50 上限、批量多 sku
- 回归：cart 纳入回归集（**八模块**基线全绿）
