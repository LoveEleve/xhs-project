# 数据备份与容灾

> 所属维度：数据安全 | 开发阶段：Phase-6 | 核心指标：RTO / RPO

---

## 🎯 一、备份策略

| 数据类型 | 备份方式 | 备份频率 | 保留周期 |
|----------|----------|----------|----------|
| MySQL | 全量备份(mysqldump) + binlog增量 | 全量每天凌晨 + binlog实时 | 全量7天，binlog30天 |
| Redis | RDB快照 + AOF | RDB每6小时 + AOF每秒 | RDB 7天 |
| ES | Snapshot | 每天凌晨 | 7天 |
| 代码/配置 | Git | 每次提交 | 永久 |

---

## 🏗️ 二、恢复方案

| 故障场景 | 恢复方案 | RTO | RPO |
|----------|----------|-----|-----|
| MySQL误删数据 | 从从库恢复 + binlog回放 | < 30分钟 | < 1分钟 |
| MySQL主库宕机 | ShardingSphere主从切换 | < 30秒 | 0(同步复制) |
| Redis数据丢失 | 从MySQL全量重建 | < 5分钟 | < 1分钟(取决于最后对账时间) |
| ES索引损坏 | XXL-Job全量重建 | < 30分钟 | 0(从MySQL源数据重建) |
| 整机故障 | K8s重新调度 + PVC数据卷 | < 5分钟 | 取决于最后一次备份 |

> **RTO**(Recovery Time Objective)：从故障到恢复的最大允许时间
> **RPO**(Recovery Point Objective)：允许丢失的最大数据量（用时间衡量）

---

## 💻 三、备份自动化

### 3.1 MySQL备份脚本

```bash
#!/bin/bash
# deploy/scripts/mysql-backup.sh
# 每天凌晨2点由XXL-Job触发

# 全量备份
mysqldump -h mysql-master -u root -proot123 \
  --single-transaction --flush-logs \
  --all-databases | gzip > /backup/mysql/full_$(date +%Y%m%d).sql.gz

# 清理7天前的备份
find /backup/mysql -name "*.sql.gz" -mtime +7 -delete

# 上传到对象存储（生产环境）
# ossutil cp /backup/mysql/ oss://my-xhs-backup/mysql/
```

### 3.2 MySQL binlog 恢复脚本（指定时间点恢复）

```bash
#!/bin/bash
# deploy/scripts/mysql-restore-pit.sh
# Point-in-Time Recovery：恢复到指定时间点

RESTORE_TIME="$1"  # 格式：2026-05-12 14:30:00
BACKUP_FILE="$2"   # 全量备份文件路径
BINLOG_DIR="/var/lib/mysql"

if [ -z "$RESTORE_TIME" ] || [ -z "$BACKUP_FILE" ]; then
    echo "用法: $0 '2026-05-12 14:30:00' /backup/mysql/full_20260512.sql.gz"
    exit 1
fi

echo "=== Step 1: 恢复全量备份 ==="
gunzip -c "$BACKUP_FILE" | mysql -h localhost -u root -proot123

echo "=== Step 2: 应用 binlog 增量（到指定时间点）==="
# 找到全量备份后的 binlog 文件
BINLOG_FILES=$(ls $BINLOG_DIR/mysql-bin.* | sort)
for binlog in $BINLOG_FILES; do
    echo "应用 binlog: $binlog (截止 $RESTORE_TIME)"
    mysqlbinlog --stop-datetime="$RESTORE_TIME" "$binlog" | mysql -h localhost -u root -proot123
done

echo "=== 恢复完成 ==="
echo "已恢复到: $RESTORE_TIME"
```

### 3.3 Redis恢复流程

```
Redis数据丢失(主从切换/内存淘汰/误删)
    ↓
1. 从MySQL全量重建关键数据
   - 用户信息 → user:info:{userId}
   - 购物车 → cart:items:{userId}
   - 计数数据 → counter:{bizType}:{bizId}
    ↓
2. XXL-Job对账修复(Redis vs MySQL差异检测)
    ↓
3. 非关键数据靠自然回填(下次访问时从DB加载)
```

