# 第五轮素材：AI 文档闭环 + 平台运维/测试盲区（2026-09-15）

## A. AI 项目需求→设计→验证闭环（docs/00~04）

### A1 旧实现 6 类问题（00-context §2，面试"为什么重做"）
1. 文档/演示驱动（demo 脚本/HANDOFF），无可回归测试与 CI → 无法证明不退化
2. 依赖丢失（.env.local 遗失、外部云库不可达）→ 不可复现
3. 架构未收口（诊断/知识/Memory/Temporal PoC/MCP 多主线并行）
4. 自研 Harness 与框架能力重复
5. 缺企业级治理（多租户/权限三态/审计/限流/降级）
6. 评测缺失（问答仅 9 条、无持续评测）
→ 结论：不继承旧代码，仅迁移知识资产；xhs-ai 独立 19020、只读优先、变更必须审批+审计。

### A2 PRD 编号与关键 AC（01-prd）
- 体系：`REQ-<域>-<序号>` → `AC-<域><序号>-<n>` → `TC-<层>-<编号>`；34 场景全部有编号+证据形态
- 关键 AC：AC-1.1 JWT/session 401；1.3 跨进程重启上下文恢复；2.1 traceId 四联（服务→主类方法→blame→片段）；2.5 重投必须审批+审计；2.6 无证据不得编造；3.3 评测 ≥50 用例、通过率 ≥90%；3.4 引用必须真实存在；4.1 用户隔离负向；5.1 风险分级 allow/approve/deny；5.3 全程审计可追溯；6.2 超时/重试/降级；7.1 一键跑分
- NFR：首字 P95 ≤2s、只读工具 ≤3s、端到端 P95 ≤15s、核心单测覆盖 ≥80%、CI 全绿

### A3 架构与 ADR（02-architecture）
- 分层：API → Agent(HarnessAgent + Tracing/Audit/Permission/Cost/Guard 中间件) → 执行(PolicyEngine/Approval/ToolRunner) → Tools/Knowledge/Memory/Query/Observability/Store
- **ADR 共 23 条**（不是 12；12 是 D01~D12 专项设计篇数）：AgentScope 选型/Redis 状态/卡片+BM25/SSE/框架 Permission+HITL 自持久化/无沙箱/Git 技能/模型网关/策略引擎 last-match-wins/MCP 只桥 Tools/观测 sanitized+failsafe/Guard 循环卫生/Redis AOF RPO≤1s/向量分阶段/网关自研薄层/扩展两代禁 full-trust/SHA pin/…
- 框架边界：agentscope_* 表不动（Flyway 只管 ai_*）；实测不支持工具热替换 → 去 MCP 化（16 自研 + MCP 20 运维直连）

### A4 测试设计（03-test-design）
- 分层：UT 14 / CT 17（LLM 桩 + fixture 录制回放）/ IT 13+1 / E2E 10 / EVAL 50 / RED 6 / PERF 4；门禁：UT<2min、CT<3min、IT<5min、E2E<20min、EVAL 通过≥90%+引用 100%
- 混沌映射：FMEA 18 项设计，实做 D1-D4（停 ES↔F2/F3、Redis failover↔F6、重启↔F11、kill MCP↔F16）

### A5 工程规范（04-engineering E1~E7 + 依赖矩阵）
- E1 版本：Spring Boot 3.2.5 / AgentScope 2.0.1 / MyBatis-Plus 3.5.7 / MCP SDK 0.17.0；Enforcer 三规则全过；冲突处置 4 项（okhttp pin、jedis pin 7.4.1、org.json 排除、MCP json 豁免）
- E2 运行时：7 个隔离线程池、SSE 心跳 15s/总限 10min、Redis CAS 重试≤3、停机≤30s
- E3 数据：message 90d / audit 180d（实现 365d）/ ES 30d ILM；Redis noeviction、内存 80% 拒写
- E5：liveness/readiness 分级（LLM/ES/RMQ 不可用不摘流量，置 ai_degraded）；MCP 只绑 127.0.0.1+digest pin
- E6：出网仅 siyu.site:443 与方舟；受控只读 SQL 三层（AST 白名单 + READ ONLY 事务 + 只读账号）
- E7：CI `build→UT→契约→集成→eval 子集→依赖扫描` 全绿才合并；灰度+回滚 ≤5min

