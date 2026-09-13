# RV10：全维度深度 Review（文档 / 代码 / 运行态 / 跨项目）

> 日期：2026-09-12 ｜ 方法：4 路并行审查（文档一致性、代码第二轮、SRE 巡检、跨项目与简历叙事）
> 结论：**RV08 的"0 遗留 🔴"被推翻**——本轮新发现 2 个审批写路径 P0 并修复；settlement 漏判（RV08 R3）由异步核验器闭环；文档口径按 RV09 完成回写；M3 可启动（带已知项）。

---

## 1. 代码（RV10 修复）

| 级别 | 问题 | 修复 |
|------|------|------|
| 🔴 | `createPending` 用 `SELECT LAST_INSERT_ID()` 且无事务，并发下审批 ID 错配 | 改 `KeyHolder + RETURN_GENERATED_KEYS`；新增同会话同指纹 pending 复用（防重复建单） |
| 🔴 | `reply` 状态转移与执行非事务：执行失败后审批永久 approved 且不可重试；`always` revoke+insert 非原子 | `TransactionTemplate` 包住状态转移+授权（原子）；执行移出事务；新增 `executionStatus=executed/failed` 与重试接口 `POST /api/ai/approvals/{id}/execute` |
| 🔴 | settlement 3s 快照漏判"消费失败再入 DLQ"（E2E 实测 cart 7→8） | 新增 `RedeliverVerifier`：10 分钟窗口内按 `DLQ_ORIGIN_MESSAGE_ID=重投新 msgId` 匹配，命中→`reentered_dlq`，超窗→`verified_no_reentry`；`ApprovalExecutor` 移除 `sleep(3000)`（不再阻塞请求线程） |
| 🟡 | MCP 初始化失败泄漏子进程 | 失败路径 `wrapper.close()` |
| 🟡 | Redis Sentinel 抖动导致启动 11 连崩 | 建连重试 6×5s（systemd Restart 兜底） |
| 🟡 | `/actuator/info` 放行、`/actuator/prometheus` 无认证 | 移除 info；prometheus 仅允许本机/内网来源；令牌比较改常量时间；TraceId 白名单清洗 |
| 🟡 | `GET /api/ai/agent/chat` 返回 500 | 新增 405 处理 |
| 🟡 | sessionId 无长度/格式校验；工具参数直通 RocketMQ admin | `@Size/@Pattern` + group/msgId/originalTopic 正则校验；工具错误统一"内部错误"文案 |
| 🟡 | SSE 中断不归档、审批探测用字符串 contains | `doFinally` 归档；改 JSON 解析判定 |

验证：**12/12 单测通过**（新增主键/去重/CAS 冲突/失败重试用例）；运行态探测：未认证 401、info 401、prometheus(local) 200、GET chat 405、admin 200；服务 `active` 且 `NRestarts=0`。

## 2. 运行态（SRE 巡检处置）

| 项 | 结论 |
|---|---|
| 16 服务 + Nacos + Sentinel/Redis/RocketMQ | ✅ 全绿；xhs-ai 已注册（my-xhs 命名空间） |
| DLQ 积压 8 条（cart-event-sink-group） | ⏳ **待业务动作**：审批单 #1 已执行但消息消费失败再入 DLQ；需排查 `cart-event-sink-group` 消费者后按新核验流程重投 |
| Grafana MCP | 名义 enabled、未注册 Agent（刻意：57 工具超预算）；yml 已加注释说明 |
| 磁盘 `/` 77% | 关注：`/tmp/xhs-ai.log` 旧手动日志已无必要；65G 扩容依赖冷启动（用户侧） |
| 内存 48/62Gi | 关注，无 swap |

## 3. 文档（RV09 回写完成）

- **简历去虚**：删除"跨实例无缝恢复/18-18 演练通过/50+ 已构建"等已达成语气；MTTR/准确率/成本均标 **M4 回填**；工具数改"3 类 27 个"；LSP 改"v1 轻量解析（jdtls 评测触发）"。
- **E2E 报告纠偏**：`reports/m2.0-dlq-e2e.md` 标注 settlement 已知缺陷与 RV10 修复。
- **去默认 RAG 回写**：`01-prd`（风险/依赖行）、`requirements/02`（容量/降级）、`03-test-design`（用例改 BM25/卡片）、`design/01/03/06/07/09`、`research/01/02`、`02-architecture`（包注释/数据模型/ADR-11）、`dependency-matrix`（MCP 已启用）——向量/embedding 全部标注"实验路径，评测触发"。
- **口径统一**：6→8 类中间件；M3 里程碑与 D12 一致。

## 4. 跨项目（harness-skills）

- 移除 `.reasonix` 跟踪（+.gitignore）；安装技能数口径修正（仓库 54 个 SKILL.md，按语言渲染约 44 个）；案例数字与 RV10 对齐（3 🔴 全部闭环、单测 12）。
- 两项目交叉叙事成立：xhs-ai RV08/RV09/RV10 均标明"评审工具 = harness-skills expert-reviewer"；harness 案例反向引用 xhs-ai 证据。

## 5. 未闭环清单（触发条件/里程碑）

| # | 事项 | 触发/里程碑 |
|---|------|------------|
| 1 | 审批超时 fail-closed + 跨实例 pub/sub（PLAT-02 / D02 §10） | ✅ RV17 交付 |
| 2 | 消费位点核验（替代"未再入 DLQ"推断） | ✅ RV17 交付，RV18 升级队列级 |
| 3 | 工具集 schema 预算（27→>40 时上 tool_search）；Grafana 工具不进 Agent | M3 前夜 |
| 4 | 用户级限流/token 预算；`raw_input_hash` 执行前校验 | M2.x |
| 5 | 依赖治理：MCP 重复类收敛、Jackson/reactor 版本全对齐、MyBatis-Plus 去留、OTel 版本对齐 | M3 动 pom 时 |
| 6 | logback AsyncAppender；SSE 单终态协议；MCP 建连移出请求线程 | M3 性能阶段 |
| 7 | 测试：Testcontainers 集成（审批/工具/SSE 契约） | M3 出口 |
| 8 | `design/10-sse-contract`、`design/11-security-authz` 补齐 | M3 入口 |

## 6. 结论

> RV10 将 M2.0 从"演示可用"拉到"可审计/可重试/可核验"：审批写路径事务化、settlement 异步闭环、启动抗抖动、安全面收口、文档与简历不再虚报。**建议：先处理 DLQ 积压 8 条（业务动作），随后进入 M3（知识底座 + Agentic Retrieval），未闭环项按表跟踪。**
