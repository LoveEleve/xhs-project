# 03 数据一致性

> 复审维度 03 | 每个模块必查 | 9 透镜全覆盖，分布式的数据层一致性为本维度核心
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。每个检查项标注了透镜类型，覆盖
> 业务逻辑/工程正确性/分布式正确性/生产级/可扩展性/并发/微服务/性能/盲区 全部 9 个维度。

---


**执行本维度后，必须在审查报告中输出 `[03] 03 数据一致性：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [03]）。**
## 检查项

### 3.1 缓存权威方向判定 | 透镜：分布式/工程/盲区

**必须检查**：模块的 Redis+MySQL 数据流方向——哪位是权威源，写操作按什么顺序，缓存失效机制是什么。

**怎么查**：
```bash
# 找同一方法内同时操作 Redis 和 DB 的代码
grep -rn -e 'redisTemplate' -e 'redisOperator' -e 'stringRedisTemplate' my-xhs-<module>/src/main/java/ -B3 -A3 | grep -B2 'Mapper\|insert\|update\|delete'
```
逐方法确认：先写谁？谁失败回滚谁？缓存失效是 delete 还是 update？

**判定**：

| 模式 | 合法场景 | 必须满足 |
|------|---------|---------|
| L1 权威: Redis 先 + MQ → MySQL 异步 | 库存、计数（低延迟写） | Redis 写成功后 MQ 必须可靠（Outbox/事务消息），写失败不回滚 Redis |
| Cache-Aside: MySQL 先 + `afterCommit` 删缓存 | 商品、内容（读多写少） | `afterCommit`（事务提交后再删），不是 commit 前。注意：commit 和 afterCommit 之间进程 crash→缓存永久残留，须配合 TTL + 对账兜底 |
| **先写 MySQL 再 SET Redis 且无缓存失效** | **永远不合法** | 脏读窗口 |

**案例**：07-inventory 是 L1 权威（Redis 预扣 → MQ → MySQL 异步持久），Canal 回声却把 MySQL 变更当作权威 DELETE Redis——架构矛盾致库存丢失。`FavoriteService.unfavorite` 回滚用 `System.currentTimeMillis()` 替代原始 ZSet score→排序精度丢失（`FavoriteService.java:94`）。

---

### 3.2 双写失败回滚完整性 | 透镜：工程/业务

**必须检查**：涉及两组数据操作的方法，任一组失败后另一组是否被回滚。

**怎么查**：
```bash
# 找包含多组操作的方法（MQ send + DB write、Redis op + DB op 等）
grep -rn -e 'redisOperator' -e 'stringRedisTemplate' -e 'asyncSend' -e 'syncSend' my-xhs-<module>/src/main/java/ -B5 -A5 | grep -B3 'Mapper\|insert\|save\|update'
```
逐方法画操作图：操作 A → 操作 B → A 失败回滚 B？B 失败回滚 A？

**判定**：
- 操作 A 成功 + 操作 B 失败 → 无回滚 A → 永久不一致
- 回滚用 `now()` 替代原始值 → 虽回滚但数据错
- 仅 log 无补偿 → 永久不一致无自愈

**案例**：`handleFollowEvent` follower 成功 + followee 失败，无回滚 follower（`CounterEventConsumer.java:306` 修复）。回滚 `score` 用 `now()` 替代 `ZSCORE` 读出的原始值（`FavoriteService.java:94`）。

---

### 3.3 MQ 消费乱序防护 | 透镜：分布式/工程/并发/盲区

**必须检查**：每个 MQ Consumer 的版本号/时间戳防护机制是否完整、原子。

**怎么查**：
```bash
# 找出所有 Consumer 的版本检查
grep -rn 'VERSION_PREFIX\|versionKey\|eventTime\|actionTime' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/

