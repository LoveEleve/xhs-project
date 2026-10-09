# 【姓名】

电话：【】｜邮箱：【】｜城市：【】｜工作年限：【】
求职意向：Java 后端开发（交易 / 高并发 / 稳定性方向）｜AI 工程效能

## 专业技能
- 语言与框架：Java 17、Spring Boot、Spring Cloud Alibaba、MyBatis / MyBatis-Plus
- 数据与中间件：MySQL（ShardingSphere 4 库 × 4 表）、Redis（Sentinel / 多级缓存）、RocketMQ、Elasticsearch、Canal、Nacos、Sentinel、XXL-Job
- 专项能力：分布式事务与最终一致性、缓存一致性、消息幂等与乱序治理、限流降级、可观测性（Prometheus / Grafana / SkyWalking / ELK）、故障注入与混沌演练、JVM / 性能调优
- 工程效能：AI 驱动研发流水线（Harness Engineering）、CI 门禁、单测与覆盖率治理

---

## 项目一 · 内容电商平台（15 微服务 / 27 容器）｜核心开发 / 稳定性 Owner

**项目介绍**：内容与交易一体的电商平台——交易侧覆盖商品、购物车、库存、优惠券、订单、支付、结算，内容侧覆盖用户、笔记、Feed、搜索、推荐，另含 IM、通知、计数、网关与认证，共 15 个 Spring Cloud 微服务。基础设施：MySQL 分库分表（订单 4 库 × 4 表）+ Redis Sentinel + RocketMQ + Elasticsearch + ShardingSphere + Canal + XXL-Job；ELK / Prometheus + Grafana / SkyWalking 全链路可观测，Docker 编排 27 容器；配套故障注入、混沌演练与容量压测环境（单机同城双活仿真口径）。

**核心工作**
- **交易一致性**：库存"Redis 分桶 + Lua 原子预扣 + DB 账本 + TCC fence 状态机"三级路径；下单与预扣用事务消息 + 本地消息表 + Broker 回查保证一致；订单状态全部条件 UPDATE、事件表以序号唯一键 + 乐观锁收敛并发。
- **支付与售后**：支付/退款双状态机 + 渠道策略化回调（三态判定、重复回调不改状态、迟到成功条件翻转补偿）；T-1 日切账单三方对账，差异分类挂账并自动收敛。
- **缓存与搜索**：布隆过滤器 + 空值缓存 + 逻辑过期异步单飞防穿透/击穿；事务提交后删缓存 + 延迟双删 + MQ 广播兜底；Canal + MQ 双通道同步 ES，外部版本号拒绝陈旧写、tombstone 防旧消息复活。
- **分片与数据访问**：ShardingSphere 4 库 × 4 表 + 订单号映射表反查；定位分片倾斜根因（Snowflake 增量被 16 整除）改哈希取模并迁移存量数据；支付表/映射表走独立数据源绕过分片路由，为支付域独立拆分预留。
- **稳定性与容灾**：同 zone 优先路由 + 数据面 zone 感知（MySQL 读本 zone、Redis 读副本写主库、故障自动降级回切）；MySQL 故障转移演练与 Redis 双主仿真；发布链路"监听 PID == 启动 PID"校验根治旧进程假成功。
- **可观测与告警**：15 服务结构化 JSON 日志 + Filebeat → Logstash → ES 按 traceId 检索；SkyWalking 接入（Spring Boot 3 插件适配 + 开销实测）；Prometheus 告警分级 + SLO 错误预算，修复告警通知黑洞。
- **配置与调度治理**：修复 SCA 死配置（shared-configs 失效 → `spring.config.import: optional:nacos:`），15/15 服务真实从 Nacos 加载；XXL-Job 任务审计，修复"从未执行"与丢窗口任务。
- **性能与 JVM**：定位类加载锁热点（每请求 Class.forName 导致 92/99 Tomcat 线程 BLOCKED）改静态桥接；G1 停顿目标 + 线程池隔离 + MDC 跨池 traceId 传递。

**关键结果**
- 性能：product 1,074→4,871 RPS（4.5x，P99 50ms）、search 508→1,448 RPS（P99 191→61ms）、home 1,861 RPS / user info 5,846 RPS（P99 ≤ 50ms）。
- 一致性：20 并发预扣不超卖、TCC 11 场景全通过（幂等/空回滚/悬挂拒绝/超量拒绝）；注入 75s 事务回查延迟后库存仍只扣一次；订单 8 场景并发对抗全过。
- 分片：倾斜治理后 16/16 片均匀（迁移存量 682 行、路由抽查全命中）。
- 容灾：MySQL 故障转移 RTO ≈ 32s（停主 10.2s / 提升 0.087s / 应用切换 22s）并沉淀 5 项短板整改路线；Redis 切主 2.3s 业务无错乱；同 zone 优先在跨区延迟注入（netem）下吞吐 +34%、P99 降低 33%~47%。
- 治理：15/15 服务配置真实生效；告警规则 31→40 条分级 + SLO 落地；内存占用 45→28Gi。

---

## 项目二 · AI 驱动研发流水线落地（Harness Engineering）｜独立落地（基于开源技能包二次开发）

**项目介绍**：将"人类设计约束、AI 写代码、机器验证"的 Harness Engineering 方法论落地到上述微服务平台：自动检测技术栈并渲染项目专属的 AI 编码约束体系（Owner Agent + 规则 + 技能），按 6 阶段流水线把 AI 编码纳入可验证的工程流程，并沉淀领域知识库。

**核心工作**
- 落地 AI 编码约束体系：自动检测 Spring Cloud Alibaba + MyBatis-Plus + ShardingSphere 技术栈，渲染 Owner Agent + 5 条规则 + 40+ 技能并注册到 AI 编码工具；修正多模块 Maven / SCA 检测边界【N】处。
- 按"需求 → 可测 AC → 垂直切片 TDD → 单测 → 双轴评审 → CI 门禁 → 部署验证"6 阶段流水线交付【N】个真实需求，产出 REQ/AC/TC 机器可读迭代协议与测试报告；核心逻辑覆盖率【x% → y%】。
- 质量门禁机械化：静态分析、架构约束、覆盖率检查接入现有 GitLab CI / Gitea Actions 门禁，实测拦截【N】次违规提交。
- 知识库沉淀：将平台 76 篇排查文档、数据库表结构、部署踩坑沉淀为 wiki（业务）+ tech（链路）分层知识库 + CONTEXT 术语表 + ADR。
- 原创扩展：自研【N】个领域技能（分片倾斜审计 / 同 zone 路由验证 / 对账任务模板）；修复上游技能包 opencode 注册适配问题并提交 PR。
