# Redis 高可用方案

## 1. 当前状态

### 开发环境
- 单节点 Redis（docker-compose）
- 端口：6379
- 无持久化配置

### 生产环境要求
- 高可用（99.99%）
- 数据持久化
- 自动故障转移

---

## 2. 方案选型

### 方案对比

| 方案 | 架构复杂度 | 数据可靠性 | 故障恢复 | 适用场景 |
|------|----------|----------|---------|---------|
| 哨兵模式 | 中 | 高 | 自动（30s-60s） | 中小规模，读写分离 |
| 集群模式 | 高 | 极高 | 自动 | 大规模，数据分片 |
| 单节点 + AOF | 低 | 中 | 手动 | 开发/测试环境 |

### 推荐方案：哨兵模式

**理由：**
1. 当前业务数据量不大，不需要数据分片
2. 哨兵模式满足 99.99% 可用性要求
3. 运维复杂度适中
4. 与现有 Redisson 客户端完全兼容

---

## 3. 哨兵模式架构

```
         +-------------------+
         |   Application     |
         |  (Redisson/Spring)|
         +--------+----------+
                  |
         +--------v----------+
         |   Sentinel 集群    |
         |  S1    S2    S3   |
         +--------+----------+
                  |
         +--------v----------+
         |  Redis 主从集群     |
         |                    |
         |  Master  ──>  Slave1
         |           ──>  Slave2
         +-------------------+
```

### 节点规划

| 角色 | 数量 | 最低配置 | 说明 |
|------|------|---------|------|
| Redis Master | 1 | 2C4G | 读写主节点 |
| Redis Slave | 2 | 2C4G | 只读副本，故障时可提升为 Master |
| Sentinel | 3 | 1C2G | 监控和自动故障转移，需要奇数个节点 |

---

## 4. Docker Compose 配置（哨兵模式）

### docker-compose-redis-sentinel.yml

```yaml
version: '3.8'

services:
  # ==================== Redis Master ====================
  redis-master:
    image: redis:7.2-alpine
    container_name: redis-master
    ports:
      - "6379:6379"
    command: >
      redis-server
      --port 6379
      --requirepass Xhs@2026#Redis
      --masterauth Xhs@2026#Redis
      --appendonly yes
      --appendfsync everysec
      --maxmemory 512mb
      --maxmemory-policy allkeys-lru
      --save 900 1
      --save 300 10
      --save 60 10000
    volumes:
      - redis-master-data:/data
    networks:
      - redis-net

  # ==================== Redis Slave 1 ====================
  redis-slave-1:
    image: redis:7.2-alpine
    container_name: redis-slave-1
    ports:
      - "6380:6379"
    command: >
      redis-server
      --port 6379
      --replicaof redis-master 6379
      --masterauth Xhs@2026#Redis
      --requirepass Xhs@2026#Redis
      --appendonly yes
      --appendfsync everysec
      --maxmemory 512mb
      --maxmemory-policy allkeys-lru
    volumes:
      - redis-slave-1-data:/data
    networks:
      - redis-net
    depends_on:
      - redis-master

  # ==================== Redis Slave 2 ====================
  redis-slave-2:
    image: redis:7.2-alpine
    container_name: redis-slave-2
    ports:
      - "6381:6379"
    command: >
      redis-server
      --port 6379
      --replicaof redis-master 6379
      --masterauth Xhs@2026#Redis
      --requirepass Xhs@2026#Redis
      --appendonly yes
      --appendfsync everysec
      --maxmemory 512mb
      --maxmemory-policy allkeys-lru
    volumes:
      - redis-slave-2-data:/data
    networks:
      - redis-net
    depends_on:
      - redis-master

  # ==================== Sentinel 1 ====================
  sentinel-1:
    image: redis:7.2-alpine
    container_name: sentinel-1
    ports:
      - "26379:26379"
    command: >
      redis-sentinel /etc/redis/sentinel.conf
    volumes:
      - ./config/redis/sentinel-1.conf:/etc/redis/sentinel.conf
      - sentinel-1-data:/data
    networks:
      - redis-net
    depends_on:
      - redis-master
      - redis-slave-1
      - redis-slave-2

  # ==================== Sentinel 2 ====================
  sentinel-2:
    image: redis:7.2-alpine
    container_name: sentinel-2
    ports:
      - "26380:26379"
    command: >
      redis-sentinel /etc/redis/sentinel.conf
    volumes:
      - ./config/redis/sentinel-2.conf:/etc/redis/sentinel.conf
      - sentinel-2-data:/data
    networks:
      - redis-net
    depends_on:
      - redis-master
      - redis-slave-1
      - redis-slave-2

  # ==================== Sentinel 3 ====================
  sentinel-3:
    image: redis:7.2-alpine
    container_name: sentinel-3
    ports:
      - "26381:26379"
    command: >
      redis-sentinel /etc/redis/sentinel.conf
    volumes:
      - ./config/redis/sentinel-3.conf:/etc/redis/sentinel.conf
      - sentinel-3-data:/data
    networks:
      - redis-net
    depends_on:
      - redis-master
      - redis-slave-1
      - redis-slave-2

volumes:
  redis-master-data:
  redis-slave-1-data:
  redis-slave-2-data:
  sentinel-1-data:
  sentinel-2-data:
  sentinel-3-data:

networks:
  redis-net:
    driver: bridge
```