# 逐检查点验证：key 维度、原子性、时钟域
```

**判定**：

| 检查点 | 问题模式 | 判定条件 |
|--------|---------|---------|
| 版本 key 缺维度 | `version:{bizType}:{bizId}` 缺 userId | 跨用户互相覆盖 |
| 检查非原子 | `GET → 比较 → SET` 非 Lua | 并发双过 |
| key 按 action 分割 | `PRE_DEDUCT` key ≠ `CONFIRM` key | CONFIRM 先于 PRE_DEDUCT 到——无防护 |
| 时钟域混用 | Consumer 用 `LocalDateTime.now()` 与 eventTime 比较 | 多实例时钟偏移 → 失效 |
| 时间精度不足 | DB `DATETIME`(秒级) vs eventTime（毫秒） | 同秒比较全部通过 |

**修复标准**：key 含全部业务维度(`userId+bizType+bizId`)；Lua `GET+比较+SET` 原子化；key 按业务实体统一不含 action；全链路用 `eventTime`；DDL `DATETIME(3)`。

**案例**：`LikeUnlikeConsumer` versionKey 缺 userId→跨用户覆盖（`LikeUnlikeConsumer.java:70`）；`CartSyncConsumer` catch 用 `now()` 混用时钟域（`CartSyncConsumer.java:139` eventTime 修复）；`InventoryDeductConsumer` key 按 action 分割不同防（改为统一 prefix）。

---

### 3.4 对账任务覆盖度 | 透镜：工程/生产级

**必须检查**：每个对账 Job 的扫描范围、修复方向、写入方式。

**怎么查**：
```bash
grep -rn 'selectDistinct\|selectList\|selectBatch' my-xhs-<module>/src/main/java/com/myxhs/*/job/
```
逐 Job 确认：扫描了哪些维度（是否完整）、修复方向（谁修谁）、写入方式（是否安全）。

**判定**：

| 检查点 | 问题模式 |
|--------|---------|
| 扫描范围不全 | 只扫 `user_id` 不扫 `follow_user_id`→粉丝侧永不修复 |
| 修复方向危险 | Redis 无 → 删 MySQL，不分"Redis 故障"vs"用户清空"→双份全丢 |
| 盲写全字段 | `updateById(selectById快照)` → 覆盖并发变更 |
| 业务类型不全 | 只对 note 对账，comment 漏了 |
| 修复时间戳 | `setUpdatedAt(LocalDateTime.now())` → 历史数据原始时间丢失 |

**案例**：`FollowCounterRepairJob` 只扫 `user_id` 侧（`FollowMapper.java:29` UNION 修复）；`CartReconcileJob` 场景 3 误删 MySQL 兜底（`CartReconcileJob.java:110` 加 EXISTS 检查修复）；`InventoryReconcileJob` 盲写全字段覆盖并发。

---

### 3.5 缓存回填安全 | 透镜：工程/盲区

**必须检查**：DB → Redis 回填缓存时，是否可能在途写操作尚未落库——导致回填覆盖了更晚的状态。

**怎么查**：
```bash
grep -rn -e 'redisOperator.*set' -e 'stringRedisTemplate.*set' my-xhs-<module>/src/main/java/ -B5 | grep -B3 'selectById\|selectList\|selectOne'
```

**判定**：

| 检查点 | 问题模式 |
|--------|---------|
| 权威数据回填 | 用滞后 MySQL 快照覆盖 Redis 权威数据→在途预扣被覆盖→超卖 |
| 回填无降级 | `redisOperator.set` 失败无 try-catch→查到 DB 数据但 500 |

**案例**：`InventoryService.getStock` 盲回填滞后 MySQL 快照覆盖 Redis 权威→超卖（`InventoryService.java:496` 删回填修复）；`SpuService.getSpuDetail`（`SpuService.java:490`）回填无 try-catch 触发 500（降级修复）。

---

### 3.6 跨服务计数一致性 | 透镜：微服务/业务

**必须检查**：同一业务计数在两个服务分别维护时，是否**指定了权威方** + 有**对账机制**。

**怎么查**：
```bash
# 同一业务概念在不同模块的 Redis key 模式
grep -rn 'like.*counter\|count.*like\|analytics.*count\|counter.*analytics' my-xhs-*/src/main/java/
```

**判定**：两个服务各维一份同语义计数器，无权威声明无对账 → 漂移后永远不自愈。

**案例**：analytics 维护 `like:set`，counter 维护独立计数——以 analytics 为权威方对账（`CounterService.reconcileLikeFromAnalytics` 修复）。

---

### 3.7 Canal/外部回声防护 | 透镜：分布式/盲区

**必须检查**：如果模块有 Canal/binlog 监听，确认它**不删除 L1 权威数据**。

**怎么查**：
```bash
grep -rn 'canal\|binlog\|mysql_binlog' my-xhs-<module>/src/main/java/
```
如果命中：确认 `UPDATE` 事件的处理逻辑——是删除缓存还是标记刷新？

**判定**：
- L1 权威（Redis 是唯一写入点）+ Canal 监听 MySQL UPDATE → 回声把权威数据 DELETE → 库存/计数丢失
- `UPDATE` 事件应跳过或只做标记，永远不 DELETE

**案例**：`InventoryCacheEvictConsumer` 收到 Canal 发出的 UPDATE 后 DELETE Redis——L1 权威的库存预扣被清空（修复：UPDATE 事件不再删缓存）。

---

### 3.8 缓存失效并发窗口 | 透镜：工程/并发

**必须检查**：缓存失效操作（删缓存/预热）是否考虑了并发读回填旧值的窗口。

**判定**：

| 模式 | 风险 | 修复 |
|------|------|------|
| 延迟双删 | 第二次删除失败→旧值残留至 TTL | MQ 异步延迟删除+重试 |
| Pipeline 当原子用 | Pipeline 非事务——中间可穿插其他命令 | 批量操作前加 pause 标记防护 |
| 冷 Key 并发 miss | 缓存过期瞬间大量请求击穿 DB | 分布式锁+双重检查 |

**案例**：`InventoryService.resizeBuckets` Pipeline 写桶，中间 preDeduct 读到部分新值——resize 期间加 pause 标记（`ResizeInProgressException`）。

---

### 3.9 时钟域一致性 | 透镜：分布式/工程/盲区

**必须检查**：时间戳比较中的时钟域是否统一——DB 精度、MQ eventTime、消费者 now() 三者是否匹配。

**怎么查**：
```bash
# Consumer 中用 now() 替代 eventTime
grep -rn -e 'LocalDateTime.now()' -e 'Instant.now()' -e 'new Date()' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/

