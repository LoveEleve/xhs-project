# my-xhs-order curl 测试记录

> 测试时间：2026-07-29
> 测试端口：19011
> 15 层验证标准

---

## 业务背景

订单服务是整个下单链路的指挥官：

```
用户下单 → order.create
  │
  ├── 幂等校验 + 分布式锁
  ├── 事务消息（半消息→本地事务→Commit）
  │     └── INSERT t_order + t_order_item + t_local_message（ShardingSphere 分片）
  ├── 延时消息（30分钟超时关单）
  └── 下游 Consumer 收到消息 → inventory.preDeduct

支付 → order.paySuccess → 状态 0→1
取消 → order.cancel → Feign 并行: inventory.release + coupon.return
```

**架构决策**：
- **事务消息保证下单原子性**：createOrder 先发半消息 → RocketMQ 回调 executeLocalTransaction → 本地事务（order+item+message 同一 @Transactional）→ Commit/Rollback
- **ShardingSphere 分库分表**：user_id%4→库，(user_id/4)%4→表，同一用户所有数据在相同分片
- **Event Sourcing 状态机**：每次状态变更 appendEvent→乐观锁 UPDATE，可回放恢复

---

## 测试 1：创建订单 — POST /api/order/create

### curl 请求

```bash
curl -s -X POST http://localhost:19011/api/order/create \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 20001" \
  -d '{"skuItems":[{"skuId":999,"quantity":2}],"couponId":null,"addressId":1,"bizIdentifier":"trace-test-002"}'
```

### L1：API 响应

```json
{"code":200,"data":{"orderId":2082307019940876290,"orderNo":"ORD2026072911273560200010002","totalAmount":198.00,"payAmount":198.00,"discountAmount":0.00,"status":0,"statusDesc":"待付款"},"success":true}
HTTP:200 TIME:0.044s
```

### L2：ACCESS 日志

```
[ACCESS] POST /api/order/create, status=200, rt=42ms, ip=127.0.0.1
traceId: 114b605ffec744f796671113e815769a
```

### L3：Redis 验证

| Key | 期望 | 实际 | 结果 |
|-----|------|------|:--:|
| `order:idempotent:trace-test-002` | 1 (24h TTL) | 1 | ✅ |
| `order:create:lock:20001` | None (safeUnlock 已释放) | nil | ✅ |

### L4：MySQL 验证（分片）

user_id=20001 → ds=20001%4=1, table=(5000)%4=0 → `my_xhs_order_1.t_order_0`

| 列 | 期望 | 实际 | 结果 |
|-----|------|------|:--:|
| status | 0 (待付款) | 0 | ✅ |
| total_amount | 198.00 (2×99) | 198.00 | ✅ |
| pay_amount | 198.00 | 198.00 | ✅ |
| order_no | ORD202607... | 匹配 | ✅ |

### L5：应用日志

```
[订单] 创建成功(事务消息): userId=20001, orderNo=ORD2026072911273560200010002, payAmount=198.00
ShardingSphere: ds1 INSERT t_order_0, ds1 INSERT t_order_item_0, ds1 INSERT t_local_message_0
```

**事务消息完整链路**：半消息→executeLocalTransaction→INSERT order+item+message 同一 @Transactional→COMMIT。

### L9：@RateLimit 验证

| 请求 | 结果 | 说明 |
|:--:|:--:|------|
| 第 1 次 | 200 | 通过 |
| 第 2-5 次 | 40201 | 幂等拦截（同一 bizIdentifier） |
| 第 6 次 | 40202 | @RateLimit(5/60s) 触发 |

### L13：Actuator 健康检查

```json
/actuator/health → {"status":"UP","components":{"db":"UP","redis":"UP","nacos":"UP"}}
```

### L14：ES 日志 + 全链路 traceId

traceId `114b605ffec744f796671113e815769a` 在 ES 中命中 21 条日志，涵盖：
- ShardingSphere Logic SQL + Actual SQL
- 事务消息执行日志
- 订单快照 INSERT
- Event Sourcing 事件

