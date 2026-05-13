# 计数服务

> 所属服务：my-xhs-counter (9004) | 开发阶段：Phase-1 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

统一管理所有计数：点赞数、收藏数、评论数、粉丝数、关注数、浏览数等。Redis INCR/DECR 实时计数，Buffer-Trigger 攒批合并后批量刷盘到 MySQL，定时对账修复保证 Redis 与 DB 最终一致。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 实时计数（INCR/DECR） | ✅ | Redis String，毫秒级读写 |
| Buffer-Trigger 攒批刷盘 | ✅ | 满 100 条或超 5 秒 → 合并同 Key → 批量写 DB |
| 合并策略 | ✅ | +1, +1, -1, +1 → +2，4 次写变 1 次 |
| 批量查询 | ✅ | Pipeline 批量 GET |
| 对账修复 | ✅ | 每天凌晨 Redis vs DB 对比，差异自动修复 |
| 本地缓存 | ✅ | Caffeine 热点计数本地缓存（1 分钟） |
| 计数归零保护 | ✅ | DECR 前检查，防止计数变为负数 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 计数记录总量 | 10 亿 | 5000 万笔记 × 7 种计数 + 1000 万用户 × 3 种计数 |
| 计数更新 QPS | 10000 | 点赞/收藏/评论/浏览等事件汇总 |
| 计数查询 QPS | 50000 | 每次加载笔记/用户都要查计数 |
| Buffer 刷盘频率 | 每 5 秒 | 满 100 条或超 5 秒触发 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
                    ┌─────────────────────────────────────────┐
                    │           my-xhs-counter (9004)          │
                    │                                          │
MQ Consumer ──────→ │  Redis INCR/DECR（实时计数）              │
(点赞/收藏/评论/关注) │       ↓                                  │
                    │  写入本地 Buffer（内存）                    │
                    │       ↓                                  │
                    │  Buffer 满 100 条 或 超时 5 秒             │
                    │       ↓                                  │
                    │  合并同 Key 增减（+1,+1,-1 → +1）          │
                    │       ↓                                  │
                    │  批量 SQL: INSERT ON DUPLICATE KEY UPDATE │
                    │       ↓                                  │
                    │  MySQL t_counter                         │
                    └─────────────────────────────────────────┘

