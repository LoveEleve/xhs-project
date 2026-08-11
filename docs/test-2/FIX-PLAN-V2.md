# P0 修复方案（FIX-PLAN-V2）

> 2026-08-10 | 基于 REVIEW-V2.md 的 P0 项梳理。
> 分类：✅ 可立即代码修复（低风险，改码→重打包→重启→验证）｜🔷 需架构/配置决策｜🔶 架构级重设计

---

## ✅ P0-1 补偿消费者库存泄漏（order）
- **问题**：`OrderCompensationConsumer:113-116` 对 `RELEASE_STOCK`/`RETURN_COUPON` 一律调 `closeTimeoutOrder`，已取消(status≠0)被跳过 → 库存/券永久泄漏。
- **方案**：按 action 分发。把 `releaseInventory` / `returnCouponIfUsed` 提为 `public`，消费者按消息 action 直接调用，不再依赖 `closeTimeoutOrder` 的 status==0 判断。
- **风险**：低。需保证 releaseInventory/returnCoupon 幂等（已具备）。

## ✅ P0-2 已取消/关单订单可支付 + 支付-取消竞态无退款（order/payment）
- **问题**：`PaymentService.pay` 不回查订单状态；`onPaymentSuccess` 状态冲突仅 return false 无退款。
- **方案**：
  - `pay` 前置回查订单 status==0（Feign order getOrderDetail），非待付款拒绝。
  - `onPaymentSuccess` 检测到订单状态≠0 且支付已成功 → 触发创建退款单（或发 REFUND 消息）。
- **风险**：中。涉及 order/payment 两模块状态机，需在本地事务/事务消息内完成。

## ✅ P0-3 InventoryCompensationJob release.lua 参数缺失（inventory）
- **问题**：`release.lua` 需 4 KEYS + 2 ARGV（total/prededuct/bucket/index + skuId/orderId），当前只传3 KEYS+1 ARGV，写死 bucket 0，单SKU订单必 Lua 报错。
- **方案**：先 `HGET {skuId}:bucket` 取真实桶号；补传 `PREDEDUCT_INDEX_KEY` 和 `orderId`，与 `releaseStock` 对齐。
- **风险**：低。

## ✅ P0-4 Coupon batchExpire 多表UPDATE+LIMIT 语法错（coupon）
- **问题**：`UserCouponMapper:46-51` 多表 UPDATE INNER JOIN SET LIMIT，MySQL 不支持 → 每小时任务必挂。
- **方案**：改 `SELECT id ... LIMIT` 取待过期 id，再按主键分批 UPDATE（或子查询后 update）。
- **风险**：低。

## ✅ P0-5 search 补偿查询漏跨库前缀（search）
- **问题**：`IncrementalIndexSyncJob:227-228` `FROM t_spu` 漏 `my_xhs_product.`，必报"表不存在" → 商品增量补偿永远失败。
- **方案**：改 `FROM my_xhs_product.t_spu`，并 try/catch 隔离笔记/商品两路，避免一失败拖垮整体。
- **风险**：低。

## ✅ P0-6a HMAC 不签 body → 防篡改失效（gateway）
- **问题**：`HmacSignatureFilter:183` 签名串 `method+path+timestamp+nonce` 不含 body/query，改金额/数量不匹配。
- **方案**：签名串纳入规范化 query + body 摘要（如 `X-Content-SHA256`），网关侧重算比对。
- **⚠️ 影响**：改签名算法会使**现有客户端签名失效**（需前端同步升级），属**前后端协同变更**，需谨慎评估发布顺序。标记为🔷更合适。

## ✅ P0-6b HMAC Redis 故障 → 非白名单 500（gateway）
- **问题**：`HmacSignatureFilter:169` per-user secret `get` 未 try/catch，Redis 故障抛异常→500。
- **方案**：`:169` 的 GET 纳入 try/catch，异常时按 fail-open 放行（与注释一致）。
- **风险**：低。但需与 GatewayAuthFilter 的 fail-closed 统一降级策略（🔷决策）。

## 🔷 P0-7 X-User-Id 伪造边界（跨模块）
- **问题**：网关鉴权路径已 set() 覆盖防伪造，但①白名单路径不清除入站 X-User-Id；②服务独立端口直连可伪造。
- **方案**：①gateway 白名单路径显式 `headers.remove(X-User-Id)`；②服务端口做内网访问限制（网络层，非纯代码）。
- **决策**：白名单清除可立即做；服务直连防护需部署层（防火墙/仅内网）配合。

## 🔷 P0-7b 内部 token 硬编码默认值（跨模块）
- **问题**：`my-xhs-internal-token-2026` 明文默认值入库。
- **方案**：去掉默认值（fail-closed），由环境变量注入。**影响**：所有服务重启需保证 INTERNAL_TOKEN 环境变量已设（start-all.sh 已 export）。
- **风险**：中（部署需同步）。

## 🔷 P0-7c 明文密码入库（跨模块）
- **方案**：Redis/MySQL 密码改环境变量注入。**配置级变更**，非代码。
- **风险**：中（需确保环境变量已配）。

## 🔶 P0-8 事务消息+本地消息表双投递（order）
- **问题**：事务消息(半消息→本地事务→commit) + 本地消息表对同 topic 双发 → 必重复。
- **方案**：架构级二选一：①保留事务消息，去掉本地消息表对同 topic 的补发；②保留本地消息表，去掉事务消息。推荐①（事务消息已保证 at-least-once + 本地事务原子）。
- **⚠️ 影响大**：涉及 order 下单链路核心，需充分回归测试，**列为单独排期**，不与 P0-1~5 同批。

---

## 执行顺序建议
1. **第一批（✅ 低风险，立即修）**：P0-1、P0-3、P0-4、P0-5、P0-6b
2. **第二批（✅ 中风险）**：P0-2
3. **第三批（🔷 需协同/部署）**：P0-6a、P0-7、P0-7b、P0-7c
4. **单独排期（🔶 架构）**：P0-8

> 每项修复流程：改码 → `mvn package -pl {module} -am` → 重启对应服务 → 验证 → 更新 pitfalls.md。
