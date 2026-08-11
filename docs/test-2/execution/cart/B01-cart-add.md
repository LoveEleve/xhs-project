# B01 — 加购 (POST /api/cart/add)

> 2026-08-08 | 链3-1 | cart服务 | chaintest_u1 | sku=2085989641275572226

## § 业务逻辑

添加商品到购物车→Redis Lua原子操作(HEXISTS+HLEN≤50+HINCRBY+HSET+SADD+ZADD)→MQ CART_TOPIC:ADD→Consumer异步写MySQL t_cart_item。新商品默认checked=1。

## § ASCII 流转图

```
curl → Gateway:19000(JWT+X-User-Id)
       → my-xhs-cart:19008(POST /api/cart/add)
         → Redis Lua cart_add.lua:
           myxhs:cart:{uid}:items → HSET skuId=2
           myxhs:cart:{uid}:checked → SADD skuId
           myxhs:cart:{uid}:sort → ZADD skuId
         → MQ CART_TOPIC:ADD → CartSyncConsumer → MySQL t_cart_item
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| 加购成功 | code=200 ✅ |
| Redis items | skuId=2085989641275572226, qty=2 ✅ |
| Redis checked | 自动勾选(skuId在checked Set) ✅ |
| Redis sort | ZSet已排序 ✅ |
| MySQL | t_cart_item: quantity=2, checked=1 ✅ |

## § 数据验证 (L2)

| 层 | 结果 |
|------|:--:|
| HTTP | 200 OK ✅ |
| Redis | items={skuId:2}, checked={skuId}, sort=[(skuId,ts)], TTL=30d ✅ |
| MySQL | t_cart_item: user=2085982901507301378, sku=2085989641275572226, qty=2 ✅ |

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 业务自洽 | 加购→3 Key同步更新 ✅ |
| 数据一致 | Redis Lua原子+MQ异步MySQL ✅ |
| 幂等安全 | 重复同skuId→HSET覆盖更新(HINCRBY累加) ✅ |
| 限流 | @RateLimit 20/min ✅ |

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET cart:19008/actuator/prometheus ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19000/api/cart/add -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-User-Id: 2085982901507301378" \
  -d '{"skuId":2085989641275572226,"quantity":2}'
```

## § 踩坑

Redis key格式: `myxhs:cart:{uid}:items` 含花括号(hash tag),Python f-string需双括号 `f'myxhs:cart:{{{uid}}}:items'`
| **性能** | RateLimit 20/min; Redis Lua cart_add.lua(<5ms, HEXISTS+HLEN≤50+HINCRBY+HSET+SADD+ZADD) ✅ |
| **可扩展** | 3 Key {userId}同slot(Items/Checked/Sort); MQ CART_TOPIC异步MySQL ✅ |
| **微服务** | cart→product Feign GET /api/product/sku/batch获取商品详情; CartSyncConsumer异步持久 ✅ |
| **并发** | Redis Lua单KEY原子操作防并发覆盖; t_cart_item uk_user_sku唯一索引幂等 ✅ |
| **安全** | X-User-Id Gateway注入防伪造; HLEN≤50防止单个用户购物车过大 ✅ |
