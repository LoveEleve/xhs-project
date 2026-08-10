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

| 顺序 | 端点 | 前置 | 认证 | 异常 |
|:--:|------|------|:--:|:--:|
| 1 | N01-template-create | Token | Admin | ⚠️ 缺Admin/参数非法 |
| 2 | N03-template-detail | N01 | JWT | ⚠️ 缓存30min旧validStart |
| 3 | N04-claim | N01+Token | JWT | ⚠️ 库存不足/限领超限/模板无效 |
| 4 | N05-user-coupons | N04 | JWT | — |
| 5 | N06-available-coupons | N04 | JWT | ⚠️ validStart未过滤 |
| 6 | N02-template-status | N01 | Admin | ⚠️ 缓存未清 |
| 7 | N07-coupon-discount | N04+订单 | Internal | ⚠️ 折扣计算/满减超限 |
| 8 | N08-use-coupon | N04+订单 | Internal | ⚠️ 已用/过期/并发 |
| 9 | N09-return-coupon | N08 | Internal | ⚠️ 已退/非本人 |

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
