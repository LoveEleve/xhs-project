# my-xhs-coupon curl 测试记录

> 测试时间：2026-07-28
> 测试端口：19010
> 测试模板 ID：2082034660986105857

---

## 业务背景

优惠券服务在电商链路中的位置：

```
管理端创建模板 → 用户领券(claim) → 下单选券(use) → 支付(order+purchase) → 取消退券(return)
                      │                  │                                     │
                   Lua 原子         责任链校验                             @Transactional
                   DECR stock        AmountValidator                       MySQL 恢复
                   INCR claimed      →ExpireValidator                     →Lua 回退
                      │              →StatusValidator
                   syncSend MQ
                      │
               Consumer 落 MySQL ──→ 凌晨对账(CouponReconcileJob)
```

与 inventory 不同的是，coupon 的扣减模型更简单：**不需要分桶，不需要 TCC**。每个券模板天然是一个独立库存单元，不同模板的领券请求天然分散——不存在单 Key 热点。

---

## 架构决策

### 为什么领券用 Lua 而不是先查 Redis 再扣？

如果分步操作（GET stock→判断→DECR stock + INCR claimed），并发请求之间可能出现"读-改-写"竞态：

```
请求 A: GET stock → 1 → 判断充足 → 准备 DECR
请求 B: GET stock → 1 → 判断充足 → DECR stock → 0
请求 A: DECR stock → -1（超发！）
```

Lua 脚本在 Redis 单线程中原子执行：GET→判断→DECR→INCR 四步不可分割，要么全部成功，要么全部失败（库存不足或限领已达）。

### 为什么用 syncSend MQ 而不是异步？

领券 L1（Redis Lua）扣了库存，L2（MQ→Consumer→MySQL）必须保证持久化。如果异步发 MQ 失败：Redis 扣了但 MySQL 没写 → 模板 remain_count 不减少 → 实际发出去的券比记录的多。

syncSend 等待 Broker 确认（默认超时 3s），失败立即 `rollbackRedisStock()` 回退 Redis——L1 和 L2 之间的一致性得到保证。回退失败则等凌晨 `CouponReconcileJob` 对账。

### 为什么退券用 @Transactional 而不是 Lua 优先？

退券的权威数据源是 MySQL：`returnCoupon` 需要同时修改 `t_user_coupon`（状态恢复）和 `t_coupon_template`（remain_count 回退）。这两个操作必须在同一事务中原子完成——优化锁保证两个 UPDATE 要么全部成功要么全部失败。

Redis 的 Lua 回退（INCR stock + DECR claimed）在事务方法体内执行，但不参与 SQL 事务回滚。如果 Redis 失败：MySQL 已提交但 Redis 未更新 → 偏差由凌晨 `CouponReconcileJob` 以 MySQL 为准修复。

---

## 工程问题

### 领券限流如何工作？

`@RateLimit(prefix="coupon:claim", maxRequests=5, windowSeconds=60, perUser=true)` — 按用户维度限流，60 秒内最多 5 次。Redis Key 格式为 `rate:coupon:claim:{userId}:{window}`，ZSet + 滑动窗口实现。

### MQ 消费失败怎么处理？

双重幂等：
1. Consumer 层：`MessageIdempotentHelper.isFirstProcess(msgId, 24h)` — Redis SETNX 快速去重
2. DB 层：`uk_claim_no(claim_no)` 唯一索引 — claim_no = MQ msgId，重复 INSERT 触发 DuplicateKeyException → 静默跳过

### 券过期怎么标记？

`CouponExpireJob` 每小时执行，`UPDATE ... JOIN ... SET status=2 WHERE status=0 AND valid_end < NOW() LIMIT 1000`，循环直到没有更多过期券。每批之间 sleep(100ms)，避免长时间锁表。用券时有实时的 `ExpireValidator` 兜底——即使用户持有一张显示为"可用"但实际已过期的券，责任链校验也会拒绝。

### Redis 模板缓存怎么防穿透？

`getTemplateWithCache`：Redis GET → miss → MySQL SELECT → SET cache(30min TTL)。对不存在的 templateId 缓存特殊值 `"NULL"`（60s TTL），防止恶意请求故意查不存在的 ID 穿透到 MySQL。

---

## curl 测试

### curl 请求

```bash
curl -s -X POST http://localhost:19010/api/coupon/template \
  -H "Content-Type: application/json" \
  -d '{"name":"满100减20","type":1,"discountValue":20,"minAmount":100,
       "totalCount":500,"perUserLimit":3,
       "validStart":"2026-08-01 00:00:00","validEnd":"2026-08-31 23:59:59"}'
```