---

## 5. Sentinel 配置文件

### config/redis/sentinel-1.conf

```conf
port 26379
sentinel monitor mymaster redis-master 6379 2
sentinel auth-pass mymaster Xhs@2026#Redis
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 10000
sentinel parallel-syncs mymaster 1
```

其他两个 sentinel 配置文件（sentinel-2.conf, sentinel-3.conf）只需修改 `port` 分别为 26380、26381。

---

## 6. 应用端配置变更

### Spring Boot 配置（application.yml）

将以下配置：
```yaml
spring:
  redis:
    host: redis
    port: 6379
    password: Xhs@2026#Redis
```

改为 Sentinel 模式：
```yaml
spring:
  redis:
    sentinel:
      master: mymaster
      nodes:
        - sentinel-1:26379
        - sentinel-2:26379
        - sentinel-3:26379
    password: Xhs@2026#Redis
```

### Redisson 配置（RedissonConfig.java）

```java
Config config = new Config();
config.useSentinelServers()
    .setMasterName("mymaster")
    .addSentinelAddress(
        "redis://sentinel-1:26379",
        "redis://sentinel-2:26379",
        "redis://sentinel-3:26379"
    )
    .setPassword("Xhs@2026#Redis");
```

---

## 7. 集群模式（扩展方案）

当数据量超过单机内存限制时，升级为 Redis Cluster：

### 特点
- 数据自动分片（16384 个 slot）
- 去中心化架构
- 原生高可用（每个分片有副本）

### 最小集群
- 3 Master + 3 Slave = 6 节点

### Redisson 集群配置
```java
Config config = new Config();
config.useClusterServers()
    .addNodeAddress(
        "redis://node1:6379",
        "redis://node2:6379",
        "redis://node3:6379",
        "redis://node4:6379",
        "redis://node5:6379",
        "redis://node6:6379"
    )
    .setPassword("Xhs@2026#Redis");
```

### 注意事项
1. 分布式锁 Key 需要添加 hash tag（已实现 `{lock}:xxx` 格式）
2. Lua 脚本必须保证所有 Key 在同一 slot（使用 hash tag）
3. 批量操作（pipeline/multi）的 Key 需要在同一节点

---

## 8. 持久化策略

| 策略 | 说明 | 优点 | 缺点 |
|------|------|------|------|
| RDB | 定时快照 | 恢复快、文件小 | 可能丢失最近数据 |
| AOF | 追加日志 | 数据更安全 | 文件大、恢复慢 |
| 混合 | RDB + AOF | 兼顾性能和安全 | 配置稍复杂 |

### 推荐配置（生产环境）
```conf
# RDB
save 900 1
save 300 10
save 60 10000

# AOF
appendonly yes
appendfsync everysec

# 混合持久化
aof-use-rdb-preamble yes
```

---

## 9. 运维手册

### 日常巡检
```bash
# 查看主从状态
redis-cli -h redis-master -a Xhs@2026#Redis info replication

# 查看 Sentinel 状态
redis-cli -h sentinel-1 -p 26379 sentinel masters
redis-cli -h sentinel-1 -p 26379 sentinel slaves mymaster

# 手动故障转移
redis-cli -h sentinel-1 -p 26379 sentinel failover mymaster
```

### 扩容 Slave
1. 启动新 Slave 节点，配置 `replicaof`
2. Sentinel 自动发现新节点

### 故障处理
| 故障 | 影响 | 处理方式 |
|------|------|---------|
| Slave 宕机 | 读负载升高 | 重启或新增 Slave |
| Master 宕机 | 短暂不可写 | Sentinel 自动切换（30s内） |
| Sentinel 宕机 1 个 | 无影响 | 重启即可 |
| Sentinel 宕机 2 个 | 无法自动故障转移 | 紧急恢复至少 1 个 |

---

## 10. 与项目现状的兼容性

### 已就绪
- ✅ Redisson RLock 分布式锁（已实现 Watchdog 机制）
- ✅ Redis Cluster hash tag 格式 `{lock}:xxx`（已实现）
- ✅ Lua 脚本 Key 在 hash tag 内（已实现）
- ✅ 延迟双删缓存策略（CacheHelper）

### 需要变更
- ⚠️ Redis 配置从单节点改为 Sentinel/Cluster
- ⚠️ 部署架构增加 Sentinel 节点
