# my-xhs 中间件拓扑

> 22 容器 | network_mode: host | 单机部署

---

## 一、拓扑全景图

```
┌─────────────────────────────────────────────────────────────────┐
│                        CLIENT                                   │
│                          │                                       │
│                  :19000 Gateway                                 │
│                          │                                       │
│        ┌─────────────────┼─────────────────┐                    │
│        ▼                 ▼                  ▼                    │
│  ┌──────────┐    ┌──────────────┐    ┌──────────────┐          │
│  │  Nacos   │    │ 业务微服务    │    │  Sentinel    │          │
│  │ :18848   │◄──►│ ×15         │◄──►│ Dash :8858   │          │
│  │(注册配置) │    │(19001-19016) │    │(流控降级)    │          │
│  └────┬─────┘    └──┬──┬───┬───┘    └──────────────┘          │
│       │             │  │   │                                    │
│       ▼             ▼  │   │                                    │
│  ┌──────────┐  ┌──────┐│   │    ┌──────────────────┐          │
│  │  MySQL   │  │Redis ││   │    │   RocketMQ       │          │
│  │ M:3306   │  │M:6379││   └───►│ NS :9876         │          │
│  │ S:3307   │  │S:6380││        │ Broker :11911    │          │
│  │(GTID复制)│  │S:26379││       │ Dash :18081     │          │
│  └──┬───┬───┘ └──────┘│        └────────┬─────────┘          │
│     │   │              │                 │                      │
│     │   ▼              │                 │                      │
│     │ ┌──────┐         │    ┌──────────────────┐              │
│     │ │Canal │         │    │   搜索/推荐       │              │
│     │ │3个   │─────────┼───►│ ES :19200 (IK)   │              │
│     │ │inst  │         │    │ SW-ES :19201     │              │
│     │ └──────┘         │    └───────┬──────────┘              │
│     │                  │            │                           │
│     │                  │    ┌───────▼──────────┐              │
│     │                  │    │   可观测性        │              │
│     │                  │    │ SkyWalking :11800│              │
│     │                  └───►│  :12800 :8080    │              │
│     │                       │ Prometheus:19090 │              │
│     │                       │ VM :8428         │              │
│     │                       │ Grafana :13000   │              │
│     │                       └───────┬──────────┘              │
│     │                               │                           │
│     │                       ┌───────▼──────────┐              │
│     │                       │   日志管道        │              │
│     │                       │ Filebeat /logs   │              │
│     │                       │ Logstash :15044  │              │
│     │                       │         :15045   │              │
│     │                       │ Kibana  :15601   │              │
│     │                       └──────────────────┘              │
│     │                                                           │
│     └─────────── XXL-Job :18080 ──────────┘                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 二、逐容器详述

### 2.1 存储层 (4 容器)

#### MySQL Master — 主库 3306

| 属性 | 值 |
|------|------|
| 镜像 | `mysql:8.0` |
| 容器名 | `my-xhs-mysql` |
| 端口 | **3306** |
| 密码 | `Xhs@2026#MySQL` |
| 字符集 | `utf8mb4` / `utf8mb4_unicode_ci` |
| InnoDB | buffer_pool=1024M, log_file=256M, flush_log=2, sync_binlog=0 |
| Binlog | `ROW`, `ROW_IMAGE=FULL`, `GTID=ON` |
| 慢查询 | threshold=0.5s, 开启 |
| 连接数 | max=500 |
| 资源 | CPU 2核, 内存 1.5G |
| 初始化 | `./sql/init-all.sql` 自动建库 |
| Health | `mysqladmin ping` 30s周期 |

**库清单**:
```
my_xhs_user, my_xhs_content, my_xhs_analytics, my_xhs_product,
my_xhs_cart, my_xhs_coupon, my_xhs_order(分库分表), my_xhs_inventory,
my_xhs_payment, my_xhs_notification, my_xhs_im, my_xhs_counter, my_xhs_search,
nacos_config, xxl_job
```

#### MySQL Slave — 从库 3307

| 属性 | 值 |
|------|------|
| 镜像 | `mysql:8.0` |
| 容器名 | `my-xhs-mysql-slave` |
| 端口 | **3307** |
| 关键参数 | `read-only=1`, server-id=2, buffer_pool=512M |
| 复制 | GTID 主从, `init-replication.sql` 自动 CHANGE MASTER |
| 连接数 | max=300 |
| 资源 | CPU 0.5核, 内存 768M |

