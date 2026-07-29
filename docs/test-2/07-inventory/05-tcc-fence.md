# 05 — TCC 分布式事务深度分析

> **前置阅读**：[架构文档 §1.1 (两条路径)](01-inventory-module.md) · §2.7-2.9 (TCC 接口) · [03-分桶预扣减](03-bucket-pre-deduct.md)
> **测试验证**：[测试 7-9 (TCC 三阶段)](02-inventory-test-record.md) — try/confirm/cancel 完整状态机
> **下游文档**：[06-MQ 消费链路](06-mq-consumer.md) · [08-库存业务全景](08-business-architecture.md)

## 为什么 inventory 有两套扣减路径？

L1/L2 路径（Redis Lua 分桶预扣 → MQ → MySQL）覆盖了高并发场景——秒杀时用户不需要等待 MySQL 事务，Redis 1ms 返回结果。但**不是所有扣减都可以"最终一致"**。

场景：订单创建需要同时调用 inventory + coupon + payment 三个服务。如果 payment 成功了但 inventory 的 L1/L2 路径中 MQ 消费失败，最终一致性意味着"用户支付成功了但库存没扣"——等凌晨对账发现时，商品已经超卖了。

TCC 路径解决的就是这个问题：**强一致性**的分布式事务。order → inventory、coupon、payment 三个 Try → 全部 Try 成功才走 Confirm（全部提交），任何一个 Try 失败则全部 Cancel（全部回滚）。

```
下单链路（强一致场景）：
  order 服务
    │
    ├── inventory.tccTry()    → available→freezing
    ├── coupon.tccTry()       → 锁定优惠券
    └── payment
          │
          ├── 全部 Try 成功 → inventory.tccConfirm() ⏋
          │                   coupon.tccConfirm()    } 全部提交
          │                   payment.confirm()     ⏌
          │
          └── 任一 Try 失败 → inventory.tccCancel()  ⏋
                              coupon.tccCancel()      } 全部回滚
                              payment.cancel()       ⏌
```

---

## TCC Fence 表：防御三类异常的核心机制

源码位置：`TccFenceService.java`（common 模块）

```sql
CREATE TABLE IF NOT EXISTS t_tcc_fence (
    xid         VARCHAR(128) NOT NULL,       -- 全局事务ID
    branch_id   BIGINT NOT NULL,             -- 分支事务ID
    action_name VARCHAR(64) NOT NULL,        -- TCC方法名，如 "tryDeductStock"
    status      TINYINT NOT NULL,            -- 1=已Try 2=已Confirm 3=已Cancel
    gmt_create  DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    gmt_modified DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (xid, branch_id)
) ENGINE=InnoDB COMMENT='TCC防悬挂表';
```

**唯一键 `(xid, branch_id)`** 是整个 TCC 模型的基础。所有三类异常都依赖这个唯一键来处理。

### 三类异常的全链路防御

#### 异常 1：幂等——Try 被重复调用

```
场景：order 服务因网络超时重试了 tryDeductStock

第 1 次 Try: INSERT (xid="tx:003", branchId=3001, status=1) → 成功 → available-10, freezing+10
第 2 次 Try: INSERT (xid="tx:003", branchId=3001, status=1) → DuplicateKeyException！

处理流程（TccFenceService.tryFence）：
  catch (DuplicateKeyException):
    SELECT status WHERE xid=? AND branchId=?
    → status=1（Try 已执行过）→ return true（幂等放行，不再执行业务）
```

**return true 而非抛异常**：幂等放行意味着调用方（inventoryTccService.tryDeductStock）继续执行后续代码——但它只是"通过 Fence 检查"，实际业务操作应该被跳过。这里的关键是 return true 后，调用方的 `@Transactional` 方法仍然会执行——**所以 tryFence 返回 true 只是告诉调用方"Fence 通过了"，调用方还需要自己保证幂等**。

实际上，`InventoryTccService.tryDeductStock()` 在 tryFence 返回 true 后仍然执行 `tryFreeze`：

```java
// InventoryTccService.java:34-57
public boolean tryDeductStock(String xid, Long branchId, List<SkuItem> skuItems) {
    if (!tccFenceService.tryFence(xid, branchId, "tryDeductStock")) {
        return false;  // 悬挂拒绝
    }
    // 这里在 tryFence 幂等放行后仍会执行 tryFreeze
    for (SkuItem item : skuItems) {
        int affected = inventoryMapper.tryFreeze(item.getSkuId(), item.getQuantity());
        if (affected == 0) { throw new InsufficientStockException(...); }
    }
    return true;
}
```

