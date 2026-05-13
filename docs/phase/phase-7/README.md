# Phase 7：多活架构 — 详细梳理

> 🎯 目标：系统具备同城双活能力，单机房故障时流量无缝切换，业务零中断
>
> ⚠️ **前置条件**：Phase 1 + Phase 2 + Phase 3 + Phase 4 + Phase 5 + Phase 6 全部功能开发完成并验收通过

### Phase 间依赖清单

| 依赖Phase | 依赖的功能点 | 本Phase使用场景 |
|-----------|------------|----------------|
| Phase 1 | 用户体系（JWT签发/刷新） | 多活路由需解析JWT获取用户信息，同区域路由决策 |
| Phase 1 | 内容服务、社交服务、计数服务 | 多活数据同步覆盖笔记/社交/计数缓存，注册中心双注册 |
| Phase 2 | 商品/库存/购物车/优惠券/订单/支付 | 多活数据层核心场景：MySQL跨机房同步、Redis跨机房路由、动态数据源切换 |
| Phase 2 | 优惠券分库、订单分库 | 动态JDBC组件按机房路由分库分表数据源 |
| Phase 3 | 搜索/Home BFF/通知 | Gateway多活路由覆盖搜索/首页/通知，Canal跨机房同步ES索引 |
| Phase 4 | Gateway鉴权/限流/灰度/染色 | 灰度路由扩展为多活路由，流量染色扩展为区域标记透传 |
| Phase 4 | IM服务（WebSocket） | IM服务多活：WebSocket连接同区域路由、消息跨机房同步 |
| Phase 4 | 公共组件（@Idempotent/@DistributedLock/@RateLimit/IdGeneratorUtil） | 多活场景分布式锁增强（跨机房锁）、幂等性保障（跨机房请求去重） |
| Phase 5 | 缓存一致性方案 | 多活缓存一致性：Canal跨机房Binlog同步→更新对端缓存 |
| Phase 5 | 分布式事务 | 多活事务：跨机房事务消息、本地消息表双写 |
| Phase 5 | 全链路流量染色 | 流量染色扩展：X-Gray-Tag → X-Region-Tag，区域标记全链路透传 |
| Phase 5 | 监控告警体系 | 多活监控：双机房独立监控面板、跨机房延迟告警、故障自动切换告警 |
| Phase 5 | 分库分表实战 | 动态数据源在分库分表基础上按机房路由 |
| Phase 5 | Canal数据同步 | Canal跨机房Binlog同步：机房A MySQL → Canal → MQ → 机房B MySQL |
| Phase 5 | 优雅停机与服务治理 | 多活故障切换：机房下线时优雅停机+流量切走+实例摘除 |
| Phase 6 | 混沌工程与故障演练 | 多活混沌：机房级故障注入、故障切换演练验证 |
| Phase 6 | 安全合规体系 | 多活安全：跨机房请求HMAC签名校验、RBAC多机房权限同步 |
| Phase 6 | 日志体系与可观测性 | 多活日志：TraceId跨机房关联、双机房日志聚合 |
| Phase 6 | 高可用与故障预案 | 多活是高可用的终极形态，Phase 6 故障预案扩展为跨机房预案 |
| Phase 6 | 限流降级方案 | 多活限流：单机房流量过载时限流+溢出到对端机房 |

---

## 一、Phase 7 概览

| 序号 | 专题 | 涉及服务 | 核心技术 |
|------|------|----------|----------|
| 42 | 多活架构概述与选型 | 全局 | CAP/BASE理论、同城双活 vs 异地多活 vs 单元化、流量路由策略、数据一致性级别 |
| 43 | 注册中心与发现多活 | User/Content/Gateway + 全部服务 | Nacos双集群部署、服务双注册、同区域优先发现、Nacos Raft/Distro跨机房 |
| 44 | 网关与负载均衡多活 | Gateway + 全部服务 | Gateway多活路由（X-Region-Tag）、LoadBalancer区域感知、Feign多活拦截器 |
| 45 | 数据层多活 | Order/Product/Content/User + 全部数据服务 | MySQL跨机房主从同步、Redis Cluster跨机房、Canal+MQ双向同步、动态数据源路由 |
| 46 | 动态组件多活与流量调度 | Common + 全部服务 | 动态JDBC组件、动态Spring Bean、多活流量调度、故障自动切换 |

---

## 二、涉及的模块与端口

### 2.1 双机房端口规划

> 多活架构下，每个服务需要部署两个实例（机房A + 机房B）。为区分机房，端口采用"基础端口 + 机房偏移量"策略：
> - 机房A（region-a）：使用原端口
> - 机房B（region-b）：基础端口 + 100

| 服务 | 机房A端口 | 机房B端口 | 数据库 | 本阶段变更 | 说明 |
|------|----------|----------|--------|-----------|------|
| my-xhs-gateway | 9000 | 9100 | — | ❌ 已存在(增强) | 网关（补齐多活路由Filter、区域标记注入） |
| my-xhs-user | 9001 | 9101 | my_xhs_user | ❌ 已存在(增强) | 用户（补齐双注册、区域感知数据源） |
| my-xhs-content | 9002 | 9102 | my_xhs_note | ❌ 已存在(增强) | 笔记（补齐双注册、Canal跨机房同步） |
| my-xhs-analytics | 9003 | 9103 | my_xhs_social | ❌ 已存在(增强) | 社交（补齐双注册、缓存跨机房同步） |
| my-xhs-counter | 9004 | 9104 | my_xhs_counter | ❌ 已存在(增强) | 计数（补齐双注册、Redis跨机房路由） |
| my-xhs-product | 9005 | 9105 | my_xhs_product | ❌ 已存在(增强) | 商品（补齐双注册、MySQL跨机房同步） |
| my-xhs-order | 9006 | 9106 | my_xhs_order (分库) | ❌ 已存在(增强) | 订单（补齐动态数据源按机房路由、Canal双向同步） |
| my-xhs-payment | 9007 | 9107 | my_xhs_payment | ❌ 已存在(增强) | 支付（补齐双注册、事务消息跨机房） |
| my-xhs-inventory | 9008 | 9108 | my_xhs_inventory | ❌ 已存在(增强) | 库存（补齐双注册、跨机房分布式锁） |
| my-xhs-cart | 9009 | 9109 | my_xhs_cart | ❌ 已存在(增强) | 购物车（补齐双注册、Redis跨机房路由） |
| my-xhs-coupon | 9010 | 9110 | my_xhs_coupon (分库) | ❌ 已存在(增强) | 优惠券（补齐动态数据源按机房路由） |
| my-xhs-search | 9011 | 9111 | my_xhs_search (ES+MySQL) | ❌ 已存在(增强) | 搜索（补齐ES跨机房同步、双注册） |
| my-xhs-notification | 9012 | 9112 | my_xhs_notification | ❌ 已存在(增强) | 通知（补齐双注册、MQ跨机房消费） |
| my-xhs-im | 9014 | 9114 | my_xhs_im | ❌ 已存在(增强) | IM（补齐WebSocket同区域路由、消息跨机房同步） |
| my-xhs-home | 9015 | 9115 | 无（BFF聚合） | ❌ 已存在(增强) | Home BFF（补齐多活路由标记读取） |
| my-xhs-common | — | — | — | ❌ 已存在(增强) | 公共模块（补齐RegionContext、RegionLoadBalancer、DynamicJdbcComponent等） |

### 2.2 基础设施双机房部署

| 基础设施 | 机房A | 机房B | 同步方式 | 说明 |
|----------|-------|-------|----------|------|
| Nacos Cluster | 8848（3节点） | 8848（3节点） | 独立集群，服务双注册 | 两个Nacos集群各自独立Raft |
| MySQL | 3306（主） | 3307（主） | MySQL主主双向复制 | 每个机房各一主，双向同步 |
| Redis Sentinel | 26379（3哨兵） | 26479（3哨兵） | Redis主从跨机房复制 | 机房A为主，机房B为从，可切换 |
| RocketMQ | 9876（Namesrv+Broker） | 9877（Namesrv+Broker） | Broker跨机房同步复制 | 双机房独立Broker，Topic双向订阅 |
| Elasticsearch | 9200（3节点） | 9201（3节点） | Cross-Cluster Replication | 机房A索引CCR同步到机房B |
| Canal | 11111 | 11112 | 各机房独立Canal | 机房A Canal同步机房A MySQL Binlog→机房B；机房B Canal反向同步 |

> **Phase 7 是多活架构增强Phase，不新增服务模块**，只在现有模块上增强多活能力，并新增公共组件。所有变更需兼容已有功能。

---

## 三、专题详细梳理

### 专题 42：多活架构概述与选型

#### 3.42.1 功能描述

多活架构是分布式系统高可用的终极形态，核心目标是：任一机房故障，流量无缝切换到存活机房，业务零中断、数据零丢失。本专题从理论出发，分析CAP/BASE在多活场景的约束，对比同城双活、异地多活、单元化架构三种方案的优劣，最终为my-xhs项目选定**同城双活**方案，并定义多活架构下的流量路由策略、数据一致性级别、故障切换SLA等核心规范。

#### 3.42.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| 全部服务 | 架构约束对象 | 所有服务必须遵循多活架构规范：双注册、区域感知、数据双写 |
| my-xhs-common | 组件提供方 | RegionContext、RegionConstant、RegionHelper等公共组件 |
| my-xhs-gateway | 流量入口 | 多活路由决策、区域标记注入、故障流量切换 |

#### 3.42.3 多活架构理论体系

##### 3.42.3.1 CAP 理论在多活场景的约束

```
CAP 三选二？—— 分布式系统最多同时满足两项：

┌─────────────────────────────────────────────────┐
│                    CAP 三角                       │
│                                                   │
│              Consistency                          │
│              (一致性)                              │
│                 /\                                 │
│                /  \                                │
│               /    \                               │
│              /  CA  \                              │
│             / (RDBMS)\                             │
│            /──────────\                            │
│     CP    /            \    AP                     │
│  (ZooKeeper)          (Cassandra)                  │
│  (etcd)               (Dynamo)                     │
│  (Nacos Raft)         (Nacos Distro/AP)            │
│                                                   │
│  多活架构 = AP 系统                                │
│  → 牺牲强一致性，保证可用性和分区容错                 │
│  → 通过最终一致性补偿                               │
└─────────────────────────────────────────────────┘
```

| CAP组合 | 含义 | 典型系统 | 多活适用性 |
|---------|------|---------|-----------|
| CP | 一致性+分区容错，牺牲可用性 | ZooKeeper、etcd、Nacos Raft协议 | ❌ 分区时不可用，违背多活目标 |
| AP | 可用性+分区容错，牺牲强一致性 | Nacos Distro协议、Eureka、Cassandra | ✅ 分区时仍可用，最终一致 |
| CA | 一致性+可用性，无分区容错 | 单机MySQL、单机Redis | ❌ 无法跨机房部署 |

**核心结论**：多活架构本质是AP系统，必须接受**分区时牺牲强一致性**，通过BASE理论中的最终一致性来补偿。

##### 3.42.3.2 BASE 理论在多活场景的应用

| BASE要素 | 含义 | my-xhs多活实现 |
|----------|------|---------------|
| **B**asically Available | 基本可用，分区时允许响应变慢或功能降级 | 机房A故障→流量切到机房B，RT可能升高但服务可用 |
| **S**oft State | 软状态，允许中间状态（数据不一致窗口） | MySQL双向同步有延迟窗口（通常<1s），Redis跨机房复制有延迟 |
| **E**ventually Consistent | 最终一致，一段时间后数据达到一致 | Canal异步同步Binlog+MQ+消费者更新，秒级最终一致 |

##### 3.42.3.3 一致性级别选型

| 级别 | 延迟 | 可用性 | 适用场景 | my-xhs应用 |
|------|------|--------|---------|-----------|
| 强一致（线性一致性） | 高（跨机房RT） | 低（分区时不可用） | 金融转账 | ❌ 不适用 |
| 顺序一致 | 中 | 中 | 配置变更 | Nacos配置变更（Raft协议保证） |
| 因果一致 | 低 | 高 | 聊天消息顺序 | IM消息（同会话有序） |
| 最终一致 | 最低 | 最高 | 大部分互联网场景 | ✅ 商品/笔记/订单/缓存等 |

#### 3.42.4 三种多活方案对比

##### 3.42.4.1 方案全景对比

| 维度 | 同城双活 | 异地多活 | 单元化架构 |
|------|---------|---------|-----------|
| **机房距离** | <50km（同城） | >1000km（跨城） | 不限（逻辑单元） |
| **网络延迟** | 1-3ms | 30-100ms | 取决于单元位置 |
| **数据同步** | MySQL主主双向同步 | 异步Binlog同步，延迟秒级 | 单元内闭环，跨单元异步 |
| **一致性级别** | 准实时（ms级延迟） | 最终一致（秒级延迟） | 单元内强一致，跨单元最终一致 |
| **流量路由** | 同区域优先，故障切换 | 按地域路由 | 按用户ID路由到固定单元 |
| **改造成本** | 🟢 低（基础设施层改造为主） | 🟡 中（需处理长延迟） | 🔴 高（业务代码需单元化改造） |
| **运维复杂度** | 🟢 低 | 🟡 中 | 🔴 高 |
| **典型公司** | 美团、滴滴 | 阿里（异地多单元） | 支付宝（单元化LDC） |
| **适用阶段** | 日活<1000万 | 日活1000万-1亿 | 日活>1亿 |

##### 3.42.4.2 方案详细分析

**方案A：同城双活**

```
                    ┌──────────────────────────┐
                    │       负载均衡 (LB)       │
                    │   Nginx / F5 / SLB       │
                    └──────────┬───────────────┘
                               │
                    ┌──────────┴──────────┐
                    │                     │
            ┌───────▼───────┐     ┌───────▼───────┐
            │   机房 A       │     │   机房 B       │
            │  (region-a)   │     │  (region-b)   │
            │               │     │               │
            │  Gateway:9000 │     │  Gateway:9100 │
            │  User:9001    │     │  User:9101    │
            │  Order:9006   │     │  Order:9106   │
            │  ...          │     │  ...          │
            │               │     │               │
            │  MySQL(主)    │◄───►│  MySQL(主)    │
            │  Redis(主)    │────►│  Redis(从)    │
            │  Nacos集群    │     │  Nacos集群    │
            │  MQ Broker    │◄───►│  MQ Broker    │
            └───────────────┘     └───────────────┘
                    │                     │
                    └──────────┬──────────┘
                               │
                    同城光纤互联 (RT < 3ms)
                    MySQL双向同步 / Redis主从复制
```

- **优点**：改造成本低、延迟极低（<3ms）、数据一致性容易保障
- **缺点**：无法防御同城级灾难（如整个城市断电）、容量上限受单城限制
- **适用场景**：日活<1000万，容忍分钟级城市级故障

**方案B：异地多活**

```
            ┌───────┐                    ┌───────┐
            │ 北京   │    专线/WAN        │ 上海   │
            │机房 A  │◄──────────────────►│机房 B  │
            │       │    RT 30-50ms      │       │
            └───┬───┘                    └───┬───┘
                │                            │
         全量服务                        全量服务
         MySQL(主)                      MySQL(主)
         异步Binlog同步 ◄──────────────► 异步Binlog同步
```

- **优点**：可防御城市级灾难、用户就近接入延迟低
- **缺点**：数据同步延迟大（秒级）、冲突解决复杂、改造成本高
- **适用场景**：日活1000万+，需要城市级容灾

**方案C：单元化架构（LDC）**

```
            ┌─────────────────────────────────────────┐
            │              全局路由层                    │
            │   用户ID % 单元数 → 路由到固定单元         │
            └──────┬──────────┬──────────┬────────────┘
                   │          │          │
            ┌──────▼──┐ ┌────▼────┐ ┌───▼──────┐
            │ 单元 R1  │ │ 单元 R2  │ │ 单元 R3  │
            │用户ID%3=0│ │用户ID%3=1│ │用户ID%3=2│
            │         │ │         │ │         │
            │ 全套服务 │ │ 全套服务 │ │ 全套服务 │
            │ MySQL   │ │ MySQL   │ │ MySQL   │
            │ Redis   │ │ Redis   │ │ Redis   │
            └─────────┘ └─────────┘ └─────────┘
```

- **优点**：数据天然隔离无冲突、可线性扩展、强一致性（单元内）
- **缺点**：业务代码改造量巨大、跨单元交互复杂、资源冗余度高
- **适用场景**：日活>1亿，极致隔离需求（如支付）

##### 3.42.4.4 脑裂问题与仲裁机制（⚠️ 深水区）

> **脑裂（Brain-Split）是多活架构最致命的风险**：机房间网络断开时，两个机房各自认为对方故障，独立接受写入，导致数据分叉且无法自动合并。

**脑裂风险矩阵**：

| 组件 | 脑裂场景 | 后果 | 严重程度 |
|------|---------|------|---------|
| MySQL主主 | 网络分区后两Master各自写入 | 数据分叉，双向复制恢复后冲突无法自动解决 | 🔴 致命 |
| Redis Sentinel | 分区后两端各选举新Master | 双主写入，数据丢失 | 🔴 致命 |
| Nacos集群 | Raft分区后两端各选举Leader | 配置不一致 | 🟡 严重 |
| Canal双向同步 | 分区后Canal各自消费本地Binlog写入对端 | 恢复后产生循环消费风暴 | 🟡 严重 |

**仲裁机制设计**：

```
┌─────────────── 机房A ────────┐     ┌─────────────── 机房B ────────┐
│                               │     │                               │
│  MySQL Master A               │     │  MySQL Master B               │
│  Redis Master A               │     │  Redis Slave B                │
│  Nacos Cluster A              │     │  Nacos Cluster B              │
│                               │     │                               │
└──────────┬────────────────────┘     └──────────┬────────────────────┘
           │                                     │
           │         ┌──────────────┐            │
           └────────►│  仲裁节点     │◄───────────┘
                     │  (公有云VM)   │
                     │  或第三个机房  │
                     └──────────────┘

仲裁节点职责：
  1. MySQL：MHA Manager部署在仲裁节点，控制主从切换决策
  2. Redis：Sentinel Quorum > N/2，仲裁节点的Sentinel参与投票
  3. Nacos：不参与Raft（两个集群独立），但仲裁节点可监控两集群健康
  4. 健康检查：仲裁节点同时Ping两个机房，判断谁"真正"不可用
```

