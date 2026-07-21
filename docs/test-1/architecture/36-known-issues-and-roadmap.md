# 36 已知不足与改进路线图

> 目标读者：P7+ 技术专家 | 面向技术评审
> 按严重程度（P0-P3）排列所有已知问题
> 最后更新：2026-07-08（Stage 4 改造完成后）

---

## 一、已完成项（Stage 2/3/4）

以下问题已在 Stage 2、Stage 3、Stage 4 中解决：

### 基础设施

| 编号 | 优先级 | 问题 | 完成阶段 | 说明 |
|------|--------|------|----------|------|
| DONE-01 | P0 | Redis 3 实例设计未实施 | Stage 2 | docker-compose 增加 16380/16381 两个实例，配置分用途淘汰策略 |
| DONE-02 | P0 | MySQL 4 实例全部单点 | Stage 3 | 4 个实例各增加 1 个 Slave，实现主从复制 + 读写分离（@ReadOnly 注解） |
| DONE-03 | P1 | Redis 无高可用 | Stage 4 | 部署 3 个 Sentinel 节点（26379/26380/26381），15 个服务启用 Sentinel 模式 |
| DONE-04 | P1 | MySQL 无故障转移 | Stage 4 | 11 个非 ShardingSphere 服务添加 JDBC Multi-Host 参数（autoReconnect + failOverReadOnly） |

### 多活架构

| 编号 | 优先级 | 问题 | 完成阶段 | 说明 |
|------|--------|------|----------|------|
| DONE-05 | P1 | Zone 多活架构缺失 | Stage 4 | ZoneContext + ZonePreferenceFilter + DynamicDataSource + ZoneLoadBalancer 完整实现 |
| DONE-06 | P2 | Redis 操作无审计日志 | Stage 4 | RedisMethodInterceptor 拦截 Redis 命令 + RedisCommandEvent 事件日志 |

### 微服务治理

| 编号 | 优先级 | 问题 | 完成阶段 | 说明 |
|------|--------|------|----------|------|
| DONE-07 | P1 | Dubbo 与 Feign 双协议并存 | Stage 4 | Dubbo 3.3.0 依赖全部移除，5 个 API 子模块删除，调用链路简化为纯 Feign |

---

## 二、未解决的 P0 问题

### P0-01: RocketMQ Broker 单点（高风险）

- **影响范围**：整个下单链路、库存异步扣减、支付/退款通知、Canal 数据同步、Feed 推送
- **当前状态**：单 Broker（ASYNC_MASTER），无 Slave，无故障切换
- **故障后果**：
  - 订单事务消息半消息全部丢失 → 下单链路断裂
  - 库存异步扣减中断 → 库存不一致
  - 支付/退款通知中断 → 支付状态不同步
  - Canal 数据同步中断 → ES 索引停滞
- **改进方案**：增加至少 1 个 Broker Slave，配置 SYNC_MASTER 模式
- **预估工时**：2d

### P0-02: @EnableScheduling 缺失（Bug）

- **影响范围**：inventory、search 服务
- **当前状态**：inventory、search 启动类缺少 `@EnableScheduling` 注解
- **故障后果**：
  - inventory：预扣超时回收定时任务（每 5 分钟）不执行 → 库存永久锁定
  - search：热搜计算定时任务（每 5 分钟）不执行 → 热搜榜不更新
  - search：索引重建定时任务（每天凌晨 4 点）不执行 → ES 索引与 DB 数据不一致
- **改进方案**：inventory、search 启动类添加 `@EnableScheduling`
- **预估工时**：0.5d

### P0-03: 核心业务服务零测试

- **影响范围**：inventory、order、payment 三个核心服务
- **当前状态**：14 个业务服务模块零测试覆盖，仅 common 模块有 7 个测试类 35 个用例
- **改进方案**：
  1. inventory：补充分桶预扣 Lua 脚本单元测试、预扣超时回收逻辑测试
  2. order：补充订单状态机流转测试、事务消息回查测试
  3. payment：补充支付状态机测试、退款逻辑测试
- **预估工时**：3d

### P0-04: 多环境 Profile 完全缺失

- **影响范围**：全部 15 个服务
- **当前状态**：K8s 启动命令中有 `-Dspring.profiles.active=k8s`，但对应的 `application-k8s.yml` 不存在。无 `application-test.yml`、`application-prod.yml`
- **改进方案**：创建 application-k8s.yml、application-test.yml、application-prod.yml，数据库连接、Redis 地址、MQ 地址按环境区分
- **预估工时**：1d

---

## 三、未解决的 P1 问题

### P1-01: RocketMQ NameServer 单点