# DDL 精度（优先查模块内；若无则查项目根 sql/ 目录）
grep -rn 'DATETIME' my-xhs-<module>/src/main/resources/ 2>/dev/null || grep -rn 'DATETIME' sql/ 2>/dev/null
grep -rn 'DATETIME' my-xhs-<module>/src/main/resources/ sql/ 2>/dev/null | grep -v 'DATETIME(3)'
```

**判定**：

| 检查点 | 问题 |
|--------|------|
| DB `DATETIME`（秒）vs eventTime（毫秒） | 同秒内任意顺序比较全通过→顺序防护失效 |
| Consumer 用 `now()` 与 `eventTime` 比较 | 多实例时钟偏移→不同实例结果不同 |
| Producer 用 `now()` 设 eventTime | 生产者时钟漂移→多生产者乱序 |

**修复标准**：DDL `DATETIME(3)` 毫秒精度；全链路用消息中的 `eventTime`；Producer 用统一时间源（如 Redis `TIME`）。

**案例**：`CartSyncConsumer` catch 块用 `LocalDateTime.now()` 替代 payload 的 `eventTime`（`CartSyncConsumer.java:139` 改 eventTime）；DDL `DATETIME`→`DATETIME(3)`。

---

### 3.10 对账与回填性能 | 透镜：性能

**必须检查**：对账 Job 的扫描方式（全量 vs 增量）、批量大小、调度频率是否合理；缓存回填是否有批量大小控制。

**怎么查**：
```bash
grep -rn 'selectList\|selectBatch\|batchSize\|SCAN' my-xhs-<module>/src/main/java/com/myxhs/*/job/
grep -rn '@Scheduled' my-xhs-<module>/src/main/java/com/myxhs/*/job/
```

**判定**：
- 对账用 `selectList` 全量扫描百万级表→高峰期可能打挂 DB
- 对账无 `batchSize` 分页→一次性加载全部数据 OOM
- 对账频率 1 分钟一次但单次执行超过 1 分钟→任务堆叠雪崩
- 缓存回填一次回填全部热点 key→瞬间流量冲击 Redis

**案例**：（全特性面预置检查项——my-xhs 02-07 的 Job 规模较小未暴露，08-15 的 search 重建索引/order 日终对账可能有此问题。）

---

### 3.11 存储抽象层耦合 | 透镜：可扩展性

**必须检查**：模块的数据操作是否直接写死 Redis/MySQL API，还是通过抽象层；切换存储后端的改动面有多大。

**怎么查**：
```bash
# 直接 redisOperator 调用数（无抽象层封装）
grep -rn 'redisOperator\.\|stringRedisTemplate\.' my-xhs-<module>/src/main/java/ | wc -l
# 抽象层封装数（Service 内部封装的 helper/util 类）
grep -rn 'class.*Cache\|class.*Repository\|class.*Storage' my-xhs-<module>/src/main/java/ | wc -l
```
如果前者远大于后者 → 存储切换时需改动大量 Service 代码。

**判定**：
- Redis 操作散落在 10+ 个 Service 类无统一 DataAccess 层 → 切 Caffeine 改全模块
- Canal Consumer 硬编码 MySQL 表名→切 Debezium 改全表映射
- **记录即可，不强制立即修改。**

**案例**：（全特性面预置检查项，按模块实际 grep 结果填充。）

---

### 3.12 多数据源一致性 | 透镜：分布式/盲区

**必须检查**：模块是否维护了 Redis 以外的其他数据源（ES / MongoDB / 本地缓存 Caffeine），如果有，这些数据源之间的同步机制是否完整。

**怎么查**：
```bash
# ES 索引同步
grep -rn 'Elasticsearch\|RestHighLevelClient\|ElasticsearchRestTemplate\|IndexCoordinates\|index\(\)' my-xhs-<module>/src/main/java/

