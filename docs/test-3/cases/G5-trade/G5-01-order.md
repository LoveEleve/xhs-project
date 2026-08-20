# G5-01 订单用例（下单 + 状态机 + 支付联动 + 关单/补偿）

> 组：G5 交易 | 服务：order(19011) + 联动 coupon/product/user/inventory/payment | 入口：**gateway(19000)**
> 依赖：G1 登录 + G3 商品（SPU+SKU 存在、inventory init）+ G4 优惠券（可选）
> 时间引用：矩阵 **#4**（订单超时关单 30min，主③+①）、**#13**（本地消息重试 ③+①）、**#25**（下单幂等 24h）、**#29**（延时消息消费 ④）
> 前提：order 服务 UP、xxl 任务 10-13 存在启用、商品+库存就绪

## 代码实证（2026-08-14，G5 梳理全量核实）

### 端点与安全（gateway:19000 → order:19011）
| 端点 | 鉴权 | 说明（代码实证） |
|---|---|---|
| POST /api/order/create | JWT + **HMAC 签名** | @RateLimit 5次/60s perUser `myxhs:order:create`；@Valid OrderCreateRequest |
| GET /api/order/{orderId} | JWT + HMAC | 归属校验（userId+orderId 联合查询）|
| GET /api/order/list | JWT + HMAC | status 可选过滤；orderByDesc(createdAt) |
| GET /api/order/by-order-no/{orderNo} | JWT + HMAC | 映射表路由 |
| POST /api/order/cancel | JWT + HMAC | @RequestParam orderId；@RateLimit 10/60 |
| POST /api/order/confirm | JWT + HMAC | 确认收货（2→3）|
| POST /api/order/deliver | JWT + HMAC | @RateLimit 10/60；@Valid DeliverRequest |
| POST /api/order/pay/create | JWT + HMAC | **pay.type=mock（默认）**：MockPayService **30% 随机失败**；remote：调支付服务 |
| GET /api/order/pay/status/{orderId} | JWT + HMAC | 归属校验；mock 模式查本地 |
| POST /api/order/pay-success | **X-Internal-Call** | @RequestParam orderId/tradeNo；乐观锁 0→1 |
| POST /api/order/pay-fail | **X-Internal-Call** | 自动取消订单 |
| POST /api/order/refund-success | **X-Internal-Call** | 订单 → 5(已退款) + 释放库存 |
| POST /api/order/refund-fail | **X-Internal-Call** | 仅日志 |
| GET /api/order/pay-amount | **X-Internal-Call** | 支付服务校验用 |
| GET /api/order/status | **X-Internal-Call** | P1-1 支付前回查 |

### 下单链路（事务消息，代码实证）
`createOrder`：
1. **幂等**：SETNX `myxhs:order:idempotent:{bizIdentifier}` **24h**，重复 → 40201"请勿重复下单"（失败时按情况释放）
2. **分布式锁**：`myxhs:order:create:lock:{userId}` 10s（SETNX+UUID），失败 → LOCK_ACQUIRE_FAIL + 删幂等键
3. **商品数据**：Feign product `sku/batch`（SkuInfoDTO：id/spuId/name/price/image——**无 spuStatus，T-047 验证点**）
4. **前置库存校验**：Feign inventory queryStock，available < 需要 → 30004"库存不足"
5. **金额**：真实 SKU 价 × 数量 = total；`getCouponDiscount`（Feign coupon 不核销）→ discount；payAmount = total - discount（<0 归 0）
6. **地址快照**：addressId → user 服务（缺失 → 空 JSON）
7. **事务消息**：`sendMessageInTransaction(ORDER_TRANSACTION_TOPIC)` → Listener.executeLocalTransaction（**独立类 OrderTransactionService 保证 @Transactional**）：
   - INSERT t_order（status=0）+ t_order_item（真实 SKU 数据）+ **t_local_message**（同事务，payload=事务消息体）
   - 成功 COMMIT / 失败 ROLLBACK；**回查 checkLocalTransaction：查 t_local_message by orderNo**（#31）
8. **优惠券核销（P0-A #53）**：couponId 非空 → Feign `useCoupon`（乐观锁）；失败 → **cancelOrder + 删幂等键 + 30016"优惠券核销失败，订单已取消"**
9. **延时关单消息**：`ORDER_CLOSE_TOPIC` **delayLevel=16（30min，配置 order.close.delay-level 可改 5=1min）**——失败不影响下单
10. 事件/快照/映射表（异步 saveOrderNoMapping）