| 验证项 | 期望 | 实际 | 结果 |
|------|------|------|:--:|
| API HTTP | 200 | 200 | ✅ |
| traceId | 32 位 hex 贯穿 | 114b605f... | ✅ |
| 分片库路由 | ds1 | ds1 ✅ | ✅ |
| 分表路由 | t_order_0 | t_order_0 ✅ | ✅ |
| 幂等键 | SETNX 成功 | 1 | ✅ |
| 分布式锁 | safeUnlock 释放 | nil | ✅ |
| @RateLimit | 第 6 次拒绝 | 40202 | ✅ |
| ES traceId 独立字段 | grok 提取生效 | 21 hits | ✅ |

---

## 测试 2：订单详情 — GET /api/order/{orderId}

### curl 请求

```bash
curl -s -H "X-User-Id: 20001" "http://localhost:19011/api/order/2082307019940876290"
```

### L1：API 响应

```json
{"code":200,"data":{"orderId":2082307019940876290,"orderNo":"ORD2026072911273560200010002","totalAmount":198.00,"payAmount":198.00,"status":4,"statusDesc":"已取消","items":[{"skuId":999,"quantity":2,"price":99.00}]},"success":true}
```

### L4：MySQL 验证（分片路由）

user_id=20001 → ds1, t_order_0。MySQL 确认数据一致：status=4（超时关单自动取消 ✅），total=198。

| 验证项 | 期望 | 实际 | 结果 |
|------|------|------|:--:|
| API HTTP | 200 | 200 | ✅ |
| orderNo | 匹配 | 匹配 | ✅ |
| status | 4 (已取消-超时关单生效) | 4 | ✅ |
| items count | 1 | 1 | ✅ |
| 分片路由 | ds1→t_order_0 | ds1→t_order_0 | ✅ |

---

## 测试 3-4：订单列表 + 按订单号查询

| # | 端点 | 结果 |
|:--:|------|:--:|
| 3 | GET `/order/list` (X-User-Id:20001) | 200, count=2 ✅ |
| 4 | GET `/order/by-order-no/ORD2026...` | 200, mapping 表路由正确 ✅ |

---

## 测试 5：取消订单 — POST /api/order/cancel

创建 orderId=2082329674207264770 → 取消 → status 0→4 ✅

---

## 测试 6-8：状态流转（支付→发货→确认收货）

| # | 端点 | 状态变化 | 结果 |
|:--:|------|:--:|:--:|
| 8 | POST `/pay/create` (mock) | 0→1 | ✅ |
| 7 | POST `/deliver` | 1→2 | ✅ |
| 6 | POST `/confirm` | 2→3 | ✅ |

最终 MySQL status=3（已完成），完整状态机已验证。

---

## 测试 9-14：支付回调端点

| # | 端点 | 状态 |
|:--:|------|:--:|
| 9 | POST `/pay-success` | ⚠️ 需 remote payment 服务回调 |
| 10 | POST `/pay-fail` | ⚠️ 同上 |
| 11 | POST `/refund-success` | ⚠️ 同上 |
| 12 | POST `/refund-fail` | ⚠️ 同上 |
| 13 | GET `/pay/status/{orderId}` | ⚠️ remote 模式不可用 |
| 14 | GET `/pay-amount` | ⚠️ 同上 |

---

## 测试总结

| # | 端点 | L1 | L3 | L4 | L5 | L9 | L14 | 结果 |
|:--:|------|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| 1 | create | 200 | ✅ | ✅ | ✅ | ✅ 40202 | ✅ traceId | ✅ |
| 2 | {orderId} | 200 | N/A | ✅ | N/A | N/A | N/A | ✅ |
| 3 | list | 200 | N/A | N/A | N/A | N/A | N/A | ✅ |
| 4 | by-order-no | 200 | N/A | N/A | N/A | N/A | N/A | ✅ |
| 5 | cancel | 200 | N/A | ✅ | ✅ | N/A | N/A | ✅ |
| 6 | confirm | 200 | N/A | ✅ | N/A | N/A | N/A | ✅ |
| 7 | deliver | 200 | N/A | ✅ | N/A | N/A | N/A | ✅ |
| 8 | pay/create | 200 | N/A | ✅ | N/A | N/A | N/A | ✅ |
| 9-14 | callbacks | — | — | — | — | — | — | ⚠️ |

**8/8 可测端点通过，6 回调端点待 payment 服务**。

