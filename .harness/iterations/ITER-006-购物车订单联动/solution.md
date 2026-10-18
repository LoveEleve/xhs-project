---
iteration: ITER-006
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：本文件仅可引用已 confirmed 的 prd.md 中的 REQ/AC；本文件 confirmed 前不得进入 coding
---

# 技术方案: 购物车订单联动（下单清车 + 取消回补）

> 机器可读的迭代协议产物之一。把 prd.md 的 REQ 转化为**实现任务与观测点**。
> 纪律：具体方法逻辑以代码为准，本文件只说明「调哪个接口/方法 + 它在整条链路中的位置」。

## 方案概述

```
下单清车：ORDER_TRANSACTION_TOPIC（已存在）→ cart-order-created-group → source=CART 才清（cart_remove_batch Lua）
取消回补：ORDER_TERMINATION_TOPIC（已存在）→ cart-order-termination-group → merge 回补（cart_merge_item Lua）
握手协议（跨 topic 乱序防护，三标记）：
  restore：① SETNX restore-pending:{orderNo} → ② 查 cleared:{orderNo}（有 → merge + SETNX restored + 删 pending）
  clear：  ① cart_remove_batch → ② SET cleared:{orderNo} → ③ 查 pending:{orderNo}（有 → merge + SETNX restored + 删 pending）
  直接购买单：restore 置 pending 后过期（零副作用）；clear 永久失败：商品从未被清（自愈）
```

四阶段：

| 阶段 | 内容 | 独立价值 |
|---|---|---|
| ① 联动主线 | order 载荷扩展 ×2 + cart 消费者 ×2 + 服务/Lua + 标记协议 | 核心需求闭环 |
| ② 可观测 | 指标族 + 日志规格 | 联动可见 |
| ③ 工程质量 | cron 注释 + Lua 资源清理 | 一致性 |
| ④ 收口 | 八模块回归 + 脚本 + §7 回写 | 闭环 |

## REQ → 任务映射

### 阶段① 联动主线（REQ-1/2/3/4 / AC-1~5）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-4 | T1.1 创建载荷 +`source`：`OrderCreateRequest` +`source` 字段；`buildTransactionPayload` Map +1 项（`source` 缺省 `"DIRECT"` 非空——Map.of 不容 null） | `my-xhs-order`：`dto/request/OrderCreateRequest.java`、`service/OrderService.java` | 单测：载荷含 source |
| AC-4 | T1.2 终结载荷 +`items`：`OrderTerminationEvent` +`List<TerminationItem{skuId,quantity}>`（`eventVersion=2`）；`OrderTerminationListener` 本地事务内查 `t_order_item`（orderId+userId 路由）填充 | `my-xhs-order`：`dto/event/OrderTerminationEvent.java`、`listener/OrderTerminationListener.java` | 单测：载荷含 items |
| AC-5 | T1.3 前端透传：`OrderCreatePage` createOrder 补 `source: state.source === 'cart' ? 'CART' : 'DIRECT'` | `frontend/src/pages/order/OrderCreatePage.tsx` | 前端核验 |
| AC-1/3 | T1.4 `cart_remove_batch.lua`（原子：HGETALL 过滤 + HDEL + SREM + ZREM 三结构批量移除）+ `RedisScriptConfig` bean + `CartService.removeBySkus(userId, skus)`（走既有同步事件：逐 sku DELETE） | `my-xhs-cart`：`resources/lua/cart_remove_batch.lua`、`config/RedisScriptConfig.java`、`service/CartService.java` | 单测：批量移除 |
| AC-1/2/3 | T1.5 `CartOrderLinkageService`（标记协议核心）：`handleOrderCreated(payload)`（source=CART：skip-if-cleared → removeBySkus → SET cleared → 查 pending → merge+restored+删 pending）；`handleOrderTerminated(payload)`（skip-if-restored → SETNX pending → 查 cleared（有→merge+restored+删 pending））；marker 键 `myxhs:cart:linkage:{cleared\|restore-pending\|restored}:{orderNo}`（TTL 7d/1h/7d） | `my-xhs-cart`：`service/CartOrderLinkageService.java`（新） | **单测：握手矩阵（AC-3）** |
| AC-1 | T1.6 `OrderCreatedCartConsumer`（topic=ORDER_TRANSACTION_TOPIC，group=`cart-order-created-group`，maxReconsume=3）：Jackson 解析（orderNo/userId/skuItems/source）→ `handleOrderCreated`；失败抛错重试 | `my-xhs-cart`：`consumer/OrderCreatedCartConsumer.java`（新） | 单测：消息解析/跳过 |
| AC-2 | T1.7 `OrderTerminationCartConsumer`（topic=ORDER_TERMINATION_TOPIC，group=`cart-order-termination-group`，maxReconsume=3）：解析（orderNo/userId/items/eventVersion）→ `handleOrderTerminated` | `my-xhs-cart`：`consumer/OrderTerminationCartConsumer.java`（新） | 单测 |