**各组件防脑裂配置**：

| 组件 | 防脑裂配置 | 说明 |
|------|-----------|------|
| MySQL | MHA + 仲裁节点 | MHA Manager在仲裁节点运行，网络分区时只允许与仲裁节点连通的机房升主 |
| Redis | `min-replicas-to-write=1` | Master至少有1个Slave连接时才接受写入，分区时少数派Master拒绝写入 |
| Redis Sentinel | Quorum=4（6个Sentinel中需4票） | 确保分区时少数派无法达到Quorum，不触发故障转移 |
| Nacos | 双集群独立 + GitOps配置同步 | 不存在跨集群脑裂风险，各自独立运行 |
| Canal | `source-region`标记 + 消费位点锁 | 分区恢复后，基于位点去重避免循环消费 |

**仲裁节点部署要求**：
- 部署在公有云VM或第三个机房，与机房A/B网络独立
- 只需轻量级服务：MHA Manager + Sentinel + 健康检查Agent
- 资源极小（2C4G即可），成本<100元/月

##### 3.42.4.5 服务分级双活策略（⚠️ 深水区）

> **行业实践：不是所有服务都需要全量双活**。按服务等级分级双活，可大幅降低改造成本和运维复杂度。

| 服务等级 | 双活策略 | 数据同步方式 | 故障切换SLA | my-xhs服务 |
|----------|---------|-------------|-----------|-----------|
| **P0 核心交易** | 强一致双活 | MySQL半同步复制 + Canal双向同步 | RPO≈0，RTO<60s | 订单、支付、库存 |
| **P1 核心功能** | 最终一致双活 | MySQL异步复制 + Canal双向同步 | RPO<1s，RTO<60s | 用户、商品、购物车、优惠券 |
| **P2 辅助功能** | 单活+冷备 | MySQL异步复制（单向），故障时手动切换 | RPO<5s，RTO<5min | 笔记、社交、计数、搜索 |
| **P3 可降级** | 单活 | 不做跨机房同步，故障时可停服 | 可接受短暂不可用 | 通知、热搜榜、推荐 |

**分级双活资源节省估算**：

| 维度 | 全量双活 | P0+P1双活 + P2冷备 + P3单活 | 节省 |
|------|---------|---------------------------|------|
| MySQL双向同步链路 | 16个DB全部双向 | 5个P0+P1 DB双向，其余单向 | 节省60%同步链路 |
| Canal实例 | 16个DB各需2个Canal | 5个DB需2个Canal，其余1个 | 节省60%Canal资源 |
| Redis跨机房 | 全部缓存跨机房 | P0+P1缓存跨机房 | 节省50%Redis资源 |
| 服务双注册 | 全部16个服务 | 5+4=9个服务 | 节省40%注册开销 |
| 改造工作量 | 全部服务需改造 | 9个服务需改造 | 节省40%开发量 |

> **建议**：初期只做P0+P1双活，验证稳定后再扩展P2。P3不做多活，故障时可降级。

##### 3.42.4.6 my-xhs选型结论

**最终选择：同城双活**

**选择理由**：

1. **my-xhs 日活预估在百万级**，同城双活足以满足容灾需求，无需异地多活
2. **改造成本最低**：基础设施层改造为主（MySQL双向同步、Nacos双集群、Gateway多活路由），业务代码改动最小
3. **数据一致性最易保障**：同城RT<3ms，MySQL双向同步延迟<100ms，可做到准实时一致
4. **运维复杂度可控**：两个机房拓扑对称，故障切换简单
5. **可演进**：未来如需异地多活，同城双活是必经之路，架构可平滑升级

#### 3.42.5 多活架构核心规范

##### 3.42.5.1 区域标记规范

| 标记 | 值 | 含义 | 透传方式 |
|------|---|------|---------|
| `X-Region-Tag` | `region-a` / `region-b` | 请求所属机房 | HTTP Header、Feign Header、MQ Message Header、ThreadLocal |
| `X-Region-Route` | `prefer-local` / `force-local` / `force-remote` | 路由策略 | HTTP Header，Gateway注入 |
| `X-Region-Failover` | `true` / `false` | 是否为故障切换流量 | HTTP Header，故障切换时自动注入 |

##### 3.42.5.2 流量路由策略

| 策略 | 含义 | 适用场景 |
|------|------|---------|
| **prefer-local**（默认） | 优先路由到本机房，本机房无可用实例时降级到对端 | 日常流量 |
| **force-local** | 强制路由到本机房，不可降级 | 数据写操作（写本机房主库） |
| **force-remote** | 强制路由到对端机房 | 故障切换、灰度验证 |

##### 3.42.5.3 数据一致性规范

| 数据类型 | 一致性级别 | 同步方式 | 不一致窗口 | 冲突解决 |
|----------|-----------|---------|-----------|---------|
| MySQL业务数据（P0核心） | 准强一致 | **MySQL半同步复制** + Canal双向同步 | ≈0（半同步等待1个Slave ACK） | 半同步确保RPO≈0 |
| MySQL业务数据（P1核心） | 最终一致 | MySQL异步主主双向复制 | <500ms | 基于时间戳Last-Write-Wins |
| MySQL业务数据（P2辅助） | 最终一致 | MySQL异步单向复制 | <1s | 冷备，故障时手动切换 |
| Redis缓存 | 最终一致 | 主从复制 + Canal异步更新 | <1s | 缓存可丢失，回源DB |
| ES索引 | 最终一致 | Cross-Cluster Replication | <3s | 索引可重建 |
| Nacos配置 | 强一致 | Nacos Raft协议（集群内） | 0 | 配置变更走Raft |
| 分布式锁 | 强一致 | Redis主节点执行 | 0 | 锁操作必须在主节点 |

##### 3.42.5.4 故障切换SLA

| 场景 | 检测时间 | 切换时间 | 数据丢失 | 业务影响 |
|------|---------|---------|---------|---------|
| 单服务实例宕机 | 10s（Nacos心跳） | 15s（摘除+重试） | 无 | 请求短暂报错后恢复 |
| 单机房全部服务宕机 | 30s（健康检查3次） | 60s（LB切换+DNS） | 无（同步已完成） | 1分钟内恢复 |
| MySQL机房A主库宕机 | 10s（MHA检测） | 30s（主从切换） | 可能丢失<1s数据 | 写入短暂不可用 |
| Redis机房A主节点宕机 | 15s（Sentinel投票） | 30s（Sentinel故障转移） | 可能丢失<1s数据 | 缓存短暂不可用 |
| 机房A整体断网 | 30s（LB健康检查） | 60s（LB切全部流量到机房B） | 可能丢失<1s数据 | 1分钟内恢复 |

#### 3.42.6 Java 文件清单

