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