### 库存联动（order 侧视角）
- 预扣：inventory 消费事务消息，key=`inventory:prededuct:{pseudoOrderId}`（**pseudoOrderId = SHA-256(orderNo) 前 8 字节**，P2-12）
- 取消/关单/退款释放：releaseInventory(orderId) → Feign release（**必须用同一 pseudoOrderId**）；失败 → ORDER_COMPENSATION_TOPIC
- 支付成功：confirmInventoryDeduct（异步，失败靠对账）
- **注意：下单成功 ≠ 预扣立即完成**——inventory 消费事务消息异步预扣

### 状态机（Order.java:33）
0 待付款 → 1 已付款（pay-success 乐观锁）→ 2 已发货（deliver，乐观锁）→ 3 已完成（confirm）；0 → 4 已取消（cancel/关单/pay-fail）；1 → 5 已退款（refund-success 后）

### 定时/补偿（order 域）
| 任务 | 触发 | 逻辑 |
|---|---|---|
| orderCloseJob（xxl#10，每分钟）| 手动触发 | 游标分页扫 created<now-30min 且 status=0 → closeTimeoutOrder（幂等）|
| localMessageRetryJob（xxl#11，每分钟）| 手动触发 | t_local_message status=0 补发（next_retry_time 判断）|
| deadLetterScanJob（xxl#12，每分钟）| 手动触发 | 重试超限消息 → status=3 死信 |
| orderMappingRepairJob（xxl#13，每分钟）| 手动触发 | 补全 t_order_no_mapping |
| OrderCompensationConsumer | MQ（ORDER_COMPENSATION_TOPIC）| **按 action 分发（P1-2 #56）**：RELEASE_STOCK→compensateReleaseStock（不依赖状态）/ RETURN_COUPON→compensateReturnCoupon / 默认 CLOSE_ORDER→closeTimeoutOrder；msgId SETNX 10min 幂等 |

### 错误码
40201 幂等拒绝 / 30008 订单不存在 / 30009 订单状态不允许 / 30004 库存不足 / 30016 优惠券 / 40002 参数 / 40202 限流 / 403 内部鉴权

### OrderCreateRequest 校验
skuItems @NotEmpty / addressId @NotNull / bizIdentifier @NotBlank / SkuItem.skuId @NotNull + quantity @NotNull

## 前置准备（执行时记录快照）
```python
from testlib import *
# 1. 注册用户 g5a_（买家）
# 2. G3 商品：SPU+SKU（sku1 price=99.90）、inventory init（total=100）
# 3. 清理：t_order 分片表测试行 + t_order_no_mapping + t_local_message + t_order_event + Redis myxhs:order:*
```

## 用例清单

### G5-01-01 下单全链路（核心）
- **前置**：g5a_ 登录 + sku1 库存已 init（total=100）+ 地址（G1-08 建一条）
- **入口**：`POST /api/order/create` body=`{"skuItems":[{"skuId":{sku1},"quantity":2}],"addressId":{addr},"bizIdentifier":"g5-o1-{ts}","couponId":null,"remark":"G5"}`（JWT+HMAC）
- **L1**：200；data.orderId/orderNo；data.payAmount = 99.90×2 = **199.80**（无券）；status=0
- **L2**（等 1-3s 事务消息+主从）：
  ```
  # ① t_order 分片落库（db=uid%4, tb=(uid/4)%4）：status=0、pay_amount=199.80、address_snapshot=真实地址 JSON
  # ② t_order_item：sku_id/price=99.90/quantity=2/total_amount=199.80/sku_image 非空（真实 SKU 数据）
  # ③ t_local_message：status=0、operation_type=ORDER_CREATED、transaction_id=orderNo
  # ④ t_order_no_mapping：order_no → order_id（异步，等 1-3s）
  # ⑤ 库存预扣：inventory:prededuct:{pseudoOrderId} 存在（Hash：sku1→2）；total=100→98（Lua 扣减）
  #    pseudoOrderId = SHA-256(orderNo) 前 8 字节（Big-endian long，P2-12 算法；执行脚本可先算出再查）
  # ⑥ Redis 幂等键 myxhs:order:idempotent:{biz} TTL≈86400
  # ⑦ t_order_event：EVENT_CREATED
  ```
