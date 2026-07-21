# my-xhs 项目 P8 级别深化评审 Prompt（含限流已评审）

> 将此文档内容发送给 AI，逐维度进行 P8 级别深度审查。
> **限流维度已单独评审完成（见 `P8-LEVEL-ROADMAP.md`），本 Prompt 从第 2 维度开始。**

---

## 前置说明

my-xhs 是一个小红书（RED）社交+电商微服务系统的纯后端实现，16 个微服务模块，技术栈为 Java 17 + Spring Boot 3.2.5 + Spring Cloud 2023.0.1。

项目当前状态经过了初步评审（见 `COMPREHENSIVE-REVIEW-REPORT.md`），**核心业务链路代码质量较高**，但在以下维度存在**P8 级别的深度差距**。

P8 级别的要求：
- 不是"有没有用某个组件"，而是"是否深入理解了框架源码并做了自定义扩展"
- 不是"能跑"，而是"在极端场景下的优雅降级和可观测"
- 不是"单个维度"，而是"多层纵深防御体系"

---

## 维度一：熔断/降级/容错体系 ⚠️(已评审)

> **跳过**，已单独在 `P8-LEVEL-ROADMAP.md` 中输出完整方案。四层限流体系：网关层 → Web Server 层 → Web Framework 层 → 组件层（Redis/MyBatis/HikariCP/Feign/RocketMQ）。

---

## 维度二：熔断与降级体系

> my-xhs 当前状态：Gateway 层有 Sentinel 基础限流，Feign 仅 2 个服务启用了 `feign.sentinel.enabled=true`。**没有服务级熔断、没有方法级熔断、没有业务级降级开关**。

### 2.1 请按以下多级熔断降级体系展开分析：

```
三级熔断降级架构：

L1 服务级熔断 (Feign + Sentinel)：
  - Sentinel 熔断规则的三态（CLOSED → OPEN → HALF_OPEN）如何配置？
  - 各服务的熔断阈值怎么设定？（错误率/慢调用比例/异常数）
  - FallbackFactory 如何区分 DegradeException / BlockException / 网络异常？
  - 熔断触发后，业务侧怎么做到"用户无感"（如库存查询失败→展示"暂时无法显示精确库存"）

L2 方法级熔断 (框架源码扩展)：
  - Redis 连接池耗尽时怎么降级？(读返回null写记录本地队列)
  - MyBatis 慢 SQL 连续触发怎么短路？(SQL 级熔断+自动恢复探测)
  - 外部 API 调用超时（如 OSS 上传图片）怎么降级？(异步重试+本地缓存)

L3 业务级降级 (Feature Flag + 开关中心)：
  - Nacos Config 作为动态开关中心的设计（不重启服务切换降级策略）
  - 大促核心链路保底方案：支付/下单不可降级，推荐/搜索/Feed 流可降级
  - 降级后的用户提示策略（"系统繁忙" vs "暂时无法使用XX功能" vs 透明降级）
  - 降级恢复的自动验证：放 5% 流量探测→指标正常→全量恢复
```

### 2.2 请用代码示例说明：
- Sentinel 三态熔断的完整配置（Nacos 动态规则）
- `FallbackFactory<T>` 如何区分不同异常类型并返回不同降级数据
- Redis 连接池耗尽降级的 `@Around` 切面实现
- Nacos Config 作为动态开关中心，服务如何监听配置变更并实时切换降级策略

### 2.3 请分析 my-xhs 的 FeignClient 清单，给出哪些必须加 Fallback、哪些可以降级、哪些绝对不能降级。

### 2.4 请设计大促场景下的完整降级预案表：

| 服务 | 正常模式 | 轻度降级（QPS 120%） | 重度降级（QPS 200%+） |
|------|---------|-------------------|---------------------|
| 搜索 | 全文检索 | 仅热门词搜索 | 关闭搜索建议 |
| Feed 流 | 个性化推荐 | 热门兜底 | 仅关注流 |
| 库存 | 精确库存 | 仅展示有无货 | 关闭库存查询 |
| 支付 | 正常回调 | 正常（不可降） | 正常（不可降） |
| ... | ... | ... | ... |

---

## 维度三：负载均衡深度设计

> my-xhs 当前状态：依赖 Nacos 默认的 LoadBalancer 策略，无任何自定义路由规则。

### 3.1 P8 要求的多策略负载均衡体系：

