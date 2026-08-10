# my-xhs-order 订单服务模块

## 模块概览

| 项目 | 内容 |
|------|------|
| 模块路径 | `my-xhs-order/` |
| 端口 | 19011 |
| 服务名 | `my-xhs-order`（Nacos） |
| 分片库 | `my_xhs_order_0 ~ my_xhs_order_3`（4 库 × 4 表 = 16 物理表） |
| 独立库 | `my_xhs_order`（订单号映射表） + `my_xhs_payment`（支付记录） |
| Java 源文件 | 30+ 个（Controller/Service/Mapper/Entity/Feign/Consumer/Job/Config） |
| 启动类 | `OrderApplication.java` |

**职责边界**：订单创建（事务消息 + ShardingSphere 分库分表）、状态流转（乐观锁 + Event Sourcing）、超时关单（延时消息 + 定时兜底）、联动释放（Feign 调用 inventory 释放库存 + coupon 退券 + payment 退款）、补偿重试（本地消息表 + 定时补发）。

**核心设计理念**：

```
下单链路：
  幂等校验→分布式锁→发送事务消息(半消息)→本地事务(INSERT order+item+event)→Commit消息
    ↓ 30分钟后
  超时关单：延时消息 → OrderCloseConsumer → cancel order → release inventory + return coupon

取消链路：
  Event Sourcing(appendEvent)→乐观锁 UPDATE→Feign 并行(inventory.release + coupon.return)

支付链路（remote）：
  order.pay/create → Feign → payment → 回调 order.pay-success → 状态 0→1
```

---

## 1. 数据模型

### 1.1 分库分表架构

ShardingSphere-JDBC 分片规则（`sharding-config.yaml`）：

```
user_id % 4 → 库路由 (ds0~ds3)
(user_id / 4) % 4 → 表路由 (_0~_3)

5 张分片表：t_order, t_order_item, t_order_event, t_order_snapshot, t_local_message
```

| 组件 | 说明 |
|------|------|
| 分片键 | `user_id`——同一用户的订单商品事件快照全部落在同一分片 |
| 绑定表 | 5 张表全部 `bindingTables`，避免 JOIN 笛卡尔积 |
| 主键 | Snowflake（worker-id 基于本机 IP 动态计算） |

### 1.2 核心表 — `t_order`

```sql
id                BIGINT PK       -- 雪花 ID
user_id           BIGINT          -- 分片键
order_no          VARCHAR(32)     -- 订单号 yyyyMMddHHmmssSSS+后缀
total_amount      DECIMAL(10,2)   -- 原价
pay_amount        DECIMAL(10,2)   -- 实付
discount_amount   DECIMAL(10,2)   -- 优惠金额
coupon_id         BIGINT          -- 使用的优惠券 ID
status            INT             -- 0待付款 1已付款 2已发货 3已完成 4已取消 5已退款
address_snapshot  TEXT            -- 收货地址快照（JSON）
remark            VARCHAR(255)
paid_at           DATETIME
delivered_at      DATETIME
completed_at      DATETIME
cancelled_at      DATETIME
deleted           TINYINT         -- @TableLogic
created_at        DATETIME
updated_at        DATETIME
```

### 1.3 核心表 — `t_order_item`

```sql
id, order_id, user_id（分片键冗余）, sku_id, spu_id, sku_name, sku_image,
price (DECIMAL), quantity (INT), total_amount (DECIMAL), created_at
```

### 1.4 核心表 — `t_order_event`（Event Sourcing）

```sql
id (AUTO_INCREMENT), order_id, user_id, event_type (VARCHAR), from_status (INT),
to_status (INT), payload (JSON), event_seq (INT), event_time, created_at
UNIQUE INDEX uk_order_event_seq (order_id, event_seq)
```

### 1.5 独立表 — `t_order_no_mapping`（非分片键查询路由）

```sql
-- 存放在独立库 my_xhs_order，不分片
id (AUTO_INCREMENT), order_no (VARCHAR UNIQUE), user_id, order_id, created_at
```

### 1.6 核心表 — `t_local_message`（补偿机制）

```sql
id, user_id（分片键冗余）, transaction_id, service_name, operation_type,
payload (JSON), status (0待处理 1成功 2失败 3死信),
retry_count, next_retry_time, created_at, updated_at
```

### 1.7 支付表 — `t_payment`（独立库 my_xhs_payment）