**common/region/**

| 文件 | 说明 |
|------|------|
| `RegionConstant.java` | 区域常量定义（REGION_A="region-a", REGION_B="region-b"） |
| `RegionContext.java` | 区域上下文（ThreadLocal持有当前区域标记） |
| `RegionHelper.java` | 区域工具类（判断当前区域、设置区域标记） |
| `RegionRouteStrategy.java` | 区域路由策略枚举（PREFER_LOCAL, FORCE_LOCAL, FORCE_REMOTE） |
| `RegionContextHolder.java` | 区域上下文持有者（基于TransmittableThreadLocal支持线程池透传） |

**common/region/filter/**

| 文件 | 说明 |
|------|------|
| `RegionContextFilter.java` | 区域上下文Filter（从Header提取X-Region-Tag写入ThreadLocal） |

**common/region/interceptor**

| 文件 | 说明 |
|------|------|
| `RegionFeignInterceptor.java` | Feign请求拦截器（透传X-Region-Tag到下游） |
| `RegionMqInterceptor.java` | MQ消息拦截器（透传X-Region-Tag到MQ Message Header） |

#### 3.42.7 面试考察点

**Q1：为什么多活架构本质是AP系统？请结合CAP理论说明。**

> 1. CAP理论指出分布式系统在网络分区（P）发生时，只能在一致性（C）和可用性（A）之间二选一
> 2. 多活架构的核心目标是"机房故障时业务不中断"，这意味着必须保证可用性（A）
> 3. 跨机房网络分区是多活架构的常态（不是异常），因此分区容错（P）必须保证
> 4. 所以多活架构只能是AP系统，牺牲强一致性，通过最终一致性（BASE理论）来补偿
> 5. 实际工程中通过MySQL双向同步、Canal异步同步、MQ消息驱动等手段实现秒级最终一致

**Q2：同城双活 vs 异地多活，你如何选型？**

> 1. 选型核心依据是业务体量和容灾等级需求
> 2. 同城双活适合日活<1000万、需防御机房级故障的场景，改造成本低、延迟低（<3ms）
> 3. 异地多活适合日活>1000万、需防御城市级故障的场景，改造成本高、需处理长延迟（30-100ms）
> 4. 单元化架构适合日活>1亿、极致隔离需求，改造成本极高
> 5. 建议渐进式演进：同城双活 → 异地双活 → 异地多活 → 单元化

**Q3：多活架构下如何防止脑裂？你的仲裁机制是什么？**

> 1. 脑裂是多活架构最致命的风险：网络分区后两个机房各自写入，数据分叉无法自动合并
> 2. MySQL防脑裂：部署MHA Manager在第三方仲裁节点，分区时只允许与仲裁节点连通的机房升主
> 3. Redis防脑裂：配置 `min-replicas-to-write=1`，Master至少有1个Slave连接时才接受写入；Sentinel Quorum设为>N/2
> 4. Nacos防脑裂：双集群独立部署，不跨机房Raft，天然无脑裂风险
> 5. 仲裁节点部署在公有云VM或第三机房，资源需求极小（2C4G），成本<100元/月
> 6. 仲裁节点的核心职责：MHA决策、Sentinel投票、双机房健康监控

**Q4：全量服务双活 vs 部分服务双活，你如何决策？**

> 1. 不是所有服务都需要全量双活，按服务等级分级双活可大幅降低改造成本
> 2. P0核心交易（订单/支付/库存）：强一致双活，半同步复制 + Canal双向同步，RPO≈0
> 3. P1核心功能（用户/商品/购物车）：最终一致双活，异步复制 + Canal双向同步，RPO<1s
> 4. P2辅助功能（笔记/社交/搜索）：单活+冷备，异步单向复制，故障时手动切换
> 5. P3可降级（通知/热搜）：单活，不做跨机房同步，故障时可停服
> 6. 分级策略可节省约40-60%的改造成本和运维资源

#### 3.42.8 服务网格与多活架构演进（P1 补充）

> Stage-3 课程 022-023 讲了 Istio 服务网格。服务网格是多活架构的演进方向——从"应用内实现流量路由/灰度/熔断"到"基础设施层统一接管"。以下是批判性补充。

##### （1）服务网格核心概念

```
传统微服务架构：
  应用代码 → SDK（Sentinel/LoadBalancer/Registry Client） → 网络
  → 流量管理逻辑耦合在应用中，SDK升级需改代码重新部署

服务网格架构：
  应用代码 → Sidecar（Envoy Proxy） → 网络
  → 流量管理逻辑下沉到基础设施层，应用无感知
  → 控制面（Istiod）统一下发配置，数据面（Envoy）执行
```

| 概念 | 说明 | 类比 |
|------|------|------|
| **Sidecar** | 与应用容器同Pod部署的Envoy代理，拦截所有入站/出站流量 | Nginx反向代理（但自动化） |
| **数据面** | Envoy代理集群，执行流量路由/负载均衡/熔断/可观测 | 交换机/路由器 |
| **控制面** | Istiod（Pilot + Citadel + Galley），下发配置到Envoy | SDN控制器 |
| **xDS协议** | Envoy与控制面的配置协议（LDS/RDS/CDS/EDS/SDS） | 路由表下发协议 |

##### （2）Istio 流量管理核心资源

```yaml
# VirtualService：定义路由规则（类似Gateway的路由配置）
apiVersion: networking.istio.io/v1alpha3
kind: VirtualService
metadata:
  name: my-xhs-product
spec:
  hosts:
    - my-xhs-product
  http:
    - match:
        - headers:
            x-region:
              exact: "region-a"    # 流量染色：region-a流量路由到region-a的Pod
      route:
        - destination:
            host: my-xhs-product
            subset: region-a
    - route:                        # 默认流量路由
        - destination:
            host: my-xhs-product
            subset: region-b
          weight: 80
        - destination:
            host: my-xhs-product
            subset: region-a
          weight: 20                # 20%灰度流量

---
# DestinationRule：定义服务子集和负载均衡策略
apiVersion: networking.istio.io/v1alpha3
kind: DestinationRule
metadata:
  name: my-xhs-product
spec:
  host: my-xhs-product
  trafficPolicy:
    connectionPool:
      tcp:
        maxConnections: 100
      http:
        h2UpgradePolicy: UPGRADE
    outlierDetection:              # 熔断：连续5次5xx → 30s摘除
      consecutive5xxErrors: 5
      interval: 30s
      baseEjectionTime: 30s
  subsets:
    - name: region-a
      labels:
        topology.kubernetes.io/zone: region-a
    - name: region-b
      labels:
        topology.kubernetes.io/zone: region-b
```

##### （3）服务网格 vs 传统微服务 SDK 对比

| 维度 | SDK模式（my-xhs当前） | 服务网格（Istio） |
|------|---------------------|-----------------|
| 流量路由 | Spring Cloud Gateway + Nacos | VirtualService + DestinationRule |
| 负载均衡 | Ribbon/LoadBalancer + Nacos | Envoy + EDS（Endpoint发现） |
| 熔断限流 | Sentinel（SDK内嵌） | Envoy OutlierDetection + RateLimit |
| 链路追踪 | SkyWalking Agent | Envoy + Jaeger/Zipkin（自动注入） |
| 安全加密 | 应用层TLS（需手动配置） | mTLS（自动双向加密，零代码侵入） |
| 灰度发布 | Nacos元数据 + Gateway路由 | VirtualService权重路由 |
| 多活路由 | 自定义Filter + 区域感知 | VirtualService header匹配 + Subset |
| 语言耦合 | Java SDK绑定 | 语言无关（Sidecar代理） |
| 运维复杂度 | 低（SDK自治） | 高（需运维Istio控制面） |

##### （4）批判性思考：my-xhs 是否需要引入 Istio？

| 引入 Istio 的优势 | 引入 Istio 的代价 |
|------------------|-----------------|
| mTLS零代码加密 | Sidecar每请求增加0.5-2ms延迟 |
| 语言无关的流量管理 | Istio控制面运维复杂（Pilot/Citadel/Galley） |
| 自动链路追踪+指标采集 | 学习曲线陡峭，团队需掌握Istio运维 |
| 灰度/多活路由声明式配置 | 资源消耗（每个Pod多一个Envoy Sidecar ≈ 100MB内存） |
| 统一的流量治理（不再依赖Java SDK） | 与Spring Cloud组件功能重叠 |

**my-xhs 当前阶段建议**：**暂不引入 Istio**，理由：
1. my-xhs 是 Java 单语言项目，Spring Cloud 生态已提供完整的流量治理能力
2. 团队规模小，Istio 运维成本 > 收益
3. 多活架构的核心问题（数据一致性/流量路由/故障切换）已在应用层解决
4. **但需要理解 Istio 的核心思想**，因为：
   - 面试高频（"你们用什么服务网格？为什么用/不用？"）
   - 大厂正在从 SDK 模式向服务网格迁移
   - 未来 my-xhs 如果多语言化（如引入 Go/Python 微服务），Istio 是更好的选择

**未来演进路径**：
```
Phase-7（当前）：Spring Cloud SDK + 应用层多活
    ↓
Phase-8（可选）：Istio 数据面（Envoy Sidecar）+ Spring Cloud SDK 共存
    ↓  
Phase-9（可选）：完全服务网格化，Spring Cloud SDK 逐步退场
```

##### （5）xDS 协议简介（理解 Istio 底层）

> xDS 是 Envoy 与控制面之间的配置分发协议，也是 Dubbo Mesh 的基础。

| 协议 | 全称 | 配置内容 | 类比 |
|------|------|---------|------|
| **LDS** | Listener Discovery Service | 监听器（入站/出站端口配置） | Nginx server块 |
| **RDS** | Route Discovery Service | 路由规则（路径/头部匹配/权重） | Nginx location块 |
| **CDS** | Cluster Discovery Service | 集群（上游服务分组） | Nginx upstream块 |
| **EDS** | Endpoint Discovery Service | 端点（集群内的具体IP:Port） | Nginx server列表 |
| **SDS** | Secret Discovery Service | 证书/密钥（mTLS） | — |

```
配置推送流程：
Istiod(Pilot) → 生成 xDS 配置 → gRPC推送 → Envoy接收 → 热更新路由/集群/监听器
                                         ↑
                                   增量推送（Delta xDS）
                                   只推送变更部分，减少资源消耗
```

---

### 专题 43：注册中心与发现多活

#### 3.43.1 功能描述

注册中心是微服务架构的核心基础设施，服务注册与发现的多活能力直接决定了流量能否正确路由到目标机房。本专题基于Nacos设计双机房注册中心方案：①Nacos双集群独立部署（机房A集群 + 机房B集群，各自Raft协议保证配置一致性）；②服务双注册（每个服务实例同时注册到两个Nacos集群，带有`region`元数据标记）；③同区域优先发现（消费者优先获取同区域的提供者实例列表，降级时获取对端实例）；④Nacos Raft协议在跨机房场景的限制与应对。

#### 3.43.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| Nacos集群A | 注册中心 | 机房A的Nacos集群（3节点，Raft协议） |
| Nacos集群B | 注册中心 | 机房B的Nacos集群（3节点，Raft协议） |
| 所有微服务 | 双注册方 | 每个实例同时注册到两个Nacos集群 |
| my-xhs-gateway | 路由决策方 | 从Nacos获取服务实例列表，根据区域标记路由 |
| my-xhs-common | 组件提供方 | NacosMultiRegistry、RegionAwareDiscovery等组件 |

#### 3.43.3 Nacos 双集群部署架构

##### 3.43.3.1 架构图

```
┌─────────────── 机房A (region-a) ───────────────┐
│                                                  │
│  ┌─────────────────────────────┐                │
│  │  Nacos Cluster A (3 nodes)  │                │
│  │  nacos-a1:8848 (Leader)     │                │
│  │  nacos-a2:8848 (Follower)   │                │
│  │  nacos-a3:8848 (Follower)   │                │
│  │                             │                │
│  │  Raft协议：配置一致性         │                │
│  │  Distro协议：服务发现AP       │                │
│  └─────────────────────────────┘                │
│                                                  │
│  Service-X:9001 (region=region-a)               │
│  Service-Y:9002 (region=region-a)               │
│                                                  │
└──────────────────────────────────────────────────┘

┌─────────────── 机房B (region-b) ───────────────┐
│                                                  │
│  ┌─────────────────────────────┐                │
│  │  Nacos Cluster B (3 nodes)  │                │
│  │  nacos-b1:8848 (Leader)     │                │
│  │  nacos-b2:8848 (Follower)   │                │
│  │  nacos-b3:8848 (Follower)   │                │
│  │                             │                │
│  │  Raft协议：配置一致性         │                │
│  │  Distro协议：服务发现AP       │                │
│  └─────────────────────────────┘                │
│                                                  │
│  Service-X:9101 (region=region-b)               │
│  Service-Y:9102 (region=region-b)               │
│                                                  │
└──────────────────────────────────────────────────┘

注意：两个Nacos集群之间不直接通信！
服务双注册是关键：每个实例同时注册到Cluster A和Cluster B
```

##### 3.43.3.2 为什么不部署跨机房Nacos集群？

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| 跨机房Nacos集群（6节点混合） | 数据天然一致 | Raft Leader选举跨机房延迟、脑裂风险 | ❌ 不推荐 |
| **双集群独立部署 + 服务双注册** | 各集群独立运行、无脑裂风险 | 服务需双注册、配置需双向同步 | ✅ 推荐 |

**关键原因**：Nacos Raft协议需要Leader选举，跨机房部署时Leader可能在不同机房，Follower写操作需要跨机房同步，RT从<1ms增加到2-3ms，且网络分区时可能脑裂。

##### 3.43.3.3 Nacos配置同步方案

配置数据（Nacos Config）需要在两个集群之间同步。方案如下：

| 方案 | 实现 | 优缺点 |
|------|------|--------|
| Nacos内置集群同步 | 不支持，两个独立集群无法直接同步配置 | — |
| **应用双订阅** | 每个服务同时订阅两个Nacos的配置，以本机房Nacos为主 | ✅ 简单可靠 |
| Nacos Config Open API同步 | 定时通过API将Cluster A的配置同步到Cluster B | ⚠️ 有延迟，需自行实现 |
| GitOps + Nacos Import | 配置文件存Git，CI/CD自动Import到两个Nacos | ✅ 推荐，配置版本可控 |

**最终选择**：GitOps + Nacos Import 为主，应用双订阅为辅。

#### 3.43.4 服务双注册设计

##### 3.43.4.1 双注册原理

```
Service-X 实例启动：
    │
    ├── 1. 读取本机房区域标记 (region-a)
    │
    ├── 2. 注册到 Nacos Cluster A
    │       serviceName: "service-x"
    │       ip: 192.168.1.10
    │       port: 9001
    │       metadata: { "region": "region-a" }  ← 关键：区域标记
    │
    └── 3. 注册到 Nacos Cluster B
            serviceName: "service-x"
            ip: 192.168.1.10
            port: 9001
            metadata: { "region": "region-a" }  ← 同样标记region-a

Service-Y 实例启动（机房B）：
    │
    ├── 1. 读取本机房区域标记 (region-b)
    │
    ├── 2. 注册到 Nacos Cluster A
    │       metadata: { "region": "region-b" }
    │
    └── 3. 注册到 Nacos Cluster B
            metadata: { "region": "region-b" }

消费者查询实例列表（从本机房Nacos查询）：
    → 获得 [Service-X(region-a), Service-Y(region-b)]
    → 根据 X-Region-Tag 优先选择同区域实例
```

##### 3.43.4.2 双注册实现方案

**方案A：Spring Cloud Nacos双注册（推荐）**

```yaml
# application-region-a.yml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: nacos-a1:8848,nacos-a2:8848,nacos-a3:8848  # 本机房Nacos
        namespace: ${NACOS_NAMESPACE:public}
        cluster-name: region-a
        metadata:
          region: region-a  # 区域标记
      # 第二注册中心配置
      discovery-secondary:
        enabled: true
        server-addr: nacos-b1:8848,nacos-b2:8848,nacos-b3:8848  # 对端机房Nacos
        namespace: ${NACOS_NAMESPACE:public}
        cluster-name: region-a  # 仍然标记为region-a
        metadata:
          region: region-a
```

**方案B：自定义 NacosMultiRegistry（更灵活）**

通过自定义 `NacosMultiRegistry` 实现 `ServiceRegistry<Registration>` 接口，在 `register()` 方法中同时注册到两个Nacos集群。

| 维度 | 方案A：配置双注册 | 方案B：自定义MultiRegistry |
|------|-----------------|--------------------------|
| 实现难度 | 🟢 低（纯配置） | 🟡 中（需自定义代码） |
| 灵活性 | 🟡 中（受限于Spring Cloud配置项） | 🟢 高（可控制注册逻辑、异常处理） |
| 心跳维护 | 各Nacos SDK各自维护 | 需自行维护双心跳 |
| 注销处理 | Spring Cloud自动注销 | 需自行处理双注销 |
| 适用场景 | 快速验证 | 生产级方案 |

**最终选择**：先方案A快速验证，后方案B生产级增强。

#### 3.43.5 同区域优先发现设计

##### 3.43.5.1 服务发现流程

```
消费者请求服务X：
    │
    ├── 1. 从本机房Nacos获取serviceX实例列表
    │       → [instance-1(region-a:9001), instance-2(region-a:9001),  ← 同区域2个
    │          instance-3(region-b:9101)]                              ← 异区域1个
    │
    ├── 2. 读取当前请求的 X-Region-Tag = region-a
    │
    ├── 3. 区域感知过滤
    │       ├── prefer-local: 优先region-a实例 → [instance-1, instance-2]
    │       │                    如果region-a全挂 → [instance-3]
    │       ├── force-local:  只选region-a实例 → [instance-1, instance-2]
    │       │                   如果全挂 → 报错，不降级
    │       └── force-remote: 只选region-b实例 → [instance-3]
    │
    └── 4. 负载均衡选择
            → RoundRobin从过滤后的实例列表中选择
```

##### 3.43.5.2 Nacos cluster-name vs 自定义metadata.region

| 方案 | 实现 | 优点 | 缺点 |
|------|------|------|------|
| Nacos `cluster-name` | `spring.cloud.nacos.discovery.cluster-name=region-a` | Nacos原生支持同集群优先 | cluster-name被Nacos用于服务端路由，可能与其他用途冲突 |
| **自定义 `metadata.region`** | `spring.cloud.nacos.discovery.metadata.region=region-a` | 不影响Nacos内部逻辑，灵活 | 需要自定义DiscoveryClient过滤逻辑 |

**最终选择**：使用 `metadata.region`，更灵活且不影响Nacos内部逻辑。同时在 `cluster-name` 也设置区域标记，双重保障。

#### 3.43.6 Nacos Raft 与 Distro 协议在多活场景的运用

| 协议 | 用途 | 一致性 | 多活场景 |
|------|------|--------|---------|
| **Raft** | Nacos集群内部Leader选举 + 配置数据一致性 | 强一致（CP） | 每个机房内3节点Raft，保证本机房配置一致 |
| **Distro** | Nacos集群间临时实例数据同步 | 最终一致（AP） | 同机房内各节点间同步服务注册数据，无需跨机房 |

**关键设计**：不使用Distro协议跨机房同步服务注册数据，而是通过**服务双注册**让每个Nacos集群都拥有全量服务实例数据。

#### 3.43.7 Java 文件清单

**common/nacos/**

| 文件 | 说明 |
|------|------|
| `NacosMultiRegistry.java` | 多注册中心注册器（实现ServiceRegistry，双注册到两个Nacos集群） |
| `NacosMultiRegistryAutoConfiguration.java` | 双注册自动配置类 |
| `NacosMultiRegistration.java` | 多注册中心Registration（封装两个Nacos的注册信息） |
| `RegionAwareDiscoveryClient.java` | 区域感知服务发现客户端（过滤实例列表，同区域优先） |
| `RegionInstanceFilter.java` | 区域实例过滤器（根据RegionContext和路由策略过滤实例） |
| `NacosSecondaryProperties.java` | 第二Nacos集群配置属性类 |

**common/nacos/health**

| 文件 | 说明 |
|------|------|
| `NacosDualHealthIndicator.java` | 双Nacos健康检查指示器 |
| `NacosRegistryHealthChecker.java` | Nacos注册中心健康检查器（检测本机房/对端Nacos是否可用） |

#### 3.43.8 配置清单

**Nacos双集群Docker部署**

```yaml
# docker-compose-nacos-dual.yml
version: '3.8'
services:
  # 机房A Nacos集群
  nacos-a1:
    image: nacos/nacos-server:v2.3.2
    environment:
      - NACOS_SERVERS=nacos-a1:8848 nacos-a2:8848 nacos-a3:8848
      - NACOS_APPLICATION_PORT=8848
      - NACOS_SERVER_IP=nacos-a1
      - MYSQL_SERVICE_HOST=mysql-a
      - MYSQL_SERVICE_PORT=3306
      - MYSQL_SERVICE_DB_NAME=nacos_config
    ports:
      - "8848:8848"

  nacos-a2:
    image: nacos/nacos-server:v2.3.2
    # ... 类似配置

  nacos-a3:
    image: nacos/nacos-server:v2.3.2
    # ... 类似配置

  # 机房B Nacos集群
  nacos-b1:
    image: nacos/nacos-server:v2.3.2
    environment:
      - NACOS_SERVERS=nacos-b1:8848 nacos-b2:8848 nacos-b3:8848
      - MYSQL_SERVICE_HOST=mysql-b
      - MYSQL_SERVICE_PORT=3307
      - MYSQL_SERVICE_DB_NAME=nacos_config
    ports:
      - "8858:8848"

  # ... nacos-b2, nacos-b3
```

#### 3.43.9 面试考察点

**Q1：Nacos在多活架构下为什么要部署双集群而不是跨机房集群？**

> 1. Nacos使用Raft协议进行Leader选举，跨机房部署时Leader选举的投票需要跨机房RT（2-3ms），虽然延迟可接受，但网络分区时存在脑裂风险
> 2. 两个独立集群各自运行Raft，分区时各机房Nacos仍可独立工作，不会脑裂
> 3. 服务双注册确保每个集群都拥有全量实例数据，不依赖集群间数据同步
> 4. 配置数据通过GitOps同步，避免Nacos配置不一致

**Q2：服务双注册会不会导致注册数据翻倍？性能影响如何？**

> 1. 是的，每个实例的注册数据在两个Nacos集群各存一份，存储翻倍
> 2. 但Nacos单集群可支撑10万+实例注册，my-xhs约16个服务×2实例=32个实例，远未达到上限
> 3. 心跳频率默认5秒，双注册意味着双倍心跳，但网络开销极小（每心跳<1KB）
> 4. 真正的瓶颈在于服务发现时的实例列表过滤，需通过区域感知过滤优化

---

### 专题 44：网关与负载均衡多活

#### 3.44.1 功能描述

网关和负载均衡是多活架构的流量调度核心。本专题包含三个核心设计：①Gateway多活路由（在现有GrayRouteFilter基础上扩展，根据`X-Region-Tag`将请求路由到同区域服务实例，故障时自动切换到对端机房）；②Spring Cloud LoadBalancer区域感知负载均衡（优先选择同区域实例，降级时选择对端实例）；③Feign多活拦截器（透传`X-Region-Tag`到下游服务调用，确保全链路区域一致性）。

#### 3.44.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-gateway | 多活路由入口 | RegionRouteFilter：注入区域标记、路由到同区域实例 |
| 所有微服务 | 区域感知消费方 | LoadBalancer区域感知、Feign透传区域标记 |
| my-xhs-common | 组件提供方 | RegionLoadBalancer、RegionFeignInterceptor等 |

#### 3.44.3 Gateway 多活路由设计

##### 3.44.3.1 多活路由Filter链

```
请求进入Gateway：
    │
    ├── 1. RegionRouteFilter（优先级最高）
    │       ├── 从请求Header读取 X-Region-Tag
    │       ├── 如果不存在，根据本机IP判断所属机房，注入 X-Region-Tag
    │       ├── 读取 X-Region-Route 路由策略（默认prefer-local）
    │       └── 将路由策略存入GatewayContext
    │
    ├── 2. AuthFilter（已有）
    │       └── JWT鉴权，不受多活影响
    │
    ├── 3. GrayRouteFilter（已有，需改造）
    │       └── 兼容多活：灰度标记与区域标记可叠加
    │           例如：X-Gray-Tag=v2 + X-Region-Tag=region-a
    │           → 路由到机房A的灰度实例
    │
    ├── 4. RateLimitFilter（已有）
    │       └── 多活限流：按区域维度限流
    │
    └── 5. TraceFilter（已有，需改造）
            └── 透传 X-Region-Tag 到下游
```

##### 3.44.3.2 RegionRouteFilter 核心逻辑

```java
/**
 * 多活路由Filter
 * 核心职责：
 * 1. 判断请求来源机房，注入X-Region-Tag
 * 2. 根据X-Region-Route策略决定路由目标
 * 3. 故障时自动切换流量到对端机房
 */
public class RegionRouteFilter implements GlobalFilter, Ordered {

    // 本机房区域标记（从配置读取）
    @Value("${myxhs.region.current:region-a}")
    private String currentRegion;

    // 对端机房区域标记
    @Value("${myxhs.region.peer:region-b}")
    private String peerRegion;

    // 机房健康状态（从Nacos/健康检查更新）
    private volatile boolean peerRegionHealthy = true;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 获取或注入区域标记
        String regionTag = exchange.getRequest().getHeaders().getFirst("X-Region-Tag");
        if (regionTag == null) {
            regionTag = currentRegion;  // 默认当前机房
            exchange.getRequest().mutate().header("X-Region-Tag", regionTag).build();
        }

        // 2. 获取路由策略
        String routeStrategy = exchange.getRequest().getHeaders()
            .getFirst("X-Region-Route");
        if (routeStrategy == null) {
            routeStrategy = "prefer-local";  // 默认同区域优先
        }

        // 3. 故障切换判断
        if (!peerRegionHealthy && "prefer-local".equals(routeStrategy)) {
            // 对端不健康，强制本机房
            routeStrategy = "force-local";
        }
        if (!isCurrentRegionHealthy() && "prefer-local".equals(routeStrategy)) {
            // 本机房不健康，强制对端
            routeStrategy = "force-remote";
            regionTag = peerRegion;
        }

        // 4. 存入GatewayContext
        exchange.getAttributes().put("regionTag", regionTag);
        exchange.getAttributes().put("regionRouteStrategy", routeStrategy);

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -1;  // 最高优先级，在所有Filter之前执行
    }
}
```

##### 3.44.3.3 故障自动切换设计

```
机房A故障切换流程：

    1. Gateway健康检查线程定时检测本机房服务健康度
       ├── 检测方式：Nacos实例列表健康状态 + 自身服务HTTP健康端点
       └── 判断条件：连续3次检测失败（30秒）

    2. 检测到机房A不可用
       ├── 标记 currentRegionHealthy = false
       ├── RegionRouteFilter自动将流量路由到机房B
       └── 注入 X-Region-Failover: true 标记

    3. 流量切换
       ├── 新请求：路由到机房B实例
       ├── 进行中请求：等待超时后重试到机房B
       └── WebSocket连接：机房B IM实例接管，客户端重连

    4. 机房A恢复后
       ├── 健康检查通过
       ├── 逐步将流量切回机房A（灰度切换，10%→50%→100%）
       └── 摘除 X-Region-Failover 标记
```

#### 3.44.4 负载均衡区域感知设计

##### 3.44.4.1 RegionLoadBalancer 核心逻辑

```java
/**
 * 区域感知负载均衡器
 * 基于 Spring Cloud LoadBalancer 的 ServiceInstanceListSupplier 扩展
 *
 * 核心逻辑：
 * 1. 获取服务实例列表
 * 2. 根据RegionContext中的区域标记，过滤同区域实例
 * 3. 根据路由策略决定是否降级到对端实例
 * 4. 从过滤后的实例列表中执行负载均衡选择
 */
public class RegionLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private final ServiceInstanceListSupplier serviceInstanceListSupplier;
    private final String currentRegion;

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        // 1. 获取所有实例
        return serviceInstanceListSupplier.get()
            .next()
            .map(instances -> {
                // 2. 读取区域上下文
                String targetRegion = RegionContext.getCurrentRegion();
                RegionRouteStrategy strategy = RegionContext.getRouteStrategy();

                // 3. 按区域过滤实例
                List<ServiceInstance> sameRegionInstances = instances.stream()
                    .filter(i -> currentRegion.equals(
                        i.getMetadata().get("region")))
                    .collect(Collectors.toList());

                List<ServiceInstance> otherRegionInstances = instances.stream()
                    .filter(i -> !currentRegion.equals(
                        i.getMetadata().get("region")))
                    .collect(Collectors.toList());

                // 4. 根据策略选择实例
                List<ServiceInstance> candidates;
                switch (strategy) {
                    case FORCE_LOCAL:
                        candidates = sameRegionInstances;
                        if (candidates.isEmpty()) {
                            return new EmptyResponse();  // 不降级
                        }
                        break;
                    case FORCE_REMOTE:
                        candidates = otherRegionInstances;
                        if (candidates.isEmpty()) {
                            return new EmptyResponse();
                        }
                        break;
                    case PREFER_LOCAL:
                    default:
                        candidates = sameRegionInstances.isEmpty()
                            ? otherRegionInstances  // 降级到对端
                            : sameRegionInstances;
                        break;
                }

                // 5. 从候选实例中RoundRobin选择
                int pos = Math.abs(position.incrementAndGet());
                ServiceInstance instance = candidates.get(pos % candidates.size());
                return new DefaultResponse(instance);
            });
    }
}
```

##### 3.44.4.2 LoadBalancer配置

```java
/**
 * 区域感知负载均衡自动配置
 */