- **影响范围**：全部 MQ 通信
- **当前状态**：单 NameServer 节点
- **改进方案**：增加第 2 个 NameServer
- **预估工时**：1d

### P1-02: Docker 容器无资源限制

- **影响范围**：全部 19 个容器
- **当前状态**：docker-compose.yml 中所有容器无 CPU/内存限制
- **改进方案**：为每个容器添加 `deploy.resources.limits`（CPU + memory）
- **预估工时**：0.5d

### P1-03: 数据库连接池未差异化

- **影响范围**：全部 15 个服务
- **当前状态**：所有服务统一使用连接池大小 20
- **改进方案**：home（BFF 聚合，依赖 10 个服务）→ 30，order → 25，其他 → 15-20
- **预估工时**：0.5d

### P1-04: 数据库 IP 硬编码

- **影响范围**：11 个非 ShardingSphere 服务
- **当前状态**：数据库连接地址硬编码为 `localhost:13306-13309`
- **改进方案**：改为环境变量（`MYSQL_HOST`、`MYSQL_PORT`）或 Docker DNS
- **预估工时**：1d

### P1-05: 敏感信息明文存储

- **影响范围**：全部服务配置文件
- **当前状态**：数据库密码、Redis 密码、JWT 密钥全部明文写在 yml 中
- **改进方案**：Jasypt 加密或迁移到 K8s Secrets
- **预估工时**：1d

### P1-06: Maven 静态检查插件缺失

- **影响范围**：构建流程
- **当前状态**：无 JaCoCo、SpotBugs、Checkstyle、PMD
- **改进方案**：添加 SpotBugs + JaCoCo（覆盖率门禁 60%）
- **预估工时**：1d

### P1-07: API 文档完全缺失

- **影响范围**：全部 15 个服务
- **当前状态**：无 Swagger/OpenAPI/Knife4j，接口变更无感知
- **改进方案**：集成 SpringDoc OpenAPI + Knife4j，Gateway 统一 Swagger UI
- **预估工时**：1d

---

## 四、未解决的 P2 问题

### P2-01: Canal 单点

- **影响范围**：ES 索引同步、Redis 缓存失效
- **当前状态**：3 个 Instance，单节点部署
- **改进方案**：Canal Server HA 模式（ZooKeeper 协调）
- **预估工时**：2d

### P2-02: Redis 淘汰策略确认

- **影响范围**：Business Redis（16381）
- **当前状态**：Sentinel 已启用，淘汰策略是否已正确配置为 `noeviction` 待确认
- **改进方案**：通过 `CONFIG GET maxmemory-policy` 确认并修正
- **预估工时**：0.5d

### P2-03: InventoryService/SpuService 线程池无关闭

- **影响范围**：inventory、product 服务
- **当前状态**：自定义线程池未声明 `@PreDestroy` 关闭方法
- **故障后果**：JVM 退出时队列中未完成任务可能丢失
- **改进方案**：添加 `@PreDestroy` 方法调用 `shutdown()` + `awaitTermination()`
- **预估工时**：0.5d

### P2-04: counter 服务过度拆分

- **影响范围**：counter 服务（5 个接口、300 行核心代码）
- **当前状态**：与 content 共享 MySQL，独立服务徒增运维成本
- **改进方案**：合并到 analytics 或 content 服务
- **预估工时**：1d

### P2-05: payment 服务过度拆分

- **影响范围**：payment 服务（5 个接口）
- **当前状态**：与 order 存在双向 Feign 调用，逻辑极简（Mock 模式）
- **改进方案**：合并到 order 服务
- **预估工时**：1d

### P2-06: search + recommend 耦合

- **影响范围**：search 服务
- **当前状态**：搜索（ES 查询优化）和推荐（召回/排序算法）两个不同技术领域耦合在同一服务
- **改进方案**：拆分为 search + recommend 两个独立服务
- **预估工时**：3d

### P2-07: Maven Checkstyle/PMD/Enforcer 缺失

- **影响范围**：构建流程
- **当前状态**：代码风格、质量、依赖一致性无自动化检查
- **改进方案**：添加 Checkstyle + PMD + Maven Enforcer
- **预估工时**：1d

### P2-08: Dubbo Service 残留文件未清理

- **影响范围**：coupon、notification、counter、cart、analytics 服务
- **当前状态**：5 个服务中存在 `dubbo/` 目录下的 Dubbo Service 接口文件，Dubbo 依赖已移除，这些文件不再使用
- **改进方案**：删除 `*/dubbo/*DubboService.java` 残留文件
- **预估工时**：0.5d

### P2-09: RocketMQ 开发模式配置

