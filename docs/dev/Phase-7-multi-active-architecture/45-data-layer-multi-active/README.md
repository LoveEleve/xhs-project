# 数据层多活

> 跨服务专题 | 开发阶段：Phase-7 | 核心挑战：跨机房数据一致性

## 专题概要

本专题包含四个核心设计：MySQL 跨机房主主双向同步（基于时间戳解决冲突）、Redis Cluster 跨机房部署（Sentinel 故障转移）、Canal + MQ 双向数据同步（Binlog 监听→MQ→对端 MySQL + 缓存更新）、动态数据源路由（基于 RegionContext 路由到对应机房数据源）。

## 涉及服务

- my-xhs-product（数据层多活代表：MySQL 跨机房同步、Redis 缓存跨机房、Canal 同步）
- my-xhs-order（分库分表多活代表：动态数据源按机房路由分库）
- my-xhs-user（核心数据多活代表：MySQL 跨机房同步、缓存一致性）
- my-xhs-content（搜索数据多活代表：MySQL→Canal→ES 跨机房同步）
- my-xhs-common（组件提供方：DynamicRegionDataSource、RegionRedisTemplate 等）

## 核心内容

| 维度 | 内容 |
|------|------|
| MySQL 双向同步 | 主主双向复制，auto-increment 步长=2，server-id 过滤循环复制 |
| 冲突解决 | INSERT：自增 ID 错位；UPDATE：Last-Write-Wins（updated_at 毫秒精度）；DELETE：删除优先 |
| Redis 跨机房 | Master A → Slave B 主从复制，6 个 Sentinel 节点跨机房投票 |
| Canal 双向同步 | Canal A/B 各监听本机房 Binlog → MQ → 对端消费写入 MySQL + 更新缓存/ES |
| Canal 循环过滤 | MQ 消息 Header 标记 source-region，消费者判断来源=本机房则跳过 |
| 动态数据源 | DynamicRegionDataSource → ShardingDataSource → 实际 MySQL（嵌套路由） |

## 重点覆盖

- MySQL 主主双向复制的循环复制问题与 server-id 过滤方案
- Canal 双向同步的循环消费问题与 source-region 标记过滤方案
- Redis Sentinel 跨机房部署与故障转移
- DynamicRegionDataSource 与 ShardingSphere 的嵌套路由兼容
- 所有表增加 updated_at DATETIME(3) 毫秒精度字段
- Canal 同步位点记录表与冲突记录表设计

## 面试高频问题

- MySQL 主主双向复制如何解决循环复制问题？
- Canal 双向同步如何避免循环？与 MySQL 循环复制问题有何不同？
- 动态数据源在分库分表场景下如何工作？
- Redis 跨机房主从切换时数据会不会丢？
- 数据同步冲突怎么解决？

## 补充一：MySQL 双向复制故障恢复 SOP

> 双向复制链路中断是生产环境高频运维场景，缺少标准恢复步骤会导致恢复时间不可控。

### 场景1：单向复制中断（A→B 正常，B→A 中断）

```
1. 检查 B 的复制状态
   mysql-B> SHOW SLAVE STATUS\G

2. 定位错误
   关注字段：Last_Error / Relay_Log_File / Exec_Master_Log_Pos / Last_SQL_Error

3. 常见原因及处理

   原因a：主键冲突（Duplicate entry）
     → STOP SLAVE;
     → SET GLOBAL SQL_SLAVE_SKIP_COUNTER=1;  -- 跳过一个事务
     → START SLAVE;
     → 验证：SHOW SLAVE STATUS\G 检查 Seconds_Behind_Master

   原因b：GTID 模式下的冲突事务
     → STOP SLAVE;
     → SET GTID_NEXT='uuid:transaction_id';  -- 指定冲突的 GTID
     → BEGIN; COMMIT;                         -- 提交空事务跳过
     → SET GTID_NEXT='AUTOMATIC';
     → START SLAVE;

   原因c：网络抖动导致连接断开
     → 自动重连（relay_log_recovery=ON 时自动恢复）
     → 如果自动重连失败：STOP SLAVE; START SLAVE;

   原因d：Binlog 被 purge 导致无法同步
     → 从 A 重新做基于 GTID 的复制：
       STOP SLAVE;
       CHANGE MASTER TO MASTER_HOST='mysql-a',
         MASTER_PORT=3306,
         MASTER_USER='repl',
         MASTER_PASSWORD='<repl_password>',
         MASTER_AUTO_POSITION=1;  -- GTID 自动定位
       START SLAVE;
```

### 场景2：双向复制全部中断

