# my-xhs-coupon 架构分析

## 一、服务拓扑

```
端口: 19007
JVM:  -Xms512m -Xmx512m (BASE)
日志: /tmp/r_coupon.log
SkyWalking: my-xhs-coupon → OAP 21.130.247.89:11800
Nacos:     namespace=my-xhs, server-addr=21.130.247.89:18848
```

## 二、依赖图

```
my-xhs-coupon
├── Redis   stock/claimed/template (库存计数+领券限次+模板缓存)
├── MySQL   my_xhs_coupon (t_coupon_template/t_user_coupon/t_coupon_outbox)
├── MQ      COUPON_CLAIM_TOPIC (生产:Outbox→Job→MQ ; 消费:CouponClaimConsumer)
├── Feign   (Order Service → Coupon: use/return/discount 内部接口)
└── Job     CouponOutboxSenderJob (扫描outbox→发送MQ→标记已发)
```

## 三、Controller 与安全

| Controller | 路径前缀 | 端点数 | 认证 |
|------|------|:--:|------|
| CouponController (管理) | `/api/coupon` | 2 | N01/N02: X-Admin-Call |
| CouponController (用户) | `/api/coupon` | 4 | N03-N06: JWT + X-User-Id |
| CouponController (内部) | `/api/coupon` | 3 | N07-N09: X-Internal-Call |

| 安全层 | 机制 | 适用 |
|------|------|------|
| 管理认证 | X-Admin-Call header校验 | N01/N02 |
| 用户认证 | Gateway JWT → X-User-Id | N03-N06 |
| 内部调用 | X-Internal-Call(`myxhs.internal.token`) | N07-N09 |
| 并发(领券) | Lua脚本原子 + claimNo唯一索引 | N04 |
| 并发(用券/退券) | 乐观锁 WHERE status=AVAILABLE/USED | N08/N09 |

## 四、数据流

### 领券 (N04 claim)
```
POST /api/coupon/claim {templateId} + X-User-Id
  → CouponService.claimCoupon()
    → 1. getTemplateWithCache(templateId)
        → Redis GET myxhs:coupon:template:{id} → ⚠️可能读到30min缓存旧validStart!
        → 未命中 → MySQL SELECT t_coupon_template → Redis SET 30min
    → 2. 校验: status=1 AND validStart<=now<=validEnd
    → 3. claim_coupon.lua: GET stock>0? → DECR stock + INCR claimed → 返回1/-1/-2/-3
    → 4. INSERT t_coupon_outbox(claimNo, status=PENDING)
    → 5. syncSend COUPON_CLAIM_TOPIC → 成功: markOutboxSent / 失败: rollbackRedisStock
    → 6. CouponOutboxSenderJob @Scheduled(5s): 扫描PENDING→重发MQ
    → 7. CouponClaimConsumer: ON DUPLICATE KEY(uk_claim_no) + decrementRemainCount
```

### 用券 (N08 use)
```
POST /api/coupon/use {userCouponId, orderId, orderAmount} + X-Internal-Call
  → CouponService.useCoupon()
    → getTemplateWithCache → 责任链:
        AmountValidator: orderAmount >= minAmount
        ExpireValidator: validStart <= now <= validEnd
        StatusValidator: status == AVAILABLE
    → 计算折扣: 满减=discountValue / 折扣=orderAmount*discountValue/100
    → 乐观锁: UPDATE SET status=USED WHERE id=? AND status=AVAILABLE
        → affected=0 → CouponAlreadyUsedException
```

### 退券 (N09 return)
```
POST /api/coupon/return {userCouponId, orderId} + X-Internal-Call
  → CouponService.returnCoupon() @Transactional
    → 乐观锁: UPDATE SET status=AVAILABLE WHERE id=? AND status=USED
    → Redis: INCR myxhs:coupon:stock:{templateId}
    → evictTemplateCache(templateId)
```

## 五、Lua 原子领券

```
claim_coupon.lua:
  EVALSHA → stock > 0? → GET claimed < perUserLimit?
  → DECR stock + INCR claimed → 返回 1(成功)
  → 库存不足 返回 -1 / 已领超限 返回 -2 / 模板无效 返回 -3
```

## 六、Outbox 模式

```
claimCoupon():
  EXECUTE LUA → 成功 → INSERT t_coupon_outbox(claimNo, status=PENDING)
  → try Send MQ 同步 → 成功 → update status=SENT
  → MQ 超时/失败 → catch → 不回滚Outbox, Job 重发

CouponOutboxSenderJob:
  定时扫描 status=PENDING → 发送 MQ → update status=SENT
  (claimNo 幂等——MQ 重发不影响)

CouponClaimConsumer:
  消费 COUPON_CLAIM_TOPIC → UPSERT t_user_coupon(uk_claim_no 唯一索引兜底)
  → 消息重复: ON DUPLICATE KEY UPDATE 幂等
```

## 七、责任链校验

```
useCoupon()/getCouponDiscount():
  AmountValidator   → 满减门槛校验(orderAmount >= minAmount)
  ExpireValidator   → 有效期校验(validStart <= now <= validEnd)
  StatusValidator   → 券状态校验(status != EXPIRED/USED)
```

## 八、缓存策略

```
模板缓存: CacheAside 30min TTL
  getTemplate() → Redis GET → 命中返回
  → 未命中 → MySQL → Redis SET(30min)
  updateTemplateStatus() → DEL myxhs:coupon:template:{id} (清缓存)
  createTemplate() → SETNX myxhs:coupon:stock:{id} 初始化库存

⚠️ 陷阱: validStart 变化后模板缓存30min不失效——
  前端看不到新validStart。测试须手动 DEL
```

## 九、batchExpire 兼容性

```
@Scheduled cron: 0 0 3 * * ? (凌晨3点)
SELECT ... FOR UPDATE LIMIT 1000 → 标记 EXPIRED
MySQL 8.0.21+ 支持 LIMIT in UPDATE，之前版本需子查询
```

## 十、Redis Key 完整清单

| Key | 类型 | TTL | 用途 |
|------|------|:--:|------|
| `myxhs:coupon:template:{id}` | String | 30min | 模板缓存(CacheAside, validStart陷阱:更新后30min不失效) |
| `myxhs:coupon:stock:{templateId}` | String | 永久 | Lua原子DECR, createTemplate时SETNX初始化 |
| `myxhs:coupon:claimed:{templateId}` | String | 永久 | Lua原子INCR, 用户已领次数(perUserLimit校验) |
| `myxhs:coupon:claimed:{templateId}:{userId}` | String | 永久 | per用户已领次数粒度