| 策略 | 适用场景 | 实现要点 |
|------|---------|---------|
| 加权响应时间 | 通用服务间调用 | 实时采集各实例 P99 响应时间，动态计算权重 |
| 一致性 Hash | IM WebSocket | 同 userId 路由到同一实例 |
| 同机房优先 | 多 AZ 部署 | 根据 Nacos 实例 metadata 中的 zone 就近路由 |
| 最小连接数 | Notification SSE | 选择活跃连接最少的实例 |
| 灰度隔离 | 金丝雀发布 | `X-Gray-Tag` + `X-AB-Group` 精确路由 |
| 压测隔离 | 全链路压测 | `X-Pressure-Test=true` 的流量路由到影子库 |

### 3.2 请展开说明：
- Nacos `NacosRule` 同集群优先路由的源码原理和配置
- 如何用 `ServiceInstanceListSupplier` 自定义负载均衡策略
- Spring Cloud LoadBalancer 的 `ReactorServiceInstanceLoadBalancer` 接口如何扩展
- 一致性 Hash 路由的 Java 实现（使用 TreeMap + 虚拟节点）

### 3.3 请给出 my-xhs 各个服务的负载均衡策略推荐配置表。

---

## 维度四：多级缓存架构与热点治理

> my-xhs 当前状态：Cache Aside + 延迟双删 + TTL 随机偏移 + 空值缓存 + 分布式锁防击穿。**功能基础但有深度缺口**。

### 4.1 请以 P8 标准审视并重构缓存体系：

```
三级缓存架构：

L1: 本地缓存 (Caffeine)
  - 多实例部署时的一致性怎么保证？（Redis Pub/Sub 广播失效 or 本地短 TTL）
  - 最大容量怎么设？（用户信息 10000 条、商品信息 5000 条、配置 500 条...）

L2: 分布式缓存 (Redis)
  - 当前缓存 Key 设计是否规范？（命名空间:业务:标识:版本 的层次结构）
  - Big Key 怎么检测和拆分？（如粉丝列表 ZSet 超过 10 万）
  - 热点 Key 怎么自动探测？（JD-hotkey 集成方案）

L3: 持久层 (MySQL + ES)  
  - Canal 增量同步 ES 的延迟怎么监控？
```

### 4.2 热点探测与防护：

- JD-hotkey 的 Worker+Dashboard 部署后，代码层面怎么集成？（对比当前 pom 声明了但无实现）
- 热点 Key 本地缓存怎么设 TTL？太长→数据不一致，太短→保护不了 Redis
- 除了 JD-hotkey，还有哪些自研方案？（如滑动窗口统计 + Caffeine 自动晋升）

### 4.3 缓存一致性深度分析：

- 延迟双删的延迟时间怎么科学设定？（依据 DB 主从同步延迟 + 业务容忍度）
- 更新 DB 成功但两次删除缓存都失败 → MQ 兜底删除 → MQ 发送也失败？终极兜底是什么？
- 多级缓存（Caffeine→Redis→DB）的级联失效怎么管理？先删哪一级？

### 4.4 请设计：
- 缓存的监控指标体系（命中率、穿透率、击穿次数、热点 Key 列表）
- Grafana 缓存大盘的 Panel 设计

---

## 维度五：分布式事务补偿体系

> my-xhs 当前状态：本地消息表 + RocketMQ 事务消息（订单创建）。核心链路做得好，但补偿链路有致命缺口（`ORDER_COMPENSATION_TOPIC` 无消费者）。

### 5.1 请对现有事务方案做深度审查：

- RocketMQ 事务消息半消息的超时回查：`checkLocalTransaction` 最多回查几次？间隔多少？
- 本地消息表 `LocalMessageRetryJob` 的扫描频率、批量大小、重试上限是否合理？
- 为什么 `ORDER_COMPENSATION_TOPIC` 没有消费者？设计意图是什么？现在应该怎么补？

### 5.2 P8 级别的多模式分布式事务体系：

| 模式 | 适用场景 | my-xhs 需求 |
|------|---------|:---:|
| **事务消息** | 下单：写 DB + 发 MQ 原子性 | ✅ 已实现 |
| **本地消息表** | 非实时一致性（库存异步扣减） | ✅ 已实现 |
| **TCC (Try-Confirm-Cancel)** | 跨服务强一致性（账户扣款） | ❌ 需要 |
| **Saga 模式** | 长事务（退款流程 3 步以上） | ❌ 需要 |
| **Seata AT 模式** | 对业务入侵低 | 🔵 备选 |