### 3.4 Redis 全量重建 Java 代码

```java
/**
 * Redis 缓存全量重建服务
 * 场景：Redis 主从切换后数据丢失、Redis 内存淘汰导致大量 Key 丢失
 * 策略：分页查询 MySQL → Pipeline 批量写入 Redis
 */
@Service
@Slf4j
public class RedisCacheRebuildService {

    private static final int BATCH_SIZE = 1000;

    /**
     * 重建用户信息缓存
     * 分页查询 MySQL → Pipeline 批量写入 Redis
     */
    public void rebuildUserInfoCache() {
        log.info("开始重建用户信息缓存...");
        long total = 0;
        long lastId = 0;

        while (true) {
            // 分页查询（基于 ID 游标，避免 OFFSET 深分页）
            List<User> users = userMapper.selectBatchAfterId(lastId, BATCH_SIZE);
            if (users.isEmpty()) break;

            // Pipeline 批量写入 Redis
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (User user : users) {
                    byte[] key = ("user:info:" + user.getId()).getBytes(StandardCharsets.UTF_8);
                    // 构建 Map<byte[], byte[]>（hMSet 要求的参数类型）
                    Map<byte[], byte[]> hash = new HashMap<>();
                    hash.put("nickname".getBytes(), user.getNickname().getBytes(StandardCharsets.UTF_8));
                    hash.put("avatar".getBytes(), (user.getAvatar() != null ? user.getAvatar() : "").getBytes(StandardCharsets.UTF_8));
                    hash.put("gender".getBytes(), String.valueOf(user.getGender()).getBytes(StandardCharsets.UTF_8));
                    connection.hMSet(key, hash);
                    // 设置 TTL（30min + 随机偏移，防雪崩）
                    long ttl = 1800 + ThreadLocalRandom.current().nextInt(300);
                    connection.expire(key, ttl);
                }
                return null;
            });

            lastId = users.get(users.size() - 1).getId();
            total += users.size();
            log.info("已重建用户缓存: {} 条, lastId={}", total, lastId);
        }

        log.info("用户信息缓存重建完成，共 {} 条", total);
    }

    /**
     * 重建计数缓存（点赞数/收藏数/评论数）
     */
    public void rebuildCounterCache() {
        log.info("开始重建计数缓存...");
        long total = 0;
        long lastId = 0;

        while (true) {
            List<CounterRecord> records = counterMapper.selectBatchAfterId(lastId, BATCH_SIZE);
            if (records.isEmpty()) break;

            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (CounterRecord record : records) {
                    String key = "counter:" + record.getBizType() + ":" + record.getBizId();
                    connection.set(key.getBytes(),
                            String.valueOf(record.getCount()).getBytes());
                    long ttl = 3600 + ThreadLocalRandom.current().nextInt(600);
                    connection.expire(key.getBytes(), ttl);
                }
                return null;
            });

            lastId = records.get(records.size() - 1).getId();
            total += records.size();
        }

        log.info("计数缓存重建完成，共 {} 条", total);
    }
}
```

### 3.5 Redis vs MySQL 对账修复