```
1. 两端 STOP SLAVE，避免数据继续分叉
   mysql-A> STOP SLAVE;
   mysql-B> STOP SLAVE;

2. 比较两端 GTID 执行情况
   mysql-A> SELECT @@GLOBAL.GTID_EXECUTED;
   mysql-B> SELECT @@GLOBAL.GTID_EXECUTED;

3. 确定哪端数据更全（GTID 集合更大的一端为准）
   - 如果 A 的 GTID 集合包含 B 的所有事务 → 以 A 为准
   - 如果两端的 GTID 有互不包含的事务 → 需要人工比对和合并

4. 数据恢复
   方式a（数据量小）：mysqldump 从数据更全的一端导出，导入另一端
   方式b（数据量大）：xtrabackup 增量备份恢复

5. 重新建立双向复制关系
   mysql-B> CHANGE MASTER TO MASTER_HOST='mysql-a', MASTER_AUTO_POSITION=1;
   mysql-B> START SLAVE;
   mysql-A> CHANGE MASTER TO MASTER_HOST='mysql-b', MASTER_AUTO_POSITION=1;
   mysql-A> START SLAVE;

6. 验证双向复制正常
   两端执行 SHOW SLAVE STATUS\G，确认 Seconds_Behind_Master=0
```

### 场景3：Master 宕机切换（MHA 场景）

```
1. MHA 自动检测并执行主从切换（通常30秒内）
2. 切换后，需重建反方向复制链路
   - 新 Master（原 Slave）已提升
   - 旧 Master（恢复后）作为新 Slave 重新接入
3. 验证双向复制正常
4. 更新 DynamicRegionDataSource 中的数据源配置（如 IP 变更）
```

### 预防措施

| 措施 | 配置 | 说明 |
|------|------|------|
| 自动重连 | `relay_log_recovery=ON` | Slave 崩溃后自动恢复 |
| Binlog 保留 | `expire_logs_days=7` | 保留7天 Binlog，避免被 purge |
| 半同步复制 | `rpl_semi_sync_master_enabled=ON` | 至少一个 Slave 确认后才返回 |
| 复制监控 | Prometheus + `SHOW SLAVE STATUS` | 延迟 > 5s 告警 |

---

## 补充二：Canal 同步消费者幂等性保障

> Canal 消费者写入本端时，如果 MQ 重复投递（网络超时重试 / Consumer rebalance），可能导致重复写入。必须保证消费端幂等。

### 问题场景

```
Canal A 监听 Binlog → RocketMQ → CanalSyncConsumer B 写入 MySQL B
                                                  ↓
                                          MQ 投递超时重试
                                                  ↓
                                    Consumer B 收到重复消息 → 重复写入！
```

### 幂等方案

#### 方案1：SQL 幂等（推荐，最简单）

```java
/**
 * Canal 同步消费者 - 基于 SQL 幂等
 * INSERT → REPLACE INTO（主键存在则替换）
 * UPDATE → UPDATE WHERE updated_at <= ?（时间戳比较，旧值不覆盖新值）
 * DELETE → DELETE WHERE updated_at <= ?（幂等删除）
 */
@Component
public class IdempotentCanalSyncConsumer {

    public void onMessage(CanalMessage message) {
        for (CanalRowData row : message.getRows()) {
            switch (row.getEventType()) {
                case INSERT:
                    // REPLACE INTO：主键存在则替换，不存在则插入
                    executeSql("REPLACE INTO " + row.getTable() + " SET " + buildSetClause(row));
                    break;
                case UPDATE:
                    // 带时间戳条件：只有当消息的时间戳 >= 当前记录的时间戳才更新
                    executeSql("UPDATE " + row.getTable() + " SET " + buildSetClause(row)
                        + " WHERE id=? AND updated_at <= ?", row.getId(), row.getUpdatedAt());
                    break;
                case DELETE:
                    // 幂等删除：记录已不存在时不报错
                    executeSql("DELETE FROM " + row.getTable() + " WHERE id=?", row.getId());
                    break;
            }
        }
    }
}
```

#### 方案2：唯一键去重表

```java
/**
 * Canal 同步消费者 - 基于唯一键去重表
 * 每条 Canal 消息有唯一的 binlog filename + position
 * 消费前先检查去重表，已消费则跳过
 */
@Component
public class DedupCanalSyncConsumer {

    public void onMessage(CanalMessage message) {
        String dedupKey = message.getBinlogFilename() + ":" + message.getBinlogPosition();

        // 1. 检查去重表
        if (dedupMapper.existsById(dedupKey)) {
            log.info("重复消息，跳过: {}", dedupKey);
            return;
        }

        // 2. 执行同步逻辑（本地事务）
        transactionTemplate.execute(status -> {
            // 写入业务数据
            syncToDatabase(message);
            // 写入去重表
            dedupMapper.insert(new DedupRecord(dedupKey, Instant.now()));
            return null;
        });
    }
}

-- 去重表 DDL
CREATE TABLE canal_sync_dedup (
    dedup_key VARCHAR(128) PRIMARY KEY COMMENT 'binlog文件名:position',
    consumed_at DATETIME(3) NOT NULL COMMENT '消费时间',
    source_region VARCHAR(16) NOT NULL COMMENT '来源机房'
) ENGINE=InnoDB;
```

#### 方案选型建议

| 方案 | 优点 | 缺点 | 适用场景 |
|------|------|------|---------|
| SQL 幂等（REPLACE INTO） | 无额外表，实现简单 | REPLACE INTO 会先 DELETE 再 INSERT，触发器/Binlog 可能级联 | 大多数场景 ✅ |
| 唯一键去重表 | 精确去重，无副作用 | 额外表，需定期清理 | 数据精确性要求极高的场景 |