### 阶段② 可观测（REQ-5 / AC-6）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-6 | T2.1 指标族（MeterRegistry）：`myxhs_cart_order_linkage_total{op=clear\|restore, result=ok\|skip\|fail}`、`myxhs_cart_linkage_handshake_total{path=restore-first\|clear-first}`、基础操作计数（add/update/remove/clear 可选） | `CartOrderLinkageService`、`CartService` | 代码核验/单测 |
| AC-6 | T2.2 日志规格：联动 INFO（orderNo/userId/count）、竞态命中 WARN、跳过 DEBUG、`MqTraceHelper.restoreTraceId` | 两消费者 + 服务 | 代码核验 |

### 阶段③ 工程质量（REQ-6 / AC-7）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-7 | T3.1 cron 注释漂移修复（javadoc "每天凌晨 4 点" → 部署实际 `0 0 * * * ?` 每小时） | `CartReconcileJob` | 注释核验 |

### 阶段④ 收口（REQ-7 / AC-8）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-8 | T4.1 cart 纳入回归集（八模块命令）；e2e 脚本补 source 场景（cart 下单→清车断言→取消→回补断言） | 回归命令、`scripts/test-e2e-business-chain.py` | 测试报告 |
| AC-8 | T4.2 对照 §7 回写（8 线索裁决 + review 修订记录） | `docs/学习/模块对照/02-购物车.md` | 文档 |

## 接口与数据变更

- **对外接口**：`POST /api/order/create` 请求体 +`source`（可选；缺省 DIRECT）
- **消息**：创建载荷 +`source`；终结载荷 +`items`（eventVersion=2）；cart 两个新消费组；无新 topic
- **数据模型**：无 DDL；Redis 标记键（三标记 + TTL：cleared/restored=7d、pending=1h）
- **前端**：OrderCreatePage createOrder +source

## 关键设计决策

| 决策 | 结论 | 理由 / 权衡 |
|------|------|------------|
| 联动方式 | 事件订阅式（ORDER_TRANSACTION_TOPIC + ORDER_TERMINATION_TOPIC） | 对齐 04B 哲学；零新服务/零 Feign/零 DDL |
| source 语义 | 仅 CART 清车；缺省 DIRECT | 直接购买不误删购物车；脚本/旧客户端不受影响 |
| 握手协议 | 三标记 + 定序操作序 | 修复孤儿窗口（真漏洞 2）与重复投递抬回数量（真漏洞 1） |
| 恢复语义 | merge max + checked=true | 期间重加购不覆盖；保留购买意图 |
| 同步事件 | 复用逐 sku DELETE | CartSyncEvent 动作实为 DELETE（对照口径勘误） |
| 清车失败/回补丢失 | 自愈（握手）/DLQ 扫描兜底 | 残余记录 |

## 风险与降级

| 风险 | 影响 | 缓解 / 降级 |
|------|------|------------|
| 跨 topic 乱序 | 商品被清后不回补 | 定序握手协议（恰好一方 merge）+ 测试矩阵 |
| 重复投递 | 误删新加购/抬回数量 | 双向 skip-if-marker；restored 整批成功后置位 |
| clear/restore 事件永久丢失 | 商品多留/少留 | clear 失败自愈（握手）；restore 靠 order 侧 Outbox+DLQ 扫描（小时级） |
| 载荷扩展影响现有订阅方 | 解析失败 | JsonNode 按字段读 + 兼容测试（现有消费者不回归） |
| 前端未传 source（旧版本） | 不清车（保守） | 缺省 DIRECT——功能退化为"现状"，无破坏 |

## 人工决策点（已决策，2026-10-17——Owner 授权 AI 决策；握手协议经 Owner review 修订）

- 决策 1~6 见 prd.md §人工决策点（联动方式/source 语义/握手协议/恢复语义/同步事件/记录不做）
