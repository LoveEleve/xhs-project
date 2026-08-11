# my-xhs 故障场景与恢复

> 8 种中间件故障 + 3 种服务级故障 | 检测 → 影响 → 恢复 SOP

---

## 一、故障影响矩阵

| 故障 | 影响范围 | 严重度 | 自动恢复 | RTO |
|------|------|:--:|:--:|:--:|
| MySQL 主库 DOWN | 全部写操作 + 部分读操作 | 🔴 严重 | ❌ 手动切换 | 5-10min |
| MySQL 主从断开 | 读操作返回旧数据 | 🟡 中等 | ✅ GTID 自动重连 | <1min |
| Redis 不可用 | 缓存失效/锁失败/布隆穿透 | 🔴 严重 | ❌ 需重启 | 1-2min |
| Redis Sentinel 切换 | 30s 选举窗口 — 读写中断 | 🟡 中等 | ✅ 自动切换 | 30-60s |
| RocketMQ Broker DOWN | MQ 生产失败 + 消费停止 | 🔴 严重 | ❌ 手动重启 | 1-3min |
| Nacos DOWN | 新服务不可注册，已有缓存可用 | 🟡 中等 | ✅ 客户端本地缓存 | — |
| ES DOWN | 搜索不可用 + 索引同步停止 | 🟡 中等 | ❌ 手动重启 | 1-2min |
| Canal 失联 | ES 索引不同步 | 🟢 低 | ❌ 手动重启 | — |
| Gateway 崩溃 | 15 服务全不可达 | 🔴 严重 | ❌ 手动启动 | <1min |
| 服务 OOM/Restart | 单服务不可用 30s | 🟡 中等 | ✅ Nacos 摘除+重启 | 30-60s |

---

## 二、MySQL 故障

### 2.1 MySQL 主库 DOWN (3306)

**检测**:
```bash
mysqladmin ping -h 127.0.0.1 -P 3306 -u root -p
SHOW SLAVE STATUS\G  # 在从库检查 Io_Running/Sql_Running
```

**影响**:
| 服务 | 影响 | 降级策略 |
|------|------|------|
| 全部 13 服务(有MySQL依赖) | 写操作全部失败 | `@Transactional` 内抛异常 |
| user/content/order | 登录/下单/发笔记 503 | 前端友好提示 |
| cart/counter | MQ 消费端写 DB 失败 | RocketMQ 重试(最多3-5次)→DLQ |

**手工恢复**:
```bash
# 1. 检查主库状态
docker logs my-xhs-mysql --tail 50

# 2. 如果磁盘满(can't create/write to file)
df -h /var/lib/docker/volumes/
docker system prune -f

# 3. 重启主库
docker restart my-xhs-mysql
sleep 5

# 4. 验证
mysql -h 127.0.0.1 -P 3306 -u root -p'Xhs@2026#MySQL' -e "SELECT 1"

# 5. 如果主库彻底损坏 → 提升从库为主库
# 注意: 当前单机部署，主从在同一机器，此场景仅讨论容灾设计
```

### 2.2 MySQL 主从断开

**检测**:
```bash
mysql -h 127.0.0.1 -P 3307 -u root -p'Xhs@2026#MySQL' -e "SHOW SLAVE STATUS\G" | grep -E "Running|Seconds_Behind_Master|Retrieved_Gtid_Set|Executed_Gtid_Set"
```

**影响**:
| 场景 | 影响 |
|------|------|
| 从库读不到新数据 | 用户信息/商品浏览返回旧版本 (<1min 延迟) |
| 写后读 (@Transactional) | 不受影响 — 事务强制走主库 |
| 非事务读 | 读到从库旧数据 — 最终一致(接受延迟) |

**自动恢复**: GTID 模式 + `--master-auto-position=1` — 从库重启后自动从断点继续。

**手工干预**:
```bash
# 从库重连主库
mysql -h 127.0.0.1 -P 3307 -u root -p'Xhs@2026#MySQL' -e "
STOP SLAVE;
RESET SLAVE;
CHANGE MASTER TO 
  MASTER_HOST='127.0.0.1',
  MASTER_PORT=3306,
  MASTER_USER='root',
  MASTER_PASSWORD='Xhs@2026#MySQL',
  MASTER_AUTO_POSITION=1;
START SLAVE;
"
```

### 2.3 从库彻底不可用

```
影响: 非事务读 → 路由到主库(额外负载)
恢复: docker restart my-xhs-mysql-slave → GTID自动追赶上
长期: 如果从库需重建 → init-replication.sql → 等待追赶完成
```

---

## 三、Redis 故障

### 3.1 Redis 主不可用 (6379)

**检测**:
```bash
redis-cli -h 127.0.0.1 -p 6379 -a 'Xhs@2026#Redis' ping
redis-cli -h 127.0.0.1 -p 26379 -a 'Xhs@2026#Redis' sentinel masters
```

**各服务降级策略差异**:

