# my-xhs-payment 支付服务模块

## 模块概览

| 项目 | 内容 |
|------|------|
| 模块路径 | `my-xhs-payment/` |
| 端口 | 19012 |
| 服务名 | `my-xhs-payment`（Nacos） |
| 数据库 | `my_xhs_payment`（MySQL 13308，独立库） |
| 数据访问 | JdbcTemplate（非 MyBatis-Plus） |
| 策略模式 | `PayChannelStrategy`（Mock/Alipay/Wechat） |

**职责边界**：创建支付单、处理支付/退款回调、通知订单服务（Feign 回调 + MQ）、超时检查、对账。被 order 通过 Feign 调用，同时通过 Feign 回调 order。

**核心设计理念**：

```
支付：order Feign → pay → 策略路由 → 创建支付单 → 模拟支付 → MQ 通知 → Feign 回调 order
退款：order Feign → refund → 创建退款单 → 模拟退款 → MQ 通知 → Feign 回调 order
对账：定时任务扫描超时 → 主动重试 → 通知订单服务
```

---

## 1. 数据模型

### 1.1 `t_payment`（支付表）

```sql
id            BIGINT PK         -- 雪花 ID
order_id      BIGINT            -- 订单 ID
user_id       BIGINT            -- 用户 ID
payment_no    VARCHAR(64)       -- 支付流水号
amount        DECIMAL(10,2)     -- 支付金额
pay_type      INT               -- 1支付宝 2微信 99Mock
status        INT               -- 0待支付 1成功 2失败 3已退款
paid_at       DATETIME
deleted       TINYINT           -- @TableLogic
created_at    DATETIME
updated_at    DATETIME
```

状态机：`0(待支付)→1(成功)→3(已退款)` 或 `0→2(失败)`

### 1.2 `t_refund`（退款表）

```sql
id             BIGINT PK
payment_id     BIGINT           -- 关联支付单
order_id       BIGINT
user_id        BIGINT
refund_no      VARCHAR(64)
refund_amount  DECIMAL(10,2)
reason         VARCHAR(255)
status         INT              -- 0退款中 1成功 2失败 3关闭
refund_type    INT              -- 1仅退款 2退货退款
refund_channel INT              -- 1原路退回 2退余额
success_at     DATETIME
deleted        TINYINT          -- @TableLogic
```

状态机：`0(退款中)→1(成功)` / `→2(失败)` / `→3(关闭)`

---

## 2. 接口清单（5 个 REST 端点）

| 方法 | 路径 | 说明 | 限流 |
|:----:|------|------|------|
| POST | `/payment/pay` | 发起支付 | @RateLimit 10/60s |
| POST | `/payment/callback/{payType}` | 第三方支付回调 | — |
| POST | `/payment/refund` | 发起退款 | — |
| POST | `/payment/refund-callback/{payType}` | 第三方退款回调 | — |
| GET | `/payment/status/{orderId}` | 查询支付状态 | — |

---

## 3. 核心架构

### 3.1 策略模式（PayChannelStrategy）

```
PayChannelStrategy (接口)
  ├── MockPayStrategy     (payType=99 或默认)
  ├── AlipayPayStrategy   (payType=1)
  └── WechatPayStrategy   (payType=2)
```

Spring 通过 `Map<Integer, PayChannelStrategy>` 自动注入——Key 为 payType 到 Strategy Bean 的映射。

### 3.2 Feign 回调

payment 通过 `OrderFeignClient` 回调 order 服务：

| 方法 | 调用 order 端点 |
|------|------|
| notifyPaySuccess | POST /order/pay-success |
| notifyPayFail | POST /order/pay-fail |
| notifyRefundSuccess | POST /order/refund-success |
| notifyRefundFail | POST /order/refund-fail |
| getOrderPayAmount | GET /order/pay-amount |

### 3.3 MQ 通知

| Topic | Tag | 说明 |
|------|------|------|
| PAY_RESULT_TOPIC | PAY_SUCCESS / PAY_FAIL | 支付结果通知 |
| REFUND_RESULT_TOPIC | REFUND_SUCCESS / REFUND_FAIL | 退款结果通知 |

### 3.4 XXL-Job 定时任务

| Job | 调度 | 说明 |
|------|------|------|
| PaymentTimeoutCheckJob | 每 30 秒 | 扫描超时支付单（30 分钟） |
| RefundTimeoutCheckJob | 每 60 秒 | 扫描超时退款单（15 天） |
| PaymentNotifyCompensateJob | 每 2 分钟 | 补偿丢失的支付通知 |
| RefundNotifyCompensateJob | 每 3 分钟 | 补偿丢失的退款通知 |

---

## 4. 幂等与并发控制

| 措施 | 实现 |
|------|------|
| 支付幂等 | Redis SETNX `payment:paying:{orderId}` (30min TTL) |
| 退款幂等 | Redis SETNX `payment:refunding:{paymentId}` (7d TTL) |
| 回调幂等 | 乐观锁 `WHERE status = 当前状态` |
| 分布式锁 | Redisson `payment:lock:pay:{orderId}` (30min) |
| 定时任务互斥 | Redisson 分布式锁 |

---

## 5. 跨模块对比

| 维度 | payment | order's MockPayService |
|------|---------|------|
| 角色 | 独立支付服务 | order 内置 mock |
| 策略模式 | ✅ 完整策略（Mock/Alipay/Wechat） | 仅 Mock |
| Feign 回调 | ✅ 回调 order | 无 |
| MQ 通知 | ✅ PAY_RESULT/REFUND_RESULT | 无 |
| 超时检查 | ✅ 4 个 XXL-Job | 无 |

---

## 6. 配置

```yaml
server.port: 19012
spring.application.name: my-xhs-payment
# MySQL: 21.130.247.89:13308/my_xhs_payment (JdbcTemplate 独立数据源)
# Redis: Sentinel 16379/16380/16381
# Nacos: 21.130.247.89:18848, namespace=my-xhs
# RocketMQ: 21.130.247.89:9876
# XXL-Job Admin: http://21.130.247.89:18080, executor port 9992
```

---

## 7. 已知问题

### 7.1 策略模式未接入真正第三方

Mock/Alipay/Wechat Strategy 的结构存在，但 `AlipayPayStrategy` 和 `WechatPayStrategy` 目前是桩实现——没有真实的 API 调用。仅 `MockPayStrategy` 有完整逻辑。