- **🔍 人工观察**：RocketMQ ORDER_TRANSACTION_TOPIC 事务消息（半消息→COMMIT）；SkyWalking order→MQ→inventory 链路
- **注意**：下单 200 但订单可能未落库（事务消息异步）——断言前等 1-3s；**用例后清理**：DEL 幂等键（24h 残留会挡住同 biz 复用）+ 按分片删 t_order/t_order_item/t_local_message/t_order_event/t_order_no_mapping

### G5-01-02 下单参数校验（负面）
- ① 缺 skuItems → 40002；② skuItems 空数组 → 40002（@NotEmpty）；③ 缺 addressId → 40002；④ 缺 bizIdentifier → 40002
- ⑤ **SkuItem 嵌套校验缺失（T-068 观察）**：List<SkuItem> 无 @Valid → 缺 skuId → **50002"商品信息查询失败"**（skuMap.get(null) 前置兜底）；缺 quantity → **500 NPE**（金额计算）——均为运行时兜底而非 40002（登记观察：补 @Valid 后可收敛 40002）
- **L2**：t_order 无新增（4 库 4 表 COUNT 基线不变）

### G5-01-03 下单幂等（24h 窗口）
- 同 bizIdentifier 连续两次 create → 第一次 200、第二次 **40201"请勿重复下单"**
- **L2**：t_order 该 biz 仅 1 行（4 库 4 表核对）

### G5-01-04 并发下单锁（同用户）
- 同用户并发 2 个不同 bizIdentifier 下单 → 恰一成功（另一 LOCK_ACQUIRE_FAIL"操作过于频繁"）或均成功但串行——**锁定竞争窗口内**；L2：锁 key `myxhs:order:create:lock:{uid}` 存在期间另一请求被拒
- **简化断言**：先手动 SET 锁 → create → **LOCK_ACQUIRE_FAIL**（+幂等键已删，可重试）；DEL 锁后重试成功

### G5-01-05 库存不足（前置校验）
- SQL 或 init 后：库存 total 改为 1（reinit 或直接操纵 Redis total=1）→ 下单 quantity=2 → **30004"库存不足: skuId=..., need=2, have=1"**
- **L2**：t_order 无新增（前置校验在事务消息前）
- **恢复**：reinit 回 total

### G5-01-06 商品信息查询失败
- 下单 skuId=999999999999（不存在）→ product batch 过滤后返回空 → **SERVICE_CALL_FAIL"商品信息查询失败"**（或 30004 前置库存查询失败——以实测为准）
- **L2**：无订单落库

### G5-01-07 优惠券核销联动（P0-A #53 回归）
- **前置**：G4 模板（满100减20）+ 领券
- 下单（sku1×2=199.80 + couponId）→ 200
- **L2**：券 status 0→1、used_order_id=订单 id（等 MQ 1-3s）；discount_amount=20；pay_amount=179.80
- **核销失败分支**：SQL 把券 status 置 1（已用）→ 下单同券 → 30016"优惠券核销失败，订单已取消" + **订单已取消(status=4)** + 幂等键已删（可换 biz 重试）
- **注意**：useCoupon 在订单提交后同步执行；失败 cancelOrder 释放预扣库存

### G5-01-08 T-060 回归：下架 SPU 下单拦截（T-047 遗留已修复）
- **背景**：T-047 遗留"下单层双状态校验"已在测试前修复（T-060：SkuInfoDTO+spuStatus，createOrder 前置校验）——**本用例验证修复后行为**
- **步骤**：上架状态下单 → **200**；SPU 下架（updateSpuStatus=0）→ 再下单 → **30003"商品已下架: skuId=..."**（PRODUCT_OFF_SHELF，30003 首次被业务使用）；恢复上架 → 再下单 → 200
- **L2**：被拒下单无订单落库（幂等键已释放——可换 biz 重试）
- **防回归**：修复还堵了 catch 块 context null NPE（500）——同类前置校验失败必须返回业务码而非 500