### ⚠️ REPLACE INTO 在双向同步场景的额外风险

> 上述缺点提到了"触发器/Binlog 级联"，但在多活双向同步场景下，还有一个更严重的问题：

**风险**：REPLACE INTO 会产生额外的 Binlog 事件，可能导致时间戳被覆盖。

```
场景：
1. 机房A 更新记录 id=1, updated_at=T1
2. Canal A 同步到机房B，使用 REPLACE INTO 写入（此时 updated_at=T1）
3. 机房B 也更新了 id=1, updated_at=T2（T2 > T1）
4. Canal B 同步到机房A，使用 UPDATE WHERE updated_at <= T2（正确，T2 > T1 更新成功）
5. 但如果步骤2的 REPLACE INTO 在机房B 产生了新的 Binlog（DELETE + INSERT）
6. Canal B 可能再次监听到这个 Binlog（虽然是本机房的，source-region 过滤了）
7. 如果 MQ 重复投递了步骤2的消息 → 再次 REPLACE INTO → updated_at 被覆盖回 T1 → 数据回退！
```

**解决措施**：
1. Canal 消费者写入时，INSERT 改用 `INSERT ON DUPLICATE KEY UPDATE`（不会先 DELETE，不产生额外 Binlog）
2. UPDATE 语句带 `updated_at <= ?` 条件（Last-Write-Wins，旧时间戳不覆盖新时间戳）
3. 去重表方案（方案2）可从根本上避免重复消费，适合数据精确性要求高的场景

---

## 补充三：auto-increment 与分库分表场景的冲突说明

> 订单服务（my-xhs-order）和优惠券服务使用了 ShardingSphere 分库分表，ShardingSphere 自带分布式 ID 生成策略（雪花算法）。此时 MySQL 的 `auto-increment-increment=2` 不应再使用。

### 冲突原因

```
ShardingSphere 雪花算法 ID：64位 = 1位符号 + 41位时间戳 + 10位机器ID + 12位序列号
MySQL auto-increment：       简单自增（1, 3, 5, ... 或 2, 4, 6, ...）

如果同时启用：
1. ShardingSphere 生成的 ID（雪花算法）和 MySQL 自增 ID 生成逻辑不同
2. 分库分表的表使用雪花算法 ID，auto-increment 配置无效（因为 ID 由 ShardingSphere 生成）
3. 非分库分表的表仍然依赖 auto-increment，需要 auto-increment-increment=2
```

### 正确配置

```ini
[mysqld]
# MySQL 全局配置：自增步长=2，用于非分库分表的表
auto-increment-increment=2
auto-increment-offset=1    # 机房A=1，机房B=2

# 分库分表的表：在 ShardingSphere 中配置雪花算法，覆盖 MySQL 自增
# ShardingSphere 的 ID 生成优先级高于 MySQL auto-increment
```

```yaml
# ShardingSphere 分片配置 - 订单表使用雪花算法
spring:
  shardingsphere:
    rules:
      sharding:
        tables:
          t_order:
            actual-data-nodes: ds0.t_order_${0..15}
            table-strategy:
              standard:
                sharding-column: user_id
                sharding-algorithm-name: order-table-mod
            key-generate-strategy:
              column: id
              key-generator-name: snowflake  # 雪花算法，不依赖 MySQL auto-increment
        key-generators:
          snowflake:
            type: SNOWFLAKE
            props:
              worker-id: 1  # 机房A=1，机房B=2（与 auto-increment-offset 一致）
```

### 表级规则总结

| 表类型 | ID 生成策略 | 冲突避免机制 |
|--------|-----------|------------|
| 非分库分表（如 `sys_config`） | MySQL `auto-increment-increment=2` + `offset=1/2` | 步长错位，A 生成奇数 B 生成偶数 |
| 分库分表（如 `t_order`） | ShardingSphere 雪花算法 `worker-id=1/2` | worker-id 不同，雪花 ID 不冲突 |
| 分库分表 + 非分库分表混合 | 各自使用上述策略 | 两套 ID 生成体系互不干扰 |

---

## 补充四：ES 跨机房同步说明

> ES 跨机房同步使用 Cross-Cluster Replication（CCR），本专题数据层主要聚焦 MySQL / Redis / Canal 同步，ES CCR 的详细配置和实现请参考 [46-动态组件与流量调度](../46-dynamic-component-and-traffic-scheduling/README.md) 中的"补充一：ES 跨机房同步（CCR）详细设计"。

简要说明：每个机房的 Canal 写入本机房 ES Leader 索引，CCR 自动将 Leader 索引同步到对端机房的 Follower 索引（只读），搜索请求可读本机房 Follower 索引。CCR 延迟通常 < 1s。

---

**相关专题**：
- ⬅️ [44-网关与负载均衡多活](../44-gateway-and-loadbalancer-multi-active/README.md)
- ➡️ [46-动态组件与流量调度](../46-dynamic-component-and-traffic-scheduling/README.md)
- 🔗 [42-多活架构概述与选型](../42-multi-active-overview-and-selection/README.md)

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容