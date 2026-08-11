# P0 修复方案 · 完整版（FIX-PLAN-V2-FULL）

> 2026-08-10 | 覆盖 REVIEW-V2 全部 P0 项（P0-1~P0-8），逐条给出：根因确认、具体改动、影响、风险、验证。**本文件为方案，未实施。**

---

## P0-1 补偿消费者库存/券泄漏（order）
- **根因确认**：`OrderCompensationConsumer:113-116` 对 `RELEASE_STOCK`/`RETURN_COUPON` 一律调 `closeTimeoutOrder`，该函数只处理 status==0，已取消(status=4)被跳过 → 库存/券永久泄漏。
- **改动**：
  1. 将 `OrderService.releaseInventory(order)`、`returnCouponIfUsed(order)` 从 private 提为 public（或加 public 包装）。
  2. `OrderCompensationConsumer` 改为按 `action` 分发：`RELEASE_STOCK→releaseInventory`、`RETURN_COUPON→returnCouponIfUsed`，不再依赖 closeTimeoutOrder。
- **影响**：order 单模块。
- **风险**：低。releaseInventory/returnCoupon 需确认幂等（cancel 链路已具备）。
- **验证**：模拟取消订单且释放库存失败 → 补偿消费者收到 RELEASE_STOCK → 确认库存/券被释放。

## P0-2 已取消/关单订单可支付 + 支付-取消竞态无退款（order/payment）
- **根因确认**：`PaymentService.pay` 只查支付单自身状态，不回查订单；`onPaymentSuccess` 状态冲突(乐观锁失败)仅 return false，无退款。
- **改动**：
  1. `PaymentService.pay`：创建支付单前，经 `OrderFeignClient.getOrderDetail` 回查订单，`status!=0(待付款)` 返回业务错误拒绝支付。
  2. `OrderService.onPaymentSuccess`：当检测到订单状态已非待付款(0) 且支付已成功 → 调 `paymentFeignClient.refund` 创建退款（走完整退款链路 refund→refund-success→onRefundSuccess）。
- **影响**：order + payment 两模块状态机。
- **风险**：中高。pay 新增一次 Feign 回查（需处理超时/降级，避免影响支付性能）；退款触发需防重复。
- **验证**：取消订单后尝试支付→拒绝；支付成功瞬间并发取消→触发自动退款→资金退回+订单状态正确。

## P0-3 InventoryCompensationJob release.lua 参数缺失（inventory）
- **根因确认**：`release.lua` 需 4 KEYS(total/prededuct/bucket/index)+2 ARGV(skuId/orderId)，补偿处只传3 KEYS+1 ARGV、写死 bucket 0 → 单SKU订单必 Lua 报错，补偿进死信。
- **改动**：补偿路径对齐 `releaseStock`：先 `HGET {skuId}:bucket` 取真实桶号，补传 `PREDEDUCT_INDEX_KEY` 与 `orderId`。
- **影响**：inventory 单模块。
- **风险**：低。
- **验证**：构造超时释放补偿消息 → 确认库存/冻结正确释放。

## P0-4 Coupon batchExpire 多表UPDATE+LIMIT 语法错（coupon）
- **根因确认**：`UserCouponMapper:46-51` 多表 UPDATE INNER JOIN SET LIMIT，MySQL 不支持 → CouponExpireJob 每小时必挂。
- **改动**：改为两步：`SELECT id ... LIMIT` 取待过期 id → 按主键分批 `UPDATE ... WHERE id IN (...) AND status=0`。
- **影响**：coupon 单模块。
- **风险**：低。
- **验证**：构造过期券 → 跑 CouponExpireJob → 状态置过期。

## P0-5 search 补偿查询漏跨库前缀（search）
- **根因确认**：`IncrementalIndexSyncJob:227-228` `FROM t_spu` 漏 `my_xhs_product.`（search 数据源是 my_xhs_content）→ 商品增量补偿必失败。
- **改动**：改 `FROM my_xhs_product.t_spu`；用 try/catch 隔离笔记/商品两路（一失败不拖垮整体）。
- **影响**：search 单模块。
- **风险**：低。
- **验证**：触发商品增量补偿 → ES product_index 更新成功。

