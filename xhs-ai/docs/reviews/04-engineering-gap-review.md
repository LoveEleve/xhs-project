# RV04 · 工程缺口 Review（实现前必看）

> 方法：对宿主/中间件/主工程实测 + 逐工程域核对设计包；只列**工程问题**（构建/运行时/数据/测试/运维/安全/流程）
> 结论：发现 30+ 工程缺口，其中 P0 12 项必须在 M1 前解决

## 一、实测发现（先纠偏）

| 实测 | 结论 | 影响 |
|------|------|------|
| JDK：仅装 8/17，`java=17`；主工程 `java.version=17` | **xhs-ai 应用 JDK 17**（AgentScope 要求 17+），设计里写的 21 需修正 | 统一运行时，免装 JDK |
| AgentScope core 依赖：jackson 2.21.1 / reactor 3.8.2 / okhttp 5.3.2 / otel 1.61.0 / snakeyaml 2.6 | 与 Spring Boot 3.x BOM 托管版本**大概率冲突** | M1 第一件事做依赖收敛 |
| Redis：`maxmemory-policy=noeviction`，maxmemory 512MB，已用 4.5MB | 状态安全（不驱逐）但 AI 缓存增长有写失败风险 | 键治理 + 内存告警 |
| Filebeat：挂载 `/logs` 采集 `/logs/*.json` | **xhs-ai 日志必须输出 JSON 到 `/logs/xhs-ai.json`** 才能进 ELK | 日志规范 |
| Prometheus：`my-xhs-services` 为静态 target 列表 | 新增 xhs-ai(19020) 需改 `prometheus.yml` + reload | 监控接入 |
| 备份：`deploy/scripts/{mysql,es,redis}-backup.sh` 已存在，**无 crontab/定时** | 备份未调度；恢复未演练 | 运维缺口 |

## 二、工程缺口清单

### E1 构建与依赖（P0）

| # | 缺口 | 处置 |
|---|------|------|
| E1.1 | JDK 版本不一致（设计 21 / 实际 17） | 统一 JDK 17；修正全部文档 |
| E1.2 | AgentScope 与 Spring Boot BOM 依赖冲突（Jackson/Reactor/OTel/okhttp） | M1 第一步：`mvn dependency:tree` + `enforcer`（banDuplicateClasses/dependencyConvergence）+ 冒烟；确定"BOM 归口"策略并记录版本矩阵 |
| E1.3 | 供应链 | 依赖精确 pin；CI 加 CVE 扫描（dependency-check/trivy）；基础镜像 pin |
| E1.4 | 制品版本化 | 语义化版本 + git sha（build-info）；jar/镜像归档 |

### E2 运行时与并发（P0）

| # | 缺口 | 处置 |
|---|------|------|
| E2.1 | SSE 与 Reactor 桥接未定（MVC vs WebFlux） | **Spring MVC + `SseEmitter`/`ResponseBodyEmitter`**，Reactor 流桥接；工具执行在专用池（禁止阻塞 event loop） |
| E2.2 | 线程池隔离（bulkhead）未定义 | agent 执行池 / 按数据源工具池（ES/Prom/MySQL/RMQ/LSP）/ SSE 写池；有界队列 + 拒绝策略 + 超时 |
| E2.3 | 多副本同会话并发控制 | CAS（`saveIfVersion`）冲突重试 + turn 级互斥；等待审批释放锁（D02 §10） |
| E2.4 | 背压/慢客户端 | SSE 缓冲上限 + 溢出策略 + 心跳；慢客户端不拖垮会话 |
| E2.5 | 优雅停机顺序 | 停止接新 → drain SSE → 等在途工具 → flush AgentState → 退出；`GracefulShutdownManager` + `preStop` |
| E2.6 | 超时/时钟统一 | deadline 从请求传播到 LLM/工具；Clock 注入（可测） |

### E3 数据工程（P0/P1）

| # | 缺口 | 处置 |
|---|------|------|
| E3.1 | Flyway 与 AgentScope 自管表边界 | `agentscope_*` 不纳管；Flyway 只管网表；迁移幂等/顺序约定 |
| E3.2 | 审计/消息增长 | 按月分区 + 归档 Job；索引：`(user_id,created_at)`、`trace_id` |
| E3.3 | 数据保留/删除 | 审计 180d、消息 90d、评测永久；用户删除级联 |
| E3.4 | ES 索引生命周期 | 日志 ILM 保留策略；知识索引 **alias 切换 + reindex** 零停机 |
| E3.5 | Redis 键治理 | 状态/缓存分逻辑 DB；全部键 TTL/上限清单；内存 60% 告警（noeviction 写失败风险） |
| E3.6 | 备份/恢复演练 | my_xhs_ai 纳入 mysql-backup.sh + systemd timer；恢复演练进发布 checklist |