```sql
id, order_id, user_id, payment_no, amount (DECIMAL), pay_type (1支付宝 2微信),
status (0待支付 1成功 2失败 3已退款), paid_at, deleted (@TableLogic)
```

---

## 2. 订单状态机

```
                    ┌────────────────────────────┐
                    │          0 - 待付款         │
                    │  （初始状态，等待支付）       │
                    └─────┬──────────────┬───────┘
                          │              │
               [支付成功]   │              │  [用户取消/超时关单]
                          ▼              ▼
           ┌──────────────┐    ┌──────────────┐
           │  1 - 已付款   │    │  4 - 已取消    │
           │  （等待发货）  │    │  （终态）      │
           └──┬───────┬───┘    └──────────────┘
              │       │
  [发货]      │       │  [退款成功]
              ▼       ▼
  ┌─────────┐  ┌──────────────┐
  │2-已发货 │  │   5 - 已退款   │
  │         │  │   （终态）     │
  └──┬──────┘  └──────────────┘
     │
     │  [确认收货]
     ▼
  ┌──────────────┐
  │  3 - 已完成   │
  │  （终态）     │
  └──────────────┘
```

**乐观锁保护**：所有状态变更 `WHERE status = currentStatus`，并发冲突时 `affected=0` → 抛异常。

**Event Sourcing**：每次状态变更是 `appendEvent(order, EVENT_TYPE, payload)` → 先 INSERT event（INSERT IGNORE + 唯一索引防重）→ 再 UPDATE order（乐观锁）。事件流可回放恢复任意时刻状态。

---

## 3. 接口清单（14 个 REST 端点）

| 方法 | 路径 | 说明 | 限流 |
|:----:|------|------|------|
| POST | `/order/create` | 创建订单（事务消息） | @RateLimit 5/60s |
| GET | `/order/{orderId}` | 订单详情 | — |
| GET | `/order/list` | 我的订单 | — |
| GET | `/order/by-order-no/{orderNo}` | 按订单号查（走映射表） | — |
| POST | `/order/cancel` | 取消订单 | @RateLimit 10/60s |
| POST | `/order/confirm` | 确认收货 | — |
| POST | `/order/deliver` | 发货 | — |
| POST | `/order/pay/create` | 创建支付（mock/remote） | — |
| GET | `/order/pay/status/{orderId}` | 支付状态 | — |
| POST | `/order/pay-success` | 支付成功回调 | — |
| POST | `/order/pay-fail` | 支付失败回调 | — |
| POST | `/order/refund-success` | 退款成功回调 | — |
| POST | `/order/refund-fail` | 退款失败回调 | — |
| GET | `/order/pay-amount` | 查询支付金额 | — |

---

## 4. 核心流程

### 4.1 下单完整链路

```
用户 POST /order/create
  │
  ├── 1. 幂等校验：SETNX order:idempotent:{bizIdentifier} (24h TTL)
  │        → 已存在 → IDEMPOTENT_REJECT
  │
  ├── 2. 分布式锁：SETNX order:create:lock:{userId} (UUID value, 10s TTL)
  │        → 不存在 → LOCK_ACQUIRE_FAIL
  │
  ├── 3. 计算金额：generateOrderNo + calculateTotal + 优惠金额
  │
  ├── 4. 发送事务消息（半消息）：
  │     rocketMQTemplate.sendMessageInTransaction(
  │         ORDER_TRANSACTION_TOPIC, msg, context)
  │     │
  │     └─→ RocketMQ 回调 OrderTransactionListener.executeLocalTransaction()
  │           └─→ @Transactional → INSERT t_order + t_order_item + t_local_message
  │               → 成功: COMMIT（消息对消费者可见）
  │               → 失败: ROLLBACK（消息不回传）
  │
  ├── 5. 发送延时消息（30分钟超时关单）
  │
  ├── 6. Event Sourcing：appendEvent(CREATED)
  ├── 7. 订单快照：takeSnapshot(CREATED)
  └── 8. 映射表：orderNo → (userId, orderId)
```

### 4.2 取消订单链路

