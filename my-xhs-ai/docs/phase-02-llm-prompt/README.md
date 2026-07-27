# Phase 2: LLM 原理 + Prompt 工程

## 前置依赖

- **Phase 1**：ML 基本概念、NumPy 矩阵运算、Python 基础

## 为什么第二个

Phase 1 给了 ML 底层概念，Phase 2 用这些概念理解 LLM 内部机制。不先理解 Attention 和 KV Cache，后面 RAG 和 Agent 就只能"调 API"。这个 Phase 的产出是 **my-xhs-ai 第一个可运行的接口**：`POST /api/ai/chat`。

## 与 my-xhs 的关联

| 本 Phase 产出 | my-xhs 集成点 | 对应业务 |
|-------------|-------------|---------|
| `POST /api/ai/chat` (SSE) | my-xhs-gateway `/api/ai/**` 路由 | 运营后台 → AI 对话入口 |
| Prompt 模板系统 | my-xhs-ai 内部使用 | 所有后续 Phase 的 Prompt 管理 |
| 结构化输出 (JSON→Java Record) | my-xhs-ai 类型安全 | Agent Tool 调用的参数/返回格式 |
| Mini Transformer | 仅学习用 | 理解 LLM 内部机制，不部署 |

## 学什么

| 模块 | 内容 |
|------|------|
| Tokenization | BPE 算法原理、中文分词特性、GPT vs 中文 tokenizer 对比实验 |
| Embedding | 词向量编码语义、位置编码（Sinusoidal vs RoPE）、余弦相似度 vs 欧氏距离 |
| Self-Attention | Q/K/V 来源、Scaled Dot-Product 推导（为什么除以 √d_k）、Multi-Head 意义 |
| Transformer | Decoder-only 架构、Layer Norm（Pre-LN）、残差连接、FFN |
| KV Cache | 内存计算公式 `2×L×d×S×dtype`、延迟实验：不同 seq_len 的首 token 时间 |
| Decoding | temperature/top-p/top-k 对比实验：固定 prompt，对比 3 组参数输出 |
| Prompt 工程 | 结构化模板（Role→Context→Rules→Output）、Few-shot 实验、结构化输出（JSON Schema+Java Record） |
| Prompt 安全 | System Prompt 泄露攻击、角色切换攻击（"Ignore all instructions"）、防御策略 |

## 生产工程问题

| 问题 | 本 Phase 如何解决 | 验证方式 |
|------|-----------------|---------|
| **幻觉** | 低 temperature (0.1) 减少概率分布尾部的不可靠采样；结构化输出约束答案格式；System Prompt 明确"不知道就说不确定" | 评测：30 个问题中编造事实的比例 |
| **输出一致性** | 低 temperature 使多次回答趋于一致；Few-shot 示例锚定输出风格 | 同一问题 5 次回答的语义相似度 > 0.9 |
| **模型降级** | 仅本 Phase 接入 DeepSeek，Phase 5 再加 FallbackModel | 本 Phase：DeepSeek 不可用时返回明确错误，不 Crash |
| **结构化输出失败** | JSON Schema 约束 + 格式错误自动重试（最多 3 次） | 100 次调用中格式错误率 < 2% |
| **Prompt 注入** | 输入过滤：检测 "ignore all instructions" / "system:" 注入模式；输出过滤：检测 System Prompt 泄露 | 10 种注入攻击全部拦截 |

## 文档清单（9 篇）

