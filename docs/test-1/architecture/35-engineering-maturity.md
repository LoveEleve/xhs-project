# 35 工程化成熟度评估

> 目标读者：P7+ 技术专家 | 面向技术评审
> 评估维度：构建工具、代码规范、测试体系、多环境、API 文档、基础设施、微服务治理
> 最后更新：2026-07-08（Stage 4 改造完成后）

---

## 一、综合评分

| 维度 | 评分 | 评级 | 趋势 |
|------|------|------|------|
| Maven 构建工程化 | 45/100 | 薄弱 | → |
| 代码规范与静态检查 | 35/100 | 薄弱 | → |
| 测试体系 | 62/100 | 及格 | ↑（+20） |
| 多环境与配置管理 | 40/100 | 薄弱 | → |
| API 文档 | 0/100 | 缺失 | → |
| 基础设施可用性 | 85/100 | 良好 | ↑（+25） |
| 微服务治理 | 78/100 | 良好 | ↑（+15） |
| 监控与可观测性 | 70/100 | 及格 | → |
| CI/CD | 72/100 | 及格 | → |
| **综合评分** | **58/100** | **及格** | ↑（+8） |

**关键进展**：经过 Stage 2/3/4 三轮改造，基础设施可用性从 60 提升到 85，测试体系从 42 提升到 62，Dubbo 全面移除使微服务治理从 63 提升到 78。Maven 构建工程化、代码规范、多环境配置仍然是薄弱项。

---

## 二、Maven 构建工程化（45/100）

### 2.1 当前状态

**已配置**：
- BOM 模块统一依赖版本管理（`my-xhs-common/pom.xml` 中 `<dependencyManagement>`）
- Maven Compiler Plugin（Java 17）
- Spring Boot Maven Plugin（可执行 jar）
- Dockerfile Maven Plugin（构建 Docker 镜像）
- `git-commit-id-maven-plugin`（已声明依赖但**未配置生成 git.properties**）

**缺失**：

| 插件 | 用途 | 影响 |
|------|------|------|
| JaCoCo | 代码覆盖率 | 无法量化测试覆盖率，无法设置覆盖率门禁 |
| SpotBugs | 静态缺陷检测 | 潜在 Bug（空指针、资源泄露）未在编译期发现 |
| Checkstyle | 代码风格检查 | 命名、缩进、import 顺序无统一约束 |
| PMD | 代码质量分析 | 重复代码、过长方法、过多参数未检测 |
| Maven Enforcer | 依赖一致性 | 传递依赖版本冲突未拦截 |
| OWASP Dependency-Check | 依赖安全漏洞扫描 | Log4j 级别的漏洞无法感知 |

### 2.2 改进建议

```
P1: 添加 SpotBugs + JaCoCo，设置覆盖率底线 60%
P2: 添加 Checkstyle + PMD，配合 .editorconfig 统一风格
P2: 添加 Maven Enforcer 防止依赖版本漂移
P3: 添加 OWASP Dependency-Check，集成到 CI 流水线
```

### 2.3 BOM 依赖版本管理

当前 BOM 导入顺序（`my-xhs-common/pom.xml`）：

```
Jackson BOM → Spring Boot BOM → Spring Cloud BOM → Spring Cloud Alibaba BOM
```

原则：越底层的 BOM 越靠前声明，上层 BOM 可覆盖底层版本。这个顺序是正确的，但**只有 common 模块定义了 BOM，其他 14 个服务模块未统一引用**，存在版本不一致风险。

**已实施**：Dubbo 3.3.0 BOM 已从 common 模块移除（Stage 4），不再引入 Dubbo 相关依赖。

---

## 三、代码规范与静态检查（35/100）

### 3.1 当前状态

**已配置**：
- 各模块有基本的包结构规范（controller/service/mapper/model）
- common 模块有统一的异常体系（BizException/ResultCode）
- common 模块有统一的分页、响应包装（Result<T>）

**缺失**：
- 无 `.editorconfig` 文件（缩进、换行符、字符集未统一）
- 无 `checkstyle.xml`（命名规范、方法长度、参数数量未约束）
- 无 `spotbugs-exclude.xml`（第三方库误报无法排除）
- 无代码审查 Checklist
- 无 commit message 规范（如 Conventional Commits）

### 3.2 改进建议

```
P1: 创建 .editorconfig（缩进 4 空格、UTF-8、LF 换行）
P2: 创建 checkstyle.xml + 集成 Maven Checkstyle Plugin
P2: 制定 commit message 规范（feat/fix/refactor/docs/test）
P3: 创建 spotbugs-exclude.xml
```

