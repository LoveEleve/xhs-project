# MyXHS 项目总结

> 仿小红书社交电商微服务平台 —— 完整代码审查与生产化改造报告

---

## 一、项目概述

### 1.1 项目定位

MyXHS 是一个**对标小红书的社交电商微服务系统**，以"内容驱动消费"为核心模式，覆盖用户从注册登录、浏览笔记、社交互动到商品搜索、加购下单、履约支付的完整业务链路。

- **类型**：社交 + 电商微服务平台
- **架构**：纯后端，16 个微服务模块，Spring Cloud Alibaba 微服务体系
- **部署**：全部基础设施通过 Docker Compose 一键拉起，不依赖云厂商服务
- **目标量级**：DAU 100 万 / QPS 峰值 1 万 / 日订单 5 万 / 可用性 99.95%

### 1.2 核心业务链路

```
用户注册/登录 → 浏览笔记(Feed流) → 社交互动(赞/评/关/藏) →
浏览商品(搜索/详情) → 加购 → 下单 → 支付 → 履约
```

### 1.3 技术栈

| 层次 | 技术选型 | 版本 |
|------|---------|------|
| JDK | OpenJDK | 17 |
| 核心框架 | Spring Boot + Spring Cloud | 3.2.x / 2023.0.x |
| 微服务体系 | Spring Cloud Alibaba | 2023.0.1.0 |
| ORM | MyBatis-Plus | 3.5.7 |
| API 文档 | SpringDoc (OpenAPI 3.0) | 2.x |
| 认证 | JWT (jjwt) | 0.12.x |
| 构建 | Maven | 3.9.x |

| 中间件 | 版本 | 用途 |
|--------|------|------|
| Nacos | 2.3.2 | 注册中心 + 配置中心 |
| Spring Cloud Gateway | — | API 网关 |
| Sentinel | 1.8.8 | 限流熔断降级 |
| RocketMQ | 5.1.4 | 异步消息 + 事务消息 |
| Redis | 7.x | 缓存 / 分布式锁 / 计数 |
| Elasticsearch | 8.12.2 | 全文搜索 |
| Canal | 1.1.7 | MySQL → ES 实时同步 |
| XXL-Job | 2.4.2 | 分布式定时任务 |
| MySQL | 8.0 | 分库分表（4 实例） |
| SkyWalking | 9.7.0 | 链路追踪 |
| Prometheus + Grafana | 2.48 / 10.2 | 监控告警 |

---

## 二、模块清单

共 **17 个模块**（含 16 个微服务 + 1 个测试模块），职责如下：

| # | 模块 | 端口 | 职责 |
|---|------|------|------|
| 1 | `my-xhs-common` | — | 公共基础模块：统一响应体、分布式锁/幂等/限频注解、缓存封装、雪花ID、消息封装、全局异常处理 |
| 2 | `my-xhs-gateway` | 8080 | API 网关：统一鉴权（JWT）、限流（Sentinel）、路由转发、CORS |
| 3 | `my-xhs-user` | 8081 | 用户服务：注册/登录、个人信息、收货地址 |
| 4 | `my-xhs-content` | 8082 | 内容服务：笔记 CRUD、话题标签、评论 |
| 5 | `my-xhs-analytics` | 8083 | 数据分析：用户行为埋点、热门统计 |
| 6 | `my-xhs-counter` | 8084 | 计数服务：点赞/收藏/评论计数、高并发读写分离 |
| 7 | `my-xhs-product` | 8085 | 商品服务：SPU/SKU 管理、商品搜索索引同步 |
| 8 | `my-xhs-order` | 8086 | 订单服务：下单、订单状态流转、4 分片库 |
| 9 | `my-xhs-inventory` | 8087 | 库存服务：库存扣减/释放、独立部署防锁竞争扩散 |
| 10 | `my-xhs-cart` | 8088 | 购物车：加购、数量变更、价格快照 |
| 11 | `my-xhs-coupon` | 8089 | 优惠券：模板管理、领券、核销、退券 |
| 12 | `my-xhs-search` | 8090 | 搜索服务：笔记/商品全文搜索、搜索建议、热搜管理 |
| 13 | `my-xhs-home` | 8091 | 首页 Feed：推荐流、关注流、热门流 |
| 14 | `my-xhs-im` | 8092 | 即时通讯：单聊、群聊、消息推送 |
| 15 | `my-xhs-notification` | 8093 | 消息通知：站内通知、SSE 实时推送、消息聚合 |
| 16 | `my-xhs-payment` | 8094 | 支付服务：支付单管理、退款、支付回调 |
| 17 | `my-xhs-test` | — | 测试模块：集成测试、端到端测试 |