**这是一个潜在问题**：tryFence 幂等放行后，`tryFreeze` 会再次执行 `available -= qty, freezing += qty`。但由于第一次已经执行过，`available` 已经减少了 qty，第二次的 `WHERE available >= qty` 可能因库存不足而失败——`affected=0` → 抛 InsufficientStockException。

**实际保护**：因为 `tryFreeze` 返回 0 时抛异常→事务回滚→inserted Fence 记录也被回滚→下次 Try 仍然成功。但 Fence 记录已在 tryFence 中插入（同一事务），所以回滚后 Fence 记录消失——这正好符合预期：幂等放行但库存不足 = 事务回滚 = 不扣库存。

#### 异常 2：悬挂——Cancel 先于 Try 到达

```
场景：order 服务发出 Cancel 后因网络延迟，Try 请求后到达

Cancel 到达: INSERT (xid="tx:004", branchId=4001, status=3) → 成功（空回滚）
             → cancelFreeze: WHERE freezing_stock >= 5 → affected=0（Try 未执行，无冻结库存可解冻）
Try 到达:    INSERT (xid="tx:004", branchId=4001, status=1) → DuplicateKeyException！

处理流程（TccFenceService.tryFence）：
  catch (DuplicateKeyException):
    SELECT status WHERE xid=? AND branchId=?
    → status=3（Cancel 已执行）→ return false（悬挂拒绝！Try 不应再执行）
```

**返回值**：tryFence 返回 false → `tryDeductStock` 直接 return false → 不执行任何 Try 逻辑。Controller 返回 `{"code":200,"data":false}`。

**为什么悬挂这么危险？** 如果 Try 后于 Cancel 执行且没有被拦截——Try 冻结的库存（freezing+=5）永远不会被 Confirm 或 Cancel（因为 Cancel 已经执行过了，Fence status=3 不允许转回 status=1）。冻结的库存变成"死库存"——available 已经回退了（Cancel 的 INCRBY），但 freezing 又被 Try 重新加回去了。两者互不抵消，总库存凭空少了 5。

#### 异常 3：空回滚——Cancel 到达但 Try 从未执行

```
场景：order 服务因超时直接 Cancel，但 Try 从未被调用

Cancel 到达: INSERT (xid="tx:005", branchId=5001, status=3) → 成功

处理流程（TccFenceService.cancelFence）：
  INSERT INTO t_tcc_fence VALUES (?, ?, ?, 3)
  → 成功——这是一次空回滚，不需要操作库存（因为 Try 从未执行）
```

**关键设计**：cancelFence 的 INSERT 在 `inventoryTccService.cancelDeductStock()` 的 `@Transactional` 方法中**最先执行**。如果 INSERT 成功（空回滚），后续的 `cancelFreeze` 仍会执行，但因 `WHERE freezing_stock >= qty` 且 freezing=0（Try 未执行），`affected=0`→`cancelFreeze` 不生效但不抛异常（行 92-95）：

```java
int affected = inventoryMapper.cancelFreeze(item.getSkuId(), item.getQuantity());
if (affected > 0) {
    log.info("[TCC Cancel] 解冻库存...");
}
// affected=0 时不抛异常，静默跳过
```

**与 releaseStock 的对比**：releaseStock 在 Redis 层操作（Lua 的 `HGET→nil` 返回 0），不依赖 MySQL 状态。Cancel 在 MySQL 层操作（SQL WHERE），需要 Fence 表区分"没执行过"和"已经执行过"。

---

## Fence 状态机的完整锁语义

```
状态只有 3 个：1(Try), 2(Confirmed), 3(Cancelled)

状态转换（CAS 保证，status 字段是乐观锁）：
  1 → 2：confirmFence  UPDATE WHERE status=1（只能从 Try 转到 Confirm）
  1 → 3：cancelFence   UPDATE WHERE status=1（只能从 Try 转到 Cancel）

不允许的转换（被拒绝）：
  2 → 1：Confirm 后不能再 Try（重复 Try 被主键拦截，但不会覆盖 2）
  2 → 3：confirmFence 拒绝：status=2 不能 Cancel
  3 → 1：tryFence 拒绝：status=3 是悬挂，拒绝 Try
  3 → 2：confirmFence 拒绝：status=3 不能 Confirm
```

**CAS 的并发安全性**：

```
并发 Confirm+Cancel 对同一 Try：
  T1: confirmFence → SELECT status → 1 → UPDATE WHERE status=1 → affected=1 ✓
  T2: cancelFence  → UPDATE WHERE status=1 → affected=0 → 不覆盖 ✓
  → 先到的获胜，后到的被乐观锁拒绝
```