| 服务 | 依赖 Redis 做什么 | Redis DOWN 时行为 |
|------|------|------|
| **user** | Token缓存/验证码/延迟双删 | 验证码不可用(立即503)，登录需直查DB |
| **product** | 布隆+逻辑过期缓存 | 布隆降级放行→直查MySQL |
| **cart** | Redis权威存储 | **完全不可用** — 购物车核心存储是Redis |
| **inventory** | TCC预扣库存 | **完全不可用** — 库存预扣依赖Redis Lua |
| **counter** | 实时计数值 | CounterEventConsumer写失败→MQ重试→DLQ |
| **coupon** | 券库存/用户券缓存 | 领券直查MySQL(乐观锁库存) — 降级可工作 |
| **analytics** | 点赞/收藏事件版本号 | 社交事件版本号丢失 → MQ重试 |
| **notification** | SSE channel/PubSub | SSE推送中断，通知只写DB |
| **im** | WebSocket消息存储 | WS连接正常但消息无法持久化 |
| **search** | 热搜/反作弊 | 搜索正常(走ES)但热搜不可用 |
| **home** | Feed inbox/outbox | Feed查询降级直查DB(压力大) |

**手工恢复**:
```bash
# 1. Redis 主挂 → Sentinel 自动切换到从 6380
# 2. 原主恢复后自动成为新主的从
docker restart my-xhs-redis
sleep 5
redis-cli -h 127.0.0.1 -p 6379 -a 'Xhs@2026#Redis' ROLE  # 应返回 slave
```

### 3.2 Sentinel 选举窗口 (30-60s)

```
时间线:
T+0s:  Master DOWN
T+5s:  Sentinel down-after-milliseconds 到期 → 标记 sdown
T+30s: quorum=1 判定 → odown → 选举新Master
T+30s: 通知客户端新Master地址
T+30s: 原Master重启 → 自动成为新Master的从
```

**客户端影响**:
- Sentinel 模式下客户端自动跟随切换 — Lettuce `RedisSentinelConnectionFactory` (Spring Boot 3 默认 Lettuce, 无 Jedis 依赖)
- 切换窗口内: Redis 操作抛连接异常 → 应用层需要重试机制
- **当前缺陷**: 大部分服务无 Redis 操作重试，切换窗口内操作直接失败

### 3.3 内存耗尽 (OOM)

```
Redis maxmemory 256mb + allkeys-lru
  → 内存不足 → LRU淘汰 → 冷数据丢失，热数据保留
  → 极端情况(全部热数据>256mb) → 写拒绝(OOM command not allowed)
  → 影响: 验证码/锁/缓存全部不可写 → 降级直查DB
```

---

## 四、RocketMQ 故障

### 4.1 Broker DOWN (11911)

**检测**:
```bash
docker logs my-xhs-mq-broker --tail 50
curl -s http://127.0.0.1:18081  # Dashboard
```

**影响**:
| 生产者 | 影响 | 兜底 |
|--------|------|------|
| CartService (asyncSend) | 购物车变更不同步DB | Redis为准，MQ恢复后CartSync无自动补发机制 |
| OrderService (syncSend/sendInTransaction) | 下单/关单/补偿全停 | **下单不可用** — 事务消息失败 |
| CouponService (syncSend+Outbox) | COUPON_CLAIM_TOPIC发不出 | Outbox表兜底 — CouponOutboxSenderJob 5s扫描 |
| InventoryService (syncSend+Outbox) | INVENTORY_TOPIC发不出 | Outbox表兜底 — InventoryOutboxSenderJob 5s扫描 |
| NoteService (asyncSend) | Feed推送停止 | FeedMessageRetryJob 30s补发 |
| Canal (Binlog→MQ) | ES索引停止同步 | Binlog积压(MySQL内存) — Canal恢复后追赶 |
| 全部消费者 | 停止消费 | 消息堆积在Broker — 恢复后批量追赶 |

**恢复**:
```bash
docker restart my-xhs-mq-broker
sleep 10
# 验证
curl -s http://127.0.0.1:18081/#/topic  # Topic列表正常
```

**消费积压处理**:
```
恢复后消费者批量追赶 → maxReconsumeTimes 限制内→成功
→ 超限 → DLQ %DLQ%{group}
→ 当前无 DLQ 消费 → 需运维手动从 Dashboard 重投或新建 DLQ 消费者
```

---

## 五、Nacos 故障

**检测**:
```bash
curl -s http://127.0.0.1:18848/nacos/v1/console/health
```

**影响**:
| 场景 | 影响 |
|------|------|
| Nacos DOWN | 新服务无法注册，**已有缓存服务列表正常** |
| 已运行微服务 | 使用本地缓存的服务实例表继续调用 — **不受影响** |
| 新启动微服务 | 无法注册到Nacos → 其他服务调用不到 → 404 |
| 配置变更 | 无法热更新 — 使用本地缓存的配置 |

**恢复**: 重启 Nacos 容器即可，微服务无需重启。

---

## 六、ES 故障

### 6.1 业务 ES DOWN (19200)