| # | 文档 | 内容要点 |
|---|------|---------|
| 01 | tokenization.md | BPE 算法+Java 实现+中文分词实验（对比 GPT tokenizer vs 中文专用） |
| 02 | embedding.md | 词向量语义+位置编码（Sinusoidal vs RoPE）+余弦相似度实验 |
| 03 | attention.md | Q/K/V 推导+Multi-Head+Scaled Dot-Product+Java 实现（前向传播） |
| 04 | transformer.md | Decoder-only 完整架构+Pre-LN+残差+FFN+前向传播实现 |
| 05 | kv-cache.md | 内存公式推导 `2×L×d×S×dtype` + 不同 seq_len 延迟实验表 |
| 06 | decoding.md | temperature/top-p/top-k 对比实验：同一 prompt 3 组参数输出对比表 |
| 07 | prompt-system.md | 结构化模板引擎（Role→Context→Rules→Output）+Git 版本管理+A/B 对比 |
| 08 | structured-output.md | JSON Schema 约束+Java Record 映射+格式错误重试（最多 3 次） |
| 09 | prompt-security.md | 注入攻击 10 种案例+输入过滤+结构化约束防御+检测率验证 |

### 论文提取规划

#### Attention Is All You Need (NeurIPS 2017) — 文档 03/04 基础

| 要提取什么 | 产出 |
|-----------|------|
| **Self-Attention 的设计动机**——为什么不是 RNN/LSTM？RNN 的两个致命问题（串行依赖+长程梯度消失） | 设计决策对比表 |
| **Scaled Dot-Product Attention 公式推导**：Q·K^T / √d_k → Softmax → ×V，每一步的物理含义 | 公式拆解图 |
| **Multi-Head 的数学证明**——为什么多头比单头好？每个 Head 学到不同关系模式的实验证据 | 多头 vs 单头对比 |
| **Positional Encoding 的 Sinusoidal 设计**——为什么用 sin/cos 而不是可学习参数？ | 设计动机分析 |
| **残差连接 + Layer Norm 的位置**——Pre-LN vs Post-LN 的差异？为什么现在都用 Pre-LN？ | 架构对比 |

#### InstructGPT (NeurIPS 2022) — 文档 07 基础

| 要提取什么 | 产出 |
|-----------|------|
| **RLHF 三阶段**：SFT→RM→PPO——每阶段的数据需求、训练目标、算力配比 | 三阶段流程图 |
| **为什么需要 RLHF**——Base Model 的"续写"行为 vs Assistant 的"回答"行为本质差异 | 行为对比分析 |
| **Reward Model 的局限**——"奖励黑客"（reward hacking）现象：模型学会了取悦打分器而非真正有用 | 问题+对策 |
| **KL 散度惩罚**——为什么 RL 阶段需要约束模型不要偏离 SFT 太远？数学公式+直觉解释 | 公式+直觉 |

## 代码结构

```
src/main/java/com/myxhs/ai/
├── transformer/
│   ├── Tokenizer.java          # BPE 分词器
│   ├── Embedding.java          # 词嵌入+位置编码
│   ├── Attention.java          # Multi-Head Self-Attention（前向传播）
│   ├── TransformerBlock.java   # Attention+FFN+LayerNorm+残差
│   ├── MiniTransformer.java    # 2层128维完整前向传播
│   └── KVCache.java            # KV Cache 计算器+内存估算
├── prompt/
│   ├── PromptTemplate.java     # 结构化模板引擎（变量替换+条件渲染）
│   ├── PromptVersion.java      # Git 版本管理+回滚
│   └── SecurityFilter.java     # 注入防御过滤器（模式匹配+黑名单）
└── controller/
    └── AiChatController.java   # POST /api/ai/chat (SSE 流式)
```

## 验证标准

1. Mini Transformer 能跑通前向传播（token IDs→logits），不需要训练
2. `POST /api/ai/chat` SSE 流式对话可用，延迟 < 3s
3. 结构化输出：LLM→JSON→Java Record，格式错误自动重试成功
4. Prompt 模板可版本回滚+A/B 对比（Git 管理）
5. 10 种注入攻击 100% 拦截
6. 同一问题 5 次回答语义相似度 > 0.9（temperature=0.1）

## 对后续的影响

- **Phase 3 (RAG)**：Embedding 理解→检索基础、Prompt 模板→检索结果拼接
- **Phase 5 (Agent)**：Prompt 工程→Agent System Prompt 设计
- **Phase 14 (微调)**：Transformer 理解→微调前提