### G5-01-09 订单详情/列表
- `GET /api/order/{orderId}`（JWT+HMAC）→ 200；归属校验：他人查询 → **30008**（联合查询查不到）
- `GET /api/order/list` → 列表 orderByDesc(createdAt)；`?status=0` → 仅待付款
- **缓存（第二轮 REVIEW 修正）**：`myxhs:order:info:{orderId}` 是**只删不读的死缓存键**（状态变更处 DEL 但 getOrderDetail 从不读——T-031/T-066 同族，登记观察）——详情每次直查 DB，**无缓存一致性断言**

### G5-01-10 取消订单
- 仅待付款可取消：取消 → 200
- **L2**：status=4、cancelled_at 有值；**库存释放**：inventory:prededuct:{pseudo} 消失（或 total 回 100）；**退券**（若有）：券 status 1→0（等 1-3s）；t_order_event=CANCELLED；快照
- **幂等**：重复取消 → **30009"只能取消待付款的订单"**
- **释放失败兜底**（构造）：临时停 inventory 服务（慎做，高风险）→ 取消 → 补偿消息 ORDER_COMPENSATION_TOPIC（RELEASE_STOCK）——标注高风险可选

### G5-01-11 状态机非法流转
- ① 取消已付款订单 → 30009；② 发货待付款订单 → 30009"只能对已付款的订单执行发货"；③ 确认收货未发货订单 → 30009；④ 支付回调已取消订单 → 30009（P1-1 触发自动退款，#57）
- ⑤ **cancel 不存在订单** → 30008；cancel 他人订单 → 30008

### G5-01-12 发货 + 确认收货（1→2→3）
- 前置：订单已付款（SQL 或 mock 支付）
- deliver（logisticsCompany/trackingNo）→ 200；L2：status=2、delivered_at、t_order_event=DELIVERED
- confirm → 200；L2：status=3、completed_at
- **乐观锁**：deliver 两次 → 第二次 30009（status≠1）

### G5-01-13 支付成功回调（pay-success）
- **入口**：`POST /api/order/pay-success?orderId=&tradeNo=`（**X-Internal-Call**；无头 → 403"仅允许内部服务调用"）
- 待付款订单 → 200；L2：status=1、paid_at、**confirm 库存扣减**（inventory:prededuct:{pseudo} 消失、total 98 不变、MySQL locked 归 0——等 1-3s）、Redis myxhs:order:info:{id} 已删
- **幂等**：重复回调 → 200（乐观锁 updated=0 时返回 **30009**——记录：第二次回调返回业务失败（已支付））
- **竞态（P1-1 #57 场景）**：订单先取消（SQL status=4）→ 调 pay-success → **30009"订单状态不允许支付"** → payment 侧自动退款（G5-02-03 联动验证）

### G5-01-14 支付失败回调（pay-fail）
- 待付款订单 + pay-fail → 200；L2：status=4（自动取消）+ 库存释放 + 退券（同 G5-01-10 联动）
- 无头 → 403

### G5-01-15 退款成功回调（refund-success）
- 前置：订单已付款（status=1）且库存已确认
- refund-success → 200；L2：**status=5（已退款）**、t_order_event=REFUNDED
- **⚠️ 运行态实证（T-071 观察）**：confirm 已清预扣记录 → refund-success 的 releaseInventory 无记录可退 → **库存 total 不回退**（支付→确认→退款全链路后库存永久减少）——登记观察（需产品语义确认：退款是否应退库存）

### G5-01-16 超时关单（orderCloseJob + 延时消息）
- **构造**：下单后 SQL 改 `created_at=now-31min`（③ 操纵）
- **触发**：xxl admin 手动触发 id=10（组 8）→ L2：status=4、库存释放、退券（若有）、handle 200"兜底关单完成，关闭 1 条"
- **延时消息路径（#29）**：console 投 ORDER_CLOSE_TOPIC（payload=orderId，delayLevel=1）→ 消费端真实关单（若 console 不可用降级为 xxl 覆盖——标注）
- **幂等**：已支付订单 → 不关（status 不变）；重复触发 → 无副作用

### G5-01-17 本地消息补发（localMessageRetryJob xxl#11）
- 构造：下单后 SQL 改 t_local_message（分片表）`next_retry_time=过去` + status=0（③）
- 触发 id=11 → L2：补发成功 status=1（等 MQ 消费）；xxl log handle 200
- **验证补发不重复预扣**：库存 total 只减一次（幂等：inventory 消费端预扣记录已存在 → -1 忽略）
- **补发失败重试**：构造 payload 非法（③ 改 body 为非法 JSON）→ 触发 → retry_count+1；**连续补发失败至 retry_count≥5 → status=3（死信，MAX_RETRIES=5 自动标记）**