**影响**:
| 服务 | 影响 | 降级 |
|------|------|------|
| search | **搜索不可用** | 降级MySQL直查(性能差但可用) |
| Canal消费者 | NoteIndexSyncConsumer/ProductIndexSyncConsumer 抛异常 | MQ重试→失败ID记录Redis Set→IncrementalIndexSyncJob补|

**恢复**:
```bash
docker restart my-xhs-elasticsearch
sleep 30  # ES 启动慢
# 验证
curl -s -u elastic:Xhs@2026#Elastic http://127.0.0.1:19200/_cluster/health
# ES恢复后，Canal消费者恢复正常同步
# 历史失败需触发 IncrementalIndexSyncJob 补同步
```

### 6.2 SkyWalking ES DOWN (19201)

**影响**: SkyWalking 链路数据停止存储 → OAP 继续接收但无法持久化 → 恢复后丢失窗口内数据。不影响微服务运行。

---

## 七、Canal 故障

**检测**:
```bash
docker logs my-xhs-canal --tail 50
curl -s http://127.0.0.1:11111
```

**影响**: Binlog → ES 索引同步停止
- 笔记创建/更新: ES 不反映最新内容
- 商品创建/更新: ES 不反映最新商品
- 库存变更: 缓存失效不触发(Canal→INVENTORY_CACHE_TOPIC)

**恢复**:
```bash
docker restart my-xhs-canal
# Canal 从断点位追 Binlog(meta.dat记录)
# 追赶完成后自动恢复正常
```

**积压处理**: 如果 Binlog 积压过多(>1h)，建议:
```bash
# 清理 Canal meta → 重新全量监听
rm canal-data/h2.mv.db
docker restart my-xhs-canal
# 同时触发 ES 全量索引重建
```

---

## 八、服务级故障

### 8.1 Gateway 崩溃 (19000)

**影响**: **全部外部请求不可达** — 15 服务全部在 Gateway 后面

**恢复**:
```bash
# start-all.sh 会自动检查 PID 文件，已运行的不重复启动
./start-all.sh
# 或单独:
setsid java -javaagent:skywalking-agent.jar ... -jar my-xhs-gateway/target/my-xhs-gateway-1.0-SNAPSHOT.jar &
```

### 8.2 单服务重启

**流程**:
```
1. 服务崩溃/kill
2. Nacos 心跳超时(15s) → 标记不健康(30s) → 摘除
3. 服务重启 → 健康检查通过(60s轮询) → 注册Nacos
4. 其他服务发现实例 → 请求恢复
```

**摘除窗口**: 30s 内其他服务仍可能向已崩溃实例发请求 → Feign 超时 500ms/3000ms → 降级

### 8.3 服务 OOM

**JVM 配置**:
```
BASE: -Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200
HEAVY: -Xms1024m -Xmx1024m (order/inventory/search)
GATEWAY: -Xms256m -Xmx256m
```

**恢复**:
```bash
# 1. 查看 OOM 日志
grep -i "outofmemory\|oom" /data/workspace/my-xhs/logs/{service}.log

# 2. 分析堆转储(如果配置了 -XX:+HeapDumpOnOutOfMemoryError)
jhat /data/workspace/my-xhs/java_pid*.hprof

# 3. 如果是内存泄漏 → 重启服务 → 长期修复泄露点
# 如果是正常流量 → 增加 -Xmx → 重启
```

---

## 九、恢复 SOP 速查

### 中间件重启顺序

```bash
# 1. 存储层 (必须最先)
docker restart my-xhs-mysql my-xhs-mysql-slave  # 等待 GTID 同步
sleep 10
docker restart my-xhs-redis my-xhs-redis-slave my-xhs-redis-sentinel
sleep 5
docker restart my-xhs-elasticsearch my-xhs-es-skywalking
sleep 30  # ES 慢

# 2. 消息/注册层
docker restart my-xhs-mq-namesrv
sleep 5
docker restart my-xhs-mq-broker my-xhs-rocketmq-dashboard
docker restart my-xhs-nacos my-xhs-sentinel-dashboard my-xhs-xxl-job-admin
sleep 10

# 3. 数据管道
docker restart my-xhs-canal  # 依赖 MySQL + RocketMQ

# 4. 可观测层
docker restart my-xhs-skywalking-oap my-xhs-skywalking-ui
docker restart my-xhs-prometheus my-xhs-grafana my-xhs-victoria-metrics
docker restart my-xhs-logstash my-xhs-filebeat my-xhs-kibana

# 5. 微服务 (最后)
./start-all.sh
```

### 快速诊断三件套

```bash
# 1. 容器状态
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep my-xhs

# 2. 中间件端口
for p in 3306 3307 6379 6380 26379 9876 11911 18848 8858 18080 19200 19201 11800 12800 8080 19090 13000 15044 15601; do
  nc -z -w1 127.0.0.1 $p && echo ":$p UP" || echo ":$p DOWN"
done

# 3. 微服务
for p in 19001 19002 19003 19004 19006 19008 19009 19010 19011 19012 19013 19014 19015 19016 19000; do
  curl -sf "http://localhost:$p/actuator/health" 2>/dev/null && echo ":$p UP" || echo ":$p DOWN"
done
```
