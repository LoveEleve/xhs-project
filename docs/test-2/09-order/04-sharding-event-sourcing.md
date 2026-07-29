# 04 — 分库分表 + Event Sourcing

> **前置阅读**：[架构文档 §1.1 (分片架构)](01-order-module.md) · §2 (状态机) · [03-事务消息下单](03-transaction-message.md)
> **源码**：`sharding-config.yaml` · `ShardingSphereDataSourceConfig` · `OrderEventService` · `OrderMapper`

## 为什么订单需要分库分表？

单表订单的物理上限约 2000 万行（在不分区的情况下）。超过这个量级，B+Tree 的层级增加，单行查询从 2 次 IO 变成 3-4 次。电商平台日订单量可达百万级——不到一个月就触达上限。

分库分表将一张逻辑表 `t_order` 拆分为 4 个库 × 4 张表 = 16 张物理表，每张表承载 1/16 的数据量。单表上限从 2000 万扩展到 3.2 亿。

**但分库分表引入了两个核心问题**：

1. **路由问题**：给定一个 `orderId`，找它在哪个分片。雪花 ID 不自带分片信息。
2. **非分片键查询问题**：用户按 `orderNo` 查订单——`orderNo` 不含 `user_id`，无法路由到具体分片。

---

## 分片规则：user_id 驱动的路由

ShardingSphere-JDBC 配置（`sharding-config.yaml`）：

```yaml
# 库路由：user_id % 4 → ds0~ds3
sharding:
  tables:
    t_order:
      actualDataNodes: ds$->{0..3}.t_order_$->{0..3}
      databaseStrategy:
        standard:
          shardingColumn: user_id
          shardingAlgorithmName: database-inline
      tableStrategy:
        standard:
          shardingColumn: user_id
          shardingAlgorithmName: table-inline

  shardingAlgorithms:
    database-inline:
      type: INLINE
      props:
        algorithm-expression: ds${user_id % 4}
    table-inline:
      type: INLINE
      props:
        algorithm-expression: t_order_${(user_id / 4) % 4}
```

### 为什么选择 user_id 作为分片键？

| 分片键候选 | 优势 | 劣势 |
|------|------|------|
| `user_id` | 用户的订单/商品/事件/快照全部在同一分片 → JOIN 不需要跨库 | 非分片键查询需要映射表 |
| `order_id` | 订单 ID 天然均匀分布 | 同一用户的订单分散在不同分片 → "我的订单"需要跨库查询 |
| `order_no` | 业务含义明确 | 字符串哈希不均匀 |

`user_id` 的选择保证了**用户维度的数据亲和性**——同一用户的所有订单（`t_order`）、订单明细（`t_order_item`）、事件（`t_order_event`）、快照（`t_order_snapshot`）都在同一分片。这意味着：

```sql
-- 用户查看"我的订单"——不需要跨库
SELECT * FROM t_order WHERE user_id = 10001 AND status = 0
-- ShardingSphere 自动路由到 ds1.t_order_0

-- 用户查看订单详情（含明细）——同一个分片内 JOIN
SELECT o.*, i.* FROM t_order o
JOIN t_order_item i ON o.id = i.order_id
WHERE o.user_id = 10001 AND o.id = ?
-- 全部在同一分片，无需跨库查询
```

### 绑定表（bindingTables）

5 张分片表全部配置为 `bindingTables`：

```yaml
bindingTables:
  - t_order, t_order_item, t_order_event, t_order_snapshot, t_local_message
```

**如果不绑定**——ShardingSphere 对 `t_order JOIN t_order_item` 会生成笛卡尔积：`ds0.t_order_0 × ds0.t_order_item_0, ds0.t_order_0 × ds0.t_order_item_1, ...`——16×16=256 种组合。绑定后，ShardingSphere 知道"这两张表的分片规则完全一致"，只生成 16 种组合（每个分片内的表配对）。

### 雪花 ID 的主键生成

```yaml
keyGenerateStrategy:
  column: id
  keyGeneratorName: snowflake
```

Snowflake 算法生成 64-bit ID：`timestamp(41bit) + workerId(10bit) + sequence(12bit)`。Worker ID 基于本机 IP 动态计算，保证多实例部署时不冲突。ID 本身不含分片信息——ShardingSphere 在执行 SQL 时根据 `user_id` 列的值做路由，不依赖 ID。

---

## 非分片键查询：t_order_no_mapping

问题：用户只知道 `orderNo`（如 `ORD2026072914393006000010044`），不知道 `user_id`。`orderNo` 不是分片键，无法路由。

解决方案：一张**独立的不分片映射表**（存在公共库 `my_xhs_order`）：

```sql
CREATE TABLE t_order_no_mapping (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) UNIQUE NOT NULL,
    user_id  BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    created_at DATETIME
);
```

查询流程：

