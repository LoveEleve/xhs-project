# my-xhs-ai 后续详细规划（2026-08-19）

> 基于 Task14 完成情况，面向"项目可展示、可面试、可落地"的目标。
> 原则：不写文档，写代码。每项产出必须有可运行/可验证的结果。

---

## 一、当前状态盘点

| 模块 | 已完成 | 未完成 |
|------|--------|--------|
| Harness 核心 | ✅ 859行自研引擎 | — |
| 工具系统 | ✅ 17工具，15个真实执行器 | service.restart/order.refund 空壳 |
| HITL 审批 | ✅ 真实 E2E 验证（CR_SUCCESS） | — |
| 评测系统 | ✅ smoke 20 + regression 80 契约测试 | E2E 评测 case 未跑通 EvalRunner |
| Memory | ✅ 向量语义检索（豆包 embedding） | 多用户测试 + 记忆来源扩展 |
| 多 Agent | ✅ Profile 分派（BUSINESS/OPS/FULL） | 不是真正多智能体 |
| 会话多轮 | ✅ ConversationService + JDBC 持久化 | — |
| logSearch | ✅ 白名单已加6个业务服务 | Nacos 侧未配置（本地生效） |
| 文档 | ✅ 30+篇 + DOC-INDEX.md 索引 | 未收敛到5篇 |

---

## 二、Phase 1：E2E 评测跑通（1天）

### 目标
`cases-e2e.yaml` 的 5 个 case 通过 `EvalRunner` 真实跑通，产出 JSON 报告。

### 任务

| # | 任务 | 产出 |
|---|------|------|
| 1.1 | 写 `E2EEvalRunnerTest`：加载 `cases-e2e.yaml`，用真实 Harness（真模型+真外部服务）跑，断言全部通过 | 测试类 + JSON 报告 |
| 1.2 | 验证每个 case 的断言：`statusIn`/`minEvidence`/`contains`/`numbersConsistent` | 断言修正（如有） |
| 1.3 | 跑通后产出 `e2e-eval-report-YYYYMMDD.md` | 报告文件 |

### 依赖
- AI App 运行中（`localhost:19020`）
- 中间件机 `21.130.247.89` 可达
- `MYXHS_LLM_API_KEY` 已配置

### 验证
```bash
mvn test -pl my-xhs-ai-app -Dtest=E2EEvalRunnerTest -DfailIfNoTests=false
```

---

## 三、Phase 2：Memory 系统完善（1天）

### 目标
Memory 从"诊断结论提取"扩展到"研发画像"，支持多用户隔离验证。

### 任务

| # | 任务 | 产出 |
|---|------|------|
| 2.1 | 记忆来源扩展：从 run 的工具调用参数中提取"常用服务名"、"常用时间窗口" | MemoryService 新增 `extractFromTools()` |
| 2.2 | 记忆注入格式优化：按类型分组展示（诊断结论 / 常用服务 / 常用窗口） | buildMemoryContext 输出格式 |
| 2.3 | 多用户测试：`dev-zhangsan`（查订单）和 `dev-lisi`（查服务）分别跑，验证记忆隔离 | 测试脚本 |
| 2.4 | 记忆 CRUD API：`GET/POST/DELETE /api/memory`（前端薄壳用） | Controller + 测试 |

### 依赖
- Phase 1 完成（E2E 验证通过）

### 验证
```bash
# 多用户隔离
curl -H 'X-User-Id: dev-zhangsan' http://localhost:19020/api/memory
curl -H 'X-User-Id: dev-lisi' http://localhost:19020/api/memory
# 应返回不同记忆
```

---

## 四、Phase 3：代码质量收尾（0.5天）

### 目标
修复已知技术债务，让代码可面试。

### 任务

| # | 任务 | 产出 |
|---|------|------|
| 3.1 | service.restart / order.refund 从 SYSTEM_PROMPT 和 PolicyGuard 中移除（或标注"预留"） | 代码修改 |
| 3.2 | toolResultMaxLen 按工具类型差异化（DLQ 查询 2000，其他 400） | AgentHarness 修改 |
| 3.3 | E2E 评测报告更新到 `final-status-v1.md` | 文档更新 |

### 验证
```bash
mvn compile -pl my-xhs-ai-app -am -DskipTests
```

---

## 五、Phase 4：面试准备（0.5天）

### 目标
准备 3 个可现场演示的场景 + 面试话术。

### 任务

| # | 任务 | 产出 |
|---|------|------|
| 4.1 | 场景1：DLQ 死信诊断+重投（已有，整理成可重复脚本） | `demo-dlq.sh` |
| 4.2 | 场景2：订单量下降归因（漏斗分析+基线对比） | `demo-order-decline.sh` |
| 4.3 | 场景3：服务 5xx 排障（日志检索+指标关联） | `demo-5xx.sh` |
| 4.4 | 面试话术更新：把 E2E 验证结果、Memory 系统、语义检索加入话术 | `pitch-3tier-v1.md` 更新 |

### 依赖
- Phase 1-3 完成

### 验证
- 3 个 demo 脚本可重复执行
- 面试话术与实际代码一致

---

## 六、时间线

```
Day 1（今天）：Phase 1 — E2E 评测跑通
Day 2（明天）：Phase 2 — Memory 完善 + Phase 3 — 代码收尾
Day 3：Phase 4 — 面试准备 + demo 脚本
```

---

## 七、不做的事（明确排除）

| 不做 | 原因 |
|------|------|
| 真正的多智能体协作 | 成本高收益低，当前 Profile 分派够用 |
| Langfuse/OTel 接入 | 非核心，后续平台化增强 |
| Docker 部署 | 不在当前范围 |
| Temporal Durable | 不在当前范围 |
| 文档从30+删到5篇 | 风险大，已有索引文档 |

---

## 八、成功标准

Phase 1 完成后：
- E2E 评测 5/5 通过
- 报告产出于 `docs/reports/e2e-eval-report-YYYYMMDD.md`

Phase 2 完成后：
- 多用户记忆隔离验证通过
- Memory CRUD API 可用

Phase 3 完成后：
- 代码编译无警告
- SYSTEM_PROMPT 无空壳工具

Phase 4 完成后：
- 3 个 demo 脚本可重复执行
- 面试话术更新完成
