---
iteration: ITER-006
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：每条 TC 归属 prd.md 中一条 AC；上游 AC 变更时本文件必须同步
---

# 测试用例: 购物车订单联动（下单清车 + 取消回补）

> 机器可读的迭代协议产物之一。TC 编号归属 AC，供 test/report.md 与 harness-quality 的 flow→test 对账使用。

## 用例清单

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | source=CART 创建事件 | 消费者/服务 | removeBySkus 调用（批量移除订单 sku）+ SET cleared | 是 |
| TC-2 | AC-1 | source=DIRECT（或缺省） | 同上 | 不碰购物车（零 Redis 写） | 是 |
| TC-3 | AC-1 | cleared 已存在（重复投递） | 同上 | 跳过（不误删用户清车后的新加购） | 是 |
| TC-4 | AC-2 | 终结事件（items 2 项） | 同上 | 逐项 merge 回补（checked=true）+ SETNX restored | 是 |
| TC-5 | AC-2 | restored 已存在（重复投递） | 同上 | 跳过（不抬回用户调小的数量） | 是 |
| TC-6 | AC-2 | 五类终结原因 | 参数化 | 全部触发回补 | 是 |
| TC-7 | AC-3 | **正常序**：clear 先处理 | 两事件按序 | clear 移除 + restored（clear 侧查 pending 无 → 等 restore；restore 查 cleared 有 → merge） | 是 |
| TC-8 | AC-3 | **竞态**：restore 先到（cleared 无） | 先 restore 后 clear | restore 置 pending；clear 后查到 pending → merge + restored + 删 pending（恰好一方 merge） | 是 |
| TC-9 | AC-3 | **clear 永久失败自愈**：cleared 无 | 仅 restore | 置 pending → （无 clear）→ 商品从未被清（语义断言） | 是 |
| TC-10 | AC-3 | 直接购买单终结 | 仅 restore（无 cleared） | 置 pending 后过期路径（零副作用：不 merge） | 是 |
| TC-11 | AC-4 | createOrder(source=CART) | 载荷断言 | payload 含 `source=CART` | 是 |
| TC-12 | AC-4 | 终结（含 2 个 item） | 载荷断言 | items[{skuId,quantity}] + eventVersion=2 | 是 |
| TC-13 | AC-4 | 现有订阅方兼容 | 兼容断言 | inventory/coupon 消费者解析不回归（八模块回归覆盖） | 是 |
| TC-14 | AC-5 | 前端 OrderCreatePage | 代码核验 | createOrder 传 source（cart/direct 两路径） | 手工 |
| TC-15 | AC-6 | 指标/日志 | 代码核验 | `myxhs_cart_*` 族 + 日志级别规格 | 手工 |
| TC-16 | AC-7 | Lua/bean/服务方法/cron 注释 | 代码核验 | 就位 + 注释与部署一致 | 手工 |
| TC-17 | AC-8 | 全仓 | 全量重编测试 | **八模块**基线全绿（含 cart 15+）+ 新增用例 | 是 |

## 边界与降级用例

| TC | 归属 AC | 场景 | 期望 |
|----|---------|------|------|
| TC-18 | AC-1 | 批量多 sku（部分不在车中） | 移除存在的、忽略缺失（幂等） | 是 |
| TC-19 | AC-2 | 恢复项 SKU 已下架/删除 | merge 降级放行（下架）/跳过（删除）——warn | 是 |
| TC-20 | AC-2 | 购物车满（50 上限） | 超限项 merge 失败 warn（部分回补） | 是 |
| TC-21 | AC-3 | 双方并发窗口最坏情况（双方都 merge） | max 幂等（用户无法在微秒窗口操作）——语义记录 | 是 |
| TC-22 | AC-1/2 | 非法消息（缺 orderNo/skuItems 非数组） | 拒绝/跳过（对齐 inventory 消费者校验风格） | 是 |
| TC-23 | AC-3 | marker TTL 断言 | cleared/restored=7d、pending=1h（Redis 调用参数断言） | 是 |