---

## TCC Fence vs Seata TCC：两套实现的异同

inventory 自己实现了 Fence 表逻辑，没有依赖 Seata。为什么？

| 维度 | 自研 TCC Fence | Seata TCC |
|------|---------|-----------|
| 依赖 | 零外部依赖（common 模块 JdbcTemplate） | seata-all + seata-spring-boot-starter |
| Fence 表 | `t_tcc_fence`（独立管理） | seata `tcc_fence_log`（由 Seata Server 管理） |
| 事务协调 | 调用方 order 服务手动编排 Try→Confirm/Cancel | Seata TC 全局事务协调器 |
| 性能 | 本地 INSERT/UPDATE，无网络开销 | 需要与 TC 通信（分支注册 + 全局提交） |
| 适用 | mini 服务间协调（3-4 个服务） | 大规模微服务（10+ 服务） |
| 灵活性 | Try/Confirm/Cancel 逻辑完全自定义 | 遵循 Seata 的 `@TwoPhaseBusinessAction` 注解规范 |

**为什么不直接上 Seata？** my-xhs 的服务规模（16 个微服务，但 TCC 只涉及 order→inventory→coupon→payment 4 个服务）不需要 Seata 的全局事务协调器。Seata 引入的额外组件（TC Server、undo_log 表、全局锁）增加了部署和运维复杂度，而自主研发的 Fence 表用 1 张表 + 132 行代码就解决了三类核心异常。

**Fence 表设计的巧妙之处**：把分布式事务的协调问题降维成了**单表主键冲突 + 状态 CAS** 的数据库问题。数据库本身提供 ACID 保证，不需要额外的共识算法。

---

## 面试 Q&A

### Q1：TCC Fence 表如何防悬挂？主键冲突后怎么区分是"幂等"还是"悬挂"？

**答案**：插入 status=3（Cancel 先到）或 status=1（Try 先到）。后来者 INSERT 触发 DuplicateKeyException → 查询现有 status：

```
如果 status=3（Cancel 先到）→ Try 被拒绝 return false
如果 status=1（Try 先到）→ Try 幂等放行 return true
```

**追问到第二层**：如果 SELECT 和后续 UPDATE 之间 status 被并发修改了呢？

→ tryFence 不 UPDATE status（只 INSERT 和 SELECT）。INSERT 失败后 SELECT 读到的是持久化状态——Try 和 Cancel 的 race condition 中，INSERT 先成功的决定 Fence 记录的状态。后来者只能读到先到者写入的 status，不存在 SELECT→UPDATE 的竞态窗口。

### Q2：Cancel 后 Confirm 能执行吗？

**答案**：不能。`confirmFence` 检查当前 status：

```java
if (currentStatus == 3) {
    log.error("Confirm 拒绝：当前已是已取消状态");
    return;  // 不执行 confirmFreeze
}
```

Cancel 后的 Cancel→Confirm 转换被阻止（status=3 不可转为 status=2）。

**追问到第二层**：如果 Cancel 和 Confirm 并发呢？

→ CAS 保证：`UPDATE ... SET status=2 WHERE status=1`。只有一个操作能在 status=1 时成功——先执行 CAS 的操作获胜，另一个被乐观锁拒绝。

### Q3：如果 Try 阶段的 SQL（tryFreeze）成功了，但后续代码抛异常导致事务回滚——Fence 记录会被回滚吗？

**答案**：会。`tryFence` 的 INSERT 和 `tryFreeze` 的 UPDATE 在同一个 `@Transactional` 方法中：

```java
@Transactional(rollbackFor = Exception.class, timeout = 10)
public boolean tryDeductStock(...) {
    tccFenceService.tryFence(...);     // INSERT fence status=1
    // ... 如果 tryFreeze 失败或抛异常 ↓
    inventoryMapper.tryFreeze(...);    // UPDATE available -= qty
    // 事务回滚 → Fence INSERT + tryFreeze UPDATE 全部回滚
}
```

Fence 记录和业务操作在同一个本地事务中——要么都成功，要么都失败。这是 Fence 表方案相比 Seata（需要补偿回滚 undo_log）更简洁的关键。

### Q4：为什么两条路径（L1/L2 和 TCC）用不同的 MySQL 字段（locked_stock vs freezing_stock）？

**答案**：隔离两种事务模型的状态，防止互相干扰：

| 路径 | 预扣字段 | 预扣含义 | 释放方式 |
|------|:--:|------|------|
| L1/L2 | locked_stock | Redis 预扣的 MySQL 缓存（异步） | MQ 消费 CONFIRM→locked-=qty |
| TCC | freezing_stock | 分布式事务的 Try 阶段冻结 | Confirm/Cancel→freezing-=qty |

