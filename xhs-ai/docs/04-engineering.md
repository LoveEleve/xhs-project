# 04 · 工程规范（E1-E7 可执行版）

> 依据：RV04 工程缺口 Review（实测）；本文档是 M1 编码的施工规范，冲突决策以此为准

## E1 构建与依赖

### E1.1 版本基线（实测对齐）

| 组件 | 版本 | 依据 |
|------|------|------|
| JDK | **17**（宿主已装；主工程一致） | AgentScope 要求 17+ |
| Spring Boot | **3.2.5**（对齐主工程） | 运维一致（actuator/日志/监控模式） |
| Spring Cloud | 不引入 | AI 服务独立，不需要注册发现于主工程 |
| AgentScope | `agentscope-harness:2.0.1` + `extensions-model-openai/redis/skill-git-repository` | R02 已验证 |
| Jackson | **2.21.1（覆盖）** | AgentScope core 要求；Spring 6.1 兼容性经冒烟验证 |
| Reactor | **3.8.2（覆盖）** | AgentScope core 要求；xhs-ai 不用 WebFlux（SSE=MVC），风险面小 |
| OTel | **1.61.0（覆盖）** | AgentScope 内置；自管 OTLP 导出 |
| SnakeYAML | **2.6（覆盖）** | AgentScope 要求 |
| MyBatis-Plus / Hikari | 对齐主工程版本 | store 层 |

### E1.2 依赖收敛（M1 第一任务）

1. `dependencyManagement` 先 Spring Boot BOM，再 AgentScope 显式覆盖（Jackson/Reactor/OTel/SnakeYAML）
2. 排除 AgentScope 中与运行无关的重依赖（`kubernetes-client`/`aliyun-sdk-oss`/`protobuf` 若非必需）
3. `maven-enforcer-plugin`：`dependencyConvergence` + `banDuplicateClasses` + `requireJavaVersion(17)` + `requireReleaseDeps`
4. `mvn dependency:tree -Dverbose` 输出入库（版本矩阵归档 `docs/engineering/dependency-matrix.md`）
5. **启动冒烟**：JSON 序列化（自定义对象）、SSE 流、MyBatis 查询、Redis 读写各 1 条集成测试；失败则 Plan B：升级 Spring Boot 3.5.x 后重验

### E1.3 供应链

- 依赖精确版本（禁 `LATEST`/范围）；CI：OWASP dependency-check（高危阻断）
- Docker 基础镜像 pin digest（v2 容器化时）

### E1.4 制品

- 版本 `xhs-ai-<semver>-<gitSha短>`；`build-info` 暴露 `/actuator/info`
- 发布制品归档到宿主机 `deploy/releases/`（保留最近 5 个）

## E2 运行时与并发

### E2.1 SSE 桥接

- 入口：Spring MVC `ResponseBodyEmitter`/`SseEmitter`；`POST /api/ai/chat/stream`
- AgentScope `streamEvents()`（Reactor）→ `subscribe` 到 MVC 回调；**不在 Reactor 线程执行阻塞工具**（工具派发到专用池）
- 事件编码：`event: <type>` + JSON data；心跳 15s；末尾 `event: done`
- 超时：SSE 总时限 10min（可配）；客户端断开→取消订阅（传播 AbortSignal）

### E2.2 线程池与隔离（bulkhead）

| 池 | 大小 | 用途 | 拒绝策略 |
|----|------|------|---------|
| agent-exec | 8-16 | Agent 回合执行 | 队列 100 + CallerRuns（保护） |
| tool-es | 8 | ES 查询 | 队列 50 + Abort |
| tool-db | 8 | MySQL 只读 | 队列 50 + Abort |
| tool-prom | 4 | 指标查询 | 队列 20 + Abort |
| tool-rmq/job | 4 | 运维工具 | 队列 20 + Abort |
| lsp | 2 | jdtls 调用 | 队列 20 + Abort |
| sse-write | 4 | SSE 写出 | 丢弃慢客户端 |

### E2.3 多副本会话一致性

- 状态写入：Redis CAS（`saveIfVersion`），冲突→重试（≤3）→ 明确失败
- turn 级互斥：同会话并发请求串行化（本地队列 + Redis 锁兜底）；等待审批释放执行资源
- HITL 跨实例恢复：见 D02 §10

### E2.4 背压/慢客户端

- SSE 每连接缓冲上限（如 256KB）；超限丢弃最旧或断开；metrics 记录慢客户端计数

### E2.5 优雅停机

`SIGTERM`：停止接新会话 → 通知 SSE 客户端 `event: shutdown` → 等待在途（≤30s）→ 刷新 AgentState → 退出；systemd `TimeoutStopSec=40`

### E2.6 超时与时钟

- Deadline 从请求贯穿：LLM（D01）/工具（D02）/SSE（E2.1）
- `Clock` 注入；所有超时/时间戳走统一时钟（可测）

## E3 数据工程

### E3.1 迁移边界

- Flyway 只管 `ai_*` 表；**不碰 `agentscope_*`**（框架自管）
- 迁移命名 `V<seq>__<desc>.sql`；只增不改；破坏性变更走两阶段（加列→回填→删列）

### E3.2/E3.3 增长与保留

| 表/索引 | 保留 | 策略 |
|---------|------|------|
| ai_message | 90d | 按月分区 + 过期 Drop Partition |
| ai_audit | 180d | 同上；只追加 |
| ai_eval_* | 永久 | 小体量 |
| ES 日志索引 | 30d | ILM delete |
| ES 知识索引 | 永久 | alias 读写分离，reindex 切 alias |

### E3.4 Redis 键治理（noeviction 前提）