- **影响范围**：RocketMQ Broker
- **当前状态**：`autoCreateTopicEnable=true`（开发模式）
- **改进方案**：改为 `false`，Topic 需要预先创建
- **预估工时**：0.5d

### P2-10: RocketMQ DLQ 无监控

- **影响范围**：死信队列
- **当前状态**：DLQ 消息无任何告警
- **改进方案**：增加 DLQ 消息数量监控 + 告警
- **预估工时**：1d

---

## 五、未解决的 P3 问题

### P3-01: OWASP Dependency-Check 缺失

- **影响范围**：依赖安全
- **改进方案**：添加 OWASP Dependency-Check 插件
- **预估工时**：0.5d

### P3-02: MQ Topic 无命名空间前缀

- **影响范围**：16 个 Topic
- **改进方案**：Topic 增加 `MYXHS_` 前缀
- **预估工时**：1d

### P3-03: .editorconfig + commit message 规范

- **影响范围**：全部开发者
- **改进方案**：创建 .editorconfig + 制定 Conventional Commits 规范
- **预估工时**：0.5d

### P3-04: 文件存储扩展性

- **影响范围**：content 服务
- **当前状态**：FileStorageService 接口抽象已实现，但仅有 LocalFileStorageService
- **改进方案**：实现 MinIO/OSS 的 FileStorageService，生产环境替换
- **预估工时**：2d

---

## 六、文档已规划但代码未实现

以下功能在架构文档中已详细设计，但代码层面尚未实施（共 18 项）：

| 编号 | 功能 | 所属模块 | 说明 |
|------|------|----------|------|
| TODO-01 | @Desensitize 数据脱敏 | common | 敏感字段自动脱敏注解 |
| TODO-02 | XssFilter XSS 防护 | gateway | 防跨站脚本攻击 |
| TODO-03 | @AuditLog 操作审计 | common | 关键操作日志记录 |
| TODO-04 | RBAC 权限管理 | gateway/user | 基于角色的访问控制 |
| TODO-05 | CSRF 防护 | gateway | 跨站请求伪造防护 |
| TODO-06 | IP 黑名单/白名单 | gateway | 恶意 IP 拦截 |
| TODO-07 | 备份恢复脚本 | ops | 数据库/Redis/ES 备份 |
| TODO-08 | RedisCacheRebuildService | common | 缓存重建服务 |
| TODO-09 | CacheReconciliationJob | common | 缓存对账定时任务 |
| TODO-10 | Promtail + Loki 日志收集 | ops | 替代 ELK 的轻量方案 |
| TODO-11 | LogstashEncoder JSON 日志 | common | 结构化日志输出 |
| TODO-12 | HPA 自动扩缩容 | k8s | 水平 Pod 自动伸缩 |
| TODO-13 | PDB 预算保护 | k8s | Pod 中断预算 |
| TODO-14 | ResourceQuota 资源配额 | k8s | 命名空间资源限制 |
| TODO-15 | NetworkPolicy 网络策略 | k8s | 服务间网络隔离 |
| TODO-16 | 蓝绿/金丝雀部署 | k8s | 零停机部署 |
| TODO-17 | AlertManager 通知渠道 | ops | 邮件/钉钉/飞书告警 |
| TODO-18 | node_exporter + 中间件 exporter | ops | 主机和中间件指标采集 |
| TODO-19 | 视频上传 | content | 多媒体内容支持 |
| TODO-20 | CDN 加速 | infra | 静态资源分发 |
| TODO-21 | MinIO 对象存储实现 | content | 生产环境文件存储 |
| TODO-22 | ES 索引别名 + ILM | search | 索引生命周期管理 |

---

## 七、架构改进路线图

### 短期（1-2 周）：修复 P0 问题

```
1. RocketMQ Broker 增加 Slave → SYNC_MASTER 模式（P0-01）
2. inventory/search 添加 @EnableScheduling（P0-02）
3. 创建 application-k8s.yml + 多环境 Profile（P0-04）
4. inventory/order/payment 补充核心单元测试（P0-03）
```

### 中期（1 个月）：补齐 P1 问题

```
5. RocketMQ NameServer 双节点（P1-01）
6. Docker 容器资源限制（P1-02）
7. 数据库 IP 环境变量化 + 敏感信息加密（P1-04, P1-05）
8. JaCoCo + SpotBugs + API 文档（P1-06, P1-07）
9. 连接池差异化（P1-03）
```

### 长期（2-3 个月）：P2 优化 + 文档落地