**读写分离**: `my-xhs-common` 的 `ReadWriteRoutingDataSource` 动态路由 — 写操作自动走 Master, 读操作默认走 Slave。`@Transactional` 强制主库读。

#### Redis Master — 主 6379 / Slave 6380 / Sentinel 26379

| 容器 | 端口 | 关键参数 |
|------|:--:|------|
| `my-xhs-redis` | **6379** | `maxmemory 256mb`, `allkeys-lru`, `appendonly everysec`, `save 900 1` |
| `my-xhs-redis-slave` | **6380** | `replicaof 127.0.0.1 6379`, 同参数 |
| `my-xhs-redis-sentinel` | **26379** | `sentinel monitor mymaster 21.130.247.89 6379 1` |

| 属性 | 值 |
|------|------|
| 密码 | `Xhs@2026#Redis` (requirepass + masterauth) |
| 镜像 | `redis:7-alpine` |
| Sentinel | quorum=1, down-after=5000ms, failover-timeout=30000ms, `config/redis/sentinel.conf` |
| 客户端连接 | `my-xhs-common` `RedisConfig` 检测 `sentinel.nodes` → 自动切换 Sentinel 模式 |
| 连接池 | Lettuce: max-active=15, max-idle=8, min-idle=4, max-wait=3000ms |
| 双连接池 | business(6379) + cache(6379), 物理同一 Redis, `RedisMultiSourceConfig` 逻辑隔离 |

---

### 2.2 消息层 (3 容器)

#### RocketMQ NameServer

| 属性 | 值 |
|------|------|
| 镜像 | `apache/rocketmq:5.1.4` |
| 容器名 | `my-xhs-mq-namesrv` |
| 端口 | **9876** |
| JVM | `-Xms128m -Xmx256m -Xmn64m`, Kona JDK17 |
| 资源 | CPU 0.25核, 内存 640M |

#### RocketMQ Broker

| 属性 | 值 |
|------|------|
| 容器名 | `my-xhs-mq-broker` |
| 端口 | **11911** |
| 配置 | `config/rocketmq/broker.conf` |
| JVM | `-Xms256m -Xmx512m -Xmn128m` |
| 资源 | CPU 0.5核, 内存 768M |
| DLQ | `my-xhs-common` `DlqMessageHandler` 监控 `%DLQ%{consumerGroup}` 堆积 |
| 依赖 | `rocketmq-namesrv` |

#### RocketMQ Dashboard

| 属性 | 值 |
|------|------|
| 镜像 | `apacherocketmq/rocketmq-dashboard:latest` |
| 端口 | **18081** |
| NamesrvAddr | `127.0.0.1:9876` |

**Topic 全集 (16 个)**:

| Topic | 生产者 | 消费者 | 用途 |
|-------|--------|--------|------|
| `CART_TOPIC` | cart | CartSyncConsumer | 购物车同步 |
| `CACHE_EVICT_TOPIC` | CacheHelper (common) | CacheEvictConsumer (user) | 延迟双删MQ兜底 |
| `PAY_RESULT_TOPIC` | payment | PayResultConsumer | 支付结果通知 |
| `REFUND_RESULT_TOPIC` | payment | RefundResultConsumer | 退款结果通知 |
| `RECOMMEND_BEHAVIOR_TOPIC` | 各服务 | BehaviorReportConsumer(search) | 推荐行为收集 |
| `NOTIFICATION_TOPIC` | 各服务 | NotificationEventConsumer | 通知事件 |
| `NOTE_INDEX_TOPIC` | Canal | NoteIndexSyncConsumer(search) | 笔记索引同步 |
| `PRODUCT_INDEX_TOPIC` | Canal | ProductIndexSyncConsumer(search) | 商品索引同步 |
| `SOCIAL_TOPIC` | analytics + content | Like/Favorite/Follow/Unfollow + NoteDelete + Counter | 社交事件 |
| `ORDER_CLOSE_TOPIC` | order(延迟) | OrderCloseConsumer | 超时关单 |
| `ORDER_COMPENSATION_TOPIC` | order | OrderCompensationConsumer | 订单补偿 |
| `COUPON_CLAIM_TOPIC` | coupon(Outbox) | CouponClaimConsumer | 领券异步落库 |
| `ORDER_TRANSACTION_TOPIC` | order(事务消息) | OrderTransactionConsumer(inventory) | 下单半消息 |
| `INVENTORY_TOPIC` | inventory | InventoryDeductConsumer | 库存扣减 |
| `INVENTORY_CACHE_TOPIC` | Canal | InventoryCacheEvictConsumer | 库存缓存失效 |
| `FEED_TOPIC` | content(本地消息表) | FeedPushConsumer(home) | Feed 推送 |