@LoadBalancerClient(name = "default", configuration = RegionLoadBalancerConfig.class)
public class RegionLoadBalancerAutoConfiguration {

    @Configuration
    static class RegionLoadBalancerConfig {
        @Bean
        @ConditionalOnMissingBean
        ReactorServiceInstanceLoadBalancer regionLoadBalancer(
                ServiceInstanceListSupplier supplier) {
            return new RegionLoadBalancer(supplier);
        }
    }
}
```

#### 3.44.5 Feign 多活拦截器设计

##### 3.44.5.1 RegionFeignInterceptor

```java
/**
 * Feign多活拦截器
 * 核心职责：将当前线程的区域上下文透传到Feign调用的下游服务
 *
 * 与现有的TraceFeignRequestInterceptor兼容：
 * - TraceFeignInterceptor 透传 X-Trace-Id + X-Gray-Tag
 * - RegionFeignInterceptor 透传 X-Region-Tag + X-Region-Route
 * - 两者可叠加使用，互不干扰
 */
public class RegionFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // 1. 透传区域标记
        String regionTag = RegionContext.getCurrentRegion();
        if (regionTag != null) {
            template.header("X-Region-Tag", regionTag);
        }

        // 2. 透传路由策略
        RegionRouteStrategy strategy = RegionContext.getRouteStrategy();
        if (strategy != null) {
            template.header("X-Region-Route", strategy.name().toLowerCase());
        }

        // 3. 透传故障切换标记
        String failover = RegionContext.isFailover() ? "true" : "false";
        template.header("X-Region-Failover", failover);
    }
}
```

##### 3.44.5.2 Feign拦截器链

```
Feign调用拦截器链：

    RegionFeignInterceptor          ← Phase 7新增：区域标记透传
         ↓
    TraceFeignRequestInterceptor    ← Phase 5已有：TraceId + GrayTag透传
         ↓
    实际HTTP请求
```

#### 3.44.6 Gateway 多活路由规则增强

在现有路由规则基础上，增加区域感知的路由谓词（Predicate）：

```yaml
# gateway-routes-region.yml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service-region-a
          uri: lb://my-xhs-user
          predicates:
            - Path=/api/user/**
            - Header=X-Region-Tag, region-a  # 匹配区域标记
          filters:
            - AddRequestHeader=X-Region-Route, prefer-local

        - id: user-service-region-b
          uri: lb://my-xhs-user
          predicates:
            - Path=/api/user/**
            - Header=X-Region-Tag, region-b
          filters:
            - AddRequestHeader=X-Region-Route, prefer-local
```

> **注意**：实际实现中不使用Predicate路由（会导致路由规则翻倍），而是通过RegionRouteFilter + RegionLoadBalancer在运行时动态决策。以上配置仅为说明意图。

#### 3.44.7 Java 文件清单

**gateway/filter/**

| 文件 | 说明 |
|------|------|
| `RegionRouteFilter.java` | 多活路由Filter（注入区域标记、路由决策、故障切换） |
| `RegionHealthChecker.java` | 机房健康检查器（定时检测本机房/对端机房服务健康度） |
| `RegionFailoverHandler.java` | 故障切换处理器（触发流量切换、通知、灰度恢复） |

**common/loadbalancer/**

| 文件 | 说明 |
|------|------|
| `RegionLoadBalancer.java` | 区域感知负载均衡器（PREFER_LOCAL/FORCE_LOCAL/FORCE_REMOTE） |
| `RegionLoadBalancerAutoConfiguration.java` | 区域负载均衡自动配置 |
| `RegionInstanceListSupplier.java` | 区域实例列表提供者（按区域过滤实例） |

**common/region/interceptor**

| 文件 | 说明 |
|------|------|
| `RegionFeignInterceptor.java` | Feign多活拦截器（透传区域标记到下游） |
| `RegionMqInterceptor.java` | MQ消息拦截器（透传区域标记到MQ消息） |

#### 3.44.8 Redis Key 清单

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `region:health:{region}` | String | 30s | 机房健康状态（UP/DOWN），定时刷新 |
| `region:failover:status` | String | 无 | 故障切换状态（NORMAL/FAILOVER/RECOVERING） |
| `region:failover:start-time` | String | 无 | 故障切换开始时间戳 |
| `region:routing:weight:{region}` | String | 无 | 机房流量权重（用于灰度恢复，如region-a=90,region-b=10） |

#### 3.44.9 面试考察点

**Q1：Gateway多活路由如何实现故障自动切换？**

> 1. RegionHealthChecker定时（10秒）检测本机房和对端机房的核心服务健康度
> 2. 连续3次检测失败后标记机房不可用，触发RegionFailoverHandler
> 3. RegionRouteFilter在路由时发现本机房不可用，自动将流量路由到对端机房
> 4. 切换过程中注入X-Region-Failover标记，下游服务可据此做特殊处理（如降级）
> 5. 恢复时采用灰度策略：10%→50%→100%逐步切回，避免瞬间流量冲击

**Q2：负载均衡的同区域优先策略如何避免请求全部打到同一个实例？**

> 1. RegionLoadBalancer先按区域过滤，得到同区域候选实例列表
> 2. 过滤后的列表通常包含2+个同区域实例（每个服务在每个机房部署2+实例）
> 3. 从候选列表中执行RoundRobin/WeightedRandom负载均衡
> 4. 只有同区域全部实例不可用时，才会降级到对端实例

---

### 专题 45：数据层多活

#### 3.45.1 功能描述

数据层是多活架构最复杂的部分，核心挑战是"跨机房数据一致性"。本专题包含四个核心设计：①MySQL跨机房主主双向同步（每个机房各一个主库，双向复制，基于时间戳解决冲突）；②Redis Cluster跨机房部署（主从跨机房复制，Sentinel故障转移）；③Canal + MQ双向数据同步（Binlog监听→MQ→对端MySQL，实现业务数据双向同步+缓存更新）；④动态数据源路由（基于ShardingSphere扩展，按请求的`X-Region-Tag`路由到对应机房的数据源）。

#### 3.45.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-product | 数据层多活代表 | 商品服务：MySQL跨机房同步、Redis缓存跨机房、Canal同步 |
| my-xhs-order | 分库分表多活代表 | 订单服务：动态数据源按机房路由分库 |
| my-xhs-user | 核心数据多活代表 | 用户服务：MySQL跨机房同步、缓存一致性 |
| my-xhs-content | 搜索数据多活代表 | 笔记服务：MySQL→Canal→ES跨机房同步 |
| my-xhs-common | 组件提供方 | DynamicRegionDataSource、RegionRedisTemplate等 |

#### 3.45.3 MySQL 跨机房主主双向同步

##### 3.45.3.1 架构图

```
┌─────── 机房A ────────┐     ┌─────── 机房B ────────┐
│                       │     │                       │
│  MySQL Master A       │     │  MySQL Master B       │
│  (read+write)         │     │  (read+write)         │
│                       │     │                       │
│  ┌───────────────┐   │     │   ┌───────────────┐   │
│  │ Binlog        │───┼─────┼──►│ Relay Log     │   │
│  │ (A的变更)     │   │     │   │ → SQL Thread  │   │
│  └───────────────┘   │     │   │ → 写入Master B│   │
│                       │     │   └───────────────┘   │
│  ┌───────────────┐   │     │   ┌───────────────┐   │
│  │ Relay Log     │   │     │   │ Binlog        │───┼───►
│  │ ← SQL Thread  │◄──┼─────┼───│ (B的变更)     │   │
│  │ → 写入Master A│   │     │   └───────────────┘   │
│  └───────────────┘   │     │                       │
│                       │     │                       │
└───────────────────────┘     └───────────────────────┘

        ◄── 双向复制 ──►
        同城光纤 RT < 3ms
        复制延迟 < 100ms
```

##### 3.45.3.2 MySQL主主双向复制配置

```ini
# MySQL Master A 配置 (机房A)
[mysqld]
server-id = 1                    # 全局唯一
log-bin = mysql-bin
binlog-format = ROW              # 推荐ROW格式，避免主从数据不一致
auto-increment-increment = 2     # 自增步长=2（两主）
auto-increment-offset = 1        # 起始值=1
                                # Master A的ID: 1,3,5,7...
                                # Master B的ID: 2,4,6,8...

# 双向复制用户
replicate-do-db = my_xhs_product,my_xhs_user,my_xhs_note,my_xhs_order,my_xhs_coupon

# 冲突解决：基于更新时间戳
# 所有表必须有 updated_at 字段
```

```ini
# MySQL Master B 配置 (机房B)
[mysqld]
server-id = 2                    # 全局唯一
log-bin = mysql-bin
binlog-format = ROW
auto-increment-increment = 2     # 自增步长=2
auto-increment-offset = 2        # 起始值=2

replicate-do-db = my_xhs_product,my_xhs_user,my_xhs_note,my_xhs_order,my_xhs_coupon
```

##### 3.45.3.3 冲突解决策略

| 冲突类型 | 场景 | 解决策略 | 实现 |
|----------|------|---------|------|
| INSERT冲突 | 两机房同时插入相同主键 | 自增ID错位（步长=2，offset不同） | `auto-increment-increment=2` |
| UPDATE冲突 | 两机房同时更新同一行 | Last-Write-Wins（基于`updated_at`时间戳） | 应用层对比时间戳，Binlog中取最新 |
| DELETE冲突 | 一机房删除，另一机房更新 | 删除优先（安全策略） | MySQL原生处理 |

##### 3.45.3.4 循环复制问题

MySQL主主双向复制的核心问题：Master A的变更复制到Master B后，Master B的Binlog又会把同样的变更复制回Master A，造成无限循环。

**解决方案**：`log_slave_updates` + `server-id`过滤

```ini
# 两个MySQL实例都配置
log_slave_updates = ON           # 记录从Binlog重放的变更

# MySQL复制SQL线程自动过滤：
# 读取Binlog事件中的server-id
# 如果事件的server-id等于自己的server-id → 跳过（说明是自己产生的变更被回放）
# 如果事件的server-id不等于自己的server-id → 执行
```

#### 3.45.3.5 MySQL Group Replication（MGR）方案对比（⚠️ 深水区）

> 文档前面选择了传统主主双向复制，但**MySQL Group Replication（MGR）是多活架构下MySQL的另一主流方案**，必须了解其优劣才能做出合理选型。

| 维度 | 主主双向复制 | MySQL Group Replication (MGR) |
|------|-------------|-------------------------------|
| **一致性** | 最终一致（异步复制） | 强一致（Paxos协议认证） |
| **冲突检测** | 无（需应用层解决，基于时间戳） | 自动检测写冲突并回滚（Certification） |
| **脑裂防护** | 🟡 无内置防护，需外部仲裁（MHA） | 🟢 Paxos Quorum自动防护，少数派不可写 |
| **性能** | 🟢 高（异步写入，无跨机房RT） | 🟡 中（需Paxos Quorum认证，RT+2-3ms） |
| **数据丢失** | 🟡 异步复制可能丢失<1s数据 | 🟢 Quorum写入确保RPO=0 |
| **运维复杂度** | 🟢 低（MySQL原生复制，成熟稳定） | 🟡 高（需管理Group、处理成员变更） |
| **MySQL版本** | 🟢 5.6+均支持 | ⚠️ 5.7.17+，推荐8.0+ |
| **限制** | 需手动处理冲突 | 单Group最多9节点、大事务性能差、DDL非事务性 |
| **适用场景** | 读多写少、容忍短暂不一致 | 写冲突多、要求强一致 |

**MGR核心工作原理**：

```
┌─────────────────────────────────────────────────┐
│           MySQL Group Replication                │
│                                                  │
│  MySQL-A          MySQL-B          仲裁节点       │
│  (Primary)        (Secondary)      (Secondary)   │
│     │                 │                │          │
│     └──── Paxos ◄────┘───────────────┘          │
│           协议通信                                 │
│                                                  │
│  写入流程：                                       │
│  1. Client → MySQL-A 提交事务                     │
│  2. MySQL-A 将事务写入Binlog                      │
│  3. Paxos协议广播事务到所有成员                     │
│  4. Certification阶段：全局序号排序，检测冲突       │
│  5. 无冲突 → 所有成员Commit                       │
│     冲突 → 冲突事务在发起方Rollback               │
│  6. Quorum（>N/2）确认 → 返回客户端成功            │
└─────────────────────────────────────────────────┘
```

**my-xhs选型理由：选择主主双向复制而非MGR**

1. **my-xhs写冲突概率极低**：同城双活下，同一行数据同时被两个机房更新的概率极小（同一用户的写操作路由到同一机房）
2. **性能优先**：异步复制写入RT更低，适合互联网业务的高并发写入
3. **运维简单**：主主复制是MySQL最成熟的功能，运维工具链完善
4. **MGR适合金融场景**：银行、支付等对一致性要求极高的场景，写冲突多且不可接受
5. **可演进**：如果未来业务需要强一致，可将P0核心服务（订单/支付）迁移到MGR

#### 3.45.3.6 半同步复制 vs 异步复制选型（⚠️ 深水区）

> 文档前面默认使用异步复制，但**半同步复制是降低RPO的关键手段**，同城双活场景下RT增加完全可接受。

| 维度 | 异步复制 | 半同步复制（rpl_semi_sync） |
|------|---------|---------------------------|
| **写入RT** | 本地写入即返回（~1ms） | 等待至少1个Slave ACK才返回（本地+2-3ms） |
| **RPO** | 可能丢失<1s数据 | **RPO≈0**（至少1个Slave已收到Binlog） |
| **可用性** | 🟢 高（Master宕机不影响写入） | 🟡 中（Slave全部宕机时降级为异步） |
| **数据安全** | 🟡 主库宕机可能丢数据 | 🟢 主库宕机不丢已确认数据 |

**半同步复制配置（P0核心服务推荐）**：

```ini
# MySQL Master 配置（订单/支付/库存服务的MySQL）
[mysqld]
# 启用半同步复制
plugin-load = "rpl_semi_sync_master=semisync_master.so;rpl_semi_sync_slave=semisync_slave.so"

# Master端配置
rpl_semi_sync_master_enabled = 1           # 启用半同步Master
rpl_semi_sync_master_timeout = 5000        # 5秒超时后降级为异步
rpl_semi_sync_master_wait_for_slave_count = 1  # 至少1个Slave确认
rpl_semi_sync_master_wait_point = AFTER_SYNC   # AFTER_SYNC模式（先写Relay Log再Commit）

# Slave端配置
rpl_semi_sync_slave_enabled = 1            # 启用半同步Slave
```

**半同步复制降级策略**：

```
正常：Master写入 → 等待Slave ACK → 返回客户端（RT +2-3ms）
降级：Master写入 → 5秒内无ACK → 降级为异步复制 → 返回客户端
恢复：Slave恢复 → 自动切回半同步

AFTER_SYNC vs AFTER_COMMIT：
  AFTER_SYNC（推荐）：先写Slave Relay Log → 再Commit Master → 返回客户端
    → 优点：客户端读到的数据一定已同步到Slave，主库宕机不丢数据
  AFTER_COMMIT：先Commit Master → 等待Slave ACK → 返回客户端
    → 缺点：主库Commit后宕机，Slave可能没收到，数据丢失
```

**my-xhs推荐配置**：

| 服务等级 | 复制方式 | 理由 |
|----------|---------|------|
| P0（订单/支付/库存） | **半同步复制** | 同城RT<3ms，半同步RT增加可接受，RPO≈0 |
| P1（用户/商品/购物车/优惠券） | 异步复制 | 写冲突概率低，异步即可，RT更优 |
| P2/P3 | 异步复制 | 非核心数据，异步够用 |

#### 3.45.3.7 GTID 在双向复制中的关键作用（⚠️ 深水区）

> 文档前面只提了 `server-id` 过滤解决循环复制，但**GTID（Global Transaction Identifier）是更现代、更安全的复制方案**。

**GTID是什么**：

```
GTID = server_uuid:transaction_id
例如：3E11FA47-71CA-11E1-9E33-C80AA9429562:1-5

含义：server_uuid为3E11FA47的MySQL实例，已执行的事务序号1-5
```

**GTID vs 传统Binlog位点复制对比**：

| 维度 | 传统Binlog位点复制 | GTID复制 |
|------|------------------|---------|
| **位点指定** | `CHANGE MASTER TO MASTER_LOG_FILE='mysql-bin.000003', MASTER_LOG_POS=73` | `CHANGE MASTER TO MASTER_AUTO_POSITION=1` |
| **故障恢复** | 需手动查找新位点，容易出错 | 自动找到断点续传，无需人工干预 |
| **循环复制** | 依赖 `server-id` 过滤 | GTID天然防重复执行（已执行的GTID自动跳过） |
| **一致性验证** | 困难（需对比Binlog位点） | 简单（对比两端的 `gtid_executed` 即可） |
| **Canal集成** | Canal需维护Binlog位点 | Canal可基于GTID记录同步进度，更精准 |

**GTID配置**：

```ini
# MySQL 双向复制GTID配置（两个Master都配置）
[mysqld]
gtid_mode = ON                    # 启用GTID
enforce_gtid_consistency = ON     # 强制GTID一致性（不允许非事务性操作影响GTID）
log_slave_updates = ON            # 记录从Binlog重放的变更（GTID必须）

# 双向复制配置（使用GTID自动定位）
-- Master A → Master B 的复制通道
CHANGE MASTER TO
    MASTER_HOST = 'mysql-b',
    MASTER_PORT = 3307,
    MASTER_USER = 'repl',
    MASTER_PASSWORD = 'repl_pwd',
    MASTER_AUTO_POSITION = 1;     -- 关键：使用GTID自动定位，无需指定Binlog位点

-- Master B → Master A 的复制通道
CHANGE MASTER TO
    MASTER_HOST = 'mysql-a',
    MASTER_PORT = 3306,
    MASTER_USER = 'repl',
    MASTER_PASSWORD = 'repl_pwd',
    MASTER_AUTO_POSITION = 1;
```

**GTID在多活场景的关键优势**：

1. **天然防循环复制**：GTID已执行的事务不会重复执行，比 `server-id` 过滤更可靠
2. **故障恢复自动化**：Master宕机切换后，新Master的GTID连续，无需手动找位点
3. **数据一致性校验**：`SELECT @@GLOBAL.GTID_EXECUTED` 对比两端的GTID集合，秒级发现不一致
4. **Canal同步精准**：Canal基于GTID记录消费进度，故障恢复后从断点GTID继续，不错过不重复

**GTID一致性检查脚本**：

```sql
-- 检查两个MySQL实例的数据一致性
-- 在任一MySQL上执行：
SELECT @@GLOBAL.GTID_EXECUTED;

-- 对比两个实例的GTID集合：
-- 如果 GTID_EXECUTED 完全一致 → 数据完全一致
-- 如果有差异 → 找出缺失的GTID，通过Canal补偿同步
```

#### 3.45.4 Redis 跨机房部署

##### 3.45.4.1 Redis跨机房架构

```
┌─────── 机房A ────────┐     ┌─────── 机房B ────────┐
│                       │     │                       │
│  Redis Master A       │────►│  Redis Slave B        │
│  (read+write)         │复制  │  (read only)          │
│                       │     │                       │
│  Sentinel A1          │     │  Sentinel B1          │
│  Sentinel A2          │     │  Sentinel B2          │
│  Sentinel A3          │     │  Sentinel B3          │
│                       │     │                       │
└───────────────────────┘     └───────────────────────┘

正常情况：
  - 写操作 → Redis Master A（机房A）
  - 读操作 → Redis Master A（机房A本读） / Redis Slave B（机房B本读）

Master A宕机时：
  - Sentinel投票选举新Master
  - 如果Slave B被提升为Master → 机房B成为写主
  - 机房A恢复后变为Slave

注意：Redis主从复制是异步的！
  - 机房B的Slave有<1ms的延迟（同城）
  - 极端情况下Master A宕机可能丢失最后<1s的写操作
```

##### 3.45.4.2 Redis区域感知路由

```java
/**
 * 区域感知Redis模板
 * 核心逻辑：
 * - 写操作：路由到Redis Master（无论在哪个机房）
 * - 读操作：优先读本机房的Redis实例
 *         本机房是Master → 直接读Master
 *         本机房是Slave → 读Slave
 */
