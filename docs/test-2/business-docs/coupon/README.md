# my-xhs-coupon 优惠券服务

> 9个端点 | CouponController | Lua原子领券 + Outbox模式 + 责任链校验

---

## 架构概览

```
管理端 HTTP(X-Admin-Call) → CouponController(/api/coupon) → CouponService → MySQL t_coupon_template
用户端 HTTP(X-User-Id)    → CouponController(/api/coupon) → CouponService → Redis库存 + MQ + MySQL
内部接口(X-Internal-Call)  → CouponController(/api/coupon) → CouponService → 责任链校验 + 乐观锁
```

## 端点清单

| ID | 方法 | 路径 | 类型 | 说明 | 认证 |
|------|------|------|------|------|:--:|
| N01 | POST | `/api/coupon/template` | 管理 | 创建券模板 | X-Admin-Call |
| N02 | PUT | `/api/coupon/template/{id}/status` | 管理 | 上下线 | X-Admin-Call |
| N03 | GET | `/api/coupon/template/{id}` | 用户 | 券模板详情 | JWT+X-User-Id |
| N04 | POST | `/api/coupon/claim` | 用户 | 领券(Lua原子) | JWT+X-User-Id |
| N05 | GET | `/api/coupon/user/list` | 用户 | 我的优惠券 | JWT+X-User-Id |
| N06 | GET | `/api/coupon/user/available` | 用户 | 可用优惠券(下单) | JWT+X-User-Id |
| N07 | GET | `/api/coupon/discount/{id}` | 内部 | 查询折扣(不核销) | X-Internal-Call |
| N08 | POST | `/api/coupon/use` | 内部 | 用券(核销) | X-Internal-Call |
| N09 | POST | `/api/coupon/return` | 内部 | 退券 | X-Internal-Call |

## Key Redis

| Key | 类型 | TTL | 用途 |
|------|------|:--:|------|
| `myxhs:coupon:template:{id}` | Hash | 30min | 模板缓存(CacheAside) |
| `myxhs:coupon:stock:{templateId}` | String | 永久 | 剩余库存数 |
| `myxhs:coupon:claimed:{templateId}:{userId}` | String | 永久 | 已领次数(perUserLimit) |

> ⚠️ **validStart 缓存陷阱**: 模板缓存30min不主动失效——validStart开始时间变了旧模板仍会命中缓存，测试须手动 `DEL myxhs:coupon:template:{id}`。

## Key MySQL

| 表 | 库 | 说明 |
|------|------|------|
| t_coupon_template | my_xhs_coupon | 券模板定义(type/discount/minAmount/perUserLimit...) |
| t_user_coupon | my_xhs_coupon | 用户领券记录(uk_claim_no唯一索引+status字段) |
| t_coupon_outbox | my_xhs_coupon | Outbox待发送MQ消息(claimNo幂等) |

## MQ

| Topic | 生产方 | 消费方 |
|------|------|------|
| COUPON_CLAIM_TOPIC | claimCoupon(outbox→Job→MQ) | CouponClaimConsumer(写t_user_coupon) |