---

### 2.3 搜索层 (2 容器)

#### Elasticsearch (业务搜索)

| 属性 | 值 |
|------|------|
| 镜像 | `elasticsearch:8.19.19` |
| 端口 | **19200** (http), **19300** (transport) |
| 密码 | `elastic` / `Xhs@2026#Elastic` |
| 插件 | `analysis-ik` (自动安装) |
| 安全 | `xpack.security.enabled=true`, SSL 关闭 |
| JVM | `-Xms256m -Xmx512m` |
| 资源 | CPU 0.5核, 内存 2G |
| 索引 | `note_index`, `product_index`, `suggest_index` |

#### Elasticsearch (SkyWalking 存储)

| 属性 | 值 |
|------|------|
| 镜像 | `elasticsearch:8.12.2` |
| 端口 | **19201** (http), **19301** (transport) |
| 密码 | `elastic` / `Xhs@2026#ElasticSW` |
| JVM | `-Xms256m -Xmx768m` |
| 资源 | CPU 1核, 内存 1.5G |

---

### 2.4 数据管道 (1 容器)

#### Canal

| 属性 | 值 |
|------|------|
| 镜像 | `my-xhs-canal-server:v1.1.7-squashed` |
| 端口 | **11111** (管理) |
| JDK | Kona JDK8 |
| 目标 | MySQL :3306 → RocketMQ :9876 |
| 配置 | `flatMessage=false` |
| 资源 | CPU 0.25核, 内存 640M |

**3 个 Instance**:

| Instance | 监听表 | MQ Topic | slaveId |
|---------|--------|---------|:--:|
| `note_instance` | `my_xhs_content.t_note` | `NOTE_INDEX_TOPIC` | 1001 |
| `product_instance` | `my_xhs_product.t_spu, t_sku` | `PRODUCT_INDEX_TOPIC` | 1002 |
| `inventory_instance` | `my_xhs_inventory.t_inventory` | `INVENTORY_CACHE_TOPIC` | 1003 |

配置文件路径:
```
config/canal/conf/
├── canal.properties
├── note_instance/instance.properties
├── product_instance/instance.properties
└── inventory_instance/instance.properties
```

---

### 2.5 注册/配置/调度层 (3 容器)

#### Nacos

| 属性 | 值 |
|------|------|
| 镜像 | `nacos/nacos-server:v2.3.2` |
| 端口 | **18848** |
| 模式 | `MODE=standalone` |
| 存储 | MySQL `nacos_config` 库 |
| 认证 | `nacos` / `nacos` |
| 资源 | CPU 1核, 内存 1.5G |
| 共享配置 | `my-xhs-common.yaml` (所有服务通过 `shared-configs` 引用) |

#### Sentinel Dashboard

| 属性 | 值 |
|------|------|
| 镜像 | `bladex/sentinel-dashboard:1.8.8` |
| 端口 | **8858** |
| 认证 | `sentinel` / `sentinel` |
| 资源 | CPU 0.25核, 内存 256M |

#### XXL-Job Admin

| 属性 | 值 |
|------|------|
| 镜像 | `xuxueli/xxl-job-admin:2.4.2` |
| 端口 | **18080** |
| 认证 | `admin` / `123456` |
| accessToken | `my-xhs-xxl-job-token-2026` |
| 存储 | MySQL `xxl_job` 库 |
| 资源 | CPU 0.5核, 内存 1G |
| 语言 | `zh_CN` |

**启用 XXL-Job 的 9 个服务**: order(9991), payment(9992), cart(9993), home(9994), coupon(9995), inventory(9996), search(9997), counter(9998), notification(9990)
> analytics 虽配置 executor port 9999 但缺 `xxl.job.enabled: true` → 实际不注册执行器

---

### 2.6 可观测性层 (6 容器)

#### SkyWalking OAP + UI

| 容器 | 端口 | 说明 |
|------|:--:|------|
| `my-xhs-skywalking-oap` | **11800** (gRPC), **12800** (HTTP) | 链路收集+分析 |
| `my-xhs-skywalking-ui` | **8080** | 可视化面板 |