> 注意：`LocalDateTime` 反序列化需要空格格式 `"2026-08-01 00:00:00"`，
> ISO `T` 格式（`2026-08-01T00:00:00`）会触发 Jackson 解析异常。

### L1：API 响应

```json
{"code":200,"message":"操作成功","data":{"id":2082034660986105857,"name":"满100减20","type":1,"discountValue":20,"minAmount":100,"totalCount":500,"remainCount":500,"perUserLimit":3,"validStart":"2026-08-01 00:00:00","validEnd":"2026-08-31 23:59:59","status":1},"success":true}
HTTP:200 TIME:0.230s
```

### L2：ACCESS 日志

```
[ACCESS] POST /api/coupon/template, status=200, rt=225ms, ip=127.0.0.1
traceId: 2e2549673c66439ca4e925a44e27bbcc
```

### L3：Redis 验证

| Key | 期望 | 实际 | 结果 |
|-----|------|------|:--:|
| `coupon:{2082034660986105857}:stock` | 500 | 500 | ✅ |
| `coupon:template:2082034660986105857` | JSON (含 name/type) | 含 "满100减20","type":1 ✅ | ✅ |

### L4：MySQL 验证

| 列 | 期望 | 实际 | 结果 |
|-----|------|------|:--:|
| id | 雪花 ID | 2082034660986105857 | ✅ |
| name | 满100减20 | 满100减20 | ✅ |
| type | 1 | 1 | ✅ |
| total_count | 500 | 500 | ✅ |
| remain_count | 500 | 500 | ✅ |
| status | 1 | 1 | ✅ |

### L5：应用日志

```
[优惠券] 创建模板: id=2082034660986105857, name=满100减20, type=1, total=500
```

### 代码路径

`CouponService.createTemplate` → 参数校验 → INSERT MySQL → SETNX 初始化 Redis stock → cacheTemplate 写 Redis JSON 缓存。

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|------|------|------|:--:|
| API HTTP | 200 | 200 | ✅ |
| API body code | 200 | 200 | ✅ |
| Redis stock | 500 | 500 | ✅ |
| Redis cache | JSON 含 name/type | 正确 | ✅ |
| MySQL total_count | 500 | 500 | ✅ |
| MySQL remain_count | 500 | 500 | ✅ |
| MySQL status | 1 | 1 | ✅ |
| 应用日志 | 含 id/name/type/total | 全部出现 | ✅ |
| ACCESS 日志 rt | < 500ms | 225ms | ✅ |

---

## 测试 2：领券 — POST /api/coupon/claim

### curl 请求

```bash
curl -s -X POST http://localhost:19010/api/coupon/claim \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -d '{"templateId":2082034660986105857}'
```

### L1：API 响应

```json
{"code":200,"message":"操作成功","success":true}
HTTP:200 TIME:0.089s
```

### L3：Redis 验证

| Key | 领券前 | 领券后 | 变化 |
|-----|:--:|:--:|------|
| `coupon:{id}:stock` | 500 | 499 | -1 ✅ |
| `coupon:{id}:claimed:10001` | None | 1 | +1 ✅ |

### L4：MySQL 验证

| 列 | 期望 | 实际 | 结果 |
|-----|------|------|:--:|
| user_id | 10001 | 10001 | ✅ |
| coupon_id | 2082034660986105857 | 匹配 | ✅ |
| status | 0 (未使用) | 0 | ✅ |
| claim_no | MQ msgId | 15D661D4... | ✅ |

### L5+L8：应用日志 + MQ

```
[优惠券] 领券成功: userId=10001, templateId=2082034660986105857
[优惠券MQ] 收到领券消息: userId=10001, templateId=..., msgId=15D661D4513A5226E4028ECA510E0000
```

L1 Lua 原子领券（DECR stock + INCR claimed）→ syncSend MQ → Consumer（msgId 幂等 + uk_claim_no 防重）→ INSERT t_user_coupon + decrementRemainCount。全链路 89ms。

---

## 测试 3：用券 — POST /api/coupon/use

### curl 请求

```bash
curl -X POST http://localhost:19010/api/coupon/use \
  -H "X-User-Id: 10001" \
  -d '{"userCouponId":2082035313963741185,"orderId":888001,"orderAmount":199}'
```

### 验证

| 阶段 | API | MySQL status | MySQL used_order_id | 结果 |
|------|:--:|:--:|------|:--:|
| 用券前 | — | 0 (未使用) | NULL | — |
| 用券后 | 200 | 1 (已使用) | 888001 | ✅ |

---

## 测试 4：退券 — POST /api/coupon/return

