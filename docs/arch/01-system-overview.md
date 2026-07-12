# 01 — 系统全景

> **目标读者**：P7+ 工程师，已理解微服务/分库分表/消息驱动的基本概念。
> **回答三个问题**：为什么这样设计？边界条件怎么处理？代价是什么？

---

## 一、系统定位

**MyXHS** 是一个社交电商平台的技术架构项目，对标小红书的核心业务链路：内容发布 → 社交互动 → 商品交易。项目定位为**架构演示与训练用途**，而非线上生产系统。

| 维度 | 说明 |
|------|------|
| 业务形态 | 笔记发布、评论互动、Feed 推荐、商品购买、即时通讯 |
| 架构目标 | 演示 P7+ 级别的分布式系统设计能力 |
| 技术栈 | Spring Boot 3.2 + Spring Cloud 2023 + Spring Cloud Alibaba 2023 |
| 部署形态 | 单机 Docker Compose（开发）或 K8s（生产规划） |

---

## 二、为什么是 17 个微服务？

### 2.1 服务清单

| 服务名 | 端口 | 类型 | 核心职责 |
|--------|------|------|---------|
| **gateway** | 19000 | 网关 | 路由转发、JWT 鉴权、HMAC 签名、限流、灰度 |
| **user** | 19001 | 业务 | 注册登录、Token 签发、个人信息、BCrypt 密码 |
| **content** | 19002 | 业务 | 笔记发布/审核/状态机、评论、DFA 敏感词过滤 |
| **analytics** | 19003 | 业务 | 点赞/收藏/关注关系数据存储与统计 |
| **counter** | 19004 | 业务 | 点赞数/粉丝数/评论数/收藏数实时计数 |
| **product** | 19006 | 业务 | SPU/SKU 管理、商品详情、多级缓存 |
| **cart** | 19008 | 业务 | 购物车管理、Lua 原子操作 |
| **inventory** | 19009 | 业务 | 库存分桶预扣、三级扣减保障、TCC 接口 |
| **coupon** | 19010 | 业务 | 优惠券模板/领取/核销/退券 |
| **order** | 19011 | 业务 | 订单创建/状态机、事务消息、ShardingSphere 分片 |
| **payment** | 19012 | 业务 | 支付/退款（Mock 实现）、策略模式、对账 |
| **notification** | 19013 | 业务 | SSE 实时推送、消息聚合、未读计数 |
| **im** | 19014 | 业务 | WebSocket 长连接、私信路由、离线消息 |
| **home** | 19015 | BFF | 首页 Feed 聚合、推拉混合模型、并行编排 |
| **search** | 19016 | 业务 | ES 搜索、热搜计算、搜索建议、推荐召回 |
| **common** | — | 公共库 | 30+ AutoConfiguration、AOP、工具类 |
| **test** | — | 工具 | 数据生成、数据库检查 |
| **benchmark** | — | 工具 | JMH 基准测试 |

### 2.2 拆分边界的设计依据

**核心原则**：按业务能力（Business Capability）拆分，而非按数据实体拆分。

| 拆分决策 | 为什么 | 代价 |
|---------|--------|------|
| **counter 独立服务** | 计数场景是典型的热点读写（如大 V 笔记点赞数），独立部署可独立扩缩容、独立缓存策略 | 当前仅 300 行核心代码，存在过度拆分嫌疑（→ 见 36-known-issues） |
| **analytics 独立服务** | 社交关系（关注/粉丝/点赞/收藏）是独立的数据域，使用 Redis Set 存储，独立服务可避免与内容服务耦合 | 与 content 共享 MySQL 13307，慢查询可能互相影响 |
| **payment 独立服务** | 支付是独立业务域，有独立数据库 my_xhs_payment、独立流水号生成 | 与 order 存在双向 Feign 调用，代码量偏少，可考虑合并 |
| **home 独立 BFF** | 首页需要聚合 10+ 个下游服务，BFF 模式屏蔽后端服务拆分细节 | 依赖链路长，任一下游故障都可能影响首页可用性 |
| **search 独立服务** | ES 查询优化、推荐算法是独立技术领域 | 搜索和推荐两个子域耦合在同一服务，建议拆分（→ 见 36） |