public class RegionRedisTemplate extends StringRedisTemplate {

    private final String currentRegion;
    private volatile String masterRegion;

    /**
     * 写操作路由到Master节点
     * Spring Data Redis的Lettuce默认就会路由写操作到Master
     * 这里只需确保连接配置包含Master和Slave
     */
    @Override
    public void afterPropertiesSet() {
        // Lettuce自动感知Master/Slave拓扑
        // 通过Sentinel模式连接，自动路由
        super.afterPropertiesSet();
    }
}
```

**Redis Sentinel配置**：

```yaml
# sentinel.conf（6个Sentinel节点共用同一配置）
sentinel monitor mymaster redis-master-a 6379 2   # 至少2个Sentinel同意才故障转移
sentinel down-after-milliseconds mymaster 10000    # 10秒无响应判定主观下线
sentinel failover-timeout mymaster 60000           # 故障转移超时60秒
sentinel parallel-syncs mymaster 1                 # 故障转移后1个Slave同时同步
```

##### 3.45.4.3 Redis缓存一致性保障

| 场景 | 方案 | 说明 |
|------|------|------|
| 写入缓存 | 写本机房Master → 主从复制到对端 | 异步复制，<1ms延迟 |
| 删除缓存 | 删除本机房Master → 主从复制到对端 | 同上 |
| Canal异步兜底 | Binlog→Canal→MQ→更新两端缓存 | Phase 5已有，多活下继续使用 |
| 缓存重建 | 故障切换后，Slave升Master，缓存从DB重建 | 缓存可丢失，回源DB |

#### 3.45.5 Canal + MQ 双向数据同步

##### 3.45.5.1 架构图

```
┌─────────── 机房A ───────────┐     ┌─────────── 机房B ───────────┐
│                              │     │                              │
│  MySQL Master A              │     │  MySQL Master B              │
│       │                      │     │       │                      │
│       ▼                      │     │       ▼                      │
│  Canal A (监听A的Binlog)     │     │  Canal B (监听B的Binlog)     │
│       │                      │     │       │                      │
│       ▼                      │     │       ▼                      │
│  RocketMQ Broker A           │     │  RocketMQ Broker B           │
│  Topic: canal-sync-a-to-b    │     │  Topic: canal-sync-b-to-a    │
│       │                      │     │       │                      │
│       │    ┌─────────────────┼─────┼──┐    │                      │
│       │    │ MQ消费（跨机房）  │     │  │    │                      │
│       │    │                 │     │  │    │                      │
│       ▼    ▼                 │     │  ▼    ▼                      │
│  Consumer-A（消费B的Binlog） │     │  Consumer-B（消费A的Binlog） │
│       │                      │     │       │                      │
│       ▼                      │     │       ▼                      │
│  写入MySQL Master A          │     │  写入MySQL Master B          │
│  + 更新Redis缓存A            │     │  + 更新Redis缓存B            │
│  + 更新ES索引A               │     │  + 更新ES索引B               │
│                              │     │                              │
└──────────────────────────────┘     └──────────────────────────────┘
```

##### 3.45.5.2 循环同步问题

与MySQL双向复制类似，Canal双向同步也存在循环问题：A→B→A→B...

**解决方案：Canal标记过滤**

```java
/**
 * Canal消息消费者
 * 核心逻辑：
 * 1. Canal写入MQ时，在Message Header中标记来源机房
 * 2. 消费者消费时，判断来源机房是否为自己
 * 3. 如果来源是自己 → 跳过（避免循环消费）
 * 4. 如果来源是对端 → 执行同步
 */
@Component
public class CanalSyncConsumer {

    @Value("${myxhs.region.current}")
    private String currentRegion;

    /**
     * 消费对端机房的Binlog变更
     */
    @RocketMQMessageListener(
        topic = "canal-sync-${myxhs.region.peer}-to-${myxhs.region.current}",
        consumerGroup = "canal-sync-consumer-${myxhs.region.current}"
    )
    public void onMessage(MessageExt msg) {
        // 1. 检查来源标记
        String sourceRegion = msg.getProperty("source-region");
        if (currentRegion.equals(sourceRegion)) {
            // 来自本机房 → 跳过，避免循环
            return;
        }

        // 2. 解析Binlog数据
        CanalEntry.RowData rowData = parseCanalData(msg);

        // 3. 写入本机房MySQL
        syncToMySQL(rowData);

        // 4. 更新本机房Redis缓存
        syncToRedis(rowData);

        // 5. 更新本机房ES索引
        syncToES(rowData);
    }
}
```

##### 3.45.5.3 Canal同步场景清单

| 源 | 目标 | 同步内容 | Topic | 消费者 |
|----|------|---------|-------|--------|
| MySQL A Binlog | MySQL B + Redis B + ES B | 商品/用户/笔记变更 | `canal-sync-a-to-b` | 机房B的Consumer |
| MySQL B Binlog | MySQL A + Redis A + ES A | 商品/用户/笔记变更 | `canal-sync-b-to-a` | 机房A的Consumer |
| MySQL A Binlog | ES A（本机房） | 笔记索引增量同步 | `canal-note-es-sync` | 机房A的ES Consumer |

> **注意**：MySQL主主双向复制已经保证数据双向同步，Canal + MQ的作用是**额外的缓存/ES同步**，以及作为MySQL复制的**异步兜底**。如果MySQL双向复制正常，Canal主要承担缓存和ES的同步；如果MySQL复制延迟或中断，Canal可以作为数据补偿通道。

#### 3.45.5.4 跨机房分布式锁设计（⚠️ 深水区）

> 多活架构下分布式锁面临新挑战：Redis主从切换时锁可能丢失，跨机房锁延迟增加。

**问题1：Redis主从切换导致锁丢失**

```
场景：
  1. Client-A 在 Redis Master A 获取锁 lock:order:123 → 成功
  2. Redis Master A 宕机，Sentinel 选举 Slave B 为新 Master
  3. Slave B 尚未同步 lock:order:123 的数据（异步复制延迟）
  4. Client-B 在新 Master B 获取锁 lock:order:123 → 成功！
  5. 两个Client同时持有锁 → 数据不一致！

解决方案：min-replicas-to-write
  Redis配置：min-replicas-to-write=1
  → Master至少有1个Slave连接时才接受写入
  → Master A宕机前，锁数据已同步到Slave B
  → 新Master B拥有锁数据，Client-B获取锁会被拒绝
```

**问题2：Redlock 算法在跨机房场景的讨论**

| 方案 | 实现 | 优点 | 缺点 | 结论 |
|------|------|------|------|------|
| 单Redis实例锁 | `SET key value NX EX ttl` | 简单、低RT | 主从切换丢锁 | ❌ 不够安全 |
| Redlock | 向N个独立Redis实例获取锁，>N/2成功才算获取 | 防单点丢锁 | RT增加×N、争议大 | 🟡 理论争议 |
| **单Redis + min-replicas-to-write** | 配合 `wait` 命令确保同步 | 简单且安全 | 需Redis 3.0+ | ✅ 推荐 |
| ZooKeeper/etcd 分布式锁 | ZK临时节点 / etcd Lease | 强一致 | RT高（跨机房5-10ms） | 🟡 备选 |

**my-xhs推荐方案：单Redis + min-replicas-to-write + wait**

```java
/**
 * 跨机房安全分布式锁
 *
 * 安全保障链：
 * 1. SET key value NX EX ttl  → 获取锁
 * 2. WAIT 1 5000              → 等待1个Slave ACK，最多5秒
 * 3. min-replicas-to-write=1  → 确保锁数据已同步到Slave
 *
 * 这样即使Master宕机，新Master也一定有锁数据
 */
@Component
public class RegionDistributedLock {

    private final StringRedisTemplate redisTemplate;
    private final String currentRegion;

    /**
     * 获取分布式锁（跨机房安全）
     *
     * @param lockKey  锁Key
     * @param requestId 请求唯一ID（用于安全释放锁）
     * @param expireSeconds 过期时间
     * @param waitReplicas 等待同步的Slave数量（推荐1）
     * @return 是否获取成功
     */
    public boolean tryLock(String lockKey, String requestId,
                           int expireSeconds, int waitReplicas) {
        // 1. 获取锁
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, requestId, Duration.ofSeconds(expireSeconds));
        if (Boolean.FALSE.equals(locked)) {
            return false;
        }

        // 2. 等待同步到Slave（确保主从切换后锁不丢失）
        if (waitReplicas > 0) {
            try {
                // 使用Lettuce的Wait命令
                // WAIT waitReplicas timeoutMs
                Long replicated = redisTemplate.execute(
                    (RedisCallback<Long>) connection ->
                        connection.serverCommands().execute("WAIT",
                            String.valueOf(waitReplicas).getBytes(),
                            "5000".getBytes())
                );
                // WAIT返回值：已同步的Slave数量
                // 如果 < waitReplicas → 锁可能不安全，但仍返回成功
                // （降级策略：宁可降级也不阻塞业务）
                log.info("Lock replicated to {} slaves (required: {})",
                    replicated, waitReplicas);
            } catch (Exception e) {
                // WAIT失败不影响锁获取（降级为普通锁）
                log.warn("WAIT command failed, lock may not be replicated", e);
            }
        }

        return true;
    }

    /**
     * 释放锁（Lua脚本保证原子性）
     */
    public boolean unlock(String lockKey, String requestId) {
        String script =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";
        Long result = redisTemplate.execute(
            new DefaultRedisScript<>(script, Long.class),
            Collections.singletonList(lockKey),
            requestId
        );
        return Long.valueOf(1L).equals(result);
    }
}
```

**跨机房锁的路由策略**：

```
写操作分布式锁：
  - 只在Redis Master所在机房执行锁操作
  - 机房A服务 → 锁操作 → Redis Master A（低延迟）
  - 机房B服务 → 锁操作 → Redis Master A（跨机房延迟2-3ms，可接受）
  - 故障切换后 → Redis Master B → 机房B服务本地执行锁操作

读操作：
  - 无需分布式锁，正常读取即可
```

#### 3.45.5.5 数据校验与对账机制（⚠️ 深水区）

> MySQL双向同步 + Canal双向同步都有可能产生数据不一致，**必须建立数据校验与对账机制**来发现和修复不一致。

**数据不一致的根因分析**：

| 根因 | 概率 | 影响 | 检测难度 |
|------|------|------|---------|
| MySQL异步复制延迟中Master宕机 | 低 | 丢失<1s数据 | 🟡 需GTID对比 |
| Canal消息消费失败/重复 | 中 | 数据缺失或重复 | 🟢 消费位点对比 |
| UPDATE冲突导致Last-Write-Wins覆盖 | 低 | 丢失更新 | 🟡 需冲突记录表 |
| 人为误操作（直接改DB） | 中 | 不可预期 | 🔴 难以自动检测 |

**三层对账体系**：

```
第一层：实时增量对账（秒级发现）
  ┌──────────────────────────────────────────┐
  │  MySQL Binlog → Canal → 对账服务          │
  │                                          │
  │  每条Binlog变更：                         │
  │  1. 记录变更的 主键 + updated_at + 源机房  │
  │  2. 查询对端同主键的 updated_at            │
  │  3. 如果对端 updated_at < 本端 → 正常同步  │
  │  4. 如果对端 updated_at > 本端 → 冲突告警  │
  │  5. 如果对端无记录 → 缺失告警              │
  └──────────────────────────────────────────┘

第二层：分钟级批量对账（1分钟一轮）
  ┌──────────────────────────────────────────┐
  │  定时任务：每分钟对比两机房热数据           │
  │                                          │
  │  1. 查询最近1分钟更新的记录（按updated_at） │
  │  2. 按主键分批计算CRC32哈希               │
  │  3. 对比两端哈希值                        │
  │  4. 哈希不一致 → 记录差异 → 告警          │
  │                                          │
  │  适用：P0核心表（订单、支付、库存）         │
  └──────────────────────────────────────────┘

第三层：每日全量对账（凌晨低峰期）
  ┌──────────────────────────────────────────┐
  │  大数据量全量对账：                        │
  │                                          │
  │  1. 按主键范围分片（每片1万行）            │
  │  2. 每片计算所有字段的CRC32哈希           │
  │  3. 对比两端哈希                          │
  │  4. 哈希不一致 → 导出差异明细 → 人工复核   │
  │                                          │
  │  适用：所有P0+P1表                        │
  └──────────────────────────────────────────┘
```

**对账差异处理流程**：

```
发现差异
    │
    ├── 自动修复（占90%）
    │   ├── 规则：以 updated_at 较新的一端为准
    │   ├── 自动更新旧数据端
    │   └── 记录修复日志到 data_reconciliation_log
    │
    ├── 人工介入（占9%）
    │   ├── 自动修复规则无法判定（updated_at相同但数据不同）
    │   ├── 生成差异报告 → DBA复核 → 手动修复
    │   └── 状态标记为 PENDING_MANUAL
    │
    └── 告警升级（占1%）
        ├── 差异量超过阈值（>100条/分钟）
        ├── 可能是同步链路故障
        └── 触发P0告警 → 值班人员立即处理
```

**对账数据库表设计**：

```sql
-- 数据对账差异记录表
CREATE TABLE data_reconciliation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id VARCHAR(50) NOT NULL COMMENT '对账批次ID',
    source_region VARCHAR(20) NOT NULL COMMENT '数据较新一端的机房',
    table_name VARCHAR(100) NOT NULL COMMENT '表名',
    row_id VARCHAR(100) NOT NULL COMMENT '差异行主键',
    field_name VARCHAR(100) COMMENT '差异字段名',
    region_a_value JSON COMMENT '机房A的值',
    region_b_value JSON COMMENT '机房B的值',
    region_a_updated_at DATETIME(3) COMMENT '机房A更新时间',
    region_b_updated_at DATETIME(3) COMMENT '机房B更新时间',
    resolution VARCHAR(20) DEFAULT 'AUTO' COMMENT '解决方式（AUTO/MANUAL/ALERT）',
    status VARCHAR(20) DEFAULT 'RESOLVED' COMMENT '状态（RESOLVED/PENDING_MANUAL/ALERTED）',
    resolved_at DATETIME(3) COMMENT '解决时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_batch (batch_id),
    INDEX idx_table_row (table_name, row_id),
    INDEX idx_status (status)
) ENGINE=InnoDB COMMENT='数据对账差异记录表';

-- 对账批次记录表
CREATE TABLE data_reconciliation_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id VARCHAR(50) NOT NULL COMMENT '批次ID',
    reconcile_type VARCHAR(20) NOT NULL COMMENT '对账类型（REALTIME/BATCH/FULL）',
    table_name VARCHAR(100) NOT NULL COMMENT '表名',
    total_count BIGINT DEFAULT 0 COMMENT '对比总行数',
    diff_count BIGINT DEFAULT 0 COMMENT '差异行数',
    auto_resolved BIGINT DEFAULT 0 COMMENT '自动修复数',
    manual_pending BIGINT DEFAULT 0 COMMENT '待人工处理数',
    start_time DATETIME(3) NOT NULL COMMENT '开始时间',
    end_time DATETIME(3) COMMENT '结束时间',
    status VARCHAR(20) DEFAULT 'RUNNING' COMMENT '状态（RUNNING/COMPLETED/FAILED）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_batch_id (batch_id)
) ENGINE=InnoDB COMMENT='对账批次记录表';
```

**Java文件清单（对账组件）**：

| 文件 | 说明 |
|------|------|
| `DataReconciliationService.java` | 对账服务（实时增量 + 定时批量 + 全量对账） |
| `ReconciliationJob.java` | 定时对账任务（Spring Scheduled，1分钟/每日凌晨） |
| `Crc32RowHasher.java` | 行数据CRC32哈希计算器 |
| `DiffResolver.java` | 差异自动修复器（Last-Write-Wins策略） |
| `ReconciliationAlertService.java` | 对账告警服务（差异超阈值触发P0告警） |

#### 3.45.6 动态数据源路由

##### 3.45.6.1 架构设计

```
请求携带 X-Region-Tag=region-a
         │
         ▼