```
POST /order/cancel
  │
  ├── 1. 查询 Order → status != 0 → ORDER_STATUS_ERROR
  │
  ├── 2. Event Sourcing：appendEvent(CANCELLED) + 乐观锁 UPDATE status=4
  │
  ├── 3. Feign 并行调用（CompletableFuture, 3s 超时）：
  │     ├── inventory.releaseStock(orderId)     → 失败→发送补偿 MQ
  │     └── coupon.returnCoupon(userCouponId)    → 失败→发送补偿 MQ
  │
  ├── 4. 订单快照：takeSnapshot(CANCELLED)
  └── 5. 清除 Redis 缓存
```

### 4.3 超时关单双重保障

```
保障 1：RocketMQ 延时消息（delayLevel=16, ~30分钟）
  sendMessageInTransaction → 半消息 → 本地事务 → COMMIT

保障 2：XXL-Job 每分钟扫描
  orderCloseJob → selectTimeoutOrders(30分钟前，游标分页)
    → 逐单 cancelOrder → 乐观锁 WHERE status=0
```

### 4.4 支付链路（remote 模式）

```
POST /order/pay/create
  │
  ├── remote → Feign → PaymentFeignClient.pay()
  │     └─ payment 服务：pay → 回调 order.pay-success → 状态 0→1
  │
  └── mock → MockPayService.createPayment()
        └─ 本地 INSERT t_payment → markPaid → 状态 0→1
```

---

## 5. 中间件交互

### 5.1 Feign — 3 个被调用方

| Feign Client | 目标服务 | 调用方法 | 场景 |
|------|------|------|------|
| InventoryFeignClient | my-xhs-inventory | `releaseStock(orderId)` | 取消/超时关单 |
| CouponFeignClient | my-xhs-coupon | `returnCoupon(userId, couponId)` | 取消/超时关单 |
| PaymentFeignClient | my-xhs-payment | `pay()`, `getPaymentStatus()`, `refund()` | 支付/退款 |

### 5.2 RocketMQ

| 角色 | Topic | 说明 |
|------|-------|------|
| TransactionListener | ORDER_TRANSACTION_TOPIC | 下单事务消息，半消息→本地事务→Commit |
| Consumer | ORDER_CLOSE_TOPIC | 延时消息，30 分钟超时关单 |
| Consumer | ORDER_COMPENSATION_TOPIC | Feign 失败补偿重试 |

### 5.3 XXL-Job

| Job | 调度 | 说明 |
|------|------|------|
| orderCloseJob | 每分钟 | 扫描超时订单（30 分钟前）- 兜底 |
| orderMappingRepairJob | 每 5 分钟 | 映射表补录 |
| localMessageRetryJob | 每 30 秒 | 补偿消息补发 |
| deadLetterScanJob | 每小时 | 死信扫描 |

---

## 6. 配置

```yaml
server.port: 19011
spring.application.name: my-xhs-order
# ShardingSphere: 4 库 × 4 表, user_id 分片键
# MySQL: 13308 (分片库) + mapping库 + payment库
# Redis: Sentinel 16379/16380/16381
# Nacos: 21.130.247.89:18848, namespace=my-xhs
# RocketMQ: 21.130.247.89:9876
# XXL-Job Admin: http://21.130.247.89:18080, executor port 9991
# Pay type: mock (default) | remote
```

---

## 7. 跨模块对比

| 维度 | order | inventory | coupon |
|------|-------|-----------|--------|
| 角色 | 编排调用方 | 被调用方 | 被调用方 |
| Feign | 调 3 个服务 | 无（纯被调） | 无（纯被调） |
| 事务模型 | RocketMQ 事务消息 | L1/L2 + TCC | MQ 异步 |
| 分片 | ShardingSphere 4×4 | 无 | 无 |
| Event Sourcing | ✅ | — | — |
| 补偿机制 | 本地消息表 + 定时补发 | L3 对账 | 凌晨对账 |

---

## 8. 已知问题

### 8.1 优惠金额是 Mock 固定值

```java
BigDecimal discountAmount = request.getCouponId() != null
    ? new BigDecimal("10.00") // Mock: 优惠券固定减 10 元
    : BigDecimal.ZERO;
```

优惠金额硬编码为 10 元，未从 coupon 服务查询实际面额。生产需要 Feign 查模板 discountValue。

### 8.2 分布式锁 value 竞争

`order:create:lock:{userId}` 锁 value = UUID，释放用 Lua 原子 `GET + 比较 + DEL`。但如果锁在 10s 内恰好过期，另一个请求获取同一锁的同一 UUID 概率几乎为 0——UUID 足够保证唯一性。
