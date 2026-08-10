# my-xhs-cart 测试执行计划

> 10端点 | 链3 | 依赖链1(Token)+链2(SKU) | 参照 TEMPLATE.md

---

## 一、前置准备

```bash
# 确认服务在线 + 获取 Token + 确认有可用SKU
curl -s "http://21.130.247.89:18848/nacos/v1/ns/instance/list?serviceName=my-xhs-cart&namespaceId=my-xhs" | python3 -c "import json,sys;print('cart:',len(json.load(sys.stdin)['hosts']))"

TOKEN=$(cat /tmp/test_token.txt)
SKU_ID=$(mysql -h 21.130.247.89 -P 3306 -u root -p'Xhs@2026#MySQL' -N -e "SELECT id FROM my_xhs_product.t_sku WHERE status=1 LIMIT 1" 2>/dev/null)
SKU_ID2=$(mysql -h 21.130.247.89 -P 3306 -u root -p'Xhs@2026#MySQL' -N -e "SELECT id FROM my_xhs_product.t_sku WHERE status=1 LIMIT 1 OFFSET 1" 2>/dev/null)
echo "SKU_ID=$SKU_ID SKU_ID2=$SKU_ID2"
```

---

## 二、执行顺序

| 顺序 | 端点 | 依赖 | 产出 | 异常 |
|:--:|------|------|------|:--:|
| 1 | C01-cart-add | Token+SKU_ID | execution/cart/C01.md | ⚠️ 超50种/无SKU/缺JWT |
| 2 | C04-cart-check | C01 | execution/cart/C04.md | ⚠️ 未添加先勾选 |
| 3 | C06-cart-list | C01 | execution/cart/C06.md | ⚠️ Product不可用 |
| 4 | C09-cart-count | C01 | execution/cart/C09.md | — |
| 5 | C02-cart-update-quantity | C01 | execution/cart/C02.md | ⚠️ 已删SKU更新 |
| 6 | C05-cart-check-all | C01 | execution/cart/C05.md | — |
| 7 | C01-cart-add(SKU2) | — | — | — |
| 8 | C03-cart-remove | C01+SKU2 | execution/cart/C03.md | ⚠️ 删不存在的SKU |
| 9 | C07-cart-merge | C01 | execution/cart/C07.md | ⚠️ 合并满仓 |
| 10 | C08-cart-clear | C01 | execution/cart/C08.md | — |
| 11 | C10-cart-reconcile | Admin | execution/cart/C10.md | ⚠️ 缺Admin |

---

## 三、异常场景

### 3.1 认证

| 场景 | 端点 | 预期 |
|------|------|------|
| 缺 JWT | C01/C06/C09 | 401 |
| 缺 X-User-Id(直连) | C01 | 401 |
| 缺 Admin-Call | C10 | 403 |

### 3.2 业务规则

| 场景 | 端点 | 预期 |
|------|------|------|
| SKU 不存在 | C01 | "商品不存在" |
| 数量超99 | C01(quantity=100) | @Max(99) → 400 |
| 购物车满50种 | C01(连续51次不同SKU) | 第51次 429 |
| 更新已删除SKU | C02(skuId被C03删除后) | CART_ITEM_NOT_FOUND |
| 合并满仓 | C07(登录车满50种) | 部分未合并 |

### 3.3 数据一致性

| 场景 | 验证 | 预期 |
|------|------|------|
| 加购后立即列表 | C01→C06 | 列表中包含新SKU |
| 更新数量后列表 | C02→C06 | quantity正确 |
| 清空后列表 | C08→C06 | 空列表 |
| 合并后原匿名车不变 | C07→Redis | 匿名车key已删除 |

### 3.4 MQ 一致性

| 场景 | 验证 | 预期 |
|------|------|------|
| 加购后MySQL同步 | C01→MySQL `SELECT FROM t_cart_item WHERE user_id=? AND sku_id=?` | 1行 |
| 删除后MySQL同步 | C03→MySQL | deleted=1 |

---

## 四、测试数据速查

| 数据 | 值 | 来源 |
|------|------|------|
| TOKEN | `cat /tmp/test_token.txt` | 链1 U03 |
| SKU_ID / SKU_ID2 | P01 P06 创建 | 链2 |
| ADMIN_TOKEN | `my-xhs-admin-token-2026` | 环境 |