┌─────────────────────────────┐
│  DynamicRegionDataSource    │
│  (extends AbstractRoutingDS)│
│                             │
│  ┌─────────┐  ┌─────────┐  │
│  │region-a │  │region-b │  │
│  │DataSource│  │DataSource│  │
│  │         │  │         │  │
│  │┌───────┐│  │┌───────┐│  │
│  ││Sharding││  ││Sharding││  │
│  ││Sphere  ││  ││Sphere  ││  │
│  ││  ds-0  ││  ││  ds-0  ││  │
│  ││  ds-1  ││  ││  ds-1  ││  │
│  │└───────┘│  │└───────┘│  │
│  └─────────┘  └─────────┘  │
└─────────────────────────────┘
         │
         ▼
根据RegionContext路由到对应机房的DataSource
```

##### 3.45.6.2 DynamicRegionDataSource 实现

```java
/**
 * 多活动态数据源
 * 基于 Spring AbstractRoutingDataSource
 * 根据 RegionContext 中的区域标记路由到对应机房的数据源
 *
 * 与 ShardingSphere 兼容：
 * - 外层：DynamicRegionDataSource 选择机房
 * - 内层：ShardingSphere 选择分片
 * - 嵌套顺序：RegionDataSource → ShardingDataSource → 实际MySQL
 */
public class DynamicRegionDataSource extends AbstractRoutingDataSource {

    private static final String REGION_A = "region-a";
    private static final String REGION_B = "region-b";

    @Override
    protected Object determineCurrentLookupKey() {
        String region = RegionContext.getCurrentRegion();
        if (region == null) {
            // 默认本机房
            region = RegionHelper.getCurrentRegion();
        }
        return region;
    }

    /**
     * 写操作：路由到本机房数据源（写本机房主库，MySQL双向复制到对端）
     * 读操作：路由到本机房数据源（读本机房从库或主库）
     *
     * 注意：写操作不会跨机房，每个机房写自己的主库，
     *       MySQL双向复制负责数据同步
     */
}
```

##### 3.45.6.3 数据源配置

```yaml
# application-region-a.yml
myxhs:
  region:
    current: region-a
    peer: region-b

spring:
  datasource:
    # 机房A数据源（本机房）
    region-a:
      jdbc-url: jdbc:mysql://mysql-a:3306/my_xhs_order_0?useSSL=false
      username: root
      password: root
      driver-class-name: com.mysql.cj.jdbc.Driver
    # 机房B数据源（对端）
    region-b:
      jdbc-url: jdbc:mysql://mysql-b:3307/my_xhs_order_0?useSSL=false
      username: root
      password: root
      driver-class-name: com.mysql.cj.jdbc.Driver
```

#### 3.45.7 分库分表多活数据源

订单服务和优惠券服务已使用ShardingSphere分库分表，多活下需要在分库分表外层再加一层区域数据源路由：

```
请求 X-Region-Tag=region-a
         │
         ▼
DynamicRegionDataSource（选择机房）
         │
         ▼ region-a DataSource
ShardingSphere（选择分片）
         │
         ▼ ds-0 或 ds-1
实际MySQL连接（机房A的分库）
```

#### 3.45.8 数据层多活场景验证清单

| # | 场景 | 验证方法 | 预期结果 |
|---|------|---------|---------|
| 1 | 机房A写入，机房B读取 | 机房A写入商品→机房B查询 | 机房B可读到最新数据（延迟<500ms） |
| 2 | 双机房同时更新同一行 | 机房A/B同时更新商品价格 | Last-Write-Wins，最终一致 |
| 3 | 机房A MySQL宕机 | Kill机房A MySQL | 写入切换到机房B，Canal继续同步 |
| 4 | 机房A Redis Master宕机 | Kill机房A Redis Master | Sentinel故障转移，机房B Slave升Master |
| 5 | Canal同步延迟 | 制造大量写入 | 延迟在可接受范围（<5s），最终一致 |
| 6 | 动态数据源切换 | 修改X-Region-Tag | 请求路由到对应机房数据源 |

#### 3.45.9 Java 文件清单

**common/datasource/**

| 文件 | 说明 |
|------|------|
| `DynamicRegionDataSource.java` | 多活动态数据源（根据区域路由数据源） |
| `DynamicRegionDataSourceAutoConfiguration.java` | 自动配置类 |
| `RegionDataSourceProperties.java` | 多机房数据源配置属性 |

**common/redis/**

| 文件 | 说明 |
|------|------|
| `RegionRedisTemplate.java` | 区域感知Redis模板 |
| `RegionRedisAutoConfiguration.java` | 区域Redis自动配置 |

**canal/consumer**

| 文件 | 说明 |
|------|------|
| `CanalSyncConsumer.java` | Canal双向同步消费者（消费对端Binlog→写入本端+更新缓存/ES） |
| `CanalSyncMessage.java` | Canal同步消息DTO |
| `CanalSyncExceptionHandler.java` | Canal同步异常处理（死信队列+告警） |

#### 3.45.10 数据库表变更

##### 3.45.10.1 所有表增加冲突解决字段

```sql
-- 多活架构要求所有业务表包含以下字段（已有表需补齐）
ALTER TABLE {table_name}
ADD COLUMN updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间戳（毫秒精度，用于多活冲突解决）';

-- updated_at 精度必须为毫秒（DATETIME(3)），避免同一秒内的更新无法区分先后
```

##### 3.45.10.2 Canal同步位点记录表

```sql
-- Canal同步位点记录表（每个机房各自维护）
CREATE TABLE canal_sync_position (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_region VARCHAR(20) NOT NULL COMMENT '来源机房',
    destination_region VARCHAR(20) NOT NULL COMMENT '目标机房',
    binlog_file VARCHAR(100) NOT NULL COMMENT 'Binlog文件名',
    binlog_position BIGINT NOT NULL COMMENT 'Binlog位点',
    gtid VARCHAR(200) COMMENT 'GTID',
    last_sync_time DATETIME(3) NOT NULL COMMENT '最后同步时间',
    synced_count BIGINT DEFAULT 0 COMMENT '已同步记录数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_region_pair (source_region, destination_region)
) ENGINE=InnoDB COMMENT='Canal同步位点记录表';
```

##### 3.45.10.3 冲突记录表

```sql
-- 数据同步冲突记录表
CREATE TABLE data_sync_conflict (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_region VARCHAR(20) NOT NULL COMMENT '冲突来源机房',
    table_name VARCHAR(100) NOT NULL COMMENT '冲突表名',
    row_id VARCHAR(100) NOT NULL COMMENT '冲突行主键',
    local_updated_at DATETIME(3) NOT NULL COMMENT '本端更新时间',
    remote_updated_at DATETIME(3) NOT NULL COMMENT '对端更新时间',
    resolution VARCHAR(20) NOT NULL COMMENT '解决策略（LOCAL_WIN/REMOTE_WIN/MANUAL）',
    local_data JSON COMMENT '本端数据快照',
    remote_data JSON COMMENT '对端数据快照',
    status VARCHAR(20) DEFAULT 'RESOLVED' COMMENT '状态（RESOLVED/PENDING_MANUAL）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_table_row (table_name, row_id),
    INDEX idx_status (status)
) ENGINE=InnoDB COMMENT='数据同步冲突记录表';
```

#### 3.45.11 面试考察点

**Q1：MySQL主主双向复制如何解决循环复制问题？**

> 1. MySQL的Binlog事件中携带`server-id`，标识产生该事件的MySQL实例
> 2. SQL线程在重放Relay Log时，会检查事件的`server-id`
> 3. 如果事件的`server-id`等于自己的`server-id`，说明这是自己产生的变更被回放，跳过
> 4. 通过这种机制，A→B→A的循环在第二层就会被自动过滤

**Q2：Canal双向同步如何避免循环？与MySQL双向复制的循环问题有何不同？**

> 1. Canal双向同步的循环问题：A的Binlog→Canal→MQ→写入B→B的Binlog→Canal→MQ→写入A→...
> 2. 解决方案：在MQ消息的Header中标记`source-region`（来源机房）
> 3. 消费者消费时判断：如果`source-region`等于本机房 → 跳过
> 4. 与MySQL的区别：MySQL通过`server-id`自动过滤，Canal需要应用层手动标记和过滤

**Q3：动态数据源在分库分表场景下如何工作？**

> 1. 嵌套数据源：DynamicRegionDataSource（外层选机房）→ ShardingDataSource（内层选分片）→ 实际MySQL
> 2. Spring的AbstractRoutingDataSource支持嵌套，外层DataSource返回的连接是内层DataSource
> 3. 路由顺序：先根据RegionContext选择机房DataSource → 再根据ShardingSphere分片键选择具体分库
> 4. 每个机房有独立的分库分表拓扑，数据通过MySQL双向复制保持一致

**Q4：为什么不用MySQL Group Replication而用主主双向复制？**

> 1. MGR基于Paxos协议提供强一致性，自动检测写冲突，但写入需要Quorum认证，RT增加2-3ms
> 2. my-xhs写冲突概率极低（同城双活下同一行同时被两个机房更新的概率极小），MGR的冲突检测能力用不上
> 3. 主主双向复制更成熟稳定，运维工具链完善，团队技术栈匹配
> 4. 异步复制写入RT更低，适合互联网业务的高并发写入场景
> 5. MGR更适合金融场景（银行/支付），写冲突多且不可接受
> 6. 如果未来业务需要强一致，可将P0核心服务迁移到MGR，两者可混合部署

**Q5：半同步复制 vs 异步复制，多活场景怎么选？**

> 1. 异步复制：Master写入即返回，RT低但可能丢失<1s数据（RPO>0）
> 2. 半同步复制：Master等待至少1个Slave ACK才返回，RT增加2-3ms但RPO≈0
> 3. 同城双活RT<3ms，半同步的额外延迟完全可接受
> 4. 推荐策略：P0核心服务（订单/支付/库存）用半同步复制，P1/P2用异步复制
> 5. 半同步有降级机制：Slave全部宕机时5秒超时自动降级为异步，不阻塞业务
> 6. AFTER_SYNC模式优于AFTER_COMMIT：客户端读到的数据一定已同步到Slave

**Q6：GTID在双向复制中有什么优势？**

> 1. GTID为每个事务分配全局唯一ID，天然防循环复制（已执行的GTID自动跳过）
> 2. 比传统 `server-id` 过滤更可靠：server-id过滤依赖log_slave_updates，GTID是MySQL内核级别保证
> 3. 故障恢复自动化：Master宕机切换后，`MASTER_AUTO_POSITION=1` 自动找到断点续传，无需手动指定Binlog位点
> 4. 数据一致性校验：对比两端的 `@@GLOBAL.GTID_EXECUTED`，秒级发现数据不一致
> 5. Canal可基于GTID记录消费进度，故障恢复后从断点GTID继续，不错过不重复

**Q7：跨机房分布式锁如何保证不丢锁？**

> 1. Redis主从切换时锁可能丢失：Master获取锁→Master宕机→Slave升主但锁数据未同步→新Master无锁
> 2. 解决方案：`min-replicas-to-write=1` + `WAIT 1 5000` 命令
> 3. 获取锁后执行WAIT等待1个Slave确认同步，确保锁数据已复制
> 4. 这样即使Master宕机，新Master也一定有锁数据
> 5. 写操作分布式锁只在Redis Master所在机房执行，故障切换后路由到新Master

**Q8：两个机房数据不一致怎么发现？对账机制怎么设计？**

> 1. 三层对账体系：实时增量对账（Canal Binlog触发，秒级发现）→ 分钟级批量对账（CRC32哈希对比，1分钟轮）→ 每日全量对账（凌晨低峰期分片CRC32）
> 2. 差异处理：90%自动修复（以updated_at较新的一端为准）→ 9%人工介入（updated_at相同但数据不同）→ 1%告警升级（差异超阈值）
> 3. 对账结果记录到 data_reconciliation_log 表，包含两端值快照和解决方式
> 4. 告警阈值：差异量>100条/分钟触发P0告警，可能是同步链路故障

---

### 专题 46：动态组件多活与流量调度

#### 3.46.1 功能描述

动态组件多活是多活架构的高级形态，核心是让应用层代码对"多机房"无感知，由框架层自动处理区域路由。本专题包含四个核心设计：①动态JDBC组件（基于ShardingSphere扩展，根据请求上下文的区域标记自动切换数据源，无需业务代码关心"写哪个机房"）；②动态Spring Bean组件（根据区域上下文动态切换Bean实现，不同机房可使用不同的策略Bean）；③多活流量调度（全链路区域标记透传增强、流量比例分配、灰度恢复）；④故障自动切换（机房级故障检测、流量自动切换、切换后的数据一致性保障）。

#### 3.46.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-common | 组件提供方 | DynamicJdbcComponent、DynamicBeanComponent、TrafficScheduler |
| my-xhs-gateway | 流量调度入口 | 流量比例分配、故障切换触发 |
| my-xhs-order | 动态JDBC使用方 | 订单分库分表+多机房数据源 |
| my-xhs-product | 动态Bean使用方 | 不同机房可使用不同的库存扣减策略 |

#### 3.46.3 动态JDBC组件

##### 3.46.3.1 设计思路

动态JDBC组件的目标是：**业务代码只管写SQL，框架层自动路由到正确的机房数据源**。

```
业务代码：
    @Autowired
    private JdbcTemplate jdbcTemplate;

    public void createOrder(Order order) {
        jdbcTemplate.update("INSERT INTO t_order ...", ...);
        // 无需关心"写哪个机房"，DynamicJdbcComponent自动路由
    }

框架层自动处理：
    1. 读取RegionContext → 当前区域=region-a
    2. 写操作 → 路由到region-a数据源（写本机房主库）
    3. MySQL双向复制自动同步到region-b
    4. 如果region-a数据源不可用 → 自动降级到region-b数据源
```

##### 3.46.3.2 DynamicJdbcComponent 实现

```java
/**
 * 动态JDBC组件
 * 核心职责：
 * 1. 封装JdbcTemplate，根据区域上下文自动选择数据源
 * 2. 写操作默认路由到本机房数据源
 * 3. 读操作默认路由到本机房数据源
 * 4. 数据源不可用时自动降级
 *
 * 与ShardingSphere兼容：
 * - 如果项目使用了ShardingSphere，DynamicJdbcComponent的路由在ShardingSphere外层
 * - 业务代码使用DynamicJdbcComponent → 区域路由 → ShardingSphere → 分片路由 → 实际MySQL
 */
@Component
public class DynamicJdbcComponent {

    private final Map<String, JdbcTemplate> regionJdbcTemplates;
    private final String currentRegion;
    private final String peerRegion;

    /**
     * 执行写操作（自动路由到本机房数据源）
     */
    public int update(String sql, Object... args) {
        JdbcTemplate jdbcTemplate = getWriteJdbcTemplate();
        return jdbcTemplate.update(sql, args);
    }

    /**
     * 执行读操作（优先本机房，降级对端）
     */
    public <T> T query(String sql, RowMapper<T> rowMapper, Object... args) {
        try {
            return getReadJdbcTemplate().query(sql, rowMapper, args);
        } catch (DataAccessException e) {
            // 本机房数据源不可用，降级到对端
            return getPeerJdbcTemplate().query(sql, rowMapper, args);
        }
    }

    /**
     * 获取写操作的JdbcTemplate
     * 写操作必须路由到本机房主库
     */
    private JdbcTemplate getWriteJdbcTemplate() {
        String region = RegionContext.getCurrentRegion();
        if (region == null) {
            region = currentRegion;
        }
        JdbcTemplate jt = regionJdbcTemplates.get(region);
        if (jt == null) {
            throw new IllegalStateException("No DataSource found for region: " + region);
        }
        return jt;
    }

    /**
     * 获取读操作的JdbcTemplate
     * 优先本机房，不可用则降级到对端
     */
    private JdbcTemplate getReadJdbcTemplate() {
        return getWriteJdbcTemplate();  // 读操作也优先本机房
    }
}
```

#### 3.46.4 动态Spring Bean组件

##### 3.46.4.1 设计思路

不同机房可能需要不同的Bean实现，例如：
- 机房A（主）：库存扣减使用"先扣后验"策略
- 机房B（备）：库存扣减使用"先验后扣"策略
- 故障切换时，机房B升级为主，需要切换Bean实现

```java
/**
 * 动态Bean组件
 * 根据区域上下文动态选择Bean实现
 *
 * 使用方式：
 *   @Autowired
 *   private DynamicBeanComponent<InventoryStrategy> inventoryStrategy;
 *
 *   // 自动根据当前区域选择对应的Bean实现
 *   inventoryStrategy.execute(strategy -> strategy.deduct(skuId, quantity));
 */
@Component
public class DynamicBeanComponent<T> {

    private final Map<String, T> regionBeans;
    private final T defaultBean;

    /**
     * 根据区域上下文获取Bean
     */
    public T getBean() {
        String region = RegionContext.getCurrentRegion();
        T bean = regionBeans.get(region);
        return bean != null ? bean : defaultBean;
    }

    /**
     * 执行区域感知操作
     */
    public void execute(Consumer<T> action) {
        action.accept(getBean());
    }

    /**
     * 执行区域感知操作（有返回值）
     */
    public <R> R executeAndReturn(Function<T, R> action) {
        return action.apply(getBean());
    }
}
```

##### 3.46.4.2 动态Bean注册

```java
/**
 * 动态Bean注册器
 * 通过 @RegionBean 注解标记区域特定的Bean实现
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RegionBean {
    String region();          // 适用的区域
    Class<?> beanType();      // Bean的接口类型
    int order() default 0;    // 优先级
}

// 使用示例
@RegionBean(region = "region-a", beanType = InventoryStrategy.class)
@Component
public class RegionAInventoryStrategy implements InventoryStrategy {
    // 机房A的库存扣减策略：先扣后验
}

@RegionBean(region = "region-b", beanType = InventoryStrategy.class)
@Component
public class RegionBInventoryStrategy implements InventoryStrategy {
    // 机房B的库存扣减策略：先验后扣
}
```

#### 3.46.5 多活流量调度

##### 3.46.5.1 流量调度架构

```
                    ┌──────────────────────┐
                    │    负载均衡 (LB)      │
                    │    Nginx / SLB       │
                    └──────────┬───────────┘
                               │
                    ┌──────────┴──────────┐
                    │                     │
                    ▼                     ▼
            ┌───────────────┐     ┌───────────────┐
            │  Gateway A    │     │  Gateway B    │
            │  (region-a)   │     │  (region-b)   │
            └───────┬───────┘     └───────┬───────┘
                    │                     │
                    ▼                     ▼
            ┌───────────────┐     ┌───────────────┐
            │ 机房A服务集群  │     │ 机房B服务集群  │
            └───────────────┘     └───────────────┘

流量调度策略（由TrafficScheduler控制）：

    正常状态：
      region-a: 50%   ←→   region-b: 50%
      （流量按权重分配，默认各50%）

    机房A故障：
      region-a: 0%    ←→   region-b: 100%
      （全部流量切到机房B）

    灰度恢复：
      region-a: 10%   ←→   region-b: 90%
      → region-a: 50% ←→   region-b: 50%
      （逐步恢复，观察指标）
```

##### 3.46.5.2 TrafficScheduler 实现

```java
/**
 * 多活流量调度器
 * 核心职责：
 * 1. 管理双机房流量权重
 * 2. 执行故障切换（调整权重）
 * 3. 灰度恢复（逐步调整权重）
 * 4. 权重持久化到Redis+Nacos
 */