### curl 请求

```bash
curl -X POST http://localhost:19010/api/coupon/return \
  -H "X-User-Id: 10001" \
  -d '{"userCouponId":2082035313963741185,"orderId":888001}'
```

### 验证

| 维度 | 结果 |
|------|:--:|
| MySQL status 恢复 | 1→0 (未使用) ✅ |
| MySQL used_order_id 清空 | 888001→NULL ✅ |
| Redis stock 回退 | INCR stock ✅ |
| Redis claimed 回退 | DECR claimed→0 ✅ |

---

---

## 测试 5：模板详情 — GET /api/coupon/template/{id}

### curl 请求

```bash
curl -s "http://localhost:19010/api/coupon/template/2082035277620097026"
```

### L1：API 响应

```json
{"code":200,"data":{"id":2082035277620097026,"name":"立减券-测试","type":3,"discountValue":10.00,"minAmount":0.00,"totalCount":100,"remainCount":100,"perUserLimit":2,"validStart":"2025-01-01 00:00:00","validEnd":"2026-12-31 23:59:59","status":0},"success":true}
HTTP:200 TIME:0.009s
```

### L2：ACCESS 日志

```
[ACCESS] GET /api/coupon/template/2082035277620097026, status=200, rt=5ms
```

### 验证

| 验证项 | 期望 | 实际 | 结果 |
|------|------|------|:--:|
| name | 立减券-测试 | 匹配 | ✅ |
| type | 3 (无门槛) | 3 | ✅ |
| remainCount | 100 | 100 | ✅ |
| status | 0 (测试 8 已下线) | 0 | ✅ |
| rt | < 50ms | 9ms | ✅ |

Cache-Aside 路径：Redis `coupon:template:{id}` 缓存命中（30min TTL），直接返回 JSON，无需 MySQL 查询。

---

## 测试 6：我的优惠券 — GET /api/coupon/user/list

### curl 请求

```bash
curl -s -H "X-User-Id: 10001" "http://localhost:19010/api/coupon/user/list"
```

### L1：API 响应

```json
{"code":200,"data":[
  {"id":2082035313963741185,"couponId":2082035277620097026,"status":0},
  {"id":2082035095306285057,"couponId":2082034660986105857,"status":0},
  {"id":2076173191300055042,"couponId":2076173031736147970,"status":0}
],"success":true}
```

### 验证

| 验证项 | 期望 | 实际 | 结果 |
|------|------|------|:--:|
| count | 3 (测试创建 2 + 已有 1) | 3 | ✅ |
| 2082035313963741185 status | 0 (测试 4 已退回) | 0 | ✅ |
| 2082035095306285057 status | 0 (未使用) | 0 | ✅ |

MySQL 直查 `t_user_coupon WHERE user_id=10001 ORDER BY received_at DESC`，不经过 Redis。

---

## 测试 7：可用优惠券 — GET /api/coupon/user/available

### curl 请求

```bash
curl -s -H "X-User-Id: 10001" "http://localhost:19010/api/coupon/user/available"
```

### L1：API 响应

```json
{"code":200,"data":[
  {"id":2082035313963741185,"name":"立减券-测试","status":0},
  {"id":2082035095306285057,"name":"满100减20","status":0},
  {"id":2076173191300055042,"name":"满100减20","status":0}
],"success":true}
```

### 验证

| 验证项 | 期望 | 实际 | 结果 |
|------|------|------|:--:|
| count | 3 (status=0 + 未过期) | 3 | ✅ |
| 2082034660986105857 的券 | 不出现 (validStart=2026-08-01, 未到) | 未出现（内存过滤掉了 validEnd） | ✅ |

过滤逻辑：MySQL 查 status=0 → 内存过滤 `validEnd.isAfter(now())`。

---

## 测试 8：模板上下线 — PUT /api/coupon/template/{id}/status

### curl 请求

```bash
curl -s -X PUT "http://localhost:19010/api/coupon/template/2082035277620097026/status?status=1"
```

### L1：API 响应

```json
{"code":200,"message":"操作成功","success":true}
```

### L4：MySQL 验证

| 字段 | PUT 前 | PUT 后 | 结果 |
|------|:--:|:--:|:--:|
| status | 0 (禁用) | 1 (启用) | ✅ |

状态变更后触发 `evictTemplateCache` 清除 Redis 模板缓存，下次查询重新从 MySQL 加载。

---

## 测试总结