查询链路：
Client → Caffeine 本地缓存（1min）→ Redis GET → MySQL（兜底）
```

### 2.2 模块交互

| 调用方 | 被调用方 | 方式 | 场景 |
|--------|---------|------|------|
| my-xhs-analytics | my-xhs-counter | MQ | 点赞/收藏/关注事件 → 计数更新 |
| my-xhs-content | my-xhs-counter | MQ | 评论事件 → 评论数更新 |
| my-xhs-home | my-xhs-counter | Feign | Feed 流聚合时批量查计数 |
| my-xhs-counter | Redis | INCR/DECR | 实时计数 |
| my-xhs-counter | MySQL | 批量 SQL | Buffer-Trigger 刷盘 |

### 2.3 Buffer-Trigger 流程详解

```
点赞事件 → Redis INCR（实时扣减，用户立即可见）
         → 写入本地 Buffer（内存 ConcurrentHashMap）
              ↓
         Buffer 满 100 条 或 超时 5 秒
              ↓
         合并同 Key 增减：
         例：counter:note_like:20001 → +1, +1, -1 → 合并为 +1
              ↓
         批量 SQL:
         INSERT INTO t_counter (target_type, target_id, count_type, count_value)
         VALUES (1, 20001, 1, 1)
         ON DUPLICATE KEY UPDATE count_value = count_value + VALUES(count_value)
              ↓
         写入失败 → 重试 3 次 → 仍失败记录日志（对账修复兜底）
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
-- 计数表（实际 DDL 来自 init-databases.sql）
CREATE TABLE IF NOT EXISTS t_counter (
    id           BIGINT   NOT NULL COMMENT 'ID',
    target_type  TINYINT  NOT NULL COMMENT '目标类型：1-笔记 2-用户',
    target_id    BIGINT   NOT NULL COMMENT '目标ID',
    count_type   TINYINT  NOT NULL COMMENT '计数类型：1-点赞 2-收藏 3-评论 4-分享 5-浏览 6-粉丝 7-关注',
    count_value  BIGINT   NOT NULL DEFAULT 0 COMMENT '计数值',
    deleted      TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_target_count (target_type, target_id, count_type),
    INDEX idx_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计数表';
```

### 3.2 索引设计

| 索引名 | 字段 | 使用场景 |
|--------|------|----------|
| `uk_target_count` | (target_type, target_id, count_type) | 唯一索引，INSERT ON DUPLICATE KEY UPDATE 依赖 |
| `idx_target` | (target_type, target_id) | 批量查询某个目标的所有计数 |

### 3.3 计数类型枚举

| target_type | count_type | 含义 | 示例 |
|-------------|-----------|------|------|
| 1（笔记） | 1 | 笔记点赞数 | 笔记 20001 被赞了 42 次 |
| 1（笔记） | 2 | 笔记收藏数 | 笔记 20001 被收藏了 18 次 |
| 1（笔记） | 3 | 笔记评论数 | 笔记 20001 有 128 条评论 |
| 1（笔记） | 5 | 笔记浏览数 | 笔记 20001 被浏览了 5000 次 |
| 2（用户） | 6 | 用户粉丝数 | 用户 10086 有 1000 个粉丝 |
| 2（用户） | 7 | 用户关注数 | 用户 10086 关注了 200 人 |

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `counter:{targetType}:{targetId}:{countType}` | String | 永久 | 计数值 |

> **示例**：`counter:1:20001:1` = 笔记 20001 的点赞数
>
> **Key 命名统一说明**：架构文档中使用 `counter:{bizType}:{bizId}` 两段式命名，关注服务的 Lua 脚本中使用 `counter:user_following:{userId}` 语义化命名。实际开发时统一采用三段式 `counter:{targetType}:{targetId}:{countType}`，关注服务的 Lua 脚本也需要适配此格式。

### 4.2 三级缓存架构

```
查询链路：Caffeine（L1）→ Redis（L2）→ MySQL（L3）

L1: Caffeine 本地缓存
    - 容量：10000 条
    - TTL：1 分钟
    - 适用：热点计数（如首页笔记的点赞数）
    - 优势：零网络开销，微秒级

L2: Redis
    - TTL：永久
    - 适用：所有计数
    - 优势：毫秒级，集群共享

L3: MySQL
    - 适用：Redis 不可用时的兜底
    - 优势：强持久
```

---

## 📡 五、接口设计

### 5.1 接口列表

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/counter/increment` | 计数+1 | 内部调用 |
| POST | `/api/counter/decrement` | 计数-1 | 内部调用 |
| GET | `/api/counter/get` | 查询计数 | ❌（公开） |
| POST | `/api/counter/batch-get` | 批量查询计数 | ❌（公开） |

### 5.2 请求/响应示例

**批量查询计数**

```http
POST /api/counter/batch-get
Content-Type: application/json

{
  "queries": [
    {"targetType": 1, "targetId": 20001, "countTypes": [1, 2, 3, 5]},
    {"targetType": 1, "targetId": 20002, "countTypes": [1, 2, 3, 5]}
  ]
}
```

```json
{
  "code": 200,
  "data": {
    "1:20001": {"like": 42, "collect": 18, "comment": 128, "view": 5000},
    "1:20002": {"like": 15, "collect": 7, "comment": 32, "view": 1200}
  }
}
```

---

## 💻 六、核心代码实现

### 6.1 Buffer-Trigger 核心实现

```java
/**
 * Buffer-Trigger 计数缓冲区
 * 关键点：攒批 → 合并同 Key → 定时刷盘
 *
 * vs 直接写 DB：每次点赞都写 DB → 10000 QPS × 1 次 SQL = 10000 次/秒
 * Buffer-Trigger：10000 次点赞 → 合并后可能只有 2000 个不同 Key → 1 次批量 SQL
 */
@Component
public class CounterBuffer {

    // 缓冲区：Key = "targetType:targetId:countType", Value = 累计增量
    // 注意：非 final，因为 flush 时采用双 Buffer 交换方案
    private volatile ConcurrentHashMap<String, AtomicLong> buffer = new ConcurrentHashMap<>();
    private final AtomicInteger bufferSize = new AtomicInteger(0);

    private static final int MAX_BUFFER_SIZE = 100;  // 满 100 条触发
    private static final int FLUSH_INTERVAL_MS = 5000; // 5 秒定时触发

    /**
     * 写入缓冲区
     */
    public void add(int targetType, long targetId, int countType, long delta) {
        String key = targetType + ":" + targetId + ":" + countType;

        // 合并同 Key 增减：+1, +1, -1 → +1
        buffer.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(delta);

        // 检查是否触发刷盘
        if (bufferSize.incrementAndGet() >= MAX_BUFFER_SIZE) {
            flush();
        }
    }

    /**
     * 定时刷盘（每 5 秒）
     */
    @Scheduled(fixedRate = FLUSH_INTERVAL_MS)
    public void scheduledFlush() {
        if (!buffer.isEmpty()) {
            flush();
        }
    }

    /**
     * 刷盘：合并后批量写 DB
     * 采用双 Buffer 交换方案，避免 flush 期间 add 写入的数据被 clear 丢失
     */
    private synchronized void flush() {
        if (buffer.isEmpty()) return;

        // 1. 双 Buffer 交换：将当前 buffer 换出，立即创建新 buffer 接收后续写入
        ConcurrentHashMap<String, AtomicLong> snapshot = buffer;
        buffer = new ConcurrentHashMap<>();
        bufferSize.set(0);

        if (snapshot.isEmpty()) return;

        // 2. 从快照中提取非零增量
        Map<String, Long> deltaMap = new HashMap<>();
        snapshot.forEach((key, value) -> {
            long delta = value.get();
            if (delta != 0) { // 合并后为 0 的跳过（+1 再 -1）
                deltaMap.put(key, delta);
            }
        });

        if (deltaMap.isEmpty()) return;

        // 3. 构建批量 SQL 参数
        List<CounterFlushDTO> flushList = deltaMap.entrySet().stream()
                .map(entry -> {
                    String[] parts = entry.getKey().split(":");
                    return new CounterFlushDTO(
                            Integer.parseInt(parts[0]),  // targetType
                            Long.parseLong(parts[1]),    // targetId
                            Integer.parseInt(parts[2]),  // countType
                            entry.getValue());           // delta
                })
                .collect(Collectors.toList());

        // 3. 批量写 DB（INSERT ON DUPLICATE KEY UPDATE）
        // 按唯一索引排序，避免死锁（见踩坑记录 8.3）
        flushList.sort(Comparator.comparing(CounterFlushDTO::getTargetType)
                .thenComparing(CounterFlushDTO::getTargetId)
                .thenComparing(CounterFlushDTO::getCountType));
        try {
            counterMapper.batchUpsert(flushList);
            log.info("Buffer-Trigger 刷盘成功: {} 条", flushList.size());
        } catch (Exception e) {
            log.error("Buffer-Trigger 刷盘失败: {} 条", flushList.size(), e);
            // 重试 3 次
            retryFlush(flushList, 3);
        }
    }
}
```

### 6.2 批量 Upsert SQL

```java
/**
 * MyBatis 批量 Upsert
 * INSERT ON DUPLICATE KEY UPDATE：首次写入 INSERT，后续更新 UPDATE
 */
@Mapper
public interface CounterMapper {

    /**
     * 批量 Upsert（id 由雪花算法生成，在 Service 层赋值）
     */
    @Insert("<script>" +
            "INSERT INTO t_counter (id, target_type, target_id, count_type, count_value) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.id}, #{item.targetType}, #{item.targetId}, #{item.countType}, #{item.delta})" +
            "</foreach>" +
            " ON DUPLICATE KEY UPDATE count_value = count_value + VALUES(count_value), " +
            " updated_at = NOW()" +
            "</script>")
    void batchUpsert(@Param("list") List<CounterFlushDTO> list);
}
```

### 6.3 对账修复

```java
/**
 * 对账修复（每天凌晨 3 点执行）
 * Redis 值 vs DB 值，差异超过阈值自动修复
 */
@Scheduled(cron = "0 0 3 * * ?")
public void reconcile() {
    log.info("开始计数对账修复...");
    int fixedCount = 0;

    // 1. 分批扫描 DB 计数记录（游标分页，每批 1000 条，避免全表扫描 OOM）
    long lastId = 0;
    int batchSize = 1000;
    List<Counter> batch;
    do {
        batch = counterMapper.selectBatchAfterId(lastId, batchSize);
        if (batch.isEmpty()) break;
        lastId = batch.get(batch.size() - 1).getId();

        for (Counter dbCounter : batch) {
        String redisKey = String.format("counter:%d:%d:%d",
                dbCounter.getTargetType(), dbCounter.getTargetId(), dbCounter.getCountType());

        // 2. 查 Redis 值
        String redisValue = redisOperator.get(redisKey);
        long redisCount = redisValue != null ? Long.parseLong(redisValue) : 0;
        long dbCount = dbCounter.getCountValue();

        // 3. 差异检查
        if (redisCount != dbCount) {
            // 以 Redis 为准（Redis 是实时更新的权威数据源）
            // 但如果 Redis 值为 0 且 DB 有值，可能是 Redis 数据丢失，以 DB 为准
            if (redisCount == 0 && dbCount > 0) {
                redisOperator.set(redisKey, String.valueOf(dbCount));
                log.warn("对账修复(Redis恢复): key={}, redis=0, db={}", redisKey, dbCount);
            } else {
                counterMapper.updateCountValue(dbCounter.getId(), redisCount);
                log.warn("对账修复(DB修正): key={}, redis={}, db={}",
                        redisKey, redisCount, dbCount);
            }
            fixedCount++;
        }
    }
    } while (batch.size() == batchSize); // 不足一批说明已扫描完毕

    log.info("计数对账修复完成，修复 {} 条", fixedCount);
}
```

### 6.4 计数查询（三级缓存）

```java
/**
 * 计数查询服务
 * 三级缓存：Caffeine → Redis → MySQL
 */
@Service
public class CounterQueryService {

    private final Cache<String, Long> localCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    public long getCount(int targetType, long targetId, int countType) {
        String key = targetType + ":" + targetId + ":" + countType;

        // L1: Caffeine 本地缓存
        Long cached = localCache.getIfPresent(key);
        if (cached != null) return cached;

        // L2: Redis
        String redisKey = "counter:" + key;
        String redisValue = redisOperator.get(redisKey);
        if (redisValue != null) {
            long count = Long.parseLong(redisValue);
            localCache.put(key, count);
            return count;
        }

        // L3: MySQL（兜底）
        Counter counter = counterMapper.selectByTarget(targetType, targetId, countType);
        long count = counter != null ? counter.getCountValue() : 0;
        redisOperator.set(redisKey, String.valueOf(count)); // 回填 Redis
        localCache.put(key, count);
        return count;
    }
}
```

---

## ⚖️ 七、方案对比

### 7.1 计数刷盘：Buffer-Trigger vs 直接写 DB vs 定时全量同步

| 维度 | Buffer-Trigger（✅ 选定） | 直接写 DB | 定时全量同步 |
|------|------------------------|----------|------------|
| DB 写入量 | 合并后极少（+1,+1,-1→+1） | 每次操作 1 次 SQL | 全量覆盖 |
| 实时性 | 5 秒延迟 | 实时 | 分钟级延迟 |
| DB 压力 | 极低 | 极高（万级 QPS） | 中等 |
| 数据丢失风险 | Buffer 在内存中，宕机丢失 5 秒数据 | 无 | 无 |

**选择理由**：计数更新 QPS 万级，直接写 DB 不可接受。Buffer-Trigger 合并同 Key 增减后批量写入，DB 压力降低 90%+。5 秒延迟对计数场景完全可接受。

### 7.2 对账策略：以 Redis 为准 vs 以 DB 为准

| 场景 | 策略 | 理由 |
|------|------|------|
| Redis 有值，DB 有值，不一致 | 以 Redis 为准 | Redis 是实时更新的权威源 |
| Redis 值为 0，DB 有值 | 以 DB 为准 | Redis 可能数据丢失（重启/故障） |
| Redis 有值，DB 无记录 | 以 Redis 为准，INSERT DB | Buffer 刷盘失败导致 DB 缺失 |

---

## 🐛 八、踩坑记录

### 8.1 Buffer 合并后为 0 仍写 DB

- **现象**：用户点赞后立即取消，Buffer 中 +1 和 -1 合并为 0，仍然执行了 SQL
- **原因**：刷盘时未过滤 delta=0 的记录
- **解决**：刷盘时 `if (delta != 0)` 过滤，合并为 0 的跳过
- **教训**：Buffer 合并后必须过滤无效操作

### 8.2 并发刷盘导致数据丢失

- **现象**：定时刷盘和满量刷盘同时触发，部分数据丢失
- **原因**：两个线程同时读取并清空 Buffer
- **解决**：`flush()` 方法加 `synchronized`，保证同一时刻只有一个线程刷盘
- **教训**：Buffer 的读取和清空必须是原子操作

### 8.3 INSERT ON DUPLICATE KEY UPDATE 死锁

- **现象**：高并发批量 Upsert 时偶发死锁
- **原因**：MySQL 在 INSERT ON DUPLICATE KEY UPDATE 时会加 Next-Key Lock，多个事务交叉加锁导致死锁
- **解决**：批量 Upsert 前按主键排序，保证加锁顺序一致
- **教训**：批量写入时必须保证加锁顺序一致

---

## 📊 九、测试验证

### 9.1 功能测试

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 计数+1 | targetType=1, targetId=20001, countType=1 | Redis INCR 成功 | ⬜ |
| 计数-1 | 同上 | Redis DECR 成功 | ⬜ |
| 查询计数 | 同上 | 返回当前计数值 | ⬜ |
| 批量查询 | 多个目标 | 返回所有计数 | ⬜ |
| Buffer 刷盘（满量） | 连续 100 次 INCR | 触发刷盘，DB 更新 | ⬜ |
| Buffer 刷盘（超时） | 1 次 INCR 后等 5 秒 | 触发刷盘，DB 更新 | ⬜ |
| Buffer 合并 | +1, +1, -1 | DB 只增加 1 | ⬜ |
| 对账修复 | Redis=100, DB=98 | DB 修正为 100 | ⬜ |
| 计数归零保护 | 计数为 0 时 DECR | 不变为负数 | ⬜ |

### 9.2 压测数据（预期基线）

| 场景 | 并发数 | 目标 QPS | 目标平均 RT | 目标 P99 RT |
|------|--------|---------|-----------|-----------|
| 计数更新（Redis INCR） | 200 | 10000 | < 5ms | < 20ms |
| 计数查询（Caffeine 命中） | 500 | 50000 | < 1ms | < 5ms |
| 计数查询（Redis 命中） | 200 | 30000 | < 5ms | < 20ms |
| 批量查询（10 个计数） | 100 | 5000 | < 10ms | < 50ms |

---

## 🎤 十、面试考察点

### Q1: Buffer-Trigger 是什么？为什么要用？

**推荐回答思路**：

> 1. "Buffer-Trigger 是一种攒批写入模式：先在内存中缓冲，满足条件后批量写入 DB"
> 2. "触发条件：满 100 条 或 超时 5 秒，先到先触发"
> 3. "核心优化——合并同 Key 增减：同一个笔记被点赞 100 次又取消 50 次，合并后只写 1 条 SQL（+50），而不是 150 条"
> 4. "效果：计数更新 QPS 从 10000 降到 DB 写入 200 次/秒，DB 压力降低 98%"

### Q2: Redis 和 DB 计数不一致怎么办？

**推荐回答思路**：

> 1. "三层保障：Buffer-Trigger 正常刷盘 + 失败重试 3 次 + 每天凌晨对账修复"
> 2. "对账逻辑：扫描 DB 所有计数记录，逐条与 Redis 对比"
> 3. "修复策略：一般以 Redis 为准（Redis 是实时更新的权威源）；但如果 Redis 值为 0 且 DB 有值，说明 Redis 数据丢失，以 DB 为准"
> 4. "为什么不以 DB 为准？因为 Buffer 有 5 秒延迟，DB 的值可能落后于 Redis"

### Q3: Buffer 在内存中，宕机了数据不会丢吗？

**推荐回答思路**：

> 1. "会丢，最多丢 5 秒的数据（Buffer 刷盘间隔）"
> 2. "但计数场景可以接受：点赞数少了几个不影响业务"
> 3. "兜底方案：每天凌晨对账修复，Redis 值 vs DB 值对比，自动修正差异"
> 4. "如果要求零丢失：可以用 Redis Stream 替代内存 Buffer，但复杂度和延迟都会增加"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-1/README.md | §3.7 | 计数服务完整设计（Buffer-Trigger/对账/Caffeine） |
| 📄 02-module-detailed-design.md | §6 | 计数服务核心链路/合并策略/对账修复 |
| 📄 03-distributed-solutions.md | §4 | 计数一致性方案 |