@Component
public class TrafficScheduler {

    // 当前流量权重（Redis持久化）
    private static final String WEIGHT_KEY = "region:routing:weight";

    /**
     * 调整机房流量权重
     * @param regionAWeight 机房A权重（0-100）
     * @param regionBWeight 机房B权重（0-100）
     */
    public void adjustWeight(int regionAWeight, int regionBWeight) {
        // 1. 参数校验
        if (regionAWeight < 0 || regionBWeight < 0
            || regionAWeight + regionBWeight != 100) {
            throw new IllegalArgumentException("权重之和必须为100");
        }

        // 2. 更新Redis权重
        redisTemplate.opsForHash().put(WEIGHT_KEY, "region-a",
            String.valueOf(regionAWeight));
        redisTemplate.opsForHash().put(WEIGHT_KEY, "region-b",
            String.valueOf(regionBWeight));

        // 3. 发布Nacos配置变更通知（所有Gateway订阅）
        nacosConfigService.publishConfig(
            "region-routing-weight",
            "myxhs",
            regionAWeight + "," + regionBWeight,
            "yaml"
        );

        // 4. 记录操作日志
        log.info("Traffic weight adjusted: region-a={}, region-b={}",
            regionAWeight, regionBWeight);
    }

    /**
     * 故障切换：将全部流量切到存活机房
     */
    public void failover(String fromRegion, String toRegion) {
        if ("region-a".equals(toRegion)) {
            adjustWeight(100, 0);
        } else {
            adjustWeight(0, 100);
        }
        // 更新故障切换状态
        redisTemplate.opsForValue().set("region:failover:status", "FAILOVER");
        redisTemplate.opsForValue().set("region:failover:start-time",
            String.valueOf(System.currentTimeMillis()));
    }

    /**
     * 灰度恢复：逐步将流量切回恢复机房
     */
    public void gradualRecovery(String targetRegion) {
        // 步骤：10% → 30% → 50% → 70% → 100%
        // 每步间隔5分钟，观察错误率和RT
        int[] steps = {10, 30, 50, 70, 100};
        for (int step : steps) {
            adjustWeight(step, 100 - step);
            // 等待5分钟观察
            // 如果错误率 > 1% → 回滚
            // 如果RT > P99基线 → 回滚
        }
        redisTemplate.opsForValue().set("region:failover:status", "NORMAL");
    }
}
```

##### 3.46.5.3 Nginx流量权重配置

```nginx
# nginx-upstream.conf

upstream gateway {
    # 机房A Gateway（权重动态调整，通过Lua脚本从Redis读取）
    server gateway-a:9000 weight=${region_a_weight};
    # 机房B Gateway
    server gateway-b:9100 weight=${region_b_weight};

    # 健康检查
    health_check interval=10s fails=3 passes=2;
}

# 使用OpenResty Lua动态更新权重
# /etc/nginx/conf.d/region_weight.lua
local redis = require "resty.redis"
local red = redis:new()
red:connect("redis", 6379)

local weight_a = red:hget("region:routing:weight", "region-a") or 50
local weight_b = red:hget("region:routing:weight", "region-b") or 50

-- 动态设置upstream权重
```

#### 3.46.6 故障自动切换设计

##### 3.46.6.1 故障检测机制

```
故障检测三维度：

1. 基础设施健康检测（RegionHealthChecker）
   ├── 每个机房Gateway定时检测本机房核心服务健康
   ├── 检测方式：HTTP GET /actuator/health
   ├── 检测间隔：10秒
   ├── 不健康判定：连续3次检测失败
   └── 恢复判定：连续5次检测成功

2. Nacos实例健康检测
   ├── Nacos客户端心跳（5秒间隔）
   ├── 不健康判定：15秒无心跳
   └── 自动摘除不健康实例

3. 业务指标检测（Prometheus）
   ├── 错误率 > 5% 持续1分钟
   ├── P99 RT > 3s 持续1分钟
   └── 触发告警 → 人工确认 → 决定是否切换
```

##### 3.46.6.2 故障切换流程

```
故障切换SOP（Standard Operating Procedure）：

┌────────────────────────────────────────────────────────────┐
│                    故障切换决策树                            │
│                                                             │
│  机房A不可用？                                               │
│  ├── 是 → 自动切换                                           │
│  │   ├── RegionHealthChecker检测到3次连续失败                 │
│  │   ├── 标记 peerRegionHealthy = false                      │
│  │   ├── RegionRouteFilter自动路由到机房B                     │
│  │   ├── TrafficScheduler.failover("region-a", "region-b")  │
│  │   ├── Nginx权重调整为 region-a:0, region-b:100            │
│  │   └── 通知：钉钉/飞书告警 + SMS                            │
│  │                                                          │
│  └── 否 → 正常运行                                           │
│                                                             │
│  机房A恢复后？                                               │
│  ├── 是 → 灰度恢复                                           │
│  │   ├── RegionHealthChecker连续5次检测成功                   │
│  │   ├── 等待MySQL双向同步追平（检查同步延迟<1s）              │
│  │   ├── 等待Redis主从同步追平                                │
│  │   ├── TrafficScheduler.gradualRecovery("region-a")       │
│  │   ├── Nginx权重逐步调整：10%→30%→50%→70%→100%            │
│  │   └── 每步观察5分钟（错误率<1%，P99 RT<基线）              │
│  │                                                          │
│  └── 否 → 继续机房B单独运行                                   │
└────────────────────────────────────────────────────────────┘
```

##### 3.46.6.3 故障切换数据一致性保障

| 阶段 | 数据一致性措施 | 说明 |
|------|--------------|------|
| 切换前 | MySQL双向同步延迟检查 | 确保同步延迟<1s才允许切换 |
| 切换中 | 写入暂停3秒 | 等待MySQL同步追平，避免数据丢失 |
| 切换后 | Canal异步补偿 | 对端恢复后，Canal补偿切换期间的数据差异 |
| 恢复时 | 数据校验 | 对比两个机房的MySQL数据，发现冲突则记录并告警 |

#### 3.46.7 IM 服务多活设计

IM 服务因使用 WebSocket 长连接，多活设计需要特殊处理：

```
IM 多活架构：

    ┌─────── 机房A ────────┐     ┌─────── 机房B ────────┐
    │                       │     │                       │
    │  IM Server A          │     │  IM Server B          │
    │  (WebSocket连接)      │     │  (WebSocket连接)      │
    │                       │     │                       │
    │  用户1 ←→ IM A        │     │  用户3 ←→ IM B        │
    │  用户2 ←→ IM A        │     │  用户4 ←→ IM B        │
    │                       │     │                       │
    │  MQ Consumer A        │     │  MQ Consumer B        │
    │  (订阅全量消息)        │     │  (订阅全量消息)        │
    │                       │     │                       │
    └───────┬───────────────┘     └───────┬───────────────┘
            │                             │
            │      RocketMQ Topic         │
            │   "im-message-topic"        │
            │                             │
            └─────────────┬───────────────┘
                          │
                    消息跨机房投递

场景分析：
  1. 用户1（机房A）→ 用户3（机房B）发消息
     - 用户1发送 → IM Server A → MQ → IM Server B → 推送给用户3

  2. 机房A故障 → 用户1、2断线重连到机房B
     - 客户端自动重连 IM Server B
     - 离线消息从MySQL加载（机房B从库可读）
```

#### 3.46.7.1 WebSocket 网关代理 — 多活 IM 的关键缺失环节（P1 补充）

> 云原生架构训练营§8 的核心洞察：WebSocket 网关代理是实现 IM 服务多活同区域路由的必要条件。当前 my-xhs 的 IM WebSocket 连接直连 Netty 服务器，绕过了 Gateway。这意味着：
> 1. 无法通过 Gateway 的 RegionRouteFilter 实现 WebSocket 连接的同区域路由
> 2. 无法通过 Gateway 的灰度规则实现 IM 服务的灰度发布
> 3. K8s Ingress 无法统一管理 HTTP + WebSocket 的流量入口

##### （1）当前架构 vs 目标架构

```
当前架构（WebSocket 绕过 Gateway）：

  客户端 ──HTTP──→ Gateway:9000 ──→ 业务服务
  客户端 ──WS────→ IM Netty:9014（直连，无路由、无限流、无区域感知）
                   ↑ 问题：无法实现同区域路由、灰度发布

目标架构（WebSocket 通过 Gateway 代理）：

  客户端 ──HTTP──→ Gateway:9000 ──→ 业务服务
  客户端 ──WS────→ Gateway:9000 ──lb:ws──→ IM Netty:9014（同区域实例）
                                       ↑ RegionRouteFilter 生效
                                       ↑ 限流规则生效
                                       ↑ 灰度路由生效
```

##### （2）Spring Cloud Gateway WebSocket 代理原理

```yaml
# Gateway 路由配置（WebSocket 代理）
spring:
  cloud:
    gateway:
      routes:
        - id: im-websocket
          uri: lb:ws://my-xhs-im    # lb:ws:// = LoadBalancer + WebSocket 协议
          predicates:
            - Path=/ws/im/**        # WebSocket 路径匹配
          filters:
            - name: RegionRoute     # 区域路由 Filter（自定义）
            - name: RateLimit       # 限流 Filter
              args:
                redis-rate-limiter.replenishRate: 100   # 每秒100个连接
                redis-rate-limiter.burstCapacity: 200   # 突发200
```

**Spring Cloud Gateway WebSocket 代理工作流程**：

```
1. 客户端发起 WebSocket 握手请求：
   GET /ws/im/connect HTTP/1.1
   Upgrade: websocket
   Connection: Upgrade
   Sec-WebSocket-Key: xxx
   Sec-WebSocket-Version: 13

2. Gateway 匹配路由规则（Path=/ws/im/**, uri=lb:ws://my-xhs-im）

3. RegionRouteFilter 执行：
   - 读取请求中的区域标记（X-Region-Tag Header 或 Cookie）
   - 从 Nacos 获取 my-xhs-im 实例列表
   - 过滤同区域实例（优先机房A → 降级机房B）
   - 选择一个实例，将 uri 改为 ws://10.0.1.5:9014

4. WebSocketProxyFilter 执行：
   - 向目标实例发起 WebSocket 握手
   - 握手成功后建立双向管道
   - 客户端 ←→ Gateway ←→ IM Server（透传帧数据）

5. 连接维持：
   - Gateway 转发 Ping/Pong 帧（心跳保活）
   - Gateway 检测 IM Server 断连 → 返回 1012（Service Restart）→ 客户端重连
```

##### （3）WebSocket 网关代理的 3 个关键问题

**问题 1：Gateway 性能瓶颈**

```
WebSocket 是长连接，每个连接占用 Gateway 一个线程/内存：
  - 传统 Servlet：每连接一线程 → 10000 连接 = 10000 线程 → OOM
  - Spring Cloud Gateway（WebFlux）：每连接一个 EventLoop → 10000 连接 = 4-8 线程 ✅

但 Gateway 仍然需要转发所有帧数据（透传模式，不解析消息内容）：
  - 假设每秒1000条消息，每条1KB → Gateway 额外 1MB/s 流量
  - 10000个连接 × 每分钟1次心跳 → Gateway 额外 167次/s Ping/Pong

结论：Spring Cloud Gateway（WebFlux）可以处理万级 WebSocket 连接，
      但不建议超过 5 万连接（内存和 CPU 限制）
      → 超大规模需要独立 WebSocket Gateway 或 IM 服务直连
```

**问题 2：断线重连与区域切换**

```
场景：机房A故障 → Gateway 的 RegionRouteFilter 检测到机房A IM 实例不可用
→ 需要将新连接路由到机房B IM 实例

问题：已有 WebSocket 连接怎么办？
方案：
  1. Gateway 检测到上游 IM Server 断连
  2. 返回 WebSocket Close 帧（code=1012, reason="Service Restart"）
  3. 客户端收到 Close 帧后自动重连
  4. 重连请求到达 Gateway → RegionRouteFilter 路由到机房B IM 实例
  5. 客户端从 MySQL 加载离线消息

关键：客户端重连策略必须是"指数退避 + 抖动"：
  retry(delay = min(2^attempt + random(0, 1000), 30000))
  避免所有客户端同时重连导致"惊群效应"
```

**问题 3：WebSocket 帧不经过 Gateway 的消息级路由**

```
Gateway 的 WebSocket 代理是"管道级"透传——只转发帧，不解析消息内容。
这意味着：
  - Gateway 无法做"消息级路由"（如：根据消息中的 toUserId 路由到对应实例）
  - Gateway 无法做"消息级限流"（如：每用户每秒最多发 10 条消息）
  - Gateway 无法做"消息级监控"（如：统计消息发送成功率）

解决方案：
  - 管道级控制（连接路由、限流、区域感知）→ Gateway 负责
  - 消息级控制（消息路由、限流、监控）→ IM Server 自己负责
  - 架构原则：Gateway 做"连接管理"，IM Server 做"消息管理"
```

##### （4）my-xhs WebSocket 网关代理实施计划

| 阶段 | 动作 | 收益 | 风险 |
|------|------|------|------|
| **短期**（Phase-7 开发期） | Gateway 配置 `lb:ws://my-xhs-im` 路由 | WebSocket 连接走 Gateway，区域路由生效 | 低，WebFlux 天然支持 |
| **中期**（Phase-7 稳定期） | 添加 RegionRouteFilter 的 WebSocket 分支 | 多活同区域路由生效 | 中，需测试断线重连 |
| **长期**（生产优化） | 评估是否需要独立 WebSocket Gateway | 支持更高级的连接管理（消息级限流） | 高，架构变更 |

**Gateway WebSocket 路由配置**：
```yaml
# application-gateway.yml
spring:
  cloud:
    gateway:
      routes:
        # HTTP API 路由
        - id: im-api
          uri: lb://my-xhs-im
          predicates:
            - Path=/api/im/**
          filters:
            - RegionRoute
            - StripPrefix=1

        # WebSocket 路由（独立配置，避免与 HTTP 路由冲突）
        - id: im-websocket
          uri: lb:ws://my-xhs-im
          predicates:
            - Path=/ws/im/**
          filters:
            - RegionRoute
```

**客户端连接 URL 变更**：
```javascript
// 旧：直连 IM Netty
const ws = new WebSocket('ws://im.myxhs.com:9014/ws/im/connect');

// 新：通过 Gateway 代理
const ws = new WebSocket('ws://gateway.myxhs.com/ws/im/connect');
// Gateway 自动 lb:ws:// 路由到同区域 IM 实例
```

**批判性思考**：
- WebSocket 网关代理是 **"看起来简单，做起来复杂"** 的典型——原理只是 `lb:ws://`，但断线重连、区域切换、帧透传性能、连接数上限都是深水区
- 对于 my-xhs 的社交场景（万级并发用户），Spring Cloud Gateway + WebFlux 的 WebSocket 代理**足够**。但如果是 IM 专有场景（百万级连接），需要独立部署 Netty WebSocket Gateway
- Microsphere 框架在课程中提出了"API 网关 + 配置网关 + WebSocket 网关三合一"的架构思路，本质上是把 Gateway 从"HTTP 请求代理"升级为"全协议流量网关"。这个方向是对的，但 Spring Cloud Gateway 已经原生支持 WebSocket，不需要额外引入框架

#### 3.46.7.2 RocketMQ 跨机房消息方案（⚠️ 深水区）

> 文档前面提到Broker跨机房同步复制，但**缺少详细设计和方案对比**。消息跨机房是多活架构的关键环节。

**三种跨机房消息方案对比**：

| 维度 | 方案A：双机房独立Broker + Topic双向订阅 | 方案B：Broker跨机房同步复制 | 方案C：RocketMQ 5.x跨地域消息路由 |
|------|--------------------------------------|---------------------------|--------------------------------|
| **架构** | 两个独立Broker，生产者双发，消费者各消费本地 | 单Broker集群，主从跨机房部署 | 5.x新特性，自动路由 |
| **一致性** | 最终一致（双发有先后） | 强一致（同步复制） | 最终一致 |
| **延迟** | 各机房本地消费无延迟 | 写入需跨机房RT（2-3ms） | 取决于路由策略 |
| **复杂度** | 🟢 低（独立部署） | 🟡 中（需配置同步复制） | 🟡 中（需5.x版本） |
| **可靠性** | 🟢 高（任一Broker宕机不影响另一） | 🟡 中（Broker主从切换可能丢消息） | 🟢 高（自动故障转移） |
| **版本要求** | RocketMQ 4.x+ | RocketMQ 4.x+ | RocketMQ 5.x+ |
| **适用场景** | ✅ 推荐（my-xhs场景） | 强一致要求 | 未来升级 |

**方案A详细设计（my-xhs选用）**：

```
┌─────── 机房A ────────┐     ┌─────── 机房B ────────┐
│                       │     │                       │
│  Producer-A           │     │  Producer-B           │
│     │                 │     │     │                 │
│     ├──► Broker-A     │     │     ├──► Broker-B     │
│     │   Topic:        │     │     │   Topic:        │
│     │   order-event   │     │     │   order-event   │
│     │                 │     │     │                 │
│     │   (同时发到B) ──┼─────┼──►  │                 │
│     │                 │     │     │  (同时发到A) ───┼──► Broker-A
│     │                 │     │     │                 │
│  Consumer-A           │     │  Consumer-B           │
│  (消费Broker-A)       │     │  (消费Broker-B)       │
│                       │     │                       │
└───────────────────────┘     └───────────────────────┘

关键设计：
  1. 生产者双发：每条消息同时发到两个Broker（本地优先，对端异步）
  2. 消费者本地消费：每个机房只消费本地Broker
  3. 消息去重：每条消息携带全局唯一ID（msgId），消费者基于msgId去重
  4. 事务消息：核心业务使用事务消息确保本地事务+消息发送的原子性
```

**生产者双发实现**：

```java
/**
 * 多活消息发送器
 * 核心逻辑：
 * 1. 同步发送到本地Broker（确保本地消费及时）
 * 2. 异步发送到对端Broker（确保对端也有消息）
 * 3. 事务消息只发本地Broker，由Canal负责跨机房数据同步
 */
