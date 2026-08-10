# 18 购物车与库存

> 复审维度 18 | 覆盖模块：06-cart, 07-inventory | 领域专属检查项
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。本维度覆盖购物车三结构合并、库存预扣/分桶/TCC 超卖防护等独有问题。
> 通用规则：并发见 02、TCC/Outbox见 06、缓存权威见 03.1、Canal回声见 03.7。

---


**执行本维度后，必须在审查报告中输出 `[18] 18 购物车与库存：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [18]）。**
## 检查项

### 18.1 购物车三结构合并正确 | 透镜：业务/并发

**必须检查**：未登录购物车（Redis）→ 登录购物车（Redis）→ DB 三方合并的正确性——是否丢失/覆盖数据。

**怎么查**：
```bash
grep -rn 'merge\|cartMerge\|mergeItem\|mergeCart' my-xhs-cart/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| merge 后未登录车未清 | merge 后未登录 Redis 数据没删→下次 merge 重复加 |
| 数量覆盖非累加 | 未登录车 sku=3 + 登录车 sku=2→merge 后 = 3（覆盖非 5） |
| merge 并发 | 两个设备同时 merge→并发更新→后到覆盖先到 |
| checked 状态丢失 | merge 后勾选状态重置→用户已选中的被清空 |

**案例**：cart merge 已有商品用旧数量覆盖新数量→新购买的商品被旧购物车覆盖（`cart_merge_item.lua` 修复加 maxItemQuantity 截断 + 返回值区分新旧）。

---

### 18.2 库存分桶与扩容 | 透镜：工程/性能

**必须检查**：库存分桶数量是否合理（太少竞争激烈、太多 Redis key 爆炸）；扩容过程中是否避免了并发读写。

**怎么查**：
```bash
grep -rn 'bucket\|Bucket\|分桶\|TOTAL_BUCKETS\|resize\|rebalance\|rehash' my-xhs-inventory/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 分桶数太少 | 10 桶→高并发时单桶热点→单 Redis 分片阻塞 |
| 分桶数太多 | 10000 桶→每个 SKU 10000 个 key→key 数爆炸→影响 Redis scan |
| 扩容中并发读写 | resize→一半桶新值一半桶旧值→并发增删读到了不一致 |
| Pipeline 非原子 | 用 Pipeline 写全部桶→中间可穿插其他命令→读到部分新值 |

**案例**：`InventoryService.resizeBuckets` 用 Pipeline 写桶数据→中间 preDeduct 读到部分新值（修复 resize 期间加 pause 标记 + `ResizeInProgressException`）。

---

### 18.3 库存预扣与确认/释放 | 透镜：业务/分布式

**必须检查**：预扣（PRE_DEDUCT）→确认（CONFIRM）→释放（RELEASE）的完整链路——confirm 是否有 Consumer；release 的 ID 派生是否正确。

**怎么查**：
```bash
grep -rn 'preDeduct\|confirmDeduct\|releaseStock\|PRE_DEDUCT\|CONFIRM\|RELEASE' my-xhs-inventory/src/main/java/
grep -rn 'confirmDeduct\|confirm.*inventory' my-xhs-order/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| confirm 无 Consumer | PRE_DEDUCT 发出→无 CONFIRM Consumer→预扣永远不确认→可用库存越来越少 |
| release OrderId 派生不一致 | Inventory 用 fold-hash orderNo→Order 用真实 orderId→release 永远查不到预扣 |
| 预扣→confirm 期间库存不可用 | Redis 不可用→预扣失败→下单失败→需降级 |
| 无预扣超时自动释放 | 下单未支付→预扣永久锁定→库存永久不可用 |

**案例**：confirm 链路断裂——`/api/inventory/confirm` 零调用方；order `releaseInventory` 用真实 orderId→inventory 用 pseudoOrderId→释放链路断裂（order 修复加 `derivePseudoOrderId` + `confirmDeduct` 调用）。

---

### 18.4 购物车时间戳保护 | 透镜：并发/分布式

**必须检查**：购物车的更新时间检查是否使用了 eventTime 而非 `now()`；MQL 到的时间戳和 DB 的 DATETIME 精度是否匹配。

**怎么查**：
```bash
grep -rn 'updatedAt\|eventTime\|isBefore\|isAfter\|LocalDateTime.now()' my-xhs-cart/src/main/java/com/myxhs/cart/consumer/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| catch 用 now() | INSERT 冲突→catch upsert 用 `LocalDateTime.now()`→比 eventTime 晚→乱序事件覆盖正确状态 |
| updateCheckedStatus 用 now() | 勾选状态用服务器时间替代事件时间→乱序覆盖 |
| DB DATETIME 秒级 | cart DDL 用 DATETIME→秒级精度→同秒操作顺序不保证 |

**案例**：`CartSyncConsumer` catch 块用 `now()` 替代 eventTime + updateCheckedStatus 同问题 + DDL DATETIME→DATETIME(3)（`CartSyncConsumer.java:129/139`）。

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| TCC fence 三态门禁 | 06.1 | Try/Confirm/Cancel 幂等 |
| Outbox 四部正确性 | 06.4 | markSent/cancel/Job/SendResult |
| 缓存权威方向 | 03.1 | L1 Redis→异步 MySQL |
| Canal 回声防护 | 03.7 | UPDATE DELETE Redis L1 |
| 锁粒度 | 02.8 | preDeduct 分桶锁粒度 |
| 缓存回填安全 | 03.5 | 滞后 MySQL 覆盖 Redis |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl my-xhs-cart,my-xhs-inventory -am
mvn test -pl my-xhs-cart,my-xhs-inventory

# 购物车 merge
grep -rn 'merge\|cartMerge\|mergeItem' my-xhs-cart/src/main/java/

# 库存分桶
grep -rn 'bucket\|Bucket\|分桶\|TOTAL_BUCKETS\|resize' my-xhs-inventory/src/main/java/

# 预扣/确认/释放链
grep -rn 'preDeduct\|confirmDeduct\|releaseStock\|PRE_DEDUCT\|CONFIRM\|RELEASE' my-xhs-inventory/src/main/java/ my-xhs-order/src/main/java/

# 购物车时间戳
grep -rn 'updatedAt\|eventTime\|isBefore\|isAfter\|LocalDateTime.now()' my-xhs-cart/src/main/java/com/myxhs/cart/consumer/
```