### 2.3 为什么不拆成 6 个服务？

如果把 counter 合并到 analytics、payment 合并到 order、search+recommend 拆开，可以得到更精简的服务拓扑。当前保持 17 个服务的理由是：

1. **教学目的**：拆分粒度过粗不利于演示微服务间的通信模式（Feign、MQ、分布式事务）
2. **独立部署能力**：即使 counter 代码量少，但计数场景的 QPS 可能远高于 analytics 的社交关系查询
3. **数据隔离**：payment 有独立数据库，如果合并到 order 会破坏数据隔离

**综合评分**：72/100（微服务拆分评审），建议最终收敛到 14 个服务（→ 见 arch/README.md#微服务拆分评审）。

---

## 三、技术栈全景

### 3.1 框架层

| 组件 | 版本 | 选型理由 |
|------|------|---------|
| **Java** | 17 | LTS 版本，Records/Sealed Classes/Pattern Matching 提升代码质量 |
| **Spring Boot** | 3.2.5 | 最新稳定版，Virtual Threads 支持 |
| **Spring Cloud** | 2023.0.1 | 与 Boot 3.2.x 兼容的最新版本 |
| **Spring Cloud Alibaba** | 2023.0.1.0 | Nacos/Sentinel/Seata 的 Spring Cloud 适配 |

### 3.2 数据层

| 组件 | 版本 | 部署规模 | 用途 |
|------|------|---------|------|
| **MySQL** | 8.0 | 4 实例（主从复制） | 关系型存储 |
| **Redis** | 7.x | 3 实例 + 3 Sentinel | 缓存/计数/分布式锁/PubSub |
| **Elasticsearch** | 8.12.2 | 单节点（开发模式） | 笔记/商品搜索 |
| **ShardingSphere** | 5.5.1 | 嵌入 order 服务 | 分库分表 |
| **Canal** | 1.1.7 | 单节点 | MySQL → MQ/Redis 数据同步 |

### 3.3 中间件层

| 组件 | 版本 | 部署规模 | 用途 |
|------|------|---------|------|
| **RocketMQ** | 5.1.4 | 单 Broker + 单 NameServer | 消息驱动、事务消息 |
| **Nacos** | 2.3.2 | 单节点（standalone） | 注册中心 + 配置中心 |
| **Sentinel** | 1.8.8 | Dashboard + 嵌入 | 限流、熔断、降级 |
| **XXL-Job** | 2.4.2 | Admin + Executor | 分布式调度 |
| **SkyWalking** | 9.7 | OAP + UI + ES | 全链路追踪 |

### 3.4 公共组件（common 模块）

| 组件 | 说明 |
|------|------|
| `ReadWriteRoutingDataSource` | 读写分离路由，@ReadOnly 注解自动路由到从库 |
| `ResponseAutoWrapper` | `Result<T>` 自动包装，Controller 返回 POJO 即可 |
| `ApiVersionHandlerMapping` | 多版本 API 路由，@ApiVersion 注解 |
| `LeastConnectionsLoadBalancer` | 最少连接负载均衡 |
| `@RateLimit` / `@DistributedLock` / `@Idempotent` | 三层 AOP 防护 |
| `TraceIdConfig` / `FeignTraceInterceptor` / `MqTraceHelper` | 全链路流量染色 |
| `CacheHelper` | Cache Aside + Caffeine + Redis 多级缓存 |
| `SqlGuardInterceptor` | MyBatis 慢 SQL 检测 + 熔断 |
| `ZoneContext` / `ZonePreferenceFilter` | Zone 多活路由 |

---

## 四、部署拓扑

### 4.1 Docker Compose 开发模式（单机）

