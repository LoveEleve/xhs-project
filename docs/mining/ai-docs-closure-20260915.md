# AI 项目文档闭环（PRD/架构/测试/工程）· 2026-09-15

## 1. 00-context：旧实现 6 类问题（不继承旧代码的依据）
文档驱动无回归测试/依赖丢失不可复现/架构未收口（诊断+知识+Memory+Temporal 多主线）/自研 Harness 重复造轮子/缺企业级治理（多租户/三态权限/审计/限流）/评测缺失（仅 9 条）。

## 2. 01-prd：需求编号与 AC
- 编号链：REQ-DIAG/KB/OPS/PLAT（34 场景）→ AC → TC；非目标：不改代码不发版、不接真实支付、不做多 Agent/Temporal。
- 关键 AC：traceId 四联（服务→主类/方法→blame→片段）、重投必须审批+审计、结论必须带证据、引用静态校验拦幻觉、A 不可读 B、只读 allow/变更 approve/高危 deny、按 traceId 追溯、超时降级切备用、mvn test -Peval 一键跑分。
- NFR：首字 P95≤2s、只读工具≤3s、端到端 P95≤15s、核心单测覆盖≥80%、静态 0 🔴、CI 全绿。

## 3. 02-architecture：23 条 ADR（要点）
AgentScope 2.0 Java；Redis Sentinel 状态存储（CAS）；卡片+BM25（向量评测触发）；SSE；框架 PermissionEngine + 自持久化审批；v1 无沙箱；GitSkillRepository；模型网关（重试/熔断/降级/预算）；策略 last-match-wins+默认 ask；工具 never-throw+三段式；代码导航轻量+JGit；MCP 仅桥接 Tools；观测 sanitized+failsafe；会话/审计统一查询；Guard 循环卫生；Redis AOF RPO≤1s/RTO≤30s、写失败 fail-closed；向量分阶段阈值（>5 万 chunk 或 P95>300ms 迁库）；jdtls 归 v1.1；网关自研薄层；容量磁盘 4-6GB；扩展两代（禁 in-process full-trust）；生态 SHA pin；MCP 一等公民+受控只读 SQL 三层。
- 边界：框架管 Harness/状态/事件/在途 HITL；自研管策略/审批/审计/工具；`agentscope_*` 表 Flyway 不碰。

## 4. 03-test-design：六层矩阵
UT 14 / CT 17（LLM 桩 + fixture 录制回放）/ IT 13+ / E2E 10 / EVAL 50 / RED 6 + PERF 4；门禁：UT<2min、CT<3min、IT<5min、E2E nightly<20min、EVAL 通过≥90% 且回退≥2% 阻断、引用 100%。
- 混沌对账：FMEA 设计 18 项；实做 D1 停 ES（F2/F3）、D2 Redis failover（F6）、D3 滚动重启（F11）、D4 kill MCP（F16）。

## 5. 04-engineering E1~E7（要点）
E1 依赖：BOM 优先 + Enforcer/OWASP；E2 运行时：7 个隔离线程池、SSE 15s 心跳/10min 时限、停机≤30s；E3 数据：Flyway 仅 ai_*、message 90d/audit 180d/ES 30d、Redis noeviction；E4 六层测试+LLM 一律桩；E5 观测：liveness/readiness 分级（LLM/ES/RMQ 挂不摘流量置 ai_degraded）、热更边界；E6 安全：出网仅 siyu.site/ark 两域、只读 SQL 三层、审计只追加、CVE 门禁；E7 流程：CI build→UT→契约→集成→eval 子集→依赖扫描，灰度回滚≤5min。
- 依赖矩阵：Boot 3.2.5 / AgentScope 2.0.1 / MP 3.5.7 / MCP SDK 0.17.0；pin Jackson 2.21.1、Reactor 3.8.2、Jedis 7.4.1、okhttp 5.3.2 等；4 项冲突处置（okhttp 降级、jedis 漂移、org.json 重复、MCP json 包豁免+退出条件）。

## 6. 目标 vs 实测（面试主动认的缺口）
| 目标 | 承诺 | 实测 | 结论 |
|---|---|---|---|
| MTTR 降幅 | ≥80% | 92.1% 保守 | 超 |
| KB hit@1 | ≥90% | 100% | 超 |
| 答案通过/引用 | ≥90%/100% | 50/50、引用 100% | 超 |
| 成本 | <¥0.1 | ¥0.012–0.048 | 超（费率未官方化） |
| 首字 P95 | ≤2s | 无实测报告 | 缺口（口径在，未采数） |
| 端到端 P95 | ≤15s | C=5 P95 39s（LLM-bound） | 未达 |
| 并发会话 | ≥50 单机 | 估算 ~10 并发（配额瓶颈） | 未达/设计上限 |
| 单测覆盖 | ≥80% | RV08 曾报 <80%，结项无覆盖复测 | 未证实 |
| 混沌 | 18 项演练 | 设计 18、实做 4+5 场景 | 部分执行已留档 |