# Caffeine/local cache
grep -rn 'CacheManager\|@Cacheable\|@CacheEvict\|Caffeine' my-xhs-<module>/src/main/java/
```

**判定**：
- ES 索引有写入路径但没有重新同步/对账机制→商品下架后 ES 仍搜到
- Caffeine+Redis 两级缓存无失效协议→Redis 过期后 Caffeine 仍返回旧值
- 新加字段后 ES mapping 没更新→查询该字段抛异常

**案例**：（全特性面预置检查项——my-xhs 11-search 模块维护 ES 索引，和 05-product 的数据同步需要审查。）

```bash
mvn compile test-compile -pl <module> -am
mvn test -pl <module>

# 双写模式（Redis+DB 同方法）
grep -rn -e 'redisOperator' -e 'stringRedisTemplate' -e 'redisTemplate' my-xhs-<module>/src/main/java/ -B3 -A3 | grep -B2 'Mapper\|insert\|update'

# 版本 key 维度（缺 userId/类型）
grep -rn -e 'VERSION_PREFIX' -e 'versionKey' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/

# Consumer 用 now() 非 eventTime
grep -rn -e 'LocalDateTime.now()' -e 'Instant.now()' -e 'new Date()' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/

# 对账扫描范围（是否 UNION 双方向）
grep -rn -e 'selectDistinct' -e 'selectList' my-xhs-<module>/src/main/java/com/myxhs/*/job/

# Canal/binlog 监听——找 DELETE 或 del 操作
grep -rn -e 'canal' -e 'binlog' my-xhs-<module>/src/main/java/ -A5 | grep -E -e 'deleteBy|DELETE|redisDelete|redis.*del\b' -e 'binlog.*delete'

# 缓存回填——滞后 MySQL 覆盖 Redis 权威
grep -rn -B5 -e 'redisOperator.*set' -e 'stringRedisTemplate.*set' my-xhs-<module>/src/main/java/ | grep -B3 'selectById\|selectList'

# DDL 时间戳精度——优先模块内，其次项目根 sql/
grep -rn -e 'DATETIME' -e 'TIMESTAMP' my-xhs-<module>/src/main/resources/ sql/ 2>/dev/null | grep -v 'DATETIME(3)'
```