---

## 四、测试体系（62/100）↑

### 4.1 当前状态

**测试类：7 个，用例：35 个，全部通过**

| 测试类 | 用例数 | 模块 | 类型 | 所属阶段 |
|--------|--------|------|------|----------|
| CacheHelperTest | 12 | my-xhs-common | 单元测试 | Stage 1 |
| SegmentIdGeneratorTest | 6 | my-xhs-common | 单元测试 | Stage 1 |
| ZoneContextTest | 16 | my-xhs-common | 单元测试 | Stage 4（新增） |
| ZonePreferenceFilterTest | 14 | my-xhs-common | 单元测试 | Stage 4（新增） |
| ZoneResolverTest | 3 | my-xhs-common | 单元测试 | Stage 4（新增） |
| ZoneConstantsTest | 2 | my-xhs-common | 单元测试 | Stage 4（新增） |

> 注：my-xhs-order 模块另有 3 个测试类（OrderServiceTest 25 用例、OrderControllerTest 22 用例、OrderTransactionServiceTest 4 用例），总计 9 个测试类约 104 个用例。此处按核心覆盖统计 7 个测试类。

**覆盖率**：无 JaCoCo 数据，无法量化。从分布看，仅覆盖 common 公共模块，14 个业务服务模块零测试覆盖。

### 4.2 改进历程

| 阶段 | 测试类 | 用例数 | 覆盖模块 | 说明 |
|------|--------|--------|----------|------|
| Stage 1 | 3 | 18 | common | CacheHelperTest、SegmentIdGeneratorTest 等 |
| Stage 4 | 7 | 35 | common（+zone） | 新增 Zone 多活 4 个测试类 |

### 4.3 缺失

| 项目 | 状态 | 说明 |
|------|------|------|
| 集成测试 | 缺失 | 无 @SpringBootTest，无 Testcontainers |
| 契约测试 | 缺失 | 无 Spring Cloud Contract |
| 性能测试 | 缺失 | 无 JMH 基准测试、无压测脚本 |
| 业务服务测试 | 缺失 | 14 个业务服务零测试 |
| 测试覆盖率门禁 | 缺失 | CI 流水线无覆盖率检查 |
| JaCoCo | 缺失 | pom.xml 无 JaCoCo 插件配置 |
| Testcontainers | 未使用 | 依赖版本已管理（1.19.3），但无任何测试使用 |

### 4.4 改进建议

```
P0: 为 inventory/order/payment 三个核心服务补充单元测试
P1: 添加 JaCoCo 插件 + 设置覆盖率门禁 60%
P1: 引入 Testcontainers 为 inventory/order 编写集成测试
P2: 为 Feign 接口引入 Spring Cloud Contract
P2: CI 流水线添加 test 阶段覆盖率检查
```

---

## 五、多环境与配置管理（40/100）

### 5.1 当前状态

**已配置**：
- Docker Compose 19 容器编排（开发环境）
- Nacos 作为配置中心（各服务 bootstrap.yml 指向 Nacos）
- K8s 部署模板（Deployment/Service/Ingress/ConfigMap）
- Zone 环境变量 `${MYXHS_ZONE}` 已用于数据库路由

**缺失**：
- **无 application-k8s.yml**：K8s 启动命令中有 `-Dspring.profiles.active=k8s`，但对应的 profile 配置文件不存在
- **无 application-test.yml / application-prod.yml**：无多环境 Profile
- **数据库 IP 硬编码**：11 个服务的 `application.yml` 中数据库连接地址仍为 `localhost:13306-13309`，未使用环境变量
- **敏感信息明文**：数据库密码、Redis 密码、JWT 密钥全部明文写在配置文件中
- **无 K8s Secrets**：无密钥管理方案

### 5.2 改进建议

```
P0: 创建 application-k8s.yml / application-test.yml / application-prod.yml
P1: 数据库 IP 改为环境变量（MYSQL_HOST / MYSQL_PORT）
P1: 敏感配置迁移到 K8s Secrets 或 Jasypt 加密
P2: 添加 ConfigMap 挂载非敏感配置
```

---

## 六、API 文档（0/100）

### 6.1 当前状态

**完全缺失**：
- 无 Swagger / OpenAPI 3.0 注解
- 无 Knife4j 集成
- 无 API 文档生成
- 无接口变更通知机制

### 6.2 改进建议

```
P1: 集成 SpringDoc OpenAPI + Knife4j
P1: 为 15 个服务的 Controller 添加 @Operation / @Schema 注解
P2: 配置 Swagger UI 聚合（Gateway 统一入口）
```

---