### 5.3 请详细实现：
- 退款流程的 Saga 模式：freezeInventory → refundPayment → releaseCoupon → 补偿逻辑
- `ORDER_COMPENSATION_TOPIC` 消费者的完整实现（含幂等 + 死信 + 人工兜底）
- RocketMQ 死信队列的消费者（消息重试 16 次后进 DLQ → 告警 + 人工处理）
- 一个 "补偿管理后台" 的接口设计（查看失败补偿记录 + 手动触发补偿 + 对账差异列表）

### 5.4 请分析：
- TCC 模式中 Cancel 操作的幂等和空回滚问题
- 为什么 my-xhs 当前不用 Seata AT？（对性能的影响 + 全局锁）

---

## 维度六：消息队列高级特性

> my-xhs 当前状态：使用了 RocketMQ 的普通消息、事务消息、延时消息、Canal→MQ。**缺顺序消息、批量消息、死信处理、消息轨迹**。

### 6.1 请逐项说明以下高级特性在 my-xhs 中如何落地：

| 特性 | 应用场景 | 实现要点 |
|------|---------|---------|
| **顺序消息** | 评论发布按时间序消费 | MessageQueueSelector 按 noteId 选择队列 |
| **批量消息** | 计数服务攒批发送 | 攒够 100 条 or 100ms → 批量 send |
| **消息过滤** | 同一 Topic 不同消费场景 | Tag（简单） or SQL92（复杂条件） |
| **延时消息优化** | 优惠券过期的精准延时 | 替代当前 XXL-Job 每小时扫描 |
| **死信队列** | 消费失败 16 次后 | 独立消费者 → 入库 → 告警 → 人工处理 |
| **消息轨迹** | 排查消息丢失 | RocketMQ trace 功能开启 |
| **Exactly Once** | 支付回调消息 | 消费端业务幂等（建议用 Redis + DB 双重保障） |

### 6.2 请用代码示例说明：
- RocketMQ 顺序消息的 Producer/Consumer 完整实现
- 计数服务批量消费 + 攒批发送的双 Buffer 设计方案
- 死信队列消费者的完整实现（入库 + 告警 + 定期扫描重试）
- 消息轨迹的开启和查询方式

### 6.3 附加问题：
- RocketMQ Consumer 的消费线程数、pullBatchSize 怎么设？（不同 Topic 不同配置）
- 消息积压时怎么快速消解？（临时扩容 Consumer 实例）

---

## 维度七：可观测性三大支柱（Metrics + Logging + Tracing）

> my-xhs 当前状态：Prometheus 9 条告警 + TraceId 手动透传 + SkyWalking OAP 部署。**SkyWalking Agent 未挂载、Grafana Dashboard 缺失、无结构化日志**。

### 7.1 Metrics 指标体系：

请设计 my-xhs 完整的指标采集矩阵：

**RED 指标（每个服务）**：
- Rate（请求量）、Errors（错误率）、Duration（P50/P90/P99）

**USE 指标（每个实例）**：
- CPU 使用率、内存使用率、磁盘 IO、网络带宽

**业务指标**：
- 下单成功率、支付成功率、用户注册量、GMV
- 库存预扣成功率、优惠券核销率
- Feed 流刷新 P99、搜索响应 P99

**中间件指标**：
- HikariCP 活跃/空闲/等待连接数
- Redis 连接池使用率、命中率
- RocketMQ 消息积压量、消费 TPS
- ES 查询 P99、索引速率

请给出每个指标的 PromQL 表达式和 Grafana Panel 类型。

### 7.2 Logging 日志体系：

- 结构化日志（JSON 格式）的输出配置（Logback JSON Encoder）
- 日志级别动态调整（不用重启，Nacos Config 推送）
- ELK/EFK 部署方案（docker-compose 追加 Filebeat + Elasticsearch + Kibana）
- 全链路日志查询：通过 TraceId 在 Kibana 中串联所有服务的日志

### 7.3 Tracing 链路追踪：

- SkyWalking Agent 挂载后，如何验证全链路追踪生效？
- TraceId 在 Gateway → Feign → MQ → DB 的透传链路是否完整？
- 如何用 SkyWalking 的拓扑图发现性能瓶颈？
- 数据库慢 SQL 如何自动上报到 SkyWalking？

### 7.4 Grafana Dashboard 设计：

请给出以下 Dashboard 的 Panel 清单：
1. **JVM 大盘**：Heap/Non-Heap/GC/Threads/CPU（参考 Spring Boot 2.x Dashboard）
2. **业务大盘**：下单漏斗（浏览→加购→下单→支付）、实时 GMV、错误率热力图
3. **中间件大盘**：MySQL/Redis/RocketMQ/ES 四大中间件的健康分
4. **限流/熔断大盘**：各服务限流触发次数、熔断打开时长、降级比例