```
┌─────────────────────────────────────────────────────┐
│                     Docker Host (8C/16G)             │
│                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ MySQL    │  │ MySQL    │  │ MySQL    │          │
│  │ user     │  │ content  │  │ order    │  ...     │
│  │ :13306   │  │ :13307   │  │ :13308   │          │
│  └──────────┘  └──────────┘  └──────────┘          │
│                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ Redis    │  │ Redis    │  │ Redis    │          │
│  │ Default  │  │ Cache    │  │ Business │          │
│  │ :16379   │  │ :16380   │  │ :16381   │          │
│  └──────────┘  └──────────┘  └──────────┘          │
│                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ RocketMQ │  │ Nacos    │  │ ES       │          │
│  │ :9876    │  │ :18848   │  │ :19200   │          │
│  └──────────┘  └──────────┘  └──────────┘          │
│                                                     │
│  15 个 Java 微服务（19000-19016）                    │
│  Sentinel :8858  XXL-Job :18080  Canal :11111      │
│                                                     │
│  总内存消耗：~6.5G（适配 8C/16G 单机）               │
└─────────────────────────────────────────────────────┘
```

**说明**：当前 docker-compose.yml 适配的是 8C/16G 单机开发环境。原始的完整版中间件集群（4 主 4 从 MySQL、3 Sentinel + 3 Redis、RocketMQ 双 Broker、Logstash、Kibana、SkyWalking OAP+UI、Prometheus、Grafana）需要 ~28-30G 内存，无法在 16G 机器上运行。因此做了以下适配：
- MySQL：4 实例仅保留主库（移除了 4 个从库）
- Redis：3 实例独立部署（移除了 3 个 Sentinel）
- RocketMQ：单 Broker + 单 NameServer（移除了 Slave Broker）
- 移除 Logstash、Kibana、SkyWalking、Prometheus、Grafana 等监控链路组件
- 所有中间件添加 `deploy.resources.limits`，MySQL buffer-pool 从 512M 降到 128M

### 4.2 MySQL 实例分布

| 实例 | 端口 | 主/从 | 承载数据库 |
|------|------|--------|-----------|
| mysql-user | 13306 | 主 | my_xhs_user, my_xhs_analytics, my_xhs_notification, my_xhs_im |
| mysql-user-slave | 13310 | 从 | 上述数据库的只读副本 |
| mysql-content | 13307 | 主 | my_xhs_content, my_xhs_counter, my_xhs_product, my_xhs_cart, my_xhs_coupon, my_xhs_search |
| mysql-content-slave | 13311 | 从 | 上述数据库的只读副本 |
| mysql-order | 13308 | 主 | my_xhs_order, my_xhs_payment |
| mysql-order-slave | 13312 | 从 | 上述数据库的只读副本 |
| mysql-inventory | 13309 | 主 | my_xhs_inventory |
| mysql-inventory-slave | 13313 | 从 | my_xhs_inventory 的只读副本 |

**关键设计**：inventory 独占实例，因为库存扣减是热点操作，独立实例可隔离 CPU/IO 影响。user/analytics/notification/im 共享 13306，因为它们都属于用户域，且都是读多写少。

### 4.3 Redis 实例分布

| 实例 | 端口 | 淘汰策略 | 用途 |
|------|------|---------|------|
| redis (default) | 16379 | allkeys-lru | 通用缓存、Session、Token |
| redis-cache | 16380 | allkeys-lru | Caffeine 二级缓存的后端 Redis |
| redis-business | 16381 | noeviction | 库存计数、分布式锁、布隆过滤器 |

**为什么分 3 个实例？**
- **隔离故障域**：Cache Redis 故障不影响 Business Redis 的库存扣减
- **不同淘汰策略**：Business Redis 的数据（库存、锁）不能淘汰，必须 `noeviction`；Cache 可以 LRU 淘汰
- **避免热点 Key 竞争**：库存扣减的 Lua 脚本与缓存查询的 IO 分离

---

## 五、ShardingSphere 分库分表（order 服务）

### 5.1 分片策略