## P0-6a HMAC 不签 body → 防篡改失效（gateway + 前端）
- **根因确认**：`HmacSignatureFilter:183` 签名串 `method+path+timestamp+nonce` 不含 body/query。
- **改动**：
  1. 网关：签名串纳入规范化 `query` + `body SHA-256`（如 `X-Content-SHA256` header 参与签名）。
  2. 提供客户端签名示例（含 body 摘要），供前端升级。
- **影响**：**破坏性变更**，所有现有客户端签名失效，需前端同步升级后才可上线。
- **风险**：高（协同）。需灰度/双签名过渡（接受旧签名一段时间）或明确切换窗口。
- **验证**：篡改 body → 签名不匹配 403；正确签名 → 200。

## P0-6b HMAC Redis 故障 → 非白名单 500（gateway）
- **根因确认**：`HmacSignatureFilter:169` per-user secret `get` 未 try/catch，Redis 故障抛异常 → 500。
- **改动**：`:169` GET 纳入 try/catch，异常时按 fail-open 放行（与注释一致）。同时与 GatewayAuthFilter 的 fail-closed 策略统一决策（建议：鉴权 fail-closed=安全优先，HMAC fail-open=可用性优先，各注明）。
- **影响**：gateway 单模块。
- **风险**：低（但需明确降级策略）。
- **验证**：Redis 停掉 → 非白名单写接口不再 500（按策略放行或明确拒绝）。

## P0-7 X-User-Id 伪造边界（gateway + 部署）
- **根因确认**：网关鉴权路径已 set() 覆盖；但①白名单路径不清除入站 X-User-Id；②服务独立端口可伪造。
- **改动**：
  a. gateway：白名单路径在放行前 `headers.remove(X-User-Id)`（防自造透传）。
  b. 部署：服务端口仅内网暴露（防火墙/Nacos 网络隔离）——**部署层，非纯代码**。
- **影响**：gateway + 部署。
- **风险**：中（a 低风险可立即做；b 需运维配合）。
- **验证**：未鉴权路径带自造 X-User-Id → 下游收不到该头。

## P0-7b 内部 token 硬编码默认值（跨模块）
- **根因确认**：`myxhs.internal.token: ${INTERNAL_TOKEN:my-xhs-internal-token-2026}` 含明文默认值。
- **改动**：去掉默认值（fail-closed），由环境变量注入（start-all.sh 已 export INTERNAL_TOKEN）。
- **影响**：所有服务。**需确保重启时环境变量已设**，否则内部调用全 403。
- **风险**：中（部署同步）。
- **验证**：内部端点带正确 X-Internal-Call → 200；无头/错头 → 403。

## P0-7c 明文密码入库（跨模块）
- **改动**：Redis/MySQL 密码改环境变量注入（配置级，非代码）。
- **风险**：中（需确保环境变量已配，否则服务起不来）。
- **验证**：服务正常启动并连上 Redis/MySQL。

## P0-8 事务消息+本地消息表双投递（order）
- **根因确认**：事务回查依赖本地消息表存在性(COMMIT)；但 ORDER_CREATED 本地消息 status=0 被 LocalMessageRetryJob 重发到同 topic → 双投递。
- **改动**：`OrderTransactionService` 插入本地消息后立即 `status=1`（已投递/成功），RetryJob 扫描 status in(0,2) 跳过它；回查仍按存在性 COMMIT。
- **影响**：order 单模块。不动回查机制、不动其他 operationType（补偿类仍走本地消息重发）。
- **风险**：中（下单核心链路，需回归）。仅改 ORDER_CREATED 状态，其余操作类型不受影响。
- **验证**：下单 → 本地消息 status=1 → RetryJob 不再重发 → inventory 预扣仅一次。

---

## 执行批次
- **第一批（低风险，改码→重打包→重启→验证）**：P0-1、P0-3、P0-4、P0-5、P0-6b、P0-8
- **第二批（中风险）**：P0-2
- **第三批（需协同/部署决策）**：P0-6a（前端协同）、P0-7b/P0-7c（部署同步）
- **P0-7a**：网关白名单清除可并入第一批；服务端口隔离走部署。