---

## 三、代码审查修复总结

本次审查覆盖全部 16 个微服务模块，累计修复问题超过 200 项，按严重等级分布如下：

### 3.1 修复统计

| 严重等级 | 问题类型 | 数量（约） | 说明 |
|---------|---------|-----------|------|
| P0 致命 | 数据安全、资损风险、死锁 | 50+ | 必须立即修复 |
| P1 高危 | 并发安全、性能劣化、可用性 | 80+ | 上线前必须修复 |
| P2 中危 | 代码质量、最佳实践 | 70+ | 建议修复 |

### 3.2 各模块关键修复

| 模块 | P0 修复要点 | P1 修复要点 | P2 修复要点 |
|------|-----------|-----------|-----------|
| **common** | JWT issuer 校验、Redis 密码硬编码、反射安全 | SecretKey 缓存、SqlGuardInterceptor 线程安全、延迟双删可配置 | IdGenerator 原子化、RedisOperator 异常处理 |
| **gateway** | JWT 安全校验、CORS 宽松配置 | RateLimit 兜底规则加载、认证异常捕获 | 安全响应头配置 |
| **user** | 实体继承 BaseEntity (@Version 乐观锁) | 地址缓存更新延迟到事务后 | DTO 参数校验 |
| **content** | 内容审核流程完整性 | 笔记搜索索引同步补偿 | 评论排序优化 |
| **counter** | SETNX 锁替换为 Redisson RLock、Watchdog 启用 | 计数 buffer 刷新原子化 | Prometheus 指标注册 |
| **product** | @Version 乐观锁、缓存驱逐事务后执行 | DTO 参数校验 | SQL 拼接防护 |
| **order** | 实体继承 BaseEntity、MQ 消费者重试上限 | 分布式锁替换为 Redisson、MQ 发送事务后执行 | 超时 Lua 脚本逻辑 |
| **inventory** | MQ 发送失败不回滚库存、orderNo hashCode 碰撞 | KEYS→SCAN 替换、SETNX→Redisson 替换 | HotSkuDetector ZSet 原子化 |
| **cart** | 实体继承 BaseEntity | 购物车缓存一致性 | DTO 参数校验 |
| **coupon** | DTO 校验注解缺失、实体继承 BaseEntity、@Transactional 缺失 | MQ 消费者幂等 + 补偿、Lua 脚本 TTL | 模板缓存 Key、SQL ORDER BY |
| **search** | ES 连接安全管理、索引重建安全 | DTO 参数校验、空关键词防护、热搜管理员校验 | 搜索建议优化 |
| **home** | Feed 流数据一致性 | notesMap NPE 防护、nanoTime 回拨 | 浮点精度注释 |
| **im** | 实体继承 BaseEntity、upsert 并发安全 | seqNo 过期、UNREAD_KEY TTL | 消息顺序保证 |
| **notification** | 实体继承 BaseEntity、@Transactional 缺失 | MQ 消费者重试上限、Redis 去重 TTL | 对账分页、模板查询缓存 |
| **payment** | 分布式锁替换 Redisson、@Transactional 缺失 | 退款金额 FOR UPDATE、MQ 发送事务后提交 | 支付单乐观锁 |

---

## 四、基础设施

### 4.1 Docker Compose 中间件清单

所有中间件通过 `docker-compose.yml` 统一编排，`network_mode: host` 模式部署：

| 中间件 | 镜像 | 端口 | 用途 |
|--------|------|------|------|
| **MySQL-User** | mysql:8.0 | 13306 | 用户 + 社交 + 基础设施（nacos_config, xxl_job） |
| **MySQL-Content** | mysql:8.0 | 13307 | 内容 + 商品 + 购物车 + 优惠券 |
| **MySQL-Order** | mysql:8.0 | 13308 | 订单（4 分片库）+ 支付 |
| **MySQL-Inventory** | mysql:8.0 | 13309 | 库存（独立部署，隔离锁竞争） |
| **Redis** | redis:7-alpine | 16379 | 缓存 / 分布式锁 / 计数 / 排行榜 |
| **Elasticsearch** | elasticsearch:8.12.2 | 19200 | 全文搜索 / SkyWalking 存储 |
| **Nacos** | nacos-server:v2.3.2 | 18848 | 服务注册 + 配置中心 |
| **Sentinel Dashboard** | sentinel-dashboard:1.8.8 | 18082 | 限流熔断控制台 |
| **RocketMQ NameServer** | rocketmq:5.1.4 | 9876 | 消息队列路由 |
| **RocketMQ Broker** | rocketmq:5.1.4 | 11911 | 消息队列存储 |
| **Canal** | canal-server:v1.1.7 | 11111 | MySQL Binlog → RocketMQ → ES 同步 |
| **XXL-Job Admin** | xxl-job-admin:2.4.2 | 18080 | 分布式定时任务调度 |
| **Prometheus** | prometheus:v2.48.1 | 19090 | 指标采集 + 告警 |
| **Grafana** | grafana:10.2.3 | 13000 | 可视化监控面板 |
| **SkyWalking OAP** | skywalking-oap-server:9.7.0 | 12800 | 链路追踪后端 |
| **SkyWalking UI** | skywalking-ui:9.7.0 | 18081 | 链路追踪控制台 |

