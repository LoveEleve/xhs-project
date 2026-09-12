# D12 · 检索与知识设计（Agentic Retrieval，非默认 RAG）

> 依据：`reviews/09-tech-necessity-review.md`（技术必要性审查）；RQ01 KB-01~08、RQ03 旧资产治理；ADR-3 修订。
> 原则：**语料小/查询少/标识符多 → 结构化+词法优先；向量是评测触发的可选项，不是默认架构。**

## 1. 知识模型（预编译可引用产物）

```
knowledge/
├── catalog.yaml            # 索引（渐进披露入口：layer/topic/摘要/路径/更新时间）
├── architecture/*.yaml     # A 级：15 卡片（服务拓扑/同步异步/事务链/消息锚点…）
├── business/*.yaml         # A 级：8 卡片（下单/支付/退款/券/三级扣减…）
├── code-map/*.yaml         # A 级：40 卡片（feign/mq/async/call-chain/trace 样例）
└── failure/*.yaml          # 故障卡（21 修复点补建 + 历史复盘）
```

卡片 schema（统一）：`id/title/layer/tags/summary/evidence(类·方法·表·topic·traceId)/source(source_path/date/git_commit/verified_at)/related`。
**入库前治理**：引用校验 → 去重 → 脱敏 → 时效核验 → catalog 重建（流水线见 RQ03 §4）。

## 2. 检索工具契约（给 Agent 的工具，不是管线）

| 工具 | 输入 | 输出 | 说明 |
|------|------|------|------|
| `knowledge_search` | query, layer?, tags?, limit≤5 | 卡片摘要+路径+provenance | **BM25+元数据过滤**；返回卡片引用而非 chunk |
| `card_read` | card 路径 | **整卡内容**（≤4K） | small-to-big：小检索、大读取；避免切块割裂 |
| `code_locate` | 符号/关键字 | 文件:行号+片段 | grep/AST/JGit；方法级；jdtls 为 v1.1 触发项 |
| `history_search` | 现象/关键词 | 故障卡（根因/修复/证据） | 知识闭环：修复→卡→下次命中 |
| 运行态工具 | — | — | 复用 M2.0：MCP（ES 日志/PromQL）、DLQ、MySQL（只读） |

约束：never-throw；结果带 `source_trace`（审计已有）；工具 schema 计入 8K 预算。

## 3. Agent 流程与预算

```
catalog 速览 → knowledge_search（1~2 次）→ card_read（命中整卡）→ 必要时 code_locate / 运行态工具 → 证据校验 → 回答
```
- 知识注入预算 **≤8K token**，按相关度排序，附 provenance；
- 多跳 ≤3 次检索（超出说明证据不足并声明）；
- 引用回链校验：回答中的类/方法/表必须存在于卡片或当前代码（引用有效性 100% 门禁）。

## 4. 评测与向量触发/止损（先证明，再引入）

| 项 | 内容 |
|---|---|
| 用例 | ≥30 条真实 KB 用例（架构/链路/代码定位/故障检索；对齐 PLAT-05） |
| 基线 | BM25+结构化+整卡注入；指标：命中率、引用有效性、拒答正确、P95 |
| 向量实验 | 仅当基线准确率 <90% 且失败样本以"词汇不匹配/同义表述"为主时启动：ES knn(Ark 2048)+BM25+RRF 对比 |
| 止损 | 实验相对基线无 ≥5% 提升 → 删除向量路径与 embedding 使用，不保留半成品 |
| jdtls 触发 | 代码定位精确率 <90% 或跨文件跳转需求明确 → 启动 v1.1 |
| tool_search 触发 | Agent 侧工具 >40 个 → 加渐进披露（当前 27） |

## 5. 反模式清单（禁止）

- ❌ 全量语料切块+embedding 入库（"先嵌了再说"）
- ❌ 答案里塞一堆 chunk（上下文腐烂/中间迷失）
- ❌ 为向量引入新组件（Milvus/pgvector）——阈值：>5 万 chunk 或 P95>300ms
- ❌ Rerank/RRF 无评测收益就上线
- ❌ 知识注入超过预算后"扩窗口硬塞"