```
分片键：user_id

库路由：user_id % 4 → 库索引 (0, 1, 2, 3)
表路由：(user_id / 4) % 4 → 表索引 (0, 1, 2, 3)

结果：4 库 × 4 表 = 16 张物理分表
```

| user_id | 库 | 表 | 物理表名 |
|---------|----|----|---------|
| 1 | ds0 | 0 | t_order_0@my_xhs_order_0 |
| 4 | ds0 | 1 | t_order_1@my_xhs_order_0 |
| 8 | ds1 | 0 | t_order_0@my_xhs_order_1 |
| 15 | ds3 | 3 | t_order_3@my_xhs_order_3 |

### 5.2 分片表

| 逻辑表 | 说明 |
|--------|------|
| `t_order` | 订单主表 |
| `t_order_item` | 订单明细 |
| `t_local_message` | 本地消息表 |
| `t_order_snapshot` | 订单快照 |

**绑定表组**：t_order、t_order_item、t_local_message、t_order_snapshot 绑定在一起，防止关联查询产生笛卡尔积。

### 5.3 非分片键查询：订单号映射表

```
t_order_no_mapping (公共表，不分片)
  order_no  →  user_id  →  order_id
     ↑             ↑           ↑
  业务订单号    分片键    分片内部主键
```

→ 详细设计见 `30-data-sharding.md`

### 5.4 为什么选 user_id 而非 order_id 作为分片键？

| 方案 | 优点 | 缺点 |
|------|------|------|
| user_id 分片 | 用户维度的查询（我的订单）直接路由到单库单表 | 按订单号查询需要映射表 |
| order_id 分片 | 按订单号查询直接路由 | 用户维度查询需要全表扫描 |

**选择 user_id**：电商系统中，"我的订单"是最高频查询，按用户 ID 分片将高频查询限制在单分片内。订单号映射表引入的额外一次查询是可接受的代价。

---

## 六、Zone 多活架构

### 6.1 为什么需要多活？

| 单 Zone 风险 | 多 Zone 收益 |
|-------------|-------------|
| Zone 级故障导致全站不可用 | Zone 故障自动切换 |
| 跨地域延迟高 | 就近接入降低延迟 |
| 无法灰度发布 | Zone 级灰度 |

### 6.2 架构概览

```
┌──────────────────────────────────────────────────┐
│                   Gateway (Zone Aware)            │
│  ZonePreferenceFilter: 10 步决策算法              │
└──────────┬───────────────┬───────────────┬───────┘
           │               │               │
     ┌─────▼─────┐   ┌─────▼─────┐   ┌─────▼─────┐
     │ Zone-A    │   │ Zone-B    │   │ Zone-C    │
     │ Services  │   │ Services  │   │ Services  │
     │ MySQL-A   │   │   │ Redis-C   │
     └───────────┘   └───────────┘   └───────────┘
```

### 6.3 核心组件

| 组件 | 作用 |
|------|------|
| `ZoneContext` | Zone 单例状态管理器，持有当前 Zone 和 Zone 偏好 |
| `ZoneResolver` | 解析请求中的 Zone 信息（Header/Cookie/固定值） |
| `ZonePreferenceFilter` | Gateway 过滤器，10 步决策算法决定路由到哪个 Zone |
| `ServiceInstanceZoneResolver` | 从 Nacos metadata 读取实例 Zone |
| `ZonePreferenceServiceInstanceListSupplier` | LoadBalancer 中的 Zone 优先策略 |

→ 详细设计见 `37-multiactive-architecture.md`

--- MySQL-B   │   │ MySQL-C   │
     │ Redis-A   │   │ Redis-B   │

## 七、API 多版本路由

### 7.1 为什么需要多版本？

业务迭代中，API 的兼容性是一个持续存在的问题。多版本路由允许 v1 和 v2 同时在线，逐步迁移流量。

### 7.2 双路由策略

```
请求 → Gateway (ApiVersionFilter) → Service (@ApiVersion)
         │                                │
         │ Accept-Version: v2              │ @ApiVersion("2.0")
         │ 路由到 v2 实例                   │ HandlerMapping 匹配
         │                                │
         └────────────────────────────────┘
```

