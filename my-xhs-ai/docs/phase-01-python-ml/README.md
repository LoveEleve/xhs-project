# Phase 1: Python + ML 基础

## 前置依赖

无。

## 为什么第一个

你是 Java 工程师，Python 不需要精通。但这个 Phase 的核心不是学 Python——是理解 ML 基本概念。后续每个 Phase 都建立在这些概念上：

| 本 Phase 学的内容 | 后续哪个 Phase 用到 | 怎么用 |
|-----------------|-------------------|--------|
| 梯度下降 + 损失函数 | Phase 2 | 理解 Transformer 训练、为什么交叉熵 |
| 过拟合/欠拟合 | Phase 2, 14 | 理解 temperature 参数、微调过拟合 |
| NumPy 矩阵运算 | Phase 2 | 手写 Attention 前向传播 |
| Python 基础 | Phase 3, 14, 16 | 跑 HuggingFace 实验、LoRA 微调、AI 编程 |

## 与 my-xhs 的关联

本 Phase 看似与 my-xhs 无关，但它是后续所有 Phase 的基础：

- Phase 2 的 Mini Transformer 需要 NumPy 矩阵运算 → 理解 LLM 怎么生成文本 → Phase 5 的 Agent 才能回答 my-xhs 运营问题
- Phase 3 的 Embedding 对比实验需要 Python → 选最优中文 Embedding 模型 → RAG 知识库能准确检索 my-xhs 文档
- 本 Phase 结束时你能用 Python 跑 HuggingFace 实验，后续 Phase 3/14 的 Python 实验不再解释 Python 语法

## 学什么

| 模块 | 内容 | 时间 |
|------|------|------|
| Python 语法 | 变量/类型/函数/类/pip/venv | 3-5天 |
| NumPy+Pandas | ndarray/矩阵运算/DataFrame/数据聚合 | 2-3天 |
| ML 基本概念 | 监督/无监督/强化学习分类、训练集/验证集/测试集、过拟合/欠拟合、准确率/精确率/召回率/F1/ROC-AUC、MSE/交叉熵、梯度下降三变种 | 3-4天 |
| 神经网络基础 | Sigmoid/ReLU/Tanh、前向传播、反向传播（链式法则）、交叉熵损失+SGD、纯 NumPy 2层网络→MNIST 90%+ | 5-7天 |
| HuggingFace | transformers 库入门、加载预训练模型、pipeline 推理 | 2-3天 |

## 生产工程问题

| 问题 | 本 Phase 解决什么 | 后续 Phase 如何承接 |
|------|-----------------|-------------------|
| **幻觉** | 理解神经网络"预测"的本质——输出是概率分布，不是"事实"。温度参数影响概率分布的尖锐程度。 | Phase 2 的 Decoding 策略 + Phase 9 的幻觉率评测 |
| **过拟合→泛化** | 训练集 99% 测试集 70% = 过拟合。LLM 的"记忆训练数据" = 过拟合的一种形式。 | Phase 14 微调过拟合检测 |

## 文档清单（5 篇）

| # | 文档 | 内容要点 |
|---|------|---------|
| 01 | python-basics.md | Python 核心语法速成：变量/控制流/函数/类/模块/pip/venv |
| 02 | numpy-pandas.md | NumPy ndarray 操作+广播机制，Pandas DataFrame 读写/筛选/聚合 |
| 03 | ml-concepts.md | ML 分类图谱、过拟合诊断（训练集vs验证集loss曲线）、评估指标公式+适用场景、损失函数对比（MSE vs 交叉熵）、梯度下降三变种（BGD/SGD/Mini-batch） |
| 04 | neural-network.md | 激活函数求导（Sigmoid/ReLU/Tanh）、前向传播矩阵推导、反向传播链式法则详细推导、NumPy 实现+MNIST 训练（目标 90%+） |
| 05 | huggingface.md | transformers 库、加载 BERT、pipeline 推理、模型卡解读 |

## 代码结构

```
experiments/                    # Python 实验目录
├── 01_python_basics.py
├── 02_numpy_pandas.ipynb
├── 03_ml_concepts.ipynb
├── 04_neural_network/
│   ├── net.py                  # 2 层神经网络 NumPy 实现
│   ├── train.py                # MNIST 训练脚本（输出 loss 曲线、准确率曲线）
│   └── test.py                 # 评估脚本
└── 05_huggingface/
    └── sentiment.py            # BERT 情感分析
```

## 验证标准

1. 手写 2 层神经网络在 MNIST 上达到 90%+ 准确率
2. 能用 HuggingFace BERT 跑情感分析
3. 能解释"分类为什么用交叉熵不用 MSE"（MSE 梯度消失 + 非凸优化）
4. 能解释"过拟合是什么意思、怎么发现（train loss 降 val loss 升）、怎么解决（正则化/Dropout/早停）"
5. 能画出训练过程的 loss 曲线和准确率曲线

## 对后续的影响

- **Phase 2 (LLM原理)**：NumPy 矩阵运算→手写 Mini Transformer、梯度下降→理解模型训练、神经网络→理解 Transformer 架构
- **Phase 14 (微调)**：Python 能力→HuggingFace PEFT LoRA 微调实验
- **Phase 16 (AI编程)**：Python 能力→用 Claude Code/Cursor 辅助开发