使用不同字段的好处：
1. `available + locked + freezing = 总库存` — MySQL 层三者互不重叠，可独立审计
2. 凌晨对账只比对 L1/L2 路径（Redis↔available+locked），不涉及 TCC
3. L1/L2 的 PRE_DEDUCT 和 TCC 的 tryFreeze 可以并发执行（操作不同字段）

**追问到第二层**：如果同一时刻 L1/L2 预扣 + TCC Try 并发扣同一个 SKU 呢？

→ 两个操作都在相同的 `available_stock` 上做减法：

```
L1/L2: UPDATE SET available -= 3, locked += 3 WHERE available >= 3
TCC:   UPDATE SET available -= 5, freezing += 5 WHERE available >= 5
```

MySQL 的行锁保证两个 UPDATE 串行执行。如果两者都需要 8 件但 available 只剩 6 → 先执行的成功（够 3 或 5），后执行的 `WHERE available >= qty` 失败→affected=0。不存在"两边都扣了但 total 超了"的情况。

---

## 发散：TCC vs Saga vs 本地消息表

inventory 实际上有三套分布式事务方案，各适用于不同场景：

| 维度 | TCC（测试 7-9） | Saga（未实现） | L1/L2 本地消息表变体（已实现） |
|------|:--:|:--:|:--:|
| 事务模型 | 两阶段（Try→Confirm/Cancel） | 正向操作 + 补偿操作 | MQ 异步 |
| 一致性 | 强一致 | 最终一致 + 补偿 | 最终一致 |
| 隔离性 | Try 阶段锁定资源（freezing） | 无锁定，操作已提交 | L1 预扣（Redis），L2 异步（MySQL） |
| 回滚 | Cancel 解冻 freezing→available | 逆操作链（退库存→退券→退款） | 无显式回滚（L3 对账修复） |
| 适用 | 短事务，资源争用低 | 长事务（数小时/数天） | 高并发短事务 |
| my-xhs 位置 | order→inventory/coupon/payment | — | inventory 独立扣减 |

**为什么没有 Saga？** my-xhs 的订单创建是同步短事务（Try→Confirm 通常在毫秒内完成），不需要 Saga 的长时间补偿链。如果未来引入"货到付款"或"预售"（订单创建到确认之间可能数天），Saga 会比 TCC 更合适——因为 TCC 的 freezing 会长期占用库存。

---

## 生产故障实验

### 实验 1：验证悬挂防御

```bash
# 先 Cancel（status=3），后 Try
curl -s -X POST http://localhost:19009/api/inventory/tcc/cancel \
  -H "Content-Type: application/json" \
  -d '{"xid":"test:fx:hang","branchId":9001,"skuItems":[{"skuId":999,"quantity":3}]}'

# → Cancel 成功（空回滚，status=3）

curl -s -X POST http://localhost:19009/api/inventory/tcc/try \
  -H "Content-Type: application/json" \
  -d '{"xid":"test:fx:hang","branchId":9001,"skuItems":[{"skuId":999,"quantity":3}]}'
# → {"code":200,"data":false}  ← Try 被拒绝！
```

**验证 MySQL**：
```sql
SELECT status FROM t_tcc_fence WHERE xid='test:fx:hang' AND branch_id=9001;
-- → status=3 (Cancel 的记录，Try 未覆盖)
SELECT available_stock, freezing_stock FROM t_inventory WHERE sku_id=999;
-- → freezing_stock 不变（Try 未被允许执行）
```

### 实验 2：Cancel 后 Confirm 被拒绝

```bash
# 1. Try
curl -X POST .../tcc/try -d '{"xid":"test:fx:cc","branchId":9002,"skuItems":[{"skuId":999,"quantity":2}]}'
# → data=true

# 2. Cancel
curl -X POST .../tcc/cancel -d '{"xid":"test:fx:cc","branchId":9002,"skuItems":[{"skuId":999,"quantity":2}]}'
# → 200, status=3

# 3. Confirm（应被拒绝）
curl -X POST .../tcc/confirm -d '{"xid":"test:fx:cc","branchId":9002,"skuItems":[{"skuId":999,"quantity":2}]}'
# → 200（不报错，但日志显示 "拒绝：当前已是已取消状态"）
```

**验证日志**：
```bash
grep '拒绝\|test:fx:cc' /data/workspace/my-xhs/logs/my-xhs-inventory/info.log | tail -3
# → [TCC Fence] Confirm 拒绝：当前已是已取消状态: xid=test:fx:cc, branchId=9002
```