@Component
public class RegionMessageSender {

    @Autowired
    private RocketMQTemplate localTemplate;   // 本地Broker模板

    @Autowired
    private RocketMQTemplate peerTemplate;    // 对端Broker模板

    /**
     * 同步双发（适用于普通消息）
     */
    public SendResult sendDual(String topic, String tag, Object message) {
        String msgId = IdGeneratorUtil.snowflakeId();  // 全局唯一消息ID
        Message<?> msg = MessageBuilder.withPayload(message)
            .setHeader("KEYS", msgId)                   // 用于去重
            .setHeader("source-region", currentRegion)  // 来源机房
            .build();

        // 1. 同步发送到本地Broker
        SendResult localResult = localTemplate.syncSend(
            topic + ":" + tag, msg);

        // 2. 异步发送到对端Broker（不阻塞业务）
        peerTemplate.asyncSend(topic + ":" + tag, msg, new SendCallback() {
            @Override
            public void onSuccess(SendResult result) {
                log.debug("Peer broker send success: msgId={}", msgId);
            }
            @Override
            public void onException(Throwable e) {
                // 对端发送失败不影响业务，Canal异步兜底
                log.warn("Peer broker send failed: msgId={}", msgId, e);
            }
        });

        return localResult;
    }

    /**
     * 事务消息发送（只发本地Broker，跨机房由Canal保证）
     */
    public TransactionSendResult sendTransaction(String topic,
            String tag, Object message, Object arg) {
        // 事务消息只发本地Broker
        // 跨机房一致性由 MySQL双向复制 + Canal 保证
        return localTemplate.sendMessageInTransaction(
            topic + ":" + tag,
            MessageBuilder.withPayload(message).build(),
            arg);
    }
}
```

**消费者去重实现**：

```java
/**
 * 多活消息去重消费者
 * 因为生产者双发，同一条消息可能在本地Broker和对端Broker各有一份
 * 消费者只消费本地Broker，但需要防重复消费
 */
@Component
@RocketMQMessageListener(
    topic = "order-event",
    consumerGroup = "order-consumer-${myxhs.region.current}"
)
public class DedupOrderConsumer implements RocketMQListener<MessageExt> {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void onMessage(MessageExt msg) {
        String msgId = msg.getKeys();  // 全局唯一消息ID

        // 1. Redis去重（SETNX，过期时间=消息最大重试间隔×2）
        Boolean isNew = redisTemplate.opsForValue()
            .setIfAbsent("mq:dedup:" + msgId, "1",
                Duration.ofMinutes(30));
        if (Boolean.FALSE.equals(isNew)) {
            log.info("Duplicate message skipped: msgId={}", msgId);
            return;  // 已消费过，跳过
        }

        // 2. 执行业务逻辑
        processMessage(msg);
    }
}
```

**方案B：Broker跨机房同步复制（备选）**：

```properties
# Broker-A（机房A，Master）
brokerClusterName=myxhs-cluster
brokerName=broker-a
brokerId=0
brokerRole=SYNC_MASTER               # 同步复制Master
flushDiskType=ASYNC_FLUSH

# Broker-B（机房B，Slave）
brokerClusterName=myxhs-cluster
brokerName=broker-a
brokerId=1
brokerRole=SLAVE                     # Slave角色
haListenPort=10912                   # HA监听端口
haMasterAddress=broker-a:10912       # Master地址（跨机房）
```

> **my-xhs选型理由**：方案A更简单、更可靠，双机房独立部署无脑裂风险，且消息延迟极低。方案B适合对消息强一致要求极高的场景。

#### 3.46.8 Java 文件清单

**common/dynamic/**

| 文件 | 说明 |
|------|------|
| `DynamicJdbcComponent.java` | 动态JDBC组件（根据区域自动切换数据源） |
| `DynamicBeanComponent.java` | 动态Bean组件（根据区域选择Bean实现） |
| `DynamicBeanRegistrar.java` | 动态Bean注册器（扫描@RegionBean注解，按区域注册Bean） |
| `RegionBean.java` | 区域Bean注解（标记区域特定的Bean实现） |

**common/traffic/**

| 文件 | 说明 |
|------|------|
| `TrafficScheduler.java` | 流量调度器（权重管理、故障切换、灰度恢复） |
| `TrafficWeight.java` | 流量权重DTO |
| `TrafficWeightListener.java` | Nacos配置监听器（监听权重变更） |

**common/failover/**

| 文件 | 说明 |
|------|------|
| `RegionFailoverManager.java` | 故障切换管理器（检测→切换→恢复全流程） |
| `RegionHealthChecker.java` | 机房健康检查器（定时检测双机房健康度） |
| `FailoverEvent.java` | 故障切换事件 |
| `FailoverEventListener.java` | 故障切换事件监听器（告警通知、日志记录） |

#### 3.46.9 Redis Key 清单

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `region:routing:weight` | Hash | 无 | 机房流量权重（field: region-a/region-b, value: 权重0-100） |
| `region:failover:status` | String | 无 | 故障切换状态（NORMAL/FAILOVER/RECOVERING） |
| `region:failover:start-time` | String | 无 | 故障切换开始时间戳 |
| `region:failover:from` | String | 无 | 故障来源机房 |
| `region:health:{region}` | String | 30s | 机房健康状态（UP/DOWN） |
| `region:sync:delay:mysql` | String | 10s | MySQL同步延迟（毫秒） |
| `region:sync:delay.redis` | String | 10s | Redis同步延迟（毫秒） |
| `region:recovery:step` | String | 无 | 灰度恢复当前步骤（1-5） |

#### 3.46.10 接口设计

**多活管控接口（Gateway服务）**

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/region/status` | 获取双机房状态概览 | ✅ |
| GET | `/api/region/health` | 获取双机房健康详情 | ✅ |
| POST | `/api/region/weight` | 调整流量权重 | ✅ |
| POST | `/api/region/failover` | 触发故障切换 | ✅ |
| POST | `/api/region/recovery` | 触发灰度恢复 | ✅ |
| GET | `/api/region/sync-status` | 获取数据同步状态 | ✅ |
| GET | `/api/region/conflicts` | 查询数据冲突记录 | ✅ |

**故障切换请求示例**

```http
POST /api/region/failover
Content-Type: application/json
Authorization: Bearer {admin-token}

{
  "fromRegion": "region-a",
  "toRegion": "region-b",
  "reason": "机房A网络故障",
  "operator": "admin"
}
```

**响应：**

```json
{
  "code": 200,
  "msg": "故障切换已触发",
  "data": {
    "fromRegion": "region-a",
    "toRegion": "region-b",
    "status": "FAILOVER",
    "startTime": "2026-05-12T10:30:00",
    "estimatedRecoveryTime": "待定"
  }
}
```

#### 3.46.11 方案对比

##### 3.46.11.1 故障切换方式对比

| 维度 | 方案A：自动切换 | 方案B：半自动切换 | 方案C：手动切换 |
|------|---------------|-----------------|---------------|
| 切换速度 | 30-60秒 | 2-5分钟 | 10-30分钟 |
| 误切换风险 | 🟡 中（需调优检测阈值） | 🟢 低（人工确认） | 🟢 无 |
| 数据丢失风险 | 🟡 中（可能有1s内数据丢失） | 🟢 低（人工确认同步状态） | 🟢 无 |
| 适用场景 | 核心业务 | 大部分业务 | 非核心业务 |

**最终选择**：方案B（半自动切换）—— 检测自动触发告警，人工确认后执行切换。平衡速度与安全性。

##### 3.46.11.2 灰度恢复策略对比

| 维度 | 线性恢复 | 指数恢复 | 阶梯恢复 |
|------|---------|---------|---------|
| 步骤 | 10%→20%→...→100% | 1%→2%→4%→8%→...→100% | 10%→30%→50%→100% |
| 总耗时 | 长（10步×5分=50分） | 短（快速放量） | 中（4步×5分=20分） |
| 安全性 | 🟢 高（缓慢放量） | 🟡 中（后期放量快） | 🟢 高 |
| 适用场景 | 谨慎恢复 | 快速恢复 | ✅ 推荐 |

**最终选择**：阶梯恢复（10%→30%→50%→100%），每步5分钟观察，发现异常立即回滚。

#### 3.46.12 面试考察点

**Q1：动态JDBC组件如何保证写操作不会跨机房？**

> 1. DynamicJdbcComponent在执行写操作时，根据RegionContext获取当前区域标记
> 2. 写操作强制路由到本机房数据源（force-local策略），不降级到对端
> 3. 数据跨机房一致性由MySQL双向复制保障，而非应用层跨机房写入
> 4. 这样设计的好处：①避免跨机房写入延迟（同城RT 2-3ms虽然可接受，但不必要）；②避免双写冲突；③MySQL主主复制本身已保证数据同步

**Q2：故障切换时如何保证数据不丢失？**

> 1. 切换前：检查MySQL同步延迟，确保<1s才允许切换
> 2. 切换中：写入暂停3秒，等待MySQL双向复制追平
> 3. 切换后：新写入全部到对端机房，MySQL复制保证双向数据最终一致
> 4. 极端情况：如果Master A突然宕机，可能丢失最后<1s的Binlog（尚未复制到对端）
> 5. 补偿方案：Canal异步兜底 + 本地消息表事务消息确保最终一致
> 6. 实际损失：同城双活RT<3ms，最多丢失3ms内的数据，业务可接受

**Q3：多活流量调度的灰度恢复为什么要阶梯式？**

> 1. 机房恢复后直接100%流量灌入，可能导致恢复机房被再次压垮
> 2. 阶梯式恢复（10%→30%→50%→100%）可以逐步验证恢复机房的承载能力
> 3. 每步观察5分钟，监控错误率、RT、CPU/内存水位
> 4. 发现异常立即回滚（将流量比例调回上一步），避免故障复发
> 5. 这种策略在大规模分布式系统中广泛使用（如阿里的异地多活恢复）

**Q4：RocketMQ跨机房消息有几种方案？各有什么优缺点？**

> 1. 方案A：双机房独立Broker + 生产者双发 + 消费者本地消费（my-xhs选用）
>    - 优点：架构简单、无脑裂风险、延迟极低
>    - 缺点：需生产者双发、消费者需去重
> 2. 方案B：Broker跨机房同步复制（SYNC_MASTER + SLAVE跨机房）
>    - 优点：消息强一致、无需双发
>    - 缺点：写入RT增加2-3ms、主从切换可能丢消息
> 3. 方案C：RocketMQ 5.x跨地域消息路由
>    - 优点：自动路由、原生支持
>    - 缺点：需5.x版本、生态尚不成熟
> 4. 事务消息跨机房：本地事务+本地Broker事务消息，跨机房由MySQL双向复制+Canal保证
> 5. 方案选型核心依据：对消息强一致的要求和团队技术栈

---

## 四、Phase 7 专题依赖关系

```
专题42（多活概述与选型）
    │
    ├──→ 专题43（注册中心多活）
    │       │
    │       └──→ 专题44（网关与负载均衡多活）
    │               │
    │               └──→ 专题45（数据层多活）
    │                       │
    │                       └──→ 专题46（动态组件与流量调度）
    │
    依赖原因：
    - 42定义了多活架构的整体规范和区域标记体系
    - 43是所有服务多活的基础（双注册+区域感知发现）
    - 44依赖43的区域标记实现流量路由
    - 45依赖44的路由能力实现数据层多活
    - 46是45的高级封装，让业务代码对多活无感知
```

## 五、开发顺序建议

| 阶段 | 专题 | 预计耗时 | 产出 |
|------|------|---------|------|
| 1 | 专题42：多活架构概述 | 2天 | RegionContext体系、区域标记规范文档 |
| 2 | 专题43：注册中心多活 | 3天 | Nacos双集群Docker部署、NacosMultiRegistry、双注册验证 |
| 3 | 专题44：网关与负载均衡多活 | 4天 | RegionRouteFilter、RegionLoadBalancer、RegionFeignInterceptor |
| 4 | 专题45：数据层多活 | 5天 | MySQL双向同步、Redis跨机房、Canal双向同步、DynamicRegionDataSource |
| 5 | 专题46：动态组件与流量调度 | 3天 | DynamicJdbcComponent、DynamicBeanComponent、TrafficScheduler、故障切换 |

> **总预计耗时**：17天

## 六、验收标准

| # | 验收项 | 验证方法 | 通过标准 |
|---|--------|---------|---------|
| 1 | 服务双注册 | 启动服务，检查两个Nacos集群是否都有注册 | 两个集群均有实例 |
| 2 | 同区域优先路由 | 发送请求到机房A Gateway，观察是否路由到机房A实例 | 100%路由到同区域 |
| 3 | 跨区域降级 | 停掉机房A的某个服务，请求是否降级到机房B | 降级成功，RT正常 |
| 4 | MySQL双向同步 | 机房A写入数据，机房B查询 | 延迟<500ms |
| 5 | Redis跨机房 | 机房A写入缓存，机房B读取 | 延迟<1s |
| 6 | Canal双向同步 | 修改商品数据，观察ES索引是否双机房更新 | 延迟<3s |
| 7 | 动态数据源 | 修改X-Region-Tag，观察SQL执行到哪个数据源 | 路由正确 |
| 8 | 故障切换 | Kill机房A全部服务，观察流量是否切换到机房B | 60秒内切换完成 |
| 9 | 灰度恢复 | 恢复机房A，触发灰度恢复 | 阶梯式恢复，10%→30%→50%→100% |
| 10 | IM多活 | 用户1（机房A）发消息给用户3（机房B） | 消息正常送达 |

## 七、面试考察点汇总

| # | 问题 | 专题 | 难度 |
|---|------|------|------|
| 1 | 为什么多活架构本质是AP系统？ | 42 | ⭐⭐ |
| 2 | 同城双活 vs 异地多活 vs 单元化，如何选型？ | 42 | ⭐⭐⭐ |
| 3 | **多活架构下如何防止脑裂？你的仲裁机制是什么？** | 42 | ⭐⭐⭐⭐ |
| 4 | **全量服务双活 vs 部分服务双活，你如何决策？** | 42 | ⭐⭐⭐⭐ |
| 5 | Nacos为什么要部署双集群而不是跨机房集群？ | 43 | ⭐⭐⭐ |
| 6 | 服务双注册的性能影响？ | 43 | ⭐⭐ |
| 7 | Gateway多活路由如何实现故障自动切换？ | 44 | ⭐⭐⭐ |
| 8 | 负载均衡的同区域优先策略如何避免单实例热点？ | 44 | ⭐⭐ |
| 9 | MySQL主主双向复制如何解决循环复制？ | 45 | ⭐⭐⭐ |
| 10 | Canal双向同步如何避免循环？ | 45 | ⭐⭐⭐ |
| 11 | **为什么不用MySQL Group Replication而用主主双向复制？** | 45 | ⭐⭐⭐⭐ |
| 12 | **半同步复制 vs 异步复制，多活场景怎么选？** | 45 | ⭐⭐⭐⭐ |
| 13 | **GTID在双向复制中有什么优势？** | 45 | ⭐⭐⭐ |
| 14 | **跨机房分布式锁如何保证不丢锁？** | 45/46 | ⭐⭐⭐⭐ |
| 15 | **两个机房数据不一致怎么发现？对账机制怎么设计？** | 45 | ⭐⭐⭐⭐ |
| 16 | 动态JDBC组件如何保证写操作不跨机房？ | 46 | ⭐⭐⭐ |
| 17 | 故障切换时如何保证数据不丢失？ | 46 | ⭐⭐⭐ |
| 18 | 灰度恢复为什么要阶梯式？ | 46 | ⭐⭐ |
| 19 | **RocketMQ跨机房消息有几种方案？各有什么优缺点？** | 46 | ⭐⭐⭐⭐ |

## 八、参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📖 《凤凰架构》 | 第6章 事件溯源 | 多活架构理论、CAP/BASE在多活场景的应用 |
| 📖 《分布式系统设计》 | 第5章 一致性 | 一致性级别选型、冲突解决策略 |
| 📖 《MySQL技术内幕：主从架构》 | 第7-9章 | MySQL主主复制、GTID、半同步复制、MGR |
| 📄 Nacos官方文档 | 集群部署 | Nacos Raft/Distro协议、集群配置 |
| 📄 MySQL官方文档 | Replication | 主主双向复制、GTID、半同步复制、循环复制 |
| 📄 MySQL官方文档 | Group Replication | MGR架构、Paxos认证、写冲突检测 |
| 📄 Canal官方文档 | HA机制 | Canal HA部署、Binlog解析、GTID位点 |
| 📄 RocketMQ官方文档 | 跨机房同步 | Broker跨机房同步复制、事务消息 |
| 📄 Spring Cloud LoadBalancer | ServiceInstanceListSupplier | 区域感知负载均衡扩展 |
| 📄 ShardingSphere官方文档 | 动态数据源 | 多数据源路由、读写分离 |
| 📄 阿里技术博客 | 异地多活 | 阿里同城双活→异地多活演进实践 |
| 📄 美团技术博客 | 同城双活 | 美团同城双活架构设计 |
| 📄 美团技术博客 | 数据对账 | 美团双机房数据一致性对账方案 |
| 📄 Redis官方文档 | Sentinel | min-replicas-to-write、Quorum配置、脑裂防护 |
| 📄 Martin Kleppmann博客 | Redlock讨论 | 分布式锁在分布式系统中的争议与思考 |
| 📄 MHA官方文档 | MHA Manager | MySQL高可用故障切换、仲裁节点部署 |