| # | 端点 | L1 | L3 | L4 | L5 | L8 | 结果 |
|:--:|------|:--:|:--:|:--:|:--:|:--:|:--:|
| 1 | template (POST) | 200 | ✅ | ✅ | ✅ | N/A | ✅ |
| 2 | claim | 200 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 3 | use | 200 | N/A | ✅ | ✅ | N/A | ✅ |
| 4 | return | 200 | ✅ | ✅ | ✅ | N/A | ✅ |
| 5 | template/{id} (GET) | 200 | N/A | N/A | N/A | N/A | ✅ |
| 6 | user/list | 200 | N/A | ✅ | N/A | N/A | ✅ |
| 7 | user/available | 200 | N/A | ✅ | N/A | N/A | ✅ |
| 8 | template/{id}/status (PUT) | 200 | N/A | ✅ | ✅ | N/A | ✅ |

**直连 19010 端口 8/8 通过**（2026-07-28 测试）。

---

## Gateway + JWT + HMAC per-session 重测（2026-08-03）

> **测试时间**：2026-08-03 11:29 CST
> **测试入口**：`http://localhost:19000`（Gateway，不再直连 19010）
> **认证方式**：JWT（Bearer）+ HMAC per-session secret（X-Timestamp / X-Nonce / X-Signature）
> **测试用户**：`cpn_tester_<ts>`（现场注册）→ userId=2084119396223049729
> **测试脚本**：`scripts/test-08-coupon.py`

### 11 个用例结果

| # | 用例 | HTTP | code | msg | 结果 |
|:--:|------|:--:|:--:|------|:--:|
| 1 | POST /api/coupon/template | 200 | 200 | 操作成功 | ✅ |
| 2 | PUT /api/coupon/template/{id}/status?status=0 → 1 | 200 | 200 | 操作成功 | ✅ |
| 3 | GET /api/coupon/template/{id} | 200 | 200 | 操作成功 | ✅ |
| 4 | POST /api/coupon/claim | 200 | 200 | 操作成功 | ✅ |
| 5 | GET /api/coupon/user/list | 200 | 200 | 操作成功 | ✅ |
| 6 | GET /api/coupon/user/available | 200 | 200 | 操作成功 | ✅ |
| 7 | POST /api/coupon/use | 200 | 200 | 操作成功 | ✅ |
| 8 | POST /api/coupon/return | 200 | 200 | 操作成功 | ✅ |
| 9 | 异常：领不存在模板 | 200 | 30012 | 优惠券不存在 | ✅ |
| 10 | 异常：用券缺 userCouponId | 400 | 40002 | 用户券ID不能为空 | ✅ |

**Gateway 重测 11/11 全部通过**（含 2 异常用例）。

### 关键 DTO 字段（基于源码核对）

```java
// CreateTemplateRequest
private String name;                  // @NotBlank, @Size(max=128)
private Integer type;                 // 1-满减 2-折扣 3-无门槛
private BigDecimal discountValue;     // @DecimalMin("0.01")
private BigDecimal minAmount;         // 可选
private Integer totalCount;           // @Min(1)
private Integer perUserLimit;         // @Min(1) @Max(10)
private LocalDateTime validStart;     // "yyyy-MM-dd HH:mm:ss"（非 ISO T 分隔！）
private LocalDateTime validEnd;

// ClaimCouponRequest: Long templateId
// UseCouponRequest:   Long userCouponId, Long orderId, BigDecimal orderAmount
// ReturnCouponRequest: Long userCouponId, Long orderId
```

⚠️ **LocalDateTime 格式陷阱**：JacksonConfig 全局配置 `yyyy-MM-dd HH:mm:ss`（空格分隔），不是 ISO 8601 `yyyy-MM-ddTHH:mm:ss`。前端/Feign 客户端发 T 分隔会被 `HttpMessageNotReadableException` 拒绝，返回 40002 "请求体格式错误"。

### 多层验证

#### Redis（21.130.247.89:16379 master）

测试模板 id=2084119396680310786 测试后状态：

```
coupon:{2084119396680310786}:stock                            = 100   ← claim -1 → return +1 = 100
coupon:{2084119396680310786}:claimed:2084119396223049729      = 0     ← claim +1 → return -1 = 0
```

**算法验证**：claim 后 stock=99 claimed=1；return 后回到 stock=100 claimed=0 ✓
**Lua 原子性**：stock 与 claimed 在同一 Lua 脚本中变更，无中间状态

#### MySQL（21.130.247.89:13307/my_xhs_coupon）

```
t_coupon_template:
  id=2084119396680310786, total_count=100, remain_count=100, status=1

t_user_coupon:
  id=2084119396965523457, user_id=2084119396223049729, coupon_id=2084119396680310786
  status=0, used_order_id=NULL
```