### G5-01-18 死信扫描重投（deadLetterScanJob xxl#12，语义修正）
- **死信产生**：由 G5-01-17 的补发失败路径自动标记（retry≥5 → status=3）或 SQL 直接构造 status=3 + updated_at 24h 内
- **deadLetterScanJob（id=12）= 死信重新投递**（非标记死信）：触发 → status=3 消息重投 → 成功 **status=1**（或失败 retryCount 转负数计数，≤3 次放弃）
- **L2**：重投成功后库存预扣仍只一次（消费端幂等）
- **注**：G2 已验证 feed 本地消息死信（content 域），此处验证 order 域 + 重投语义

### G5-01-19 映射表修复（orderMappingRepairJob xxl#13）
- 构造：删除某订单的 t_order_no_mapping 行（③）
- 触发 id=13 → L2：映射行补全；by-order-no 查询恢复可用

### G5-01-20 补偿消费者（P1-2 #56 回归）
- **RELEASE_STOCK**：SQL 构造已支付订单（status=1，预扣记录存在）→ 向 ORDER_COMPENSATION_TOPIC 投 RELEASE_STOCK 消息（console 或取消触发）→ 补偿释放 → **total 回满**（不依赖订单状态）
- **RETURN_COUPON**：已支付+已用券订单 → 投 RETURN_COUPON → 券退回
- **CLOSE_ORDER**：待付款订单 → 投 CLOSE_ORDER → 关单
- **幂等**：同 msgId 重投 → 跳过（Redis 10min）

### G5-01-21 下单限流（5次/60s）
- 连打 6 次（不同 biz）→ 第 6 次 **40202**；`ZCARD myxhs:order:create:OrderController:createOrder:{uid}`=5
- **清理**：DEL 限流 key

### G5-01-22 下单鉴权（安全）
- ① 无签名 → 403（gateway HMAC）；② 无 JWT → 401；③ pay-success 无 X-Internal-Call → 403；④ /api/order/status 无 X-Internal-Call → 403（对照 G3 sku/batch 模式）

### G5-01-23 订单侧支付（pay/create）
- **⚠️ 运行配置实证（2026-08-14）**：`pay.type: remote`（order yml）——pay/create **走 payment 服务**（Feign pay），**非本地 MockPayService**；MockPayService 的 30% 失败（payType=2）仅当 pay.type=mock 时加载（@ConditionalOnProperty）——**当前环境不适用**
- **入口**：`POST /api/order/pay/create` body=`{"orderId":{oid},"payType":1|2|99}`（JWT+HMAC）
- **分叉（remote 链路）**：
  - **payType=99**：payment 同步成功 → 订单 **status=1**（即时）
  - **payType=1/2**：payment 注册模拟器回调（5s 周期+1-3s 延迟，90% 成功/10% 失败）→ **等 5-15s** 订单 status=1（成功）或 4 自动取消（失败）；回调 tradeNo=MOCK_TRADE_（payment 模拟器，非 order 本地）
  - **幂等**：已支付订单再 pay/create → **40201"请勿重复支付"**（payment 侧 status key）
- **L2**：t_payment（my_xhs_payment 库）记录 CREATE/PAY_SUCCESS/PAY_FAIL 事件；订单状态联动
- **注意**：pay 限流 10次/60s perUser（payment 侧）——连测注意窗口

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G5-01-01~23 | 2026-08-15 14:34~14:56 | ✅ 23/23 | 全量回归（Task9 后）；T-110 新观察（EVENT_CREATED 不落库）；T-068 收敛（40002）；详见 README 回归记录 |

## 断言关键词速查
- 200 / 30004 库存不足 / 30008 不存在 / 30009 状态不允许 / 30016 券 / 40201 幂等 / 40202 限流 / 403 鉴权
- 关键 L2：t_order 分片落库、t_local_message、preDeduct/confirm/release 三态、pseudoOrderId 幂等、状态机 0-5、t_order_no_mapping