**发现的修复**：traceId 跨 MQ 事务消息传播，补了一行 `MqTraceHelper.wrapWithTraceId()`。
| rt | < 100ms | 44ms | ✅ |

---

## Gateway + JWT + HMAC per-session 重测（2026-08-03）

> 测试时间：2026-08-03 14:55-14:56
> 测试入口：Gateway 19000
> 认证方式：JWT + HMAC per-session secret + X-User-Id header
> 测试用户：order_tester_* (register→login, userId=2084171442041151489)
> 测试脚本：`scripts/test-09-order.py`
> 测试模式：`pay.type=mock`（默认）

### 16 个用例结果（14 接口 + 2 异常）

| # | 用例 | HTTP | code | msg | 结果 |
|:--:|------|:--:|:--:|------|:--:|
| 1 | POST 创建订单 | 200 | 200 | 操作成功 | ✅ |
| 2 | GET 订单详情 | 200 | 200 | 操作成功 | ✅ |
| 3 | GET 订单列表 | 200 | 200 | 操作成功 | ✅ |
| 4 | GET 按订单号查询 | 200 | 200 | 操作成功 | ✅ |
| 5 | POST 创建支付(mock) | 200 | 200 | 操作成功 | ✅ |
| 6 | GET 支付状态 | 200 | 200 | 操作成功 | ✅ |
| 7 | POST 发货 | 200 | 200 | 操作成功 | ✅ |
| 8 | POST 确认收货 | 200 | 200 | 操作成功 | ✅ |
| 9 | POST 取消订单 | 200 | 200 | 操作成功 | ✅ |
| 10 | POST 支付成功回调 | 200 | 200 | 操作成功 | ✅ |
| 11 | POST 支付失败回调 | 200 | 200 | 操作成功 | ✅ |
| 12 | POST 退款成功回调 | 200 | 200 | 操作成功 | ✅ |
| 13 | POST 退款失败回调 | 200 | 200 | 操作成功 | ✅ |
| 14 | GET 支付金额 | 200 | 200 | 操作成功 | ✅ |
| 15 | 异常: 创建缺 skuItems | 400 | 40002 | 商品列表不能为空 | ✅ |
| 16 | 异常: 取消不存在订单 | 200 | 30008 | 订单不存在 | ✅ |

**16/16 全部通过**。

### 关键 DTO 字段（基于源码核对）

```java
// OrderCreateRequest — POST /api/order/create
@NotEmpty  List<SkuItem> skuItems      // SkuItem: @NotNull skuId + @NotNull quantity
          Long            couponId      // 可选（不使用优惠券即可省略）
@NotNull  Long            addressId     // 收货地址 ID
          String          remark;       // 订单备注
@NotBlank String          bizIdentifier // 幂等键（每个订单唯一）

// PayRequest — POST /api/order/pay/create
@NotNull  Long   orderId
@NotNull  Integer payType   // 1-支付宝(永远成功)  2-微信(Mock 30% 随机失败)

// DeliverRequest — POST /api/order/deliver
@NotNull  Long   orderId
          String logisticsCompany
          String trackingNo
```

**观察**：MockPayService payType=1 直接成功，payType=2 ThreadLocalRandom 30% 失败。

### 状态机验证（MySQL 13308 端口，分片路由 ds1.t_order_0）

| 订单 ID | 状态 | 路径 | 验证 |
|------|:--:|------|:--:|
| `...97010` (订单 A) | 3 已完成 | 待付款→支付→发货→确认收货 | ✅ |
| `...93121` (订单 B) | 4 已取消 | 待付款→取消 | ✅ |
| `...28705` (订单 C) | 1 已付款 | 待付款→支付成功回调 | ✅ |
| `...15425` (订单 D) | 4 已取消 | 待付款→支付失败回调(自动取消) | ✅ |
| `...39489` (订单 E) | 5 已退款 | 待付款→支付→退款成功回调 | ✅ |

所有状态流转符合 `0→[1→2→3|4|5]` 定义。

### 多层验证

#### Redis
- **幂等键**：`order:idempotent:order-test-{ts}-{biz}` = 1，TTL ~24h（10 条，对应 2 次运行 × 5 订单）✅
- **分布式锁**：`order:create:lock:*` 全部已释放（safeUnlock Lua）✅
- **限流计数器**：`order:cancel:*` 缓存已过期 ✅