### 4.2 数据库分库策略

```
MySQL-User    :13306 → 用户表、社交关系、Nacos 配置库、XXL-Job 库
MySQL-Content :13307 → 笔记、商品、购物车、优惠券
MySQL-Order   :13308 → 订单（4 分片库 order_db_0~3）+ 支付
MySQL-Inventory:13309 → 库存（独立部署，防止行锁扩散拖垮其他库）
```

---

## 五、生产能力建设

### 5.1 ELK 日志平台

- 所有 17 个模块统一使用 **Logstash Encoder** 输出 JSON 结构化日志
- 日志格式包含业务 `traceId` 和 SkyWalking `tid`，支持链路关联
- 支持 Filebeat → Elasticsearch → Kibana 标准采集管线（ES 已部署，Filebeat/Kibana 方案就绪）

### 5.2 SkyWalking TraceId 集成

- 所有模块 `logback-spring.xml` 统一配置 `[%X{traceId:-}] [%tid]` 日志格式
- 日志中的 `%tid` 字段来自 SkyWalking Agent 自动注入，实现日志与链路追踪关联
- 生产环境使用 JSON 结构化日志，控制台输出含彩色高亮

### 5.3 Grafana Dashboard 预置

三个预置监控面板（`config/grafana/provisioning/dashboards/json/`）：

| Dashboard | 文件 | 监控内容 |
|-----------|------|---------|
| JVM 监控 | `jvm-monitor.json` | 堆内存、GC、线程、类加载 |
| 接口监控 | `api-monitor.json` | QPS、RT、错误率、HTTP 状态码 |
| 业务监控 | `biz-metrics.json` | 订单量、支付成功率、库存水位、优惠券核销率 |

Grafana 通过 Provisioning 机制自动加载 Prometheus 数据源和 Dashboard。

### 5.4 Flyway 数据库版本迁移

- 父 POM 引入 `flyway-maven-plugin` 依赖
- 迁移脚本目录：`sql/migration/`
  - `user/V1__init_user.sql` — 用户库初始化
  - `content/V1__init_content.sql` — 内容库初始化
  - `order/V1__init_order.sql` — 订单库初始化
  - `inventory/V1__init_inventory.sql` — 库存库初始化
- 各模块 `application.yml` 已配置 Flyway 数据源

### 5.5 JaCoCo + SpotBugs 代码质量

已在父 POM `<pluginManagement>` 中统一配置：

- **JaCoCo 0.8.12**：`prepare-agent` + `report` 阶段，生成测试覆盖率报告
- **SpotBugs 4.8.6.0**：`effort=Max` / `threshold=Medium`，`verify` 阶段自动检查

GitLab CI 中 SpotBugs 作为独立 Stage 执行（`allow_failure: true`），覆盖率报告作为 Artifact 留存 7 天。

### 5.6 GitLab CI/CD

完整 5 阶段流水线（`.gitlab-ci.yml`）：

| Stage | Job | 触发条件 |
|-------|-----|---------|
| compile | `mvn compile` | MR / main / develop |
| test | `mvn test` + JaCoCo | MR / main / develop |
| check | SpotBugs | MR / main / develop |
| build | Docker 镜像构建（14 个模块） | main（手动触发） |
| deploy | K8s 滚动更新 | main（手动触发） |

镜像推送至阿里云容器镜像仓库 `registry.cn-hangzhou.aliyuncs.com/myxhs`。

### 5.7 K8s 部署模板

`k8s/` 目录包含：

| 文件 | 用途 |
|------|------|
| `namespace.yaml` | 命名空间 `myxhs` |
| `configmap.yaml` | 公共配置（Nacos / Redis / RocketMQ 地址） |
| `deployment-template.yaml` | Deployment 模板（含健康检查、资源限制、SkyWalking Agent） |
| `service-template.yaml` | Service 模板（ClusterIP） |
| `ingress-template.yaml` | Ingress 模板（Nginx Ingress，路径路由） |
| `README.md` | K8s 部署指南 |

### 5.8 Sentinel Nacos 数据源

