# Phase 9: 评测体系（5 维 + Constraint Decay）

## 前置依赖

- **Phase 3 (RAG)**：RAGAS 评测方法论
- **Phase 5 (Agent架构)** + **Phase 6 (MCP)**：Agent 系统+Tool 存在了才能评测

## 为什么第九

Agent 存在了（Phase 5-8），但不评测就是盲飞。本 Phase 建立完整评测体系：50+ my-xhs 运营评测集 + 5 维评测框架 + CI/CD 自动化回归。Constraint Decay 论文告诉我们——如果 Agent 只在 happy path 上测、不在约束/故障下测，上线必翻。

## 与 my-xhs 的关联

| 评测内容 | my-xhs 数据源 | 评测什么问题 |
|---------|-------------|-------------|
| 订单问题评测集 | business-domain.md 订单域 Top 20 #1-5 | "今天订单量？比昨天？支付成功率？" |
| 内容问题评测集 | 内容域 Top 20 #6-10 | "今天发布多少笔记？热门话题？" |
| 基础设施评测集 | 异常检测域 #17-19 | "最近有哪些异常？库存不足？" |
| 用户评测集 | 用户域 #20 | "今天注册多少用户？活跃度？" |
| 约束/故障评测集 | 故障注入实验 | "Tool 超时时 Agent 行为？注入错误信息？" |
| Scorecard | Prometheus + Grafana | 6 项生产指标可视化+告警 |

## 学什么

| 模块 | 内容 |
|------|------|
| Constraint Decay | Agent happy path 好但约束下静默失败→约束测试权重 > happy path |
| 5 维框架 | 任务完成/约束满足/故障鲁棒/成本延迟/安全护栏 |
| 评测金字塔 | Static→Unit→Integration→E2E 四层 + CI/CD 集成 + 回归门禁 |
| 评测集 | 50+ my-xhs 运营问题+预期答案+预期 Tool 调用+难度分级 |
| 生产 Scorecard | 6 项指标+告警阈值 (P95 延迟<15s, 约束违反率<3%, 成本<$0.10) |

## 生产工程问题

| 问题 | 本 Phase 如何解决 | 验证方式 |
|------|-----------------|---------|
| **幻觉率** | Golden Dataset 标注"可验证事实" vs "推测"，计算幻觉率 = 编造事实/总输出 | 50 条评测中幻觉率 < 5% |
| **拒答率** | 评测集中包含"Agent 应该回答" vs "Agent 应该拒答"两类，分别计算通过率 | 应回答 100%、应拒答 100% |
| **漂移检测** | 每次 Prompt/Model/Tool 变更→自动跑全量评测→评分下降 > 5% 阻止合并 | CI/CD 门禁生效 |
| **多轮退化** | 评测集包含多轮对话场景（5 轮/10 轮/15 轮），监控各轮 RAGAS 分数趋势 | 10 轮后忠实度下降 < 10% |

## 文档清单（7 篇）

| # | 文档 | 内容要点 |
|---|------|---------|
| 01 | constraint-decay.md | Constraint Decay 论文精读（详见下方论文提取） |
| 02 | 5d-framework.md | 五维度定义+每维度评测方法+适用工具（DeepEval/RAGAS/Promptfoo） |
| 03 | testing-pyramid.md | Static（Schema校验）→Unit（Mock 工具）→Integration（真实 MCP）→E2E（全场景） |
| 04 | golden-dataset.md | 50+ 条评测数据（约束类 > 50%）+预期答案+预期 Tool 调用序列+难度分级 |
| 05 | automation-pipeline.md | CI/CD 集成：每次 Prompt/Model/Tool 变更→自动跑评测→回归门禁（下降 > 5% 阻止） |
| 06 | production-scorecard.md | 6 项指标定义+Prometheus 采集+Grafana Dashboard 配置 |
| 07 | tool-comparison.md | DeepEval/RAGAS/Promptfoo/Langfuse 四工具对比：适用场景+集成成本+局限 |

### Constraint Decay 论文提取规划（文档 01）

| 要提取什么 | 产出 |
|-----------|------|
| **核心发现**：Agent 在 happy path vs 约束条件下的通过率差距——具体数据是多少？ | 数据对比表（happy path vs 约束，按任务类型分） |
| **哪类约束最致命**：性能约束/向后兼容/安全约束/边界条件——哪种 Agent 退化最严重？ | 约束分类+退化程度排序 |
| **为什么约束下 Agent 静默失败**——LLM 是"猜"而不是"验证"？还是 Prompt 没覆盖约束？ | 根因分析 |
| **评测策略启发**：评测集中约束测试权重应该 > happy path 测试——具体比例？ | 评测集设计指南 |
| **my-xhs 映射**：Top 20 运营问题中哪些是"约束类"？怎么构造约束变体？ | 10 条约束变体问题 |

### AgentBench 论文提取规划（文档 03/04 参考）

| 要提取什么 | 产出 |
|-----------|------|
| **8 环境的评测设计**——为什么要分 8 个环境？每个环境测什么？ | 环境分类表 |
| **LLM-as-Agent 的通用能力 vs 环境特化能力**——哪些 LLM 在哪类环境表现好？ | 能力矩阵 |
| **对 my-xhs 评测集的启发**——AgentBench 的环境设计如何映射到 my-xhs 运营场景 | 设计指南 |
| **my-xhs 映射**：Top 20 运营问题中哪些是"约束类"？怎么构造约束变体？ | 10 条约束变体问题 |

## 代码结构

```
src/main/java/com/myxhs/ai/eval/
├── EvalEngine.java              # 5 维评测引擎
├── ConstraintInjector.java      # 约束注入+故障注入
├── EvalDatasetLoader.java       # 评测集加载
├── RegressionGate.java          # 回归门禁（评分下降>5%→阻止）
├── PipelineRunner.java          # CI/CD 适配器
└── ScorecardCollector.java      # 6 项指标收集+Prometheus 推送
```

## 验证标准

1. 评测集 50+ 条：约束类 > 50%，覆盖 Top 20 运营问题中 15+
2. Static+Unit+Integration+E2E 四层可运行
3. Prompt 变更→自动跑评测→评分<95% 阻止合并
4. 生产 6 项指标 Grafana Dashboard+告警

## 对后续的影响

- **Phase 10 (AgentOps)**：评测是 AgentOps 核心