| 键前缀 | 类型 | TTL | 上限 |
|--------|------|-----|------|
| `ai:state:*`（框架） | string/hash | 会话级 | — |
| `ai:cache:tool:*` | string | 5-300s | 1 万键 |
| `ai:lock:session:*` | string | 30s | — |
| `ai:approval:wake:*` | pub/sub | — | — |

- 状态与缓存使用**不同逻辑 DB**；内存 60% 告警；≥80% 拒绝缓存写（保状态）

### E3.5 备份与恢复

- `my_xhs_ai` 纳入 `deploy/scripts/mysql-backup.sh`；**systemd timer** 每日 03:30
- 恢复演练：每季度一次（记录 RTO）；备份校验（dump 文件校验 + 试恢复）

## E4 测试工程

| 层 | 范围 | 时机 | 时长预算 |
|----|------|------|---------|
| UT | 策略/状态机/脱敏/分块/评测器 | 每提交 | <2min |
| 契约 | 外部系统 fixture（ES/Prom/RMQ/Job/Ark/LLM） | 每提交 | <3min |
| 集成 | MySQL/Redis/ES（本地或 Testcontainers） | PR | <5min |
| E2E | 对话/SSE/审批/权限（docker 环境） | nightly | <20min |
| 评测 | 离线子集（PR）/全量（nightly） | 分层 | 子集<5min |
| 红队 | 注入/越权/危险指令 | 发布前 | 手动 |

- fixture 版本化（`src/test/resources/fixtures/<system>/<case>.json`，变更随 PR）
- LLM 一律用桩；`-Peval-live` 才出网

## E5 可观测与运维

### E5.1 日志接入 ELK

- logback：CONSOLE + `JSON appender → /logs/xhs-ai.json`（Filebeat 已采 `/logs/*.json`）
- JSON 字段对齐主工程：`APP_NAME=xhs-ai`、`level`、`message`、`traceId`、`stack_trace`、`logger_name`、`thread_name`、`@timestamp`

### E5.2 监控接入

- `prometheus.yml` 增 target `192.168.0.142:19020`（labels: service=xhs-ai, application=xhs-ai）+ `POST /-/reload`
- Grafana dashboard JSON 入库 `（规划）AI 专属 Grafana 看板：当前复用平台看板 + `ai_*` 指标直查（见本文件 E7 与 16 题）`
- 告警 rules：LLM 错误率/延迟/成本/审批积压/tool 失败率/Redis 内存/磁盘

### E5.3 健康检查分级

- liveness：进程存活；readiness：**MySQL/Redis 可达**（本地关键依赖）
- LLM/ES/RMQ 不可用**不摘流量**，暴露 `/actuator/health` details + `ai_degraded` 标志

### E5.4 配置热更边界

| 可热更（Nacos） | 需重启（启动参数/env） |
|----------------|----------------------|
| 阈值/开关/白名单/采样率/预算 | 端口/数据源/密钥/依赖版本 |

### E5.5 密钥

dev：`.env.local`（600，gitignored）；生产：Secret Manager/KMS + 启动注入；轮换记录入审计

### E5.6 部署形态

v1：bare jar + `setsid` + systemd 自愈（与 15 服务一致，复用 boot-selfheal 模式）；v2：Dockerfile + compose profile

### E5.7 MCP server 运行形态

官方/社区 MCP（Node/Python）容器化部署；仅绑 `127.0.0.1`；npm 精确版本/镜像 digest pin；资源限额（CPU/内存）；健康检查纳入自愈脚本与告警

### E5.8 MCP 工具治理

MCP 工具统一入 ToolRegistry（风险分级/审批/审计/maxBytes）；**场景化工具集 + 渐进加载**（D02 §11）；tool schema token 预算告警

## E6 安全工程

1. **出网白名单**：仅 `siyu.site:443`、`ark.cn-beijing.volces.com:443`（iptables/安全组，规则入库）
2. **受控只读查询**（ADR-23）：允许受控 SELECT（**三层防护**：AST 白名单 + `START TRANSACTION READ ONLY` + 只读账号），叠加行数/超时/结果脱敏/全量审计；禁用多语句/`INTO OUTFILE`/`SLEEP`/锁；GRANT 脚本入库 `deploy/scripts/ai-readonly-grant.sql`
3. 审计防篡改：只追加 + 应用账号无 UPDATE/DELETE；可选 hash 链
4. 数据主体删除接口（会话/记忆/反馈级联）+ 审计
5. 依赖/镜像 CVE 扫描门禁（高危阻断）

## E7 流程工程

- Git：短分支（≤3 词）、conventional commits、PR 模板（背景/改动/验证/风险/回滚）
- CI：`build → UT → 契约 → 集成 → eval 子集 → 依赖扫描`；全绿才可合并
- 发布：版本化制品 + 灰度（网关 header/用户）+ 回滚步骤（旧 jar 重启 ≤5min）
- ADR：决策变更必须新增/修订 ADR（`docs/02-architecture.md` §5）
- **apply-harness**：为 xhs-ai 生成 `.harness/`（owner/rules/skills/changes），阶段产物走 harness 流水线

## M1 施工顺序（首个里程碑）

1. 依赖收敛验证（E1.2）→ 版本矩阵归档
2. 工程骨架（Spring Boot + Actuator + Flyway + MyBatis + Logback JSON）
3. AgentScope 装配 + siyu-all 对话/SSE 打通（最小闭环）
4. 会话状态（Redis DistributedStore）+ CAS + 优雅停机
5. ELK/Prometheus 接入（E5.1/E5.2）+ 健康分级
6. 单测/契约骨架 + CI 门禁（E7.2）

> 验收：M1 后 `POST /api/ai/chat/stream` 可多用户隔离、重启可恢复；日志进 ELK、指标进 Prometheus；CI 全绿。