## 七、基础设施可用性（85/100）↑

### 7.1 MySQL

| 配置项 | 状态 | 完成阶段 |
|--------|------|----------|
| 4 实例按业务域划分 | ✅ 已完成 | Stage 1 |
| 主从复制（1 主 1 从） | ✅ 已完成 | Stage 3 |
| 读写分离（@ReadOnly 注解） | ✅ 已完成 | Stage 3 |
| JDBC Multi-Host 故障转移 | ✅ 已完成 | Stage 4 |
| 连接池差异化 | 部分完成 | — |
| Docker 资源限制 | ❌ 缺失 | — |
| 数据库 IP 环境变量化 | ❌ 缺失 | — |

**JDBC Multi-Host 故障转移（Stage 4）**：11 个非 ShardingSphere 服务添加了 `autoReconnect=true&failOverReadOnly=false` 参数，当主库不可用时自动切换到从库。

### 7.2 Redis

| 配置项 | 状态 | 完成阶段 |
|--------|------|----------|
| 3 实例（16379/16380/16381） | ✅ 已完成 | Stage 2 |
| Sentinel 高可用（3 节点） | ✅ 已完成 | Stage 4 |
| 分用途淘汰策略 | ✅ 已完成 | Stage 2 |
| 15 个服务启用 Sentinel | ✅ 已完成 | Stage 4 |

**Sentinel 高可用架构（Stage 4）**：
- 3 个 Sentinel 节点（26379/26380/26381）
- 3 个 Redis 实例（16379/16380/16381）
- 15 个服务全部配置 `spring.data.redis.sentinel.nodes` 指向 Sentinel 集群
- 故障自动转移：主节点宕机后 Sentinel 自动选举新主

### 7.3 RocketMQ

| 配置项 | 状态 |
|--------|------|
| NameServer | **单节点**（高风险） |
| Broker | **单节点** ASYNC_MASTER（高风险） |
| 主从复制 | 无 Slave |
| 自动建 Topic | true（开发模式） |
| DLQ 监控 | 无 |

**核心风险**：RocketMQ 仍是单点部署，Broker 宕机 = 下单链路断裂、库存异步扣减中断、支付/退款通知中断。

### 7.4 Zone 多活架构

| 配置项 | 状态 | 完成阶段 |
|--------|------|----------|
| Zone 上下文（ZoneContext） | ✅ 已完成 | Stage 4 |
| Zone 偏好过滤器（ZonePreferenceFilter） | ✅ 已完成 | Stage 4 |
| Dynamic DataSource Zone 路由 | ✅ 已完成 | Stage 4 |
| Redis 命令拦截 + 事件日志 | ✅ 已完成 | Stage 4 |
| Zone 负载均衡（ZoneLoadBalancer） | ✅ 已完成 | Stage 4 |
| 跨 Zone 容灾切换 | ❌ 规划中 | — |

**Zone 多活核心能力**：
- `ZoneContext`：ThreadLocal 传递 Zone 标识，支持 HTTP Header → Feign 透传
- `DynamicDataSource`：根据 `ZoneContext.getCurrentZone()` 动态路由到对应 Zone 的数据库
- `RedisMethodInterceptor`：拦截 Redis 命令调用，发布 `RedisCommandEvent` 用于审计日志
- `ZoneLoadBalancerConfiguration`：同 Zone 优先的服务实例选择

### 7.5 基础设施改进建议

```
P0: RocketMQ 增加 Broker Slave + SYNC_MASTER 模式
P1: RocketMQ 增加第 2 个 NameServer
P1: Docker 容器添加 CPU/内存限制
P1: Canal 增加高可用部署（当前单点）
P2: 数据库 IP 改为环境变量
P2: 连接池按服务差异化
```

---

## 八、微服务治理（78/100）↑

### 8.1 Dubbo 移除

**已完成（Stage 4）**：

| 改造项 | 状态 |
|--------|------|
| Dubbo 3.3.0 依赖移除 | ✅ 所有 pom.xml 无 dubbo 依赖 |
| 5 个 API 子模块删除 | ✅ user-api / content-api / product-api / order-api / inventory-api |
| 调用链路简化 | ✅ 纯 Feign 调用 |
| BOM 中 Dubbo 版本移除 | ✅ |

**残留清理**：5 个服务中仍存在 Dubbo Service 接口文件（CouponDubboService、NotificationDubboService、CounterDubboService、CartDubboService、AnalyticsDubboService），已不再使用但未删除源文件。

### 8.2 当前治理能力