| 层级 | 组件 | 机制 |
|------|------|------|
| **Gateway** | `ApiVersionFilter` | 根据 `Accept-Version` Header 路由到不同服务实例 |
| **Service** | `ApiVersionHandlerMapping` | 扩展 `RequestMappingHandlerMapping`，将 `@ApiVersion` 作为额外匹配条件 |

### 7.3 使用方式

```java
@RestController
@RequestMapping("/api/notes")
public class NoteController {

    @GetMapping("/{id}")
    @ApiVersion("1.0")
    public Result<NoteDTO> getNoteV1(@PathVariable Long id) { ... }

    @GetMapping("/{id}")
    @ApiVersion("2.0")
    public Result<NoteDTOV2> getNoteV2(@PathVariable Long id) { ... }
}
```

→ 详细设计见 `47-api-versioning.md`

---

## 八、XXL-Job 分布式调度

### 8.1 任务全景（18 个 Job）

| 服务 | 任务数 | 典型任务 |
|------|--------|---------|
| order | 4 | 超时关单、本地消息重试、死信扫描、映射修复 |
| payment | 4 | 支付超时检查、支付通知补偿、退款超时检查、退款通知补偿 |
| inventory | 1 | 库存对账 |
| counter | 1 | 计数对账 |
| cart | 1 | 购物车对账 |
| coupon | 1 | 优惠券过期处理 |
| analytics | 1 | 关注计数修复 |
| home | 1 | Feed 收件箱清理 |
| notification | 1 | 未读消息对账 |
| search | 3 | 协同过滤推荐、特征计算、热池计算 |

### 8.2 调度策略

- **补偿型任务**：对账/修复类，通常每天凌晨执行（如 `inventoryReconcileJob`）
- **超时型任务**：扫描类，通常每分钟执行（如 `orderCloseJob`）
- **计算型任务**：推荐/特征，通常每小时或每天执行

→ 详细清单见 `32-cicd-deployment.md`

---

## 九、关键架构指标

| 指标 | 数值 | 说明 |
|------|------|------|
| 微服务数 | 17 个（含 test/benchmark） | 业务服务 15 个 |
| MySQL 实例 | 4 主 4 从 | 按业务域划分 |
| Redis 实例 | 3 实例 + 3 Sentinel | 按用途划分 |
| RocketMQ Topic | 17 个 | 按业务域划分 |
| RocketMQ Consumer | 22 个 | 含事务消息消费者 |
| FeignClient | 15 个 | 分布在 5 个模块 |
| XXL-Job 任务 | 18 个 | 分布在 8 个服务 |
| 总代码行数 | ~10 万行 | 含 common 模块 30+ 配置类 |

---

## 十、代价与局限

| 局限 | 影响 | 改进方向 |
|------|------|---------|
| **Nacos standalone 单点** | Nacos 宕机 → 新服务无法注册/发现 | 升级为集群模式 |
| **Sentinel Dashboard 无认证** | 任何人都可修改限流规则 | 添加认证 |
| **ES 单节点** | ES 宕机 → 搜索不可用 | 添加数据节点 |
| **counter 过度拆分** | 5 接口 300 行代码独立服务 | 合并到 analytics |
| **payment 过度拆分** | 与 order 双向 Feign 调用 | 合并到 order |
| **密钥明文硬编码** | JWT/HMAC/MySQL 密码在 Git 中 | Jasypt/Vault 加密 |
| **Canal 单点** | Canal 宕机 → ES 索引不同步 | Canal 集群部署 |
| **无 Alertmanager** | 21 条告警规则形同虚设 | 部署 Alertmanager |

→ 完整问题清单见 `44-engineering-issues-roadmap.md`

---

> **下一篇**：`02-module-interaction.md` — 17 个模块的调用关系图、RocketMQ 事件流全景、BFF 聚合层拓扑、最长调用链追踪