| 属性 | 值 |
|------|------|
| 镜像 | `apache/skywalking-oap-server:9.7.0` + `skywalking-ui:9.7.0` |
| 存储 | ES :19201 |
| JVM | `-Xms512m -Xmx1024m` |
| Agent | `skywalking-agent-9.6.0.jar`, 全服务挂载, `SW_PLACEHOLDER` 动态替换 |
| 资源 | OAP: CPU 1核/1.5G, UI: CPU 0.25核/640M |

#### Prometheus

| 属性 | 值 |
|------|------|
| 镜像 | `prom/prometheus:v2.48.1` |
| 端口 | **19090** |
| 配置 | `config/prometheus/prometheus.yml` + `alert_rules/` |
| 保留 | 15 天 |
| 抓取 | 各服务 `/actuator/prometheus` |

#### VictoriaMetrics

| 属性 | 值 |
|------|------|
| 镜像 | `victoriametrics/victoria-metrics:v1.93.12` |
| 端口 | **8428** |
| 保留 | 30 天 |
| 角色 | Prometheus 兼容 TSDB, 长期存储 |

#### Grafana

| 属性 | 值 |
|------|------|
| 镜像 | `grafana/grafana:10.2.3` |
| 端口 | **13000** |
| 认证 | `admin` / `Xhs@2026#Admin` |
| 数据源 | Prometheus (自动 provisioning) |
| Dashboard | `config/grafana/provisioning/dashboards/json/` |

#### Logstash

| 属性 | 值 |
|------|------|
| 镜像 | `logstash:8.19.19` |
| 端口 | **15044** (TCP, JSON lines, 微服务直连), **15045** (Beats, Filebeat 采集) |
| 输出 | ES :19200, 索引 `myxhs-logs-YYYY.MM.dd` |
| JVM | `-Xms256m -Xmx512m` |
| 资源 | CPU 0.5核, 内存 768M |
| 双通道 | TCP 直连(实时) + Filebeat(文件采集 `/logs/*.json`) |

#### Filebeat

| 属性 | 值 |
|------|------|
| 镜像 | `filebeat:8.19.19` |
| 输出 | Logstash :15045 (ES 直写禁用) |
| 采集 | `/logs/*.json` (只读), 微服务 logback JSON_FILE |
| 资源 | CPU 0.1核, 内存 192M |

#### Kibana

| 属性 | 值 |
|------|------|
| 镜像 | `kibana:8.19.19` |
| 端口 | **15601** |
| 连接 ES | :19200, 用户 `kibana_system` / `Xhs@2026#KibanaSystem` |
| 资源 | CPU 0.25核, 内存 1.5G |

---

## 三、端口分配表

| 端口 | 服务 | 协议 | 用途 |
|:----:|------|------|------|
| 3306 | MySQL Master | TCP | 读写 |
| 3307 | MySQL Slave | TCP | 只读 |
| 6379 | Redis Master | TCP | 缓存/锁/布隆 |
| 6380 | Redis Slave | TCP | 只读副本 |
| 26379 | Redis Sentinel | TCP | 主从监控 |
| 9876 | RocketMQ NameServer | TCP | 路由发现 |
| 11911 | RocketMQ Broker | TCP | 消息存储 |
| 18081 | RocketMQ Dashboard | HTTP | 管理面板 |
| 11111 | Canal | HTTP | 管理接口 |
| 18848 | Nacos | HTTP | 注册/配置 |
| 8858 | Sentinel Dashboard | HTTP | 流控面板 |
| 18080 | XXL-Job Admin | HTTP | 调度中心 |
| 19200 | ES 业务搜索 | HTTP | IK 分词 |
| 19201 | ES SkyWalking | HTTP | 链路存储 |
| 19300 | ES 业务 transport | TCP | 集群通信 |
| 19301 | ES SW transport | TCP | 集群通信 |
| 11800 | SkyWalking OAP gRPC | gRPC | Agent 上报 |
| 12800 | SkyWalking OAP HTTP | HTTP | 查询+健康 |
| 8080 | SkyWalking UI | HTTP | 可视化面板 |
| 19090 | Prometheus | HTTP | 指标采集 |
| 8428 | VictoriaMetrics | HTTP | TSDB |
| 13000 | Grafana | HTTP | 可视化面板 |
| 15044 | Logstash TCP | TCP | 微服务直连日志 |
| 15045 | Logstash Beats | TCP | Filebeat 采集 |
| 15601 | Kibana | HTTP | 日志可视化 |
| 27091 | Seata | TCP | 单独部署(未启用) |