```java
/**
 * 缓存对账服务
 * 定时对比 Redis 和 MySQL 的数据，发现不一致时自动修复
 * 运行频率：每天凌晨 3 点（XXL-Job 触发）
 */
@Component
public class CacheReconciliationJob {

    /**
     * 对账入口
     */
    @XxlJob("cacheReconciliation")
    public void execute() {
        log.info("开始缓存对账...");
        ReconciliationReport report = new ReconciliationReport();

        // 1. 对账计数数据（Redis counter vs MySQL t_counter）
        reconcileCounters(report);

        // 2. 对账用户信息（Redis user:info vs MySQL t_user）
        reconcileUserInfo(report);

        // 3. 输出报告
        log.info("对账完成: 检查={}, 不一致={}, 已修复={}",
                report.getTotalChecked(), report.getInconsistent(), report.getFixed());

        // 4. 不一致数量超阈值则告警
        if (report.getInconsistent() > 100) {
            alertService.sendAlert("缓存对账异常",
                    "不一致数量: " + report.getInconsistent());
        }
    }

    private void reconcileCounters(ReconciliationReport report) {
        long lastId = 0;
        while (true) {
            List<CounterRecord> dbRecords = counterMapper.selectBatchAfterId(lastId, 1000);
            if (dbRecords.isEmpty()) break;

            for (CounterRecord dbRecord : dbRecords) {
                report.incrementChecked();
                String key = "counter:" + dbRecord.getBizType() + ":" + dbRecord.getBizId();
                String redisValue = redisTemplate.opsForValue().get(key);

                if (redisValue == null) {
                    // Redis 缺失 → 回填
                    redisTemplate.opsForValue().set(key,
                            String.valueOf(dbRecord.getCount()), 1, TimeUnit.HOURS);
                    report.incrementFixed();
                } else if (!redisValue.equals(String.valueOf(dbRecord.getCount()))) {
                    // 值不一致 → 以 MySQL 为准修复
                    log.warn("计数不一致: key={}, redis={}, mysql={}",
                            key, redisValue, dbRecord.getCount());
                    redisTemplate.opsForValue().set(key,
                            String.valueOf(dbRecord.getCount()), 1, TimeUnit.HOURS);
                    report.incrementInconsistent();
                    report.incrementFixed();
                }
            }

            lastId = dbRecords.get(dbRecords.size() - 1).getId();
        }
    }
}
```

### 3.6 ES全量重建

```
ES索引损坏/数据不一致
    ↓
1. XXL-Job触发全量重建任务
2. 按ID分页查询MySQL + 断点续传
3. 批量写入ES(Bulk API, 每批1000条)
4. 完成后对账(MySQL count vs ES count)
```

### 3.7 恢复演练计划

```
每季度执行一次恢复演练（测试环境）：

演练1: MySQL 误删恢复
  1. 在测试库执行 DELETE FROM t_order WHERE id = 12345
  2. 使用 binlog 恢复脚本恢复到删除前
  3. 验证数据完整性

演练2: Redis 全量重建
  1. 执行 FLUSHALL 清空 Redis
  2. 触发 RedisCacheRebuildService 全量重建
  3. 验证缓存命中率恢复到正常水平

演练3: ES 索引重建
  1. 删除 ES 索引
  2. 触发 XXL-Job 全量重建
  3. 验证搜索结果与 MySQL 数据一致

演练4: 整体灾难恢复
  1. 模拟整机故障（K8s 删除所有 Pod）
  2. 验证 K8s 自动重新调度
  3. 验证数据从 PVC 恢复
  4. 验证服务恢复时间 < 5 分钟
```

---

## ⚖️ 四、方案对比

| 维度 | mysqldump | xtrabackup | MySQL复制 |
|------|-----------|-----------|-----------|
| 备份速度 | 慢(逻辑备份) | 快(物理备份) | 实时 |
| 恢复速度 | 慢(重放SQL) | 快(拷贝文件) | 即时(从库可读) |
| 锁影响 | --single-transaction无锁(InnoDB) | 无锁 | 无锁 |
| 复杂度 | 低 | 中 | 低 |

**最终选择**：mysqldump全量(简单可靠) + binlog增量(实时) + 主从复制(热备)

---

## 🎤 五、面试考察点

### Q1: 数据误删了怎么恢复？

**推荐回答思路**：

> 1. "MySQL：主从延迟窗口内从从库恢复；超过窗口用全量备份+binlog回放到指定时间点"
> 2. "Redis：从MySQL全量重建+XXL-Job对账修复"
> 3. "ES：从MySQL源数据全量重建索引"

### Q2: RTO和RPO怎么定的？

**推荐回答思路**：

> 1. "核心链路(下单/支付)RTO<30秒——靠主从自动切换"
> 2. "数据RPO<1分钟——MySQL binlog实时复制、Redis AOF每秒持久化"
> 3. "非核心功能(搜索/推荐)RTO<30分钟——可接受降级"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 04-基础设施与部署.md | §12 | 数据备份与恢复完整方案 |
| 📄 03-分布式解决方案.md | §11 | 灾备与故障恢复 |
