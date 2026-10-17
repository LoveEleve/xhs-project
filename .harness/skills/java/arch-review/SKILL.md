---
name: arch-review-java
stage: 架构体检
description: 扫描代码库，发现架构摩擦点，生成可视化报告，逐一打磨改进方案。
disable-model-invocation: true
---

# 架构体检技能（Architecture Review）— Java 版

> 扫描代码库，发现"浅模块"，生成可视化 HTML 报告，然后逐个打磨改进方案。

---

## 1. 工作流程

### Step 1: 探索

**先确定范围再扫描——YAGNI。** 关注最近频繁变更的模块，它们是投入产出比最高的地方。

- 如果用户指定了方向（模块、子系统、痛点），直接使用
- 否则，用 `git log --oneline` 找热点区域

使用 `explore` 工具遍历代码库，关注以下摩擦信号：

| 信号 | 说明 |
|------|------|
| **浅模块** | 接口几乎和实现一样复杂——模块没有真正隐藏复杂性 |
| **耦合过深** | 理解一个概念需要跳转多个小模块 |
| **接缝缺失** | 纯函数被提取出来只是为了可测试，但真正的 Bug 藏在组合调用中 |
| **测试困难** | 核心逻辑无法通过当前接口直接测试 |

### Step 2: 生成 HTML 报告

写入 OS 临时目录（`$TMPDIR` 或 `/tmp` 或 `%TEMP%`），文件名 `arch-review-<timestamp>.html`。

使用 **Tailwind CDN** 布局 + **Mermaid CDN** 画架构图。

每个候选问题一张卡片，包含：

- **涉及文件** — 哪些文件/模块
- **问题** — 为什么当前架构有摩擦
- **方案** — 浅显易懂的改进描述
- **收益** — 用 locality 和 leverage 解释
- **Before/After 图** — Mermaid 对比图
- **推荐强度** — `Strong` / `Worth exploring` / `Speculative`

最后用 **Top recommendation** 推荐优先处理哪个。

### Step 3: 打磨循环

用户选择后，运行 `/harnessing` 技能，沿决策树推进。

---

## 2. 对 Java 项目的特别关注点

语言特定检查项见 `.harness/rules/工程结构.md`（依赖方向、架构约束、语言特有反模式等），按其中"架构审查关注点"一节逐项核对。
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