---

## 四、资源规划

| 分组 | 容器 | CPU (核) | 内存 (MB) |
|------|------|:--:|:--:|
| 存储 | MySQL ×2 + Redis ×3 | 3.35 | 3400 |
| 消息 | RocketMQ ×3 | 1.0 | 2048 |
| 搜索 | ES ×2 | 1.5 | 3584 |
| 管道 | Canal | 0.25 | 640 |
| 注册调度 | Nacos + Sentinel + XXL-Job | 1.75 | 2816 |
| 可观测 | SkyWalking ×2 + Prom + VM + Grafana | 2.25 | 4608 |
| 日志 | Logstash + Filebeat + Kibana | 0.85 | 2460 |
| **中间件合计** | **22 容器** | **~11** | **~19GB** |

**微服务**: 3×1G(HEAVY order/inventory/search) + 11×512M + 256M(gateway) = **~8.75GB**

**总计**: 中间件 ~19GB + 微服务 ~8.75GB = **~28GB** (峰值), 实际运行 ~14-16GB

---

## 五、账号密码统一清单

| 服务 | 用户 | 密码 | 来源 |
|------|------|------|------|
| MySQL | `root` | `Xhs@2026#MySQL` | docker-compose MYSQL_ROOT_PASSWORD |
| Redis | (requirepass) | `Xhs@2026#Redis` | docker-compose |
| ES 业务 | `elastic` | `Xhs@2026#Elastic` | ELASTIC_PASSWORD |
| ES SkyWalking | `elastic` | `Xhs@2026#ElasticSW` | ELASTIC_PASSWORD |
| Kibana | `kibana_system` | `Xhs@2026#KibanaSystem` | ELASTICSEARCH_PASSWORD |
| Grafana | `admin` | `Xhs@2026#Admin` | GF_SECURITY_ADMIN_PASSWORD |
| Nacos | `nacos` | `nacos` | 默认 |
| Sentinel | `sentinel` | `sentinel` | 默认 |
| XXL-Job | `admin` | `123456` | 默认 |
| XXL-Job token | — | `my-xhs-xxl-job-token-2026` | PARAMS `--xxl.job.accessToken` |
| JWT secret | — | `MyXhs@2026#JwtSecretKey!ForTokenSign` | user/gateway/im application.yml |
| HMAC secret | — | `myxhs-hmac-secret-key-2024` | Gateway `HmacSignatureFilter` |
| ADMIN_TOKEN | — | `my-xhs-admin-token-2026` | 环境变量注入 analytics 服务 |
| INTERNAL_TOKEN | — | `my-xhs-internal-token-2026` | 12 服务 application.yml |

---

## 六、启动依赖顺序

```
MySQL                     ← 最底层
├── MySQL Slave           ← depends_on mysql
├── Nacos                 ← depends_on mysql (存储 nacos_config 库)
├── XXL-Job               ← depends_on mysql (存储 xxl_job 库)
└── Canal                 ← depends_on mysql + rocketmq-broker

RocketMQ NameServer        ← 无依赖(最底层)
└── RocketMQ Broker       ← depends_on namesrv
    └── RocketMQ Dashboard ← depends_on namesrv

Redis                      ← 无依赖
├── Redis Slave           ← depends_on redis
└── Redis Sentinel        ← depends_on redis + redis-slave

ES 业务                    ← 无依赖
├── Logstash              ← depends_on es
│   └── Filebeat          ← depends_on logstash
└── Kibana                ← depends_on es

ES SkyWalking              ← 无依赖
└── SkyWalking OAP        ← depends_on es-skywalking
    └── SkyWalking UI     ← depends_on oap

Prometheus                 ← 无依赖
├── Grafana               ← depends_on prometheus
└── VictoriaMetrics        ← 无依赖(被动写入)
```

**微服务启动批次** (start-all.sh):

| 批次 | 服务 | 依赖中间件 |
|:--:|------|------|
| 1 | user, content, analytics, counter | MySQL + Redis + Nacos + RocketMQ |
| 2 | product, cart, inventory, coupon | + Sentinel |
| 3 | order, payment | + XXL-Job |
| 4 | notification, im, home, search | + ES + Canal |
| 5 | **gateway** (最后) | 全部微服务就绪 |
