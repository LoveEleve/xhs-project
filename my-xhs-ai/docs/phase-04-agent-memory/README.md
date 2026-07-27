# Phase 4: Agent 记忆系统

## 前置依赖

- **Phase 3 (RAG)**：检索管道→直接用于记忆检索

## 为什么第四

记忆是 Agent 与普通 LLM 调用的本质区别——有记忆 vs 无记忆：任务完成率 **+129%**。先理解记忆的三次进化，再在 Phase 5 学 Agent 时就知道记忆怎么设计。

## 与 my-xhs 的关联

| 本 Phase 产出 | my-xhs 集成点 | 回答什么业务问题 |
|-------------|-------------|----------------|
| 工作记忆 | Redis Business (16381) Hash | 当前会话上下文：用户刚问了什么 |
| 中期记忆 | ES 19200 + heat_score | 高频访问的业务知识自动"加热" |
| 长期记忆 | MySQL 13306 + ES 混合 | 用户偏好持久化："运营小王习惯中文简短回答" |

## 学什么

| 模块 | 内容 |
|------|------|
| 第三次进化 | MemoryOS (EMNLP 2025 Oral)：OS 级管理——分层存储+热数据提升+缓存替换（LRU/LFU） |
| 三层分层 | 短期（Redis Hash + TTL，类比 CPU 缓存）→中期（ES + heat_score，类比 RAM）→长期（MySQL + ES，类比磁盘） |
| 四大模块 | Storage（存入）→Updating（层级流转）→Retrieval（三层统一检索）→Consolidation（自动压缩合并） |
| Java 实现 | Redis 工作记忆+ES 热数据+MySQL 长期+LRU 淘汰+冲突解决 |
| 对比 | Mem0(47K⭐) vs Zep(8K⭐) vs Letta(12K⭐) vs MemoryOS 四者对比表 |

## 生产工程问题

| 问题 | 本 Phase 如何解决 | 验证方式 |
|------|-----------------|---------|
| **冷启动** | 新用户无记忆时使用业务知识库（Phase 3 RAG）作为默认上下文 | 新用户首次提问仍能得到有用回答 |
| **上下文污染** | MemoryConsolidator 在合并时过滤低置信度/错误信息（来源标注+置信度评分） | 注入错误信息后 Consolidation 自动过滤 |
| **多轮退化** | 中期记忆定期 Consolidation，保留关键事实丢弃冗余 | 10 轮对话后记忆检索准确率 > 85% |

## 文档清单（6 篇）

| # | 文档 | 内容要点 |
|---|------|---------|
| 01 | memory-evolution.md | 三次进化：Message列表→向量存储→OS级管理 |
| 02 | memoryos-deep-dive.md | MemoryOS EMNLP 2025 Oral 精读（详见下方论文提取） |
| 03 | tool-comparison.md | Mem0/Zep/Letta/MemoryOS 四者架构对比表 |
| 04 | java-memory-impl.md | Java 实现：Redis(工作)+ES(中期)+MySQL(长期)+LRU淘汰+冲突解决 |
| 05 | consolidation.md | 自动 Consolidation：相似记忆合并+冲突检测+置信度评估 |
| 06 | benchmark.md | LoCoMo 基准实验：有记忆 vs 无记忆对比 |

### MemoryOS 论文提取规划（文档 02）

| 要提取什么 | 产出 |
|-----------|------|
| **OS 类比映射**：短期=CPU缓存、中期=RAM、长期=磁盘——MemoryOS 如何用 OS 概念解决"Agent 记忆怎么管理" | OS 类比图 |
| **热数据提升机制**：`mid_term_heat_threshold` 如何工作？高频交互如何"加热"？冷数据如何降级？ | 算法流程图 + Java 伪代码 |
| **四大模块源码级理解**：Storage(存入)→Updating(层级流转)→Retrieval(三层检索)→Generation(个性化回复) | 每个模块的输入/输出/内部状态 |
| **LoCoMo 基准数据**：F1 +49.11%、BLEU-1 +46.18%——怎么测出来的？测试集是什么？ | 基准设计分析 |
| **与 Mem0/Zep/Letta 的架构差异**——"中期缓冲层"是关键创新 | 对比表 + 设计哲学分析 |
| **Java 映射方案**：OS 分层存储/热数据/缓存替换 如何用 Java 实现 | 技术选型决策 |

### MemGPT 论文提取规划（文档 01/03 基础）

| 要提取什么 | 产出 |
|-----------|------|
| **虚拟内存的类比**：Main Context（内存）↔ Recall Storage（磁盘）↔ Archival Storage（归档） | OS 类比图 |
| **Agent 如何主动决定信息迁移**——类似 OS 的 page fault，Agent 触发检索的机制 | 决策流程图 |
| **MemGPT vs MemoryOS 设计差异**——MemGPT 是 Agent 驱动的检索，MemoryOS 是系统级调度 | 架构对比 |

## 代码结构

```
src/main/java/com/myxhs/ai/memory/
├── WorkingMemoryStore.java     # Redis Hash + TTL（短期记忆）
├── MidTermMemoryManager.java   # ES + heat_score + 冷热分离（中期记忆）
├── LongTermMemoryStore.java    # MySQL + ES 混合（长期记忆）
├── MemoryHeatManager.java      # Redis Sorted Set 热数据管理 + LRU/LFU
├── MemoryRetriever.java        # 三层统一检索（Redis→ES→MySQL 降级）
└── MemoryConsolidator.java     # 自动 Consolidation + 冲突解决 + 置信度评估
```

## 验证标准

1. 同一 sessionId 多次对话，Agent 记得上一轮的用户偏好
2. 热数据提升：高频记忆排在检索结果前 3 位
3. 缓存淘汰：容量满时 LRU 淘汰最不常用项，淘汰前后检索精度下降 < 5%
4. 冲突解决：新事实（"用户偏好简短回答"）覆盖旧事实（"用户偏好详细回答"）
5. 冷启动：新用户首次提问使用 Phase 3 RAG 知识库兜底

## 对后续的影响

- **Phase 5 (Agent架构)**：记忆是 Agent 运行时的核心组件——每个 Agent 调用前自动检索相关记忆
- **Phase 13 (治理)**：记忆系统的审计追踪——谁在何时查询了什么记忆