```
10. counter/payment 服务合并（P2-04, P2-05）
11. search + recommend 拆分（P2-06）
12. Canal 高可用（P2-01）
13. Dubbo 残留文件清理（P2-08）
14. 线程池关闭修复（P2-03）
15. 18 项文档规划功能按优先级实施
```

---

## 八、优先级修复总清单

| 优先级 | 编号 | 类别 | 问题 | 预估工时 | 状态 |
|--------|------|------|------|----------|------|
| **P0** | DONE-01 | Redis | 3 实例设计 | — | ✅ 已完成 |
| **P0** | DONE-02 | MySQL | 主从复制 | — | ✅ 已完成 |
| **P0** | P0-01 | RocketMQ | Broker 单点 | 2d | 待修复 |
| **P0** | P0-02 | Bug | @EnableScheduling 缺失 | 0.5d | 待修复 |
| **P0** | P0-03 | 测试 | 核心业务服务零测试 | 3d | 待修复 |
| **P0** | P0-04 | 配置 | 多环境 Profile 缺失 | 1d | 待修复 |
| **P1** | DONE-03 | Redis | Sentinel 高可用 | — | ✅ 已完成 |
| **P1** | DONE-04 | MySQL | Multi-Host 故障转移 | — | ✅ 已完成 |
| **P1** | DONE-05 | Zone | Zone 多活架构 | — | ✅ 已完成 |
| **P1** | DONE-07 | 治理 | Dubbo 全局移除 | — | ✅ 已完成 |
| **P1** | P1-01 | RocketMQ | NameServer 单点 | 1d | 待修复 |
| **P1** | P1-02 | Docker | 容器无资源限制 | 0.5d | 待修复 |
| **P1** | P1-03 | MySQL | 连接池未差异化 | 0.5d | 待修复 |
| **P1** | P1-04 | 配置 | 数据库 IP 硬编码 | 1d | 待修复 |
| **P1** | P1-05 | 安全 | 敏感信息明文 | 1d | 待修复 |
| **P1** | P1-06 | 构建 | Maven 静态检查缺失 | 1d | 待修复 |
| **P1** | P1-07 | API | 文档完全缺失 | 1d | 待修复 |
| **P2** | DONE-06 | Zone | Redis 命令拦截 + 事件日志 | — | ✅ 已完成 |
| **P2** | P2-01 | Canal | 单点 | 2d | 待修复 |
| **P2** | P2-02 | Redis | 淘汰策略确认 | 0.5d | 待修复 |
| **P2** | P2-03 | Bug | 线程池无关闭逻辑 | 0.5d | 待修复 |
| **P2** | P2-04 | 微服务 | counter 过度拆分 | 1d | 待修复 |
| **P2** | P2-05 | 微服务 | payment 过度拆分 | 1d | 待修复 |
| **P2** | P2-06 | 微服务 | search+recommend 耦合 | 3d | 待修复 |
| **P2** | P2-07 | 构建 | Checkstyle/PMD/Enforcer 缺失 | 1d | 待修复 |
| **P2** | P2-08 | 代码 | Dubbo Service 残留文件 | 0.5d | 待修复 |
| **P2** | P2-09 | RocketMQ | 开发模式配置 | 0.5d | 待修复 |
| **P2** | P2-10 | RocketMQ | DLQ 无监控 | 1d | 待修复 |
| **P3** | P3-01 | 构建 | OWASP 依赖检查缺失 | 0.5d | 待修复 |
| **P3** | P3-02 | MQ | Topic 无命名空间前缀 | 1d | 待修复 |
| **P3** | P3-03 | 规范 | editorconfig + commit 规范 | 0.5d | 待修复 |
| **P3** | P3-04 | 存储 | 文件存储扩展性 | 2d | 待修复 |

---

## 九、版本历史

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| v1.0 | 2026-07-08 | 初始版本，基于 Stage 4 改造完成后整理。标记 7 项已完成（Stage 2/3/4），22 项待实施 |
| — | Stage 2 | Redis 3 实例设计完成 |
| — | Stage 3 | MySQL 主从复制 + 读写分离完成 |
| — | Stage 4 | Redis Sentinel + MySQL Multi-Host + Zone 多活 + Dubbo 移除完成 |

---

> **关联文档**：
> → 35-engineering-maturity.md（工程化成熟度评估）
> → 07-chaos-engineering.md（混沌工程）
> → 12-degrade-switch-and-config.md（降级开关与动态配置）
> → 28-distributed-transaction.md（分布式事务方案全景）
> → 31-observability.md（可观测性体系）
> → 32-cicd-deployment.md（CI/CD 与部署）
> → 33-data-storage-design.md（数据存储设计全景）
> → 34-security-deep-dive.md（安全体系深度分析）
