# my-xhs-coupon 测试执行计划

> 9端点 | 链4 | 依赖链1(Token) | 参照 TEMPLATE.md

---

## 一、前置准备

```bash
TOKEN=$(cat /tmp/test_token.txt)
ADMIN_TOKEN="my-xhs-admin-token-2026"

# 确认服务在线
curl -s "http://21.130.247.89:18848/nacos/v1/ns/instance/list?serviceName=my-xhs-coupon&namespaceId=my-xhs" | python3 -c "import json,sys;print('coupon:',len(json.load(sys.stdin)['hosts']))"
```

---

## 二、执行顺序

| 顺序 | 端点 | 前置 | 认证 | 产出 | 异常 |
|:--:|------|------|:--:|------|:--:|
| 1 | N01-template-create | Token | Admin | execution/coupon/N01-template-create.md | ⚠️ 缺Admin/参数非法 |
| 2 | N03-template-detail | N01 | JWT | execution/coupon/N03-template-detail.md | ⚠️ 缓存30min旧validStart |
| 3 | N10-template-list | 无(公开) | 公开 | execution/coupon/N10-template-list.md | ⚠️ 领券中心取列表 |
| 4 | N04-claim | N01+Token | JWT | execution/coupon/N04-claim.md | ⚠️ 库存不足/限领超限/模板无效 |
| 5 | N05-user-coupons | N04 | JWT | execution/coupon/N05-user-coupons.md | — |
| 6 | N06-available-coupons | N04 | JWT | execution/coupon/N06-available-coupons.md | ⚠️ validStart未过滤 |
| 7 | N02-template-status | N01 | Admin | execution/coupon/N02-template-status.md | ⚠️ 缓存未清 |
| 8 | N07-coupon-discount | N04+订单 | Internal | execution/coupon/N07-coupon-discount.md | ⚠️ 折扣计算/满减超限 |
| 9 | N08-use-coupon | N04+订单 | Internal | execution/coupon/N08-use-coupon.md | ⚠️ 已用/过期/并发 |
| 10 | N09-return-coupon | N08 | Internal | execution/coupon/N09-return-coupon.md | ⚠️ 已退/非本人 |

---

## 三、异常场景

### 3.1 认证

| 场景 | 端点 | 预期 |
|------|------|------|
| 缺 JWT | N04 | 401 |
| 缺 Admin-Call | N01 | 403 |
| 缺 Internal-Call | N08 | 403 |

### 3.2 业务规则

| 场景 | 端点 | 预期 |
|------|------|------|
| 领券库存不足 | N04(第total+1次) | -1 COUPON_SOLD_OUT |
| 超 perUserLimit | N04(第perUserLimit+1次) | -2 ALREADY_RECEIVED |
| validStart 未生效 | N04(validStart在未来) | 模板无效 |
| validEnd 已过期 | N04(已过期模板) | 过期 |
| 折扣计算8.5折 | N07(100元订单) | 减免=15元(非85) |
| 满减超订单金额 | N07(50元券+30元订单) | 减免=30元(非50) |
| 并发用券 | N08(两次POST同券) | 一次成功一次冲突 |

### 3.3 数据一致性

| 场景 | 验证 | 预期 |
|------|------|------|
| 领券后Redis库存 | N04→Redis DECR | stock减少1 |
| 领券后MySQL插入 | N04→MySQL | t_user_coupon新增1行 |
| 领券后claimed计数 | N04→Redis INCR | claimed+1 |
| 用券后状态 | N08→MySQL | status=1(USED) |
| 退券后状态 | N09→MySQL | status=0(AVAILABLE) |
| 退券后Redis | N09→Redis | stock INCR+1 |

---

## 四、测试数据速查

| 数据 | 值 | 来源 |
|------|------|------|
| TOKEN | `cat /tmp/test_token.txt` | 链1 U03 |
| ADMIN_TOKEN | `my-xhs-admin-token-2026` | 环境 |
| INTERNAL_TOKEN | `my-xhs-internal-token-2026` | 环境 |
| TEMPLATE_ID | N01 返回 | N01 创建 |
| USER_COUPON_ID | N04 返回 | N04 领券 |

---
## 测试要点补充（2026-08-10，实测修正）

- **认证**：N01/N02 管理端点需 JWT+Admin；N04 领券需 JWT+HMAC；N10 公开。
- **N01-template-create**：日期格式必须 `yyyy-MM-dd HH:mm:ss`（空格，非ISO"T"）；validStart 必须未来；否则 40002。
- **N04-claim 异步落库**：返回200后需等 2-5s 再查 N05。
- **N08-use 校验模板状态**：模板下架则 30016(COUPON_NOT_AVAILABLE)；用券/退券/折扣是**内部端点**(X-Internal-Call 直连 19010，带 X-User-Id)。
- **N07/N08/N09 内部端点**：GET /api/coupon/discount/{id}?orderAmount=、POST /use{userCouponId,orderId,orderAmount}、POST /return{userCouponId,orderId}。
- **N10-template-list（新增公开接口）**：GET /api/coupon/template/list，返回可领券列表。

---
## L0-L4 逐端点核对清单

### N04-claim
- [ ] L0: token+HMAC + templateId(可领)
- [ ] L1正常: 200; 异常: 库存不足→COUPON_SOLD_OUT, 超限→ALREADY_RECEIVED
- [ ] L2: Redis `myxhs:coupon:stock:{id}` DECR + `claimed` INCR; MySQL `t_user_coupon` 新增(等MQ 2-5s)
- [ ] L3: 并发(超卖Lua原子) / 幂等 / 限领

### N08-use / N09-return
- [ ] L1: POST use → 200 discount; return → 200
- [ ] L2: MySQL `t_user_coupon.status` 0→1→0
- [ ] L3: 并发用券一次成功 / 幂等; 模板须上架否则30016

### N01-template-create
- [ ] L1: 200; 异常: 过去validStart→40002
- [ ] L2: MySQL `t_coupon_template` 新增
- [ ] L3: 日期格式(空格) / 校验