---

## 维度八：配置管理与 Feature Flag

> my-xhs 当前状态：Nacos 只做注册中心。各服务 `spring.cloud.nacos.config.enabled` 未启用。配置全靠本地 yml 文件。

### 8.1 Nacos Config 全量接入方案：

- 各服务的配置迁移计划（哪些配置放 Nacos、哪些保留本地）
- 配置分组策略（`DEFAULT_GROUP` vs `ORDER_GROUP` vs `PAYMENT_GROUP`）
- 共享配置抽取（`my-xhs-common.yml`：Redis、Feign、Sentinel 等公共配置）
- 敏感配置加密（Jasypt 或 Nacos 内置加密插件）
- 配置变更监听 + 热刷新（`@RefreshScope` vs `@ConfigurationProperties` vs `EnvironmentChangeEvent`）

### 8.2 Feature Flag 动态开关：

- 用 Nacos Config 实现 Feature Flag 的设计方案
- 灰度功能开关：新功能先对 5% 用户开放 → 验证 → 全量
- 降级开关：大促期间一键关闭非核心功能
- AB 实验开关：不同用户群体看到不同推荐算法

### 8.3 请实现：
- 一个 `@FeatureFlag` 注解，标记某个接口是否受开关控制
- 开关变更的监听机制（`@NacosConfigListener` 或 `EnvironmentChangeEvent`）
- 开关变更日志：谁在什么时候改了什么开关？影响范围？

---

## 维度九：灰度发布与流量治理

> my-xhs 当前状态：Gateway 有 `TrafficColoringFilter`（注入 6 个染色标记）+ `GrayRouteFilter`。**有染色但缺少完整的灰度发布闭环**。

### 9.1 请设计完整的灰度发布方案：

```
灰度发布完整闭环：

1. 流量染色（已实现）：
   - 标记：X-Gray-Tag (stable/canary)、X-AB-Group (A/B/C)
   - 染色规则：白名单用户、按 userId hash % 100、地理位置

2. 流量路由（部分实现）：
   - Nacos 元数据标记实例版本（version=v1, version=v2）
   - Spring Cloud LoadBalancer 按 X-Gray-Tag 选择实例

3. 灰度验证（缺失）：
   - 金丝雀实例的指标（QPS/错误率/P99）与基线实例实时对比
   - 差异超过阈值 → 自动触发回滚

4. 灰度放大（缺失）：
   - 5% → 20% → 50% → 100%，每步观察 N 分钟
   - 支持通过 Nacos Config 调整比例，无需重启

5. 回滚（缺失）：
   - 一键将 X-Gray-Tag=CANARY 的流量重置为 STABLE
```

### 9.2 请用代码/配置说明：
- Nacos 实例 metadata 如何标记版本（`spring.cloud.nacos.discovery.metadata.version=v2`）
- Spring Cloud LoadBalancer 如何根据 `X-Gray-Tag` header 选择实例
- 灰度比例动态调整的 Nacos 配置格式

### 9.3 附加：
- AB 实验的流量分配（A 组 50%、B 组 50%）和指标对比
- 压测流量的隔离（`X-Pressure-Test=true` → 路由到独立集群 or 影子表）

---

## 维度十：数据库深度设计

> my-xhs 当前状态：4 个 MySQL 实例 + 订单 ShardingSphere 4库×4表 + `order_no_mapping`。**缺读写分离、慢查询治理、数据归档、索引审查**。

### 10.1 读写分离方案：

- ProxySQL 的配置和使用（docker-compose 追加）
- ShardingSphere-JDBC 读写分离的配置（yaml 格式）
- 写后读一致性怎么保证？（HintManager 强制走主库 or 等从库延迟）
- 从库延迟监控和自动摘除

### 10.2 慢查询治理：

- MySQL 慢查询日志开启 + Filebeat 采集到 ES
- 慢 SQL 自动分析（Explain → 缺少索引 → 创建索引建议）
- MyBatis `SqlGuardInterceptor`（已在限流篇讨论的慢 SQL 熔断）

### 10.3 数据归档策略：

- 订单表：6 个月前的数据归档到历史表 or 冷存储
- 行为日志表（`t_user_behavior`）：按天分表 or 7 天清理
- 历史数据保留策略：热数据（3个月）→ 温数据（3-12个月）→ 冷数据（归档文件）

### 10.4 索引设计审查：