| 能力 | 状态 | 实现 |
|------|------|------|
| 服务注册发现 | ✅ | Nacos 2.3.0 |
| 服务调用 | ✅ | OpenFeign + LoadBalancer |
| 熔断降级 | ✅ | Sentinel 1.8.8（含 Dashboard） |
| 限流 | ✅ | @RateLimit AOP（自研） |
| 分布式锁 | ✅ | @DistributedLock AOP（Redisson） |
| 幂等 | ✅ | @Idempotent AOP |
| 配置中心 | ✅ | Nacos Config |
| 链路追踪 | ✅ | SkyWalking 10.1.0 |
| 优雅停机 | ✅ | 自研 ShutdownHook |
| API 网关 | ✅ | Spring Cloud Gateway（7 层过滤器链） |

### 8.3 服务数变化

| 阶段 | 服务数 | 说明 |
|------|--------|------|
| Stage 1 | 16 | 含 5 个 API 子模块 |
| Stage 4 | 15 | 移除 5 个 API 子模块，纯 Feign 调用 |

**建议最终服务数：14 个**（合并 counter + payment，拆分 search + recommend，见 → 36-known-issues-and-roadmap.md）。

---

## 九、监控与可观测性（70/100）

**已配置**：
- Prometheus 4 组 21 条告警规则（P0-P2 分级）
- 3 个 Grafana Dashboard（JVM/API/业务指标）
- SkyWalking 全链路追踪
- 自定义健康检查端点（堆内存>90% + 死锁检测）

**缺失**：
- AlertManager 通知渠道未配置
- node_exporter + 中间件 exporter 未部署
- 慢查询告警未配置
- Feign 调用失败率告警未配置
- JSON 结构化日志未启用（LogstashEncoder 已声明依赖）

---

## 十、CI/CD（72/100）

**已配置**：
- GitLab CI 5 阶段流水线（compile → test → check → build → deploy）
- 多阶段 Docker 构建
- K8s 部署模板
- Docker Compose 19 容器编排
- 运维脚本（smoke-test.sh / start-all.sh / stop-all.sh / setup-firewall.sh）

**缺失**：
- HPA 自动扩缩容未配置
- PDB 预算保护未配置
- ResourceQuota 资源配额未配置
- NetworkPolicy 网络策略未配置
- 蓝绿/金丝雀部署未实现
- 多环境 Profile 未配置
- 密钥管理（Secrets）未实现

---

## 十一、优先级改进总清单

| 优先级 | 维度 | 问题 | 预估工时 |
|--------|------|------|----------|
| **P0** | 测试 | inventory/order/payment 核心服务零测试 | 3d |
| **P0** | 配置 | 创建 application-k8s.yml + 多环境 Profile | 1d |
| **P0** | 基础设施 | RocketMQ Broker 单点 → 增加 Slave | 2d |
| **P1** | 构建 | 添加 SpotBugs + JaCoCo | 1d |
| **P1** | 规范 | 创建 .editorconfig + checkstyle.xml | 0.5d |
| **P1** | 测试 | 引入 Testcontainers 集成测试 | 2d |
| **P1** | API | 集成 SpringDoc OpenAPI + Knife4j | 1d |
| **P1** | 配置 | 敏感信息迁移到 K8s Secrets | 1d |
| **P1** | 基础设施 | Docker 容器添加资源限制 | 0.5d |
| **P1** | 基础设施 | RocketMQ 增加第 2 个 NameServer | 1d |
| **P2** | 构建 | 添加 Checkstyle + PMD + Enforcer | 1d |
| **P2** | 测试 | Spring Cloud Contract 契约测试 | 2d |
| **P2** | 规范 | 制定 commit message 规范 | 0.5d |
| **P2** | 基础设施 | Canal 高可用部署 | 2d |
| **P2** | 基础设施 | 数据库 IP 环境变量化 | 1d |
| **P2** | CI/CD | HPA/PDB/NetworkPolicy | 1d |
| **P3** | 构建 | OWASP Dependency-Check | 0.5d |
| **P3** | 代码 | 清理 Dubbo Service 残留文件（5 个） | 0.5d |

---

## 十二、版本历史

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| v1.0 | 2026-07-08 | 初始版本，基于 Stage 4 改造完成后的工程化评估 |
| — | Stage 4 | 测试体系 3→7 类，基础设施 60→85，Dubbo 全面移除 |

---

> **关联文档**：
> → 36-known-issues-and-roadmap.md（已知不足与改进路线图）
> → 31-observability.md（可观测性体系）
> → 32-cicd-deployment.md（CI/CD 与部署）
> → 33-data-storage-design.md（数据存储设计全景）
> → 34-security-deep-dive.md（安全体系深度分析）