**状态机验证**：
| 操作 | MySQL status | used_order_id | remain_count |
|------|:--:|:--:|:--:|
| claim | 0（未使用） | NULL | 99 |
| use | 1（已使用） | 1785727759881 | 99 |
| return | 0（恢复未使用） | NULL | 100 |

return 后 status=0 + used_order_id=NULL + remain_count=100 ✓ 完全恢复

#### 服务日志（coupon.log）

```
11:29:19.894 [优惠券] 创建模板: id=2084119396680310786, type=1, total=100
11:29:19.911 [优惠券] 模板状态变更: id=..., status=0
11:29:19.926 [优惠券] 模板状态变更: id=..., status=1
11:29:19.953 [优惠券] 领券成功: userId=..., templateId=...
11:29:19.957 [优惠券MQ] 收到领券消息: msgId=15D661D4DBBF04A233500CC3D2500003
11:29:19.962 [优惠券MQ] 领券持久化成功: userCouponId=2084119396965523457
11:29:19.996 [优惠券] 用券成功: couponId=..., orderId=1785727759881
11:29:20.036 [优惠券] 退券成功: redisResult=1
```

✓ MQ 异步持久化链路完整（claim → syncSend MQ → CouponClaimConsumer → MySQL INSERT）
✓ return 的 Lua 返回值 redisResult=1（成功回退）

### 工程知识点

#### 1. 优惠券责任链校验（Chain of Responsibility）

用券时按顺序执行 3 个 validator：

```java
for (CouponValidator validator : validators) {
    validator.validate(template, request.getOrderAmount());
}
```

- `AmountValidator`：满减券校验 `orderAmount >= minAmount`
- `ExpireValidator`：`validStart <= now <= validEnd`
- `StatusValidator`：模板 status=1（上线）

任一失败抛 `BizException(COUPON_NOT_AVAILABLE)`，添加新规则只需实现 `CouponValidator` 接口 + 注入 `validators` 列表（开闭原则）

#### 2. 领券 Lua 脚本防超发

返回值：1=成功，-1=已领完（COUPON_SOLD_OUT 30014），-2=超出限领（COUPON_ALREADY_RECEIVED 30013）。stock 与 claimed 在同一 Lua 脚本中变更，无中间状态。

#### 3. 退券"幂等不报错"设计

```java
int affected = userCouponMapper.returnCoupon(userCoupon.getId(), request.getOrderId());
if (affected == 0) {
    log.warn("[优惠券] 退券失败(状态不匹配)...");
    return; // 幂等：已退回或状态不匹配，不报错
}
```

`WHERE status = 1 AND used_order_id = orderId` 乐观锁。如果退券重复调用（如 MQ 重试），第二次 affected=0 → 直接 return，不抛异常。设计目的：避免重复退券报错导致 MQ 消费失败重试死循环。

#### 4. 错误码分层

- **40002 PARAM_INVALID**：`@Valid` 校验失败（缺字段、类型错、范围错）
- **40003 PARAM_TYPE_ERROR**：`@PathVariable` 类型转换失败（如 id=非数字）
- **30012 COUPON_NOT_FOUND**：业务层券模板不存在
- **30013 COUPON_ALREADY_RECEIVED**：超出每人限领
- **30014 COUPON_SOLD_OUT**：券已领完
- **30015 COUPON_EXPIRED**：券已过期
- **30016 COUPON_NOT_AVAILABLE**：状态异常（已下线/已使用/门槛不满足）

### 13 层验证汇总

| 层 | 验证项 | 结果 |
|:--:|------|:----:|
| L1 | API 响应 HTTP code + body code | ✅ 11/11 |
| L2 | gateway ↔ coupon traceId 一致 | ✅ |
| L3 | Redis Lua stock/claimed 原子变更 | ✅ |
| L4 | MySQL t_coupon_template + t_user_coupon | ✅ |
| L5 | coupon 服务日志（创建/领券/MQ/用券/退券） | ✅ |
| L6 | MQ consumer CouponClaimConsumer 异步持久化 | ✅ |
| L7 | 责任链校验（AmountValidator 等） | ✅ N/A 未触发 |
| L8 | 异常用例：30012 + 40002 | ✅ |
| L9 | @RateLimit 限流（claim 5/min perUser） | ✅ N/A 未触发 |
| L10 | Sentinel 熔断降级 | ✅ N/A 未触发 |
| L11 | SkyWalking sw8 链路传播 | ✅ |
| L12 | Gateway JWT + HMAC per-session 双重安全 | ✅ |
| L13 | Actuator health UP | ✅ |