### E4 测试工程（P1）

| # | 缺口 | 处置 |
|---|------|------|
| E4.1 | Testcontainers 资源预算 | 16C/38G 限制并发容器；复用已启动中间件（本地模式）优先 |
| E4.2 | fixture 版本化 | 录制/回放 + 脱敏；变更需更新 fixture（契约测试） |
| E4.3 | CI 分层时长 | PR 子集 <10min；nightly 全量；live(eval-live) 仅手动 |
| E4.4 | 压测方案 | 会话级脚本（SSE 并发 + 工具 QPS）；wrk 已有；M4 执行并校准 D07 |
| E4.5 | 确定性 | LLM 桩（faux provider）、时间/随机注入、网络 fixture |

### E5 可观测与运维（P0）

| # | 缺口 | 处置 |
|---|------|------|
| E5.1 | 日志未接入 ELK | logback JSON appender → `/logs/xhs-ai.json`；字段对齐（APP_NAME/level/traceId/stack_trace） |
| E5.2 | Prometheus 未抓取 | `prometheus.yml` 增 19020 static target + reload；Grafana dashboard JSON 入库；告警 rules 文件 |
| E5.3 | 健康检查分级 | readiness 只查本地关键依赖（MySQL/Redis）；LLM/外部不可用不摘流量，暴露降级标志 |
| E5.4 | 配置管理边界 | 可热更（阈值/开关/白名单 via Nacos）vs 需重启（依赖/端口）清单 |
| E5.5 | 密钥管理 | dev `.env.local` → 生产 Secret Manager/KMS；轮换 + 审计 |
| E5.6 | 部署形态 | v1 与现状一致（bare jar + 自愈脚本 + systemd）；v2 容器化（Dockerfile 已规划） |
| E5.7 | 备份调度 | 无 crontab → **systemd timer**（backup + 校验 + 告警） |
| E5.8 | 容量告警 | 磁盘 80% / 内存 / Redis 60% / ES heap；Grafana + Prometheus rules |

### E6 安全工程（P0）

| # | 缺口 | 处置 |
|---|------|------|
| E6.1 | 出网白名单未落地 | 仅放行 siyu.site 与 ark.cn-beijing 的 FQDN:443；iptables/安全组规则入库 |
| E6.2 | SQL 注入面 | ~~v1 禁止 text2sql~~ → **已修订为受控只读查询**（ADR-23/D09）：三层防护+行数/超时+审计；GRANT 脚本版本化 |
| E6.3 | 审计防篡改 | 只追加 + 独立账号；可选 hash 链/外部归档 |
| E6.4 | 数据主体删除 | 会话/记忆/反馈删除接口 + 级联 + 审计 |
| E6.5 | 依赖/镜像 CVE | CI 扫描门槛（高危阻断） |

### E7 流程工程（P1）

| # | 缺口 | 处置 |
|---|------|------|
| E7.1 | Git/评审规范 | 分支/提交规范 + PR 模板 + Review checklist（对齐 harness 阶段） |
| E7.2 | CI 阶段定义 | build → UT → 契约 → 集成 → eval 子集 → 依赖扫描；门禁 |
| E7.3 | 发布/回滚 | 版本化制品 + 灰度（网关 header/用户）+ 回滚时限与步骤 |
| E7.4 | 文档/ADR 流程 | 文档随代码评审；ADR 变更记录 |
| E7.5 | apply-harness | 为 xhs-ai 生成 `.harness/`（owner/rules/skills/changes）并纳入 CI |

## 三、P0 汇总（M1 前必须闭环）

E1.1 JDK17统一 · E1.2 依赖收敛 · E2.1 SSE桥接 · E2.2 线程池隔离 · E2.3 会话CAS · E3.1 Flyway边界 · E3.5 Redis键治理 · E5.1 ELK接入 · E5.2 Prometheus接入 · E6.1 出网白名单 · E6.2 禁text2sql+GRANT版本化 · E7.2 CI门禁

## 四、下一步

1. 修正文档 JDK 21→17（已完成）；新增 `04-engineering.md`（把 E1-E7 写成可执行规范）
2. R05 旧资产治理 → P4 全量测试设计
3. M1 首任务：依赖收敛验证 + 骨架 + ELK/Prom 接入（P0 闭环）
