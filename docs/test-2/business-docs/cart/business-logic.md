# my-xhs-cart 业务逻辑分析

## 一、购物车限制

```
上限: 50种商品 (cart_add.lua HLEN检查)
下限: 0种 (空购物车)
数量: 单skuId最多99件 (HINCRBY截断)
```

## 二、勾选状态

```
默认选中: HINCRBY后 SADD checked (符合99%场景)
全选:   HKEYS → DEL checked → SADD 所有skuId (原子重建)
取消全选: HKEYS → DEL checked (不移除items,仅取消选中标记)
单条勾选: HEXISTS验证存在 → SADD/SREM (防TOCTOU并发删除)
```

## 三、排序

```
ZSet按 addedAt 倒序排列 (ZREVRANGE)
新商品 ZADD NX (不覆盖已有排序)
```

## 四、匿名购物车合并

```
登录时 mergeAnonymousCart():
  Lua遍历匿名商品 → 登录购物车已有则取max(quantity)
  → 登录购物车没有则ADD
  → 已存在商品 quantity>=10000 发UPDATE事件
  → 幂等: 取max而非覆盖
```

## 五、购物车列表

```
Pipeline 批量执行:
  HGETALL items (数量)
  SMEMBERS checked (选中)
  ZREVRANGE sort (排序)
→ Feign批量取SKU (ProductFeignClient GET /api/product/sku/batch)
→ 下架/获取失败 标记 valid=false
→ 排序+计算 checkedCount/checkedAmount
→ allChecked 只看 valid=true 的商品
```

## 六、商品信息刷新

```
batchGetSkuInfo(): Feign批量查询，失败降级返回空Map
下架商品: valid=false (不阻塞返回，前端灰色展示)
valid=false的商品不计入 checkedAmount
```
