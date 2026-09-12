# R05 · 旧资产治理清单与入库白名单

> 对象：`my-xhs-ai/`（278 文件：200 md / 54 yaml / 15 pdf / 4 sh / 4 java / 1 xml）
> 原则：**旧代码零参考**；文档仅作知识源，经治理（校验/去重/脱敏/时效）后按层入库；PDF 不入库

## 1. 资产盘点

| 目录 | 数量 | 内容 | 初判 |
|------|------|------|------|
| `knowledge/architecture` | 15 | YAML 卡片（bff/gateway/middleware/service-topology/sync-async/transaction-chain/message-anchor…）+ 校验报告 | **A（高价值）** |
| `knowledge/business` | 8 | YAML 卡片（order-create/payment-flow/refund-flow/coupon-lifecycle/inventory-three-level/close-order/compensation-path） | **A** |
| `knowledge/code-map` | 40 | feign-map / mq-map / async-event-map / call-chain / mainline 卡片 / trace 样例 | **A** |
| `knowledge/failure` | 1 | gateway-body-cache-reentry 故障卡 | **A** |
| `business-analysis/tech` | 50 | 架构 C4/设计/Harness/深挖/评审文档 | **B（治理后）** |
| `business-analysis/{overview,decisions,conventions,pillar-1..4,09-reference}` | 53 | 分析/决策/规范/参考 | **B** |
| `business-analysis/{plan,08-execution,d0,_legacy}` | 29 | 计划/执行/历史 | **C（不入库）** |
| `docs/vol-ai` | 13 | 5 卷深入文档 + METHODOLOGY + HANDOFF | **B** |
| `docs/phase-00..16` | 16 | AI 学习/规划手册 | **C** |
| `docs/reference` | 20 | 参考文章/论文（含 15 PDF） | **C/D（PDF 不入库/版权）** |
| 根目录报告/脚本 | ~20 | FINAL-SUMMARY/SHOWCASE/HANDOFF/demo-*.sh | **C** |
| 4 个 java + 1 xml | 5 | 旧代码 | **D（禁止参考）** |

## 2. 分级标准

| 级 | 标准 | 处置 |
|----|------|------|
| **A** | 结构化、面向"系统本体"、可直接映射知识三层 | 轻治理（引用校验+日期标注+脱敏）→ 入库 |
| **B** | 有价值但叙述性/可能过期/需提炼 | 治理（提炼成卡+代码校验）→ 入库 |
| **C** | 过程产物/学习资料/报告 | 不入库；仅在需要时人工参考 |
| **D** | 旧代码/版权材料/废弃件 | 禁止参考；归档 |

## 3. 入库白名单（v1）

**A 级（约 64 文件，直接治理入库）**
- `knowledge/architecture/*.yaml`（9 卡片）+ `architecture-questions.yaml`、`architecture-card-schema.md`（schema 作为入库模板）
- `knowledge/business/*.yaml`（7 卡片）
- `knowledge/code-map/**/*.yaml`（feign-map/mq-map/async-event-map/call-chain/mainline/trace 样例）
- `knowledge/failure/gateway-body-cache-reentry.yaml`

**B 级（精选约 30-40 文件，提炼后入库）**
- 业务/架构：`business-analysis/{overview,pillar-1-content,pillar-2-commerce,pillar-3-ops,pillar-4-ai-security}` 中与交易/库存/支付/券/运维直接相关的分析
- 决策与规范：`business-analysis/{decisions,conventions}`
- 深挖：`docs/vol-ai/0[0-5]-*`（对应架构/工具/记忆/HITL/评测）
- 反模式案例：`business-analysis/tech/architecture-review-v1.md`、`design-agent-harness.md`（作为"为何重做"的对照，不入知识库正文）

**不入库（C/D）**
- `plan/`、`08-execution/`、`d0/`、`_legacy/`、`docs/phase-*`、`docs/reports`、根目录报告与 demo 脚本
- `docs/reference/**`（含 15 个 PDF：版权 + 非项目知识）
- 4 个旧 java + 1 xml（**禁止参考**，仅归档）

## 4. 治理流水线（入库前必做）

1. **来源标注**：每 chunk 带 `source_path / source_grade / source_date / git_commit`
2. **引用校验**：卡片中出现的类/方法/表/topic 必须在当前代码/配置中存在（不存在→标记"历史"或删除）
   - 已知需校验的高风险点：`traceId` 样例、`TransactionConfig`、`BodyCacheFilter`、`PaymentReconcileJob`、`DLQ 监控` 等（覆盖 21 个修复点）
3. **去重**：文件 hash + 语义去重（同名卡片保留最新并标注版本）
4. **脱敏**：旧文档含云主机 IP（如 21.130.247.89）、账号/密码示例 → 删除或打码
5. **时效**：`source_date` 早于关键修复（2026-09-12）的卡片，需比对当前代码后标注 `verified_at`
6. **改写**：B 级叙述文档提炼为统一卡片（标题/结论/证据/相关代码/相关 topic）
7. **入库**：文档层（architecture/business）+ 代码层（code-map）入 ES `xhs_ai_knowledge`（v1 词法/元数据；向量评测触发后再加），带 `layer` 过滤字段；同步重建 `catalog.yaml`

## 5. 与知识三层的映射 & 规模

| 层 | 主来源 | 预计 chunk（≤2 万上限） |
|----|--------|----------------------|
| architecture | knowledge/architecture + overview 提炼 | 200-400 |
| business | knowledge/business + pillar 提炼 | 150-300 |
| code | code-map（feign/mq/call-chain/mainline）+ LSP 动态卡片 | 500-1500 |
| 合计 | — | **~1-2k（远低于 v1 阈值）** |

## 6. 覆盖验收（对应 RQ01 KB 场景）

| KB 场景 | 所需资产 | 现状 |
|---------|---------|------|
| KB-01 架构总览 | architecture 卡片 | ✅ 现有 |
| KB-02 业务链路 | business 卡片 | ✅ 现有 |
| KB-03 三级扣减/TCC | inventory-three-level.yaml + 代码 | ✅+校验 |
| KB-04 事务消息 | transaction-message-anchor.yaml | ✅ |
| KB-05 代码定位 | code-map + LSP | 部分（LSP 动态补全） |
| KB-06 blame/历史 | 动态（Git） | 无需存量 |
| KB-07 变更影响 | feign/mq/call-chain 映射 | ✅ 现有（需校验） |
| KB-08 历史故障 | failure 卡（仅 1 张） | ⚠️ 需从修复记录补建（21 个修复点 → 补卡） |

## 7. 执行计划

1. 生成清单 CSV（path/type/grade/hash/date）→ 归档 `docs/requirements/assets-inventory.csv`
2. A 级批处理：校验引用 → 脱敏 → 入库；产出校验报告（通过/标记/剔除）
3. B 级提炼：人工+AI 提炼成卡（每文档 ≤5 卡），先做 `vol-ai` + pillar 精选
4. **补建故障卡**：21 个运行态修复点 → 每个一张 failure 卡（KB-08 的主要来源）
5. 验收：RQ01 KB-01..08 每场景至少 1 张通过引用校验的卡片