```
GET /api/order/by-order-no/{orderNo}
  │
  ├── mappingRepository.selectByOrderNo(orderNo)  -- 查公共库
  │     → (orderNo=ORD20260729..., userId=10001, orderId=20823...)
  │
  └── orderMapper.selectOne(WHERE userId=10001 AND id=20823...)  -- 带分片键查分片库
        → ShardingSphere 路由到 ds1.t_order_0 ✓
```

**为什么映射表用独立数据源？** 映射表不走 ShardingSphere 分片路由——它使用独立的 `mappingJdbcTemplate`（`@Qualifier("mappingJdbcTemplate")`）。如果映射表也走 ShardingSphere，insert 时没有 `user_id`（此时还不知道 user_id）→ 无法路由到分片。

**异步写入**：`saveOrderNoMapping` 在 `createOrder` 的最后一步执行——不在本地事务内，失败不影响下单。`OrderMappingRepairJob` 每 5 分钟补录缺失的映射记录。

---

## Event Sourcing：不可变事件流

订单的每一次状态变更都记录为一个不可变事件：

```
t_order_event:
  id | order_id | user_id | event_type      | from_status | to_status | event_seq
  ---|----------|---------|-----------------|-------------|-----------|----------
  1  | 20823... | 10001   | ORDER_CREATED   | NULL        | 0         | 1
  2  | 20823... | 10001   | ORDER_PAID      | 0           | 1         | 2
  3  | 20823... | 10001   | ORDER_DELIVERED | 1           | 2         | 3
  4  | 20823... | 10001   | ORDER_COMPLETED | 2           | 3         | 4
```

### 事件追加的原子性

源码：`OrderEventService.appendEvent()`

```java
@Transactional(rollbackFor = Exception.class, timeout = 10)
public void appendEvent(Order order, String eventType, Map<String, String> payload) {
    // 1. 幂等检查：如果订单已是目标状态，跳过
    int targetStatus = EVENT_STATUS_MAP.get(eventType);
    if (order.getStatus() == targetStatus) {
        log.info("[OrderEvent] 幂等跳过：订单已是目标状态");
        return;
    }

    // 2. INSERT 事件（INSERT IGNORE + uk_order_event_seq 防重）
    int nextSeq = getNextSeq(order.getId());
    orderEventMapper.insertIgnore(order.getId(), order.getUserId(),
            eventType, order.getStatus(), targetStatus, jsonPayload, nextSeq);

    // 3. 乐观锁更新 Order 状态
    int affected = orderMapper.updateStatusWithLock(
            order.getId(), order.getUserId(), order.getStatus(), targetStatus);
    if (affected == 0) {
        throw new IllegalStateException("并发冲突：订单状态已被其他操作修改");
    }
}
```

**为什么先 INSERT 事件再 UPDATE 状态？** Event Sourcing 的原则：事件是事实的不可变记录——必须先持久化"发生了什么"，再更新"当前状态"。如果反过来——先 UPDATE 再 INSERT——UPDATE 成功后 INSERT 失败，状态变了但事件丢失，事件流不完整。

### INSERT IGNORE 的幂等保证

`t_order_event` 有唯一索引 `uk_order_event_seq (order_id, event_seq)`。同一个 `(orderId, seq)` 的重复 INSERT 被 `INSERT IGNORE` 静默跳过——不会抛异常不会覆盖。这意味着即使 appendEvent 被重试（MQ 重投或回调重试），事件流也不会出现重复行。

### 事件流的回放能力

从事件流可以重建订单的任意历史状态：

```sql
-- 从事件流恢复"订单在支付前一刻的状态"
SELECT event_type, from_status, to_status, payload
FROM t_order_event
WHERE order_id = ? AND event_seq <= 2  -- 只看前两个事件
ORDER BY event_seq;
```

事件流存储了 `from_status` → `to_status` 的完整转换链 + `payload`（JSON，含时间戳、操作原因等）。不需要额外的 `t_order_history` 表——事件流本身就是历史记录。

---

## 订单状态机

```
0(待付款) ──[pay-success/mock]──> 1(已付款) ──[deliver]──> 2(已发货) ──[confirm]──> 3(已完成)
    │                                  │
    ├─[cancel]──> 4(已取消)              └─[refund-success]──> 5(已退款)
    │
    └─[timeout]──> 4(已取消)

终态: 3(已完成), 4(已取消), 5(已退款)
```

**乐观锁保护**：所有状态变更 SQL 带有 `WHERE status = currentStatus`。并发操作时——如用户取消和超时关单同时触发——只有一个成功。

```java
// OrderMapper.updateStatusWithLock
@Update("UPDATE t_order SET status = #{toStatus}, updated_at = NOW() " +
        "WHERE id = #{id} AND user_id = #{userId} AND status = #{fromStatus}")
int updateStatusWithLock(@Param("id") Long id, @Param("userId") Long userId,
                         @Param("fromStatus") Integer fromStatus,
                         @Param("toStatus") Integer toStatus);
```

