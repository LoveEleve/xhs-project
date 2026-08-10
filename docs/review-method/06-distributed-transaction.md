# 06 分布式事务

> 复审维度 06 | 每个模块必查 | 9 透镜全覆盖，跨服务数据原子性为本维度核心
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。每个检查项标注了透镜类型，覆盖
> 业务逻辑/工程正确性/分布式正确性/生产级/可扩展性/并发/微服务/性能/盲区 全部 9 个维度。
> 注：MQ 事务消息/延迟消息的发送可靠性见 04.8/04.9；双写回滚见 03.2。
> 本维度以 TCC 模式为核心编写，若模块使用 Seata AT 模式（`@GlobalTransactional` + undo_log），请额外检查：undo_log 表是否存在、回滚 SQL 是否正确生成、undo_log 是否有定期清理机制。

---


**执行本维度后，必须在审查报告中输出 `[06] 06 分布式事务：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [06]）。**
## 检查项

### 6.1 TCC Fence 三态门禁 | 透镜：工程/并发/盲区

**必须检查**：TCC 三个操作（Try/Confirm/Cancel）的 fence 结果是否**实际门禁了业务执行**——返回幂等命中/已取消时不执行后续业务代码。

**怎么查**：
```bash
grep -rn 'tryFence\|confirmFence\|cancelFence\|TccFenceService' my-xhs-<module>/src/main/java/
```
逐 TCC 操作检查：fence 返回什么状态？返回 DUPLICATE/REJECTED/EXECUTE？**业务代码是否依据 fence 返回值决定执行与否。**

**判定**：

| 检查点 | 缺陷 | 正确形态 |
|--------|------|---------|
| fence 返回值被忽略 | `tryFence()` 调用后不看返回值直接执行库存扣减→双扣/双冻 | 检查 `fenceResult`：EXECUTE 才执行，DUPLICATE 跳过，REJECTED 抛异常 |
| fence 无乐观锁 | 并发双 Confirm 都通过 fence→MySQL 插入两条 Confirm 记录 | fence 表用 `uk_xid_action` + `version` 乐观锁防并发 |
| Cancel fence 无 Cancel 记录 | Cancel 直接删 Try 记录→再 Cancel 找不到→误判为未执行→双解冻 | Cancel fence 用状态位 1→3 而非 DELETE |
| fence 幂等但业务未跳过 | fence 返回 DUPLICATE/REJECTED→代码校验了返回值→但**校验后仍然执行了业务代码**→双扣/双冻 | fence 返回 DUPLICATE→直接 return；REJECTED→抛异常回滚；EXECUTE 才执行业务 |

**案例**：07-inventory TCC fence 返回幂等命中后业务仍执行→双冻结/双解冻（修复用三态枚举 `TryFenceResult{DUPLICATE, REJECTED, EXECUTE}` + 业务门禁）。

---

### 6.2 TCC 超时与僵事务清理 | 透镜：生产级/工程

**必须检查**：如果 Try 成功后应用崩溃（没发 Confirm/Cancel），僵事务是否有自动超时取消机制；清理是否按 xid 粒度而非 SKU 聚合。

**怎么查**：
```bash
grep -rn 'TccTimeout\|tccTimeout\|timeout.*freeze\|freeze.*expire\|frozen.*timeout' my-xhs-<module>/src/main/java/
grep -rn 'fence.*timeout\|expired.*fence\|cancelPending' my-xhs-<module>/src/main/java/
```

**判定**：

| 检查点 | 缺陷 | 正确形态 |
|--------|------|---------|
| 无超时 Cancel | Try 扣冻结但系统崩溃→永久冻结（库存永久不可用） | 定时 Job 扫描 frozen_time > N 秒的未 Confirm/Cancel 记录→自动 Cancel |
| 按 SKU 聚合取消 | 用户 A 的 xid1 冻结 2 个 → 用户 B 的 xid2 冻结 3 个 → 全部 Cancel→A 的冻结被误取消 | 必须按 xid 维度逐个 cancel，不能按 SKU 聚合 |
| 超时时间过短 | 超时 30s 但 Confirm 链路延迟 20s→正常事务被取消 | 超时 > 预期最大 Try→Confirm 耗时 |

**案例**：07-inventory 原 `TccTimeoutJob` 按 SKU 聚合 cancel→误取消其他用户的冻结（修复用 xid 维度 fence 驱动取消：scan freezeDetail status=1 超时→逐 xid cancelFence）。

---

### 6.3 TCC 冻结明细与对账 | 透镜：工程/盲区

**必须检查**：Try 写入的冻结明细表是否包含完整的 xid/orderNo/amount/时间戳——足够用于超时取消 + 对账。

**怎么查**：
```bash
grep -rn 't_tcc_freeze_detail\|freezeDetail\|TccFreezeDetail\|FreezeDetail' my-xhs-<module>/src/main/java/
grep -rn 'freeze.*insert\|insert.*freeze' my-xhs-<module>/src/main/java/
```

**判定**：
- 无冻结明细表→只能按 total freezing 聚合取消→无法按 xid 粒度取消
- 明细缺 `xid`→无法关联 fence 记录→超时清理无据
- 明细缺 `createdAt`→无法判断超时
- Try 成功但写明细失败→冻结已扣但明细为空→对账不可追溯

**案例**：07-inventory 原无 `t_tcc_freeze_detail` 表，freezing_stock 一维聚合无 xid 维度→超时取消全清（修复新增 freezeDetail 表 + xid/frozen_time 字段）。

---

### 6.4 Outbox 四部正确性 | 透镜：生产级/工程/盲区

**必须检查**：Outbox 模式的四个关键步骤是否全部正确：标记 sent → 取消出箱 → Job 查 SendResult → payload 与 Consumer 一致性。

**怎么查**：
```bash
grep -rn 'markSent\|markOutboxSent\|cancelOutbox\|OutboxSenderJob' my-xhs-<module>/src/main/java/
```

**判定**：

| 步骤 | 缺陷 | 正确形态 |
|------|------|---------|
| 1. markSent | MQ send **后**才 markSent→MQ send 失败也 mark→消息永久丢失 | **MQ send 成功（SEND_OK）**才 markSent；失败 cancelOutboxEvent |
| 2. cancelOutbox | 仅有 markSent 无 cancel 路径→只有成功和永久 pending 两种状态 | send 失败→cancel→Job 下次重新扫 |
| 3. SendResult 检查 | Outbox Job 不检查 SyncSend 返回值→发送失败仍标记成功 | 检查 `result.getSendStatus() == SEND_OK` 后才 markOutboxSent |
| 4. payload 与 Consumer 一致 | Producer payload 用 snake_case / Consumer 用 camelCase→字段 null（见 04.3） | 统一 camelCase（见 04.3） |

**案例**：07-inventory 四个问题全中：markSent 在 send 之前、无 cancelOutbox、Job 不查 SendResult、payload snake_case→camelCase 不一致。

---

### 6.5 SAGA 补偿完整性 | 透镜：分布式/业务

**必须检查**：如果模块使用 SAGA 模式（链式调用 + 失败逆向补偿），每个正向操作的补偿操作是否都**真实存在且可执行**。

**怎么查**：
```bash
grep -rn 'compensate\|undo\|rollback\|revert\|release' my-xhs-<module>/src/main/java/
```
逐补偿操作检查：正向动作 A 的补偿操作 `compensateA()` 是否真的能撤销 A 的效果。

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 补偿方法签名与正向不匹配 | `release(orderId)` 但正向 `deduct(derivedOrderId)`→ID 不一致，补偿执行失败 |
| 补偿操作不可逆 | `deductStock` 的补偿 `releaseStock`→库存释放后可能被他人扣掉→补偿后总数不对 |
| 补偿顺序错误 | 正向 A→B→C 失败→补偿 C→B→A→但 B 的补偿依赖 A 已完成→死锁 |

**案例**：order `releaseInventory` 用真实 orderId 查不到 inventory 的 derivedPseudoOrderId→补偿释放失败（修复 order 侧新增 `derivePseudoOrderId`）。

---

### 6.6 分布式事务框架耦合 | 透镜：可扩展性

**必须检查**：TCC/Outbox/SAGA 的实现是手动编码还是依赖框架（Seata）。框架切换的改动面有多大。

**怎么查**：
```bash
grep -rn 'seata\|GlobalTransaction\|@GlobalTransactional\|io.seata' my-xhs-<module>/src/main/java/ | wc -l
grep -rn 'TccFenceService\|tryFence\|Fence' my-xhs-<module>/src/main/java/ | wc -l
```

**判定**：
- 手动 fence（自写 TccFenceService）→切 Seata AT 需改全部 TCC 代码
- 用 `@GlobalTransactional`→切 Seata TCC 改注解即可
- **记录即可，不强制修改。**

**案例**：my-xhs 全量手动 fence（自建 TccFenceService + freezeDetail 表），无 Seata 集成。切 Seata 为全局改造。

---

### 6.7 事务监控与告警 | 透镜：生产级/微服务

**必须检查**：目标模块的分布式事务是否有监控——冻结中事务数、超时中事务数、补偿失败数、Outbox 待发送数。

**怎么查**：
```bash
grep -rn 'metric\|counter\|meter\|gauge\|@Timed\|Micrometer' my-xhs-<module>/src/main/java/com/myxhs/*/service/ | grep -i 'tcc\|outbox\|freeze\|compensate'
```

**判定**：
- TCC freezeDetail status=1 且超过阈值→无 metric→运维看不见堆积
- Outbox 待发送数 > 100→无 metric→消息发送故障不告警
- 补偿失败无 metric→数据不一致无人知晓

**案例**：（全特性面预置检查项——07-inventory 的 TCC/Outbox 监控指标待补充。）

---

### 6.8 TCC/Outbox 表增长与性能 | 透镜：性能

**必须检查**：freezeDetail 表和 outbox 表是否会随时间增长——是否有定期归档/清理机制；TccTimeoutJob 的扫描查询是否有性能限制。

**怎么查**：
```bash
# 找冻结/Outbox 表的 DDL——确认是否有归档策略
grep -rn 'tcc_freeze_detail\|t_inventory_outbox\|t_order_outbox' my-xhs-<module>/src/main/resources/sql/
# 超时 Job 的扫描 SQL——是否有 LIMIT + 索引
grep -rn 'selectDistinct\|selectList\|selectBatch' my-xhs-<module>/src/main/java/com/myxhs/*/job/
```

**判定**：
- freezeDetail 表无归档→生产运行 1 年后千万行→全表扫描超时
- Outbox 表无清理→发送成功的记录保留→表增长影响 Job 扫描性能
- TccTimeoutJob 无 `LIMIT 100`→单次扫描全表→阻塞其他操作
- 无 `INDEX(frozen_time, status)`→超时查询全表扫描

**案例**：（全特性面预置检查项——07-inventory 的 freezeDetail/outbox 表刚新建，归档策略待补充。）

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| Outbox 发送可靠性（SendResult/payload一致性） | 04.3/04.8 | 详见 MQ 可靠性维度 |
| 双写回滚（DB 失败回滚 MQ） | 03.2 | 详见数据一致性维度 |
| 事务与 MQ 的并发控制 | 02.4 | 分布式锁防止并发 Try |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl <module> -am
mvn test -pl <module>

# TCC fence 使用
grep -rn 'tryFence\|confirmFence\|cancelFence\|TccFence' my-xhs-<module>/src/main/java/

# Outbox 状态管理
grep -rn 'markSent\|markOutboxSent\|cancelOutbox\|OutboxSender' my-xhs-<module>/src/main/java/

# 冻结明细
grep -rn 'freezeDetail\|tcc_freeze\|freezing_stock\|FreezeDetail' my-xhs-<module>/src/main/java/

# 超时清理
grep -rn 'TccTimeout\|timeout.*frozen\|freeze.*timeout' my-xhs-<module>/src/main/java/

# 补偿操作
grep -rn 'compensate\|undo\|revert\|release.*Stock\|release.*Inventory' my-xhs-<module>/src/main/java/

# Seata/框架
grep -rn 'seata\|@GlobalTransactional\|io.seata' my-xhs-<module>/src/main/java/

# 监控 metric
grep -rn 'metric\|counter\|meter\|gauge' my-xhs-<module>/src/main/java/ | grep -i 'tcc\|outbox\|freeze\|compensate'
```