- 对以下高频查询给出推荐索引：
  - `SELECT * FROM t_order WHERE user_id = ? AND status = ? ORDER BY created_at DESC`
  - `SELECT * FROM t_note WHERE status = 1 ORDER BY like_count DESC LIMIT 20`
  - `SELECT COUNT(*) FROM t_user_behavior WHERE target_type = 1 AND action_type IN (1,3,5) AND created_at BETWEEN ? AND ?`
- 联合索引的字段顺序原则（等值查询在前，范围查询在后）

---

## 维度十一：测试体系

> my-xhs 当前状态：仅 2 个单元测试类（common 模块），覆盖率 ≈ 0%。

### 11.1 测试金字塔设计：

| 层级 | 工具 | 目标覆盖率 | 核心内容 |
|------|------|:---:|------|
| 单元测试 | JUnit 5 + Mockito + AssertJ | 核心链路 60% | Service 层逻辑、工具类、Lua 脚本 |
| 集成测试 | Spring Boot Test + Testcontainers | 核心链路 30% | Controller + DB + Redis + MQ 全链路 |
| 契约测试 | Spring Cloud Contract | 关键 Feign 接口 | 服务间 API 兼容性 |
| 端到端测试 | 人工/自动化脚本 | 主流程 100% | 注册→登录→购物车→下单→支付 |

### 11.2 请给出详细的测试编写计划：

**第一优先级（立即）** —— 核心交易的单元测试：

| 模块 | 类 | 测试场景 | 用例数 |
|------|------|---------|:---:|
| Order | OrderService | 创建订单成功/库存不足/幂等/事务消息回滚/关单 | 8 |
| Payment | PaymentService | 支付成功/重复支付/回调幂等/策略路由 | 6 |
| Inventory | InventoryService | 分桶预扣/超卖防护/释放/桶间均衡 | 6 |
| Gateway | GatewayAuthFilter | Token有效/过期/黑名单/白名单/缺Token | 5 |
| Coupon | CouponService | Lua领券/退券/责任链校验 | 4 |

**第二优先级（1-2周内）** —— 社交+缓存：

| 模块 | 类 | 测试场景 |
|------|------|---------|
| Cart | CartService | Lua加购/删除/全选/超限 |
| Counter | CounterService | 递增/递减/归零保护/对账修复 |
| Content | DFAFilter | 命中/不命中/干扰字符/全角半角 |
| Common | CacheHelper | 穿透/击穿/雪崩/延迟双删 |

### 11.3 集成测试示例：

- `@SpringBootTest` + `@Testcontainers` 启动真实 MySQL/Redis/RocketMQ 容器
- 下单全链路集成测试：购物车加购 → 下单 → 库存预扣 → 支付 → 库存确认扣减
- 支付回调幂等集成测试：并发发送 10 次相同回调 → 只处理一次

---

## 维度十二：CI/CD + 容器化

> my-xhs 当前状态：有 `docker-compose.yml`（中间件部署），无 Jenkinsfile、无 Dockerfile、无 K8s 部署文件。

### 12.1 请设计完整的 CI/CD 流水线：

```
Jenkins Pipeline 阶段：

Stage 1: Checkout (拉取代码)
Stage 2: Compile + Unit Test (编译 + 单元测试)
Stage 3: Static Code Analysis (SonarQube 代码质量扫描)
Stage 4: Package (Maven 打包 + Docker 镜像构建)
Stage 5: Push (推送 Docker 镜像到私有仓库)
Stage 6: Deploy to STAGING (部署到预发布环境)
Stage 7: Integration Test (集成测试)
Stage 8: Deploy to PRODUCTION (滚动更新到生产)
       - 灰度发布（先 10% → 观察 5 分钟 → 100%）
       - 失败自动回滚
```

### 12.2 请给出：

- Jenkinsfile（完整 Groovy 脚本，可复制使用）
- 每个微服务的 Dockerfile 模板（多阶段构建，含 SkyWalking Agent）
- Kubernetes Deployment + Service YAML（以 order-service 为例）
- 滚动更新策略配置（`maxSurge: 1, maxUnavailable: 0`）

---

## 输出要求

请按以下结构输出分析报告：

```markdown
## 维度 N：XXX

### N.1 当前状态分析（my-xhs 实际代码中的情况）

### N.2 P8 级别差距（与业界标准的对比）

### N.3 完整实现方案（分 Layer 展开，每层有关键代码示例）

### N.4 需要修改的 my-xhs 文件清单

### N.5 验收标准
```