#### MySQL（端口 13308，非 13309）
- **分库路由**：userId=2084171442041151489 → ds1(my_xhs_order_1), table=t_order_0 ✅
- **映射表**：`my_xhs_order.t_order_no_mapping` 5 条记录，order_no ↔ user_id ↔ order_id ✅
- **订单表**：`my_xhs_order_1.t_order_0` 5 条记录，status 正确（3/4/1/4/5）✅
- ⚠️ **端口发现**：order 独立 MySQL 端口 13308（非 13309），因为 ShardingSphere 配置了单独的数据源

#### 服务日志
- `[Gateway] 鉴权通过` — JWT + X-User-Id 校验通过 ✅
- `ShardingSphere-SQL Logic SQL: INSERT INTO t_order` → `Actual SQL: ds1 ::: INSERT INTO t_order_0` ✅
- `[OrderEvent] 幂等跳过：订单已是目标状态` — Event Sourcing append ✅
- `[订单回调] 收到支付成功通知` — 回调端点日志正常 ✅

#### traceId 全链路
- traceId=`5b96014705644599b52c9567796a9b95`
- Gateway：`>>> POST /api/order/create` → `<<< status=200 duration=645ms`
- Order：`ShardingSphere Logic SQL → Actual SQL ds1` + `[OrderEvent]`
- **Gateway ↔ Order traceId 一致** ✅

#### Feign 编排验证（间接）
- 支付失败回调 → `onPaymentFailed` → Feign 并行 `inventory.release` + `coupon.return`（无优惠券时跳过 coupon）
- 退款成功回调 → `onRefundSuccess` → Feign 顺序 `inventory.release` + `coupon.return`
- Fallback：`CompletableFuture` 并行 3s 超时 + `ORDER_COMPENSATION_TOPIC` 补偿

### 工程知识点

1. **双端口 MySQL**：order 独立端口 13308（4 分库 + 1 主库），其余服务共用 13309。查 order 数据必须连 13308。
2. **MockPayService payType=1 vs 2**：支付宝永远成功，微信 `ThreadLocalRandom` 30% 失败 → `onPaymentFailed` 自动取消释放库存。测试用 payType=1 保证确定性。
3. **@RateLimit(5/60s) 限流触发**：首次测试创建 6 个订单时第 6 个被限流拒绝。修复方案是减少创建次数（退款失败回调复用订单 E，该回调仅记日志无状态变更）。
4. **回调端点无 X-User-Id**：支付/退款回调（10-14）通过 `t_order_no_mapping` 反查 userId 做分片路由，不依赖 header。
5. **退款失败回调无状态校验**：`notifyRefundFail` 仅打日志返回 200 OK，不检查订单状态 —— 只为异步通知支付服务记录。
6. **cancel @RequestParam 非 @RequestBody**：取消订单用 `?orderId=xxx` query string 传参，非 JSON body。

### 13 层验证汇总

| 层 | 验证项 | 结果 |
|:--:|------|:--:|
| L1 | API 响应 HTTP 200 + body code 200 | ✅ |
| L2 | Gateway ↔ Order traceId 一致 (5b960147...) | ✅ |
| L3 | Redis：10 idempotency key + lock 已释放 + rate limit 清除 | ✅ |
| L4 | MySQL：5 orders in ds1.t_order_0, 4 status states correct | ✅ |
| L5 | ShardingSphere Logic→Actual SQL (ds1INSERT t_order_0) | ✅ |
| L6 | MQ：RocketMQ 事务消息 (orders persisted = commit succeeded) | ✅ |
| L7 | OrderEvent event sourcing ("幂等跳过：已是目标状态") | ✅ |
| L8 | 异常用例：40002 (缺 skuItems) + 30008 (订单不存在) | ✅ |
| L9 | @RateLimit：create 5/60s → 第 6 次触发限流 | ✅ |
| L10 | Sentinel：actuator UP, enabled=true | ✅ |
| L11 | SkyWalking：N/A（按操作手册本测试不覆盖 sw8 链路） | N/A |
| L12 | Gateway JWT auth + HMAC per-session secret | ✅ |
| L13 | Actuator /health UP, 15 服务注册, Sentinel/Redis/Nacos UP | ✅ |