## 深度 REVIEW 补充（2026-08-14 第一轮，全量代码核对）
### L0/L1 已核
- ✅ 端点安全矩阵（15 端点：用户端 JWT+HMAC 全要、内部回调 X-Internal-Call）
- ✅ 下单链路 10 步（幂等 24h/锁 10s/前置库存/真实价/券折扣不核销/事务消息/核销 P0-A/延时关单 delay16/事件快照映射）
- ✅ 事务消息：本地事务=订单+明细+本地消息表同事务；回查=查 t_local_message by orderNo（#31 单测级）
- ✅ 状态机全流转（0→1→2→3、0→4、1→5）+ 非法流转错误码
- ✅ 取消/关单释放链路（pseudoOrderId 一致性、补偿消息三 action 分发）
- ✅ xxl#10-13 运行库确认（组 8、每分钟、trigger_status=1）
- ✅ 延时关单 delayLevel=16（30min；配置可改 5=1min——**当前运行配置未显式设置=默认 16**）
- ✅ MockPayService **30% 随机失败**（余额不足）→ pay/create 有失败路径
- ✅ **T-047 实证前置**：order SkuInfoDTO 无 spuStatus（G3 修复仅 cart/product）
- ✅ 分片规则（库=uid%4 表=(uid/4)%4）+ 映射表路由（order_no_mapping 主库非分片）

### 第二轮修正（2026-08-14 前置修复后复审）
- ✅ **G5-01-08 改为 T-060 回归**（原"预期可下单"断言已被修复推翻——测试前已修下单层 SPU 校验，30003 拦截已验证）
- ✅ **新增 G5-01-23**：order pay/create（Mock 30% 失败→自动取消）——原遗漏的 order 侧支付入口
- ✅ T-061 修复安全性确认：order PaymentFeignClient（refund/status）带 InternalCallFeignConfig 自动注入 X-Internal-Call——**修复不破坏 order→payment 真实链路**
- ✅ T-062 修复走查：DB 扫描分批循环（<100 break 无死循环）、乐观锁、TIMEOUT 事件、锁 30s
- ✅ T-060 走查：30003 抛于事务消息前 → catch context==null 释放幂等键（可重试）；skuInfo==null → SKU_NOT_FOUND 覆盖部分缺失
- ✅ G5-01-01 本地消息表断言按分片（t_local_message_{uid/4%4}）修正

### 第三轮修正（2026-08-14 执行前第二轮深度 REVIEW）
- ✅ **G5-01-23 修正**：Mock 30% 失败**仅 payType=2（微信）**，payType=1 恒成功（MockPayService 代码实证）；mock 支付写 **order 库 t_payment**（MOCK_ 前缀，与 my_xhs_payment 库区分）
- ✅ **G5-01-17/18 语义修正**：死信**产生**=localMessageRetryJob 补发失败 retry≥5（MAX_RETRIES=5）自动标 status=3；**deadLetterScanJob（id=12）是死信重投**（status=3→1 或负数计数≤3）——原文档"id=12 标记死信"方向错误
- ✅ **G5-01-09 修正**：订单详情缓存 `myxhs:order:info:{id}` 是**只删不读的死缓存键**（T-031 同族，登记观察）——无缓存断言
- ✅ 补执行细节：pseudoOrderId=SHA-256(orderNo) 前 8 字节算法、幂等键用例后 DEL（24h 残留）、按分片清理清单
- ✅ 新增 G5-01-23 前置说明：payType=2 连测统计 7:3 分布

### 待 L2 确认
- [ ] 事务消息半消息→COMMIT 全链路（订单 200 后 1-3s 落库）
- [ ] G5-01-06 不存在 SKU 的具体错误码（SERVICE_CALL_FAIL vs 30004）
- [ ] 并发锁实测窗口
- [ ] pay-success 幂等第二次返回（30009 vs 200）
- [ ] T-047 下单层实证结论（G5-01-08）
- [ ] 死信构造（localMessageRetryJob 补发后 retry 递增路径）

### 已知风险
- 事务消息回查（#31）运行态不可直测（半消息难构造）——标注单测级
- console 投递若不可用（G4 已证 dashboard 403）——延时/补偿投消息降级：SQL 构造 + xxl 覆盖或改造 Outbox 通道
- 分片查询必须带 userId；裸查订单需遍历 4 库 4 表
- 下单后库存预扣是异步（inventory 消费）——断言时序注意