> 每项完成后更新 pitfalls.md 并标注修复状态。本方案待 review 确认后实施。

---

## 深度 REVIEW 结论（对方案本身的核验，2026-08-10）

> 逐条回读真实代码核验修复方案是否正确、是否有遗漏。**结论：方案方向均正确，以下为核验结果与需补充的细节。**

| P0 | 核验结果 | 需补充的细节 |
|:--:|------|------|
| P0-1 | ✅ 成立。代码 :113 已有注释"待修: 需加 public 方法"，方向一致 | `releaseInventory(orderId,orderNo,userId)` 需要 **orderNo**，补偿消息可能没有 → 需先加载 Order 取 orderNo；`returnCouponIfUsed(Order)` 需先加载 Order。补充：分发时先 `orderService.getOrderById` 加载，再调对应方法 |
| P0-2 | ✅ 可行（退款链路完整） | pay 新增回查 Feign 需设**超时/降级**（避免拖慢支付）；自动退款触发须幂等（payment refund 有 SETNX refunding key，已具备） |
| P0-3 | ✅ 成立。release.lua 明确要求 4 KEYS+2 ARGV，调用方先 HGET 桶号 | 桶号读取可能过期(已被并发重分配) → 需按 releaseStock 同逻辑处理 |
| P0-4 | ✅ 成立。SQL 确认 `UPDATE...INNER JOIN...LIMIT`，MySQL 不支持 | 两步 SELECT 需保留**同一 join 条件**：`SELECT uc.id FROM t_user_coupon uc INNER JOIN t_coupon_template ct ON uc.coupon_id=ct.id AND ct.deleted=0 WHERE uc.status=0 AND ct.valid_end<NOW() LIMIT n`，再 `UPDATE t_user_coupon SET status=2 WHERE id IN(...)` |
| P0-5 | ✅ 成立 | — |
| P0-6a | ⚠️ 方案正确但**破坏性需过渡** | 直接改签名会让现网客户端全 403。**补充：过渡方案**——网关先同时接受"旧签名(无body)"与"新签名(含body)"，按客户端版本头或先试新后试旧；切换窗口后再强制新签名。**这是最重要补充点** |
| P0-6b | ✅ 成立。:170 GET 未 try/catch，nonce 有 try/catch 佐证不对称 | 修复后需明确：HMAC fail-open + JWT 鉴权 fail-closed 并存的降级策略（鉴权安全优先、HMAC 可用性优先） |
| P0-7a | ✅ 成立 | 白名单路径 remove(X-User-Id) 需在 chain.filter 前对 exchange 的 headers 做可变复制 |
| P0-7b/7c | ⚠️ 成立但**部署同步** | 去默认值后所有服务重启必须保证 INTERNAL_TOKEN/密码环境变量已设，否则内部调用全 403 / 服务起不来。需先确认 start-all.sh 已 export，并逐个服务验证 |
| P0-8 | ✅ **验证有效**：回查只查存在性(COMMIT)、RetryJob 只扫 status∈(0,2) | ORDER_CREATED 标 status=1 即可：回查仍 COMMIT、RetryJob 跳过。确认 status=1 非 0/2 即可 |

### REVIEW 后最关键的补充/风险
1. **P0-6a 必须做签名过渡**，否则是破坏性事故（现网全 403）。建议独立灰度，不与第一批同发。
2. **P0-2 的 pay 回查**需带超时+降级，且自动退款要幂等。
3. **P0-1 需先加载 Order 取 orderNo**，再调 releaseInventory/returnCouponIfUsed。
4. **P0-7b/7c 部署同步**：去默认值前必须先确认环境变量覆盖，且需全量重启，改动面大，建议列为独立发布批次。

### 调整后的执行批次（REVIEW 后）
- **第一批（安全，不含破坏性）**：P0-1、P0-3、P0-4、P0-5、P0-6b、P0-7a、P0-8
- **第二批**：P0-2（含 pay 回查降级 + 幂等退款）
- **第三批（破坏性/部署协同）**：P0-6a（签名过渡，需前端）、P0-7b/P0-7c（去默认值，需全量重启）