- 在 `my-xhs-gateway`、`my-xhs-counter`、`my-xhs-order`、`my-xhs-payment`、`my-xhs-product` 的 `pom.xml` 中引入 `sentinel-datasource-nacos` 依赖
- 限流/降级规则存储于 Nacos，支持**动态推送、实时生效**，无需重启服务
- 预置规则示例文件：`config/sentinel/my-xhs-gateway-flow-rules.json`、`my-xhs-gateway-degrade-rules.json`

### 5.9 Redis 高可用方案

已编写完整的 Redis HA 方案文档（`docs/redis-ha.md`）：

- 开发环境：单节点 Redis（docker-compose）
- 生产推荐：**哨兵模式**（1 Master + 2 Slave + 3 Sentinel）
- 包含：架构拓扑、故障转移流程、Spring Boot 配置示例、运维手册

---

## 六、项目结构

### 6.1 新增关键目录

```
my-xhs/
├── config/                              # 基础设施配置
│   ├── canal/conf/                      # Canal 实例配置（note/product/inventory）
│   ├── grafana/provisioning/            # Grafana 自动加载
│   │   ├── datasources/                 # Prometheus 数据源
│   │   └── dashboards/json/            # 3 个预置 Dashboard
│   ├── prometheus/                      # Prometheus 配置 + 告警规则
│   │   ├── prometheus.yml
│   │   └── alert_rules/
│   ├── rocketmq/                        # RocketMQ Broker 配置
│   └── sentinel/                        # Sentinel 规则示例
│       ├── my-xhs-gateway-flow-rules.json
│       ├── my-xhs-gateway-degrade-rules.json
│       └── README.md
├── k8s/                                 # Kubernetes 部署模板
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── deployment-template.yaml
│   ├── service-template.yaml
│   ├── ingress-template.yaml
│   └── README.md
├── sql/migration/                       # Flyway 数据库迁移脚本
│   ├── user/V1__init_user.sql
│   ├── content/V1__init_content.sql
│   ├── order/V1__init_order.sql
│   └── inventory/V1__init_inventory.sql
├── docs/                                # 项目文档
│   ├── architecture/                    # 架构设计文档
│   ├── business/                        # 业务设计文档
│   ├── distributed/                     # 分布式方案文档
│   ├── infrastructure/                  # 基础设施文档
│   ├── production/                      # 生产运维手册
│   ├── redis-ha.md                      # Redis 高可用方案
│   └── project-summary.md              # 本文件
├── .gitlab-ci.yml                       # GitLab CI/CD 流水线
└── docker-compose.yml                   # 基础设施编排
```

### 6.2 日志配置覆盖

所有 17 个模块均已配置 `logback-spring.xml`（`src/main/resources/`）：

```
my-xhs-analytics, my-xhs-cart, my-xhs-common, my-xhs-content,
my-xhs-counter, my-xhs-coupon, my-xhs-gateway, my-xhs-home,
my-xhs-im, my-xhs-inventory, my-xhs-notification, my-xhs-order,
my-xhs-payment, my-xhs-product, my-xhs-search, my-xhs-test, my-xhs-user
```

特性：JSON 结构化日志 + 控制台彩色输出 + TraceId + SkyWalking tid + 异步写入。

### 6.3 新增关键文件

| 文件 | 用途 |
|------|------|
| `.gitlab-ci.yml` | CI/CD 流水线定义 |
| `docs/redis-ha.md` | Redis 高可用方案文档 |
| `docs/project-summary.md` | 项目总结文档（本文件） |
| `config/grafana/provisioning/dashboards/json/*.json` | 3 个 Grafana Dashboard |
| `config/sentinel/*.json` | Sentinel 规则示例 |
| `k8s/*.yaml` | K8s 部署模板（5 个文件） |
| `sql/migration/*/V1__init_*.sql` | Flyway 迁移脚本（4 个） |

---

## 七、总结

MyXHS 项目经过系统性代码审查与生产化改造，已具备以下能力：

- **代码安全**：200+ 项问题修复，覆盖数据安全、并发安全、可用性三大维度
- **可观测性**：SkyWalking 全链路追踪 + Prometheus 指标 + Grafana 可视化 + ELK 日志平台
- **持续交付**：GitLab CI/CD 5 阶段流水线 + Docker 镜像 + K8s 部署模板
- **数据库治理**：4 实例分库分表 + Flyway 版本迁移
- **流量治理**：Sentinel 限流熔断 + Nacos 动态规则
- **高可用**：Redis 哨兵方案、MySQL 独立部署隔离、MQ 事务消息保障

技术栈对标一线互联网公司生产标准，适合作为微服务架构实践、面试展示项目。
