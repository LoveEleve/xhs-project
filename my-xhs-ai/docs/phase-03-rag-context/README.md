# Phase 3: RAG + 上下文工程

## 前置依赖

- **Phase 2**：Embedding 理解、Prompt 工程

## 为什么第三

Phase 2 理解了"LLM 怎么生成文本"，Phase 3 解决"LLM 怎么利用外部知识"。RAG 是 2026 年 100% AI Agent 岗位的基准技能。产出是 **my-xhs 知识库问答系统**。

## 与 my-xhs 的关联

| 本 Phase 产出 | my-xhs 集成点 | 回答什么业务问题 |
|-------------|-------------|----------------|
| RAG 检索管道 | ES 19200 dense_vector | "my-xhs 有几个数据库实例？" |
| my-xhs 知识库索引 | 索引 docs/test-2/*.md + SQL schema + API 文档 | "订单表怎么分片的？" |
| 上下文管理 | my-xhs-ai ContextManager | 多轮对话中保持知识上下文 |

## 学什么

| 模块 | 内容 |
|------|------|
| 文档解析 | Markdown/HTML 结构化提取、表格/代码块处理 |
| 分块策略 | 固定大小 vs 语义 vs 递归、overlap、三种策略检索对比实验 |
| Embedding 模型 | BGE/text2vec/OpenAI 中文效果定量对比（Python 跑实验，Java 集成结果） |
| 向量存储 | ES dense_vector + HNSW 索引原理、ef_construction/m/num_candidates 参数调优 |
| 检索策略 | BM25→稠密→RRF 融合→Cross-Encoder Rerank 全链路 |
| 查询优化 | Query Rewrite、HyDE（假设文档嵌入）、多轮查询改写 |
| 上下文工程 | 窗口管理、结构化压缩（目标+状态+结论+下一步）、Token 预算、越界自动重试 |
| RAG 评测 | RAGAS 四指标：忠实度/答案相关性/上下文精度/召回；检索失败 vs 生成失败定位 |

## 生产工程问题

| 问题 | 本 Phase 如何解决 | 验证方式 |
|------|-----------------|---------|
| **多轮退化** | 上下文压缩：保留目标+状态+结论+下一步，丢弃冗余；Token 预算限制上下文长度 | 10 轮对话后 RAGAS 忠实度下降 < 10% |
| **上下文污染** | 检索结果标注来源+置信度；低置信度片段（相似度 < 0.7）不注入 | 注入错误文档后回答不引用错误信息 |
| **向量检索退化** | HNSW 索引定期重建；监控 QPS 和延迟 | ES 查询延迟 < 100ms (P95) |
| **Embedding 漂移** | Embedding 版本号存储；模型升级后自动增量重建索引 | 模型升级后 Top-5 准确率下降 < 5% |

## 文档清单（9 篇）

| # | 文档 | 内容要点 |
|---|------|---------|
| 01 | document-parsing.md | Markdown/HTML 解析+表格代码块处理 |
| 02 | chunking-strategy.md | 三种分块策略+overlap 实验+检索效果对比表 |
| 03 | embedding-compare.md | BGE/text2vec/OpenAI 在中英文文档上的余弦相似度定量对比（Python 实验） |
| 04 | vector-store.md | ES dense_vector 配置+ HNSW 原理+参数调优实验 |
| 05 | retrieval-strategy.md | BM25→稠密→RRF→Rerank 全链路实现+Top-5 准确率对比 |
| 06 | query-optimization.md | Query Rewrite+HyDE+多轮改写实验 |
| 07 | context-engineering.md | 窗口管理+结构化压缩+Token 预算+越界自动重试 |
| 08 | rag-evaluation.md | RAGAS 四指标详解+检索/生成故障分类定位 |
| 09 | myxhs-knowledge.md | 索引 my-xhs 模块文档+SQL schema+API 文档→知识库问答验证 |

## 代码结构

```
src/main/java/com/myxhs/ai/rag/
├── DocumentParser.java         # 文档解析器（Markdown/HTML→结构化文本）
├── ChunkingStrategy.java       # 分块策略（Fixed/Semantic/Recursive + overlap）
├── EmbeddingService.java       # Embedding 服务（BGE/text2vec API 接入）
├── VectorStoreService.java     # ES dense_vector CRUD + HNSW 索引管理
├── RetrievalPipeline.java      # 检索管道（BM25+稠密→RRF→Rerank）
├── QueryRewriter.java          # 查询改写（LLM 驱动）
├── ContextManager.java         # 上下文管理（压缩+Token 预算+越界重试）
└── RAGASEvaluator.java         # RAGAS 评测（4 指标）
```

## 验证标准

1. 三种分块策略的检索效果对比表（固定/语义/递归 Top-5 准确率）
2. BGE vs text2vec vs OpenAI 余弦相似度对比表
3. 混合检索 Top-5 准确率 > 80%
4. my-xhs 知识库能回答 "my-xhs 有几个数据库实例、分片策略是什么、订单状态机有哪些状态"
5. Context overflow 时自动压缩重试成功（不丢关键信息）
6. 10 轮对话后 RAGAS 忠实度下降 < 10%

## 对后续的影响

- **Phase 4 (Agent记忆)**：检索即记忆的一种形式——RAG 管道直接用于记忆检索
- **Phase 5 (Agent)**：工具调用结果需要拼入上下文——上下文工程是 Agent 工程的前置
- **Phase 9 (评测)**：RAGAS 评测方法论延续到 Agent 评测