### A6 设计目标 vs 实测（面试口径对照，含未达标项）
| 目标 | 承诺 | 实测 | 判定 |
|---|---|---|---|
| MTTR | ≥80% | 92.1% 保守下界（10/10） | 超目标 |
| KB hit@1 | ≥90% | 100%（30/30） | 通过 |
| 答案/引用 | ≥90% / 引用 100% | 50/50、引用 100% | 通过 |
| 拒答 | ≥95% | SEC 5/5 + 红队 8/8 | 通过 |
| 单次成本 | <¥0.1 | ¥0.012–0.048（估算） | 通过 |
| 首字 P95 | ≤2s | **未实测** | 缺口 |
| 端到端 P95 | ≤15s | C=5 P95 39.0s | 未达（LLM-bound） |
| 并发 ≥50 | 单机 50 | 估算 ~10 并发，压测可持续 C≈5 | 未达/设计上限 |
| 单测覆盖 ≥80% | 80% | 61/61 通过但**无覆盖率复测** | 未证实 |
| 审计留存 | ≥180d | 实现 365d | 超设计 |

> 结项口径："目标—实测—差距"三列可背；未达标项均标注为 LLM 配额瓶颈或未复测。

## B. 平台运维与测试盲区

### B1 环境盘点（config/production-env-config/docker-container-review-20260811-162334/，12 目录）
- 结构：docker-info / networks / volumes / images / containers / daemon / deploy-src / **runtime-queries** / host-system / container-runtime / business / effective-config
- runtime-queries 抓取 10+ 项实测输出：mysql 主从、redis 主从、sentinel、rocketmq、nacos（configs/services from db）、prometheus、grafana、elasticsearch 等
- 样例事实：RocketMQ `DefaultCluster / broker-a`、版本 `V5_1_4`；存在 `%RETRY%DLQ_MONITOR_*` 系统重试主题
- business 目录：远程微服务清单 / xxl-job 任务 / mysql·redis·rocketmq 深查 / nacos 安全

### B2 部署踩坑（DEPLOY-NOTES.md，15+ 条，挑 10）
1. 部署包必须最新（330 文件，2026-08-13）；zip 为根目录结构，解压根 `docker compose up -d`
2. 覆盖升级卷名跟随项目名，项目名不变则数据保留
3. 前置：`systemctl enable docker`（原为 disabled，开机不自启）
4. compose `depends_on` 写空映射 `service:` 会报错
5. RocketMQ 5.1.4 官方镜像**无内置 Prometheus exporter**（需自建 textfile/metrics）
6. `broker.conf` 禁止行内注释（Properties 解析会把 `#` 后内容吃进值里，SYNC_FLUSH 曾静默回退）
7. 加固：SYNC_FLUSH + `autoCreateTopicEnable=false`
8. MySQL 8.0.46 无 `Innodb_deadlocks` 状态变量（监控需另取）
9. 复制监控需独立从库 exporter（3307）；从库 1236/open relay log 重建修复
10. `docker exec` 执行中文 SQL 需 `--character_set_client`；healthcheck 镜像缺 curl（用 wget/`/dev/tcp`）

### B3 迁移脚本（deploy/.../sql/migration/，6 个）
- order/inventory/content/user 的 V1__init；content V2__add_push_progress（Feed 断点续推列+索引）；cart V1__cart_event_uk_msg_id（事件流水幂等唯一索引）

### B4 服务单测盘点
- 平台 13 个服务共 **236 个 @Test 方法**（analytics3/cart2/common6/content2/counter1/coupon4/home2/inventory4/order5/payment1/product3/search4/user3 个测试类）；旧 AI 模块另有 55 个测试类（零参考，不计）
- ⚠️ 未验证这批单测当前全绿（仅在 test-2/test-3 阶段运行过）→ 简历暂不写"236 单测"

### B5 运行态验证文档
- `docs/test-3/F-037-RUNTIME-VERIFY.md`：验证"库存补偿写回预扣实际桶而非 bucket0"（bucketCount>1 的 SKU，比对 `inventory:bucket:count` 与桶值）——可作为"补偿桶归属"追问的实证材料
