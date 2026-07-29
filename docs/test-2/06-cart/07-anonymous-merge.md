# 匿名购物车合并算法

> 源码：`CartService.mergeAnonymousCart()`（第 425-468 行）
> 验证：`02-cart-test.md` §1.9

---

## 1. 使用场景

用户在未登录状态下浏览商品并加入购物车（匿名购物车），登录后匿名购物车需要与已有购物车合并。

```
登录前：匿名购物车 [skuId=A qty=3, skuId=B qty=1]
登录后：已有购物车 [skuId=A qty=5, skuId=C qty=2]

合并结果：[skuId=A qty=5, skuId=B qty=1, skuId=C qty=2]
          ↑ 取 max(3,5)=5    ↑ 新增    ↑ 原有不变
```

---

## 2. 合并算法

```java
mergeAnonymousCart(userId, request):
  for each MergeItem item:
    if exists(user cart):
      // 已存在：取较大数量（幂等）
      int mergedQty = min(max(currentQty, item.qty), 99)
      HSET itemsKey skuId mergedQty
    else if currentSize < 50:
      // 新商品：直接加入
      HSET itemsKey skuId min(item.qty, 99)
      currentSize++

    // 默认选中 + 首次时间戳
    SADD checkedKey skuId
    ZADD sortKey NX timestamp skuId  // NX: 已存在不覆盖时间
```

**取 max 策略而不是累加**：多次合并结果一致——合并两次 `{A:3, B:1}` 的结果仍然是 `{A:3, B:1}`（不是 `{A:6, B:2}`）。取较大数量保证幂等。

**ZADD NX 保护首次时间**：`NX` 参数只对不存在成员执行添加——如果 SKU 在匿名购物车和已有购物车中都有，保留已有购物车的时间戳（更早加入），不覆盖。

---

## 3. 非原子性的设计权衡

```java
// mergeAnonymousCart 不使用 Lua 脚本
for (each item):
  HEXISTS → HSET → SADD → ZADD(NX)  // 每个商品 4 次 Redis 命令
```

**潜在的并发问题**：

```
线程 A: mergeAnonymousCart（skuId=D）        线程 B: addToCart(skuId=E)
  currentSize = HLEN → 49                        Lua: HLEN=49 → HINCRBY → 50
  currentSize < 50 → HSET skuId=D                Lua: SADD checked(E)

  → 如果 addToCart 在 merge 的 HLEN 之后执行：
    merge 看到 49 但 addToCart 把它变成 50，merge 再加变成 51
  → 超了 50 上限！
```

**为什么接受这个风险？** 合并是**登录时的一次性操作**——一个用户每天最多合并几次。概率极低（登录瞬间 addToCart 并发），后果可接受（多 1 个品种，列表仍正常展示）。为低频操作写 Lua 脚本的复杂度不值得。

---

## 4. 与 addToCart 的合并语义对比

| 操作 | 同 SKU 行为 | 上限检查 | 原子性 |
|------|------|:---:|:---:|
| `addToCart` | HINCRBY 累加（3 + 2 = 5） | ✅ Lua HLEN ≤ 50 | Lua 原子 |
| `mergeAnonymousCart` | `max(currentQty, mergeQty)` | ❌ 非原子 HLEN | 非原子 |

addToCart 累加（多次加购同 SKU），合并取 max（不想覆盖用户已加购的数量）。语义不同源于使用场景——加购是重复行为累加，合并是选择性取大。

---

## 5. 发散：其他电商的购物车合并策略

| 平台 | 策略 | 适合场景 |
|------|------|------|
| **取 max**（当前） | 同 SKU 取较大 | 希望保留用户已加购数量 |
| 累加 | 同 SKU 直接相加 | 简单直观，但可能超限 |
| 覆盖 | 登录后完全替换 | 大促场景，锁定用户选择 |
| 提示选择 | 弹窗让用户手动合并 | 高价值商品，不能出错 |

取 max 是电商的通用做法——用户已登录后加购的数量通常比未登录时更精确（已登录时会认真考虑数量）。如果用户在未登录时加了 10 个，登录后又加了 1 个，取 10 而不是 11——更接近用户的实际意图。

---

## 关联文档

- `01-cart-module.md` — §7 匿名购物车合并
- `02-cart-test.md` — §1.9 合并测试