并发场景：

```
T1: 用户点"取消" → appendEvent(CANCELLED) → UPDATE WHERE status=0 → affected=1 ✓
T2: 超时关单    → appendEvent(CANCELLED) → UPDATE WHERE status=0 → affected=0 → 静默跳过
→ 只有一个成功，状态变为 4
```

---

## 订单快照：独立于事件流的全量镜像

`t_order_snapshot` 在关键节点保存订单的完整 JSON 快照：

```java
private void takeSnapshot(Long orderId, Long userId, String event) {
    Order order = orderMapper.selectOne(...);
    List<OrderItem> items = orderItemMapper.selectList(WHERE order_id=...);
    // 序列化整个订单（含明细）为 JSON
    snapshotMapper.insert(OrderSnapshot(event, snapshotJson));
}
```

**事件流 vs 快照**：

| 维度 | t_order_event | t_order_snapshot |
|------|------|------|
| 存储内容 | 状态转换（from→to + payload） | 订单完整状态（含明细项） |
| 查询方式 | 逐事件重放 | 直接读取完整 JSON |
| 用途 | 审计追查、状态恢复 | 快速还原某个时刻的完整订单 |

快照在 `CREATED`、`PAID`、`CANCELLED`、`DELIVERED`、`COMPLETED`、`REFUNDED` 六个时刻生成。

---

## 面试 Q&A

### Q1：为什么分片键选 user_id 而不是 order_id？

**答案**：`user_id` 保证用户维度的数据亲和性——用户的订单、明细、事件、快照全部在同一分片。用户查看"我的订单"只需要一次分片内查询——如果选 `order_id`，"我的订单"需要跨 4 个库扫描。

**追问**：user_id 作为分片键，如果一个大 V 有百万订单，会不会导致分片不均？

→ 会。大 V 的所有数据集中在一个分片——这就是热点分片问题。但订单系统不像微博——单个用户的订单量通常在几十到几百。如果一个用户真的有百万订单（B2B 采购），可以从 `user_id` 分片升级为 `user_id + 时间维度` 的复合分片键（如 `(user_id/10000) % 4` 按用户组路由）。

### Q2：INSERT IGNORE + 乐观锁 UPDATE 两层保护会不会有死锁？

**答案**：不会。`INSERT IGNORE` 操作的是 `t_order_event` 表（唯一索引），`UPDATE` 操作的是 `t_order` 表（主键）。两张不同的表，不会产生同一个行锁的死锁。INSERT IGNORE 如果命中唯一键——静默跳过，不抛异常，不持有锁。

**追问**：如果 INSERT IGNORE 成功但 UPDATE 失败呢？

→ 事件已写入但状态未更新——事件流记录了"有人尝试了操作"但实际状态没有改变。这是一种可接受的不一致——下一次对账可以发现并修复（通过补偿 Job 重放事件）。

### Q3：映射表的异步写入会不会丢数据？

**答案**：可能。`saveOrderNoMapping` 不在本地事务内——如果 JVM 在写入映射表之前崩溃，orderNo 无法反查 userId。但 `OrderMappingRepairJob` 每 5 分钟扫描 `t_order` 表——发现没有映射记录的 orderId 就从订单中提取 orderNo 和 userId 补录映射。最坏情况是用户 5 分钟内无法通过 orderNo 查订单。

---

## 发散：ShardingSphere vs Vitess vs 自研分片

| 方案 | order 的使用 | 替代方案 | 为什么不用替代 |
|------|------|------|------|
| ShardingSphere-JDBC | 配置文件中声明分片规则 | Vitess（数据库中间件） | Vitess 需要独立部署集群管理 MySQL 拓扑——运维成本高 |
| INLINE 算法 | `ds${user_id % 4}` | 一致性哈希 | 电商场景下 user_id 是自增的——模运算已经足够均匀 |
| 映射表 | 独立 JdbcTemplate | ShardingSphere Hint 强制路由 | Hint 需要在代码中硬编码分片信息 | 映射表更解耦 |

## 生产故障实验

### 实验：验证分片路由的正确性

```bash
# user_id=10001: ds=10001%4=1, table=(10001/4)%4=2500%4=0
# 订单落在 my_xhs_order_1.t_order_0

# 验证
curl -s -X POST http://localhost:19011/api/order/create \
  -H "X-User-Id: 10001" -d '{"skuItems":[{"skuId":999,"quantity":1}],...}'

# 查对应分片
mysql -h21.130.247.89 -P13308 my_xhs_order_1 \
  -e "SELECT id, user_id, order_no FROM t_order_0 WHERE user_id=10001 ORDER BY created_at DESC LIMIT 1"
# → 分片正确：user_id=10001 在 ds1.t_order_0
```
