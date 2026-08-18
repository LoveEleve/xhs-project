# 收官资产索引（my-xhs-ai）

> 日期：2026-08-18 | 用途：把"研发完结 → 作品集完结"的资产统一索引，避免散落。
> 配套：复盘、讲稿、JD 矩阵、理论专题和 `docs/reports/` 评测证据。

---

## 一、资产清单

| 资产 | 文件 | 用途 |
|------|------|------|
| 项目总复盘（论文式） | `retrospective-v1.md` | 作品集/博客母稿，串起全部叙事 |
| 三档讲稿 | `pitch-3tier-v1.md` | 30s / 3min / 10min 面试自述 |
| 九问讲稿 | `nine-questions-v1.md` | 架构九问 + 高频追问深度答法 |
| JD 命中矩阵 | `jd-hit-matrix-v1.md` | 投递地图（语言过滤 + 能力命中 + 补位话术） |
| 评测证据 | `docs/reports/eval-gate-report-20260817.{json,md}` | 真库真模型门禁结果归档 |
| nightly 全量报告 | `docs/reports/nightly-100-report-20260817.md` | smoke 20 + regression 80 = 100 条全量首跑 |
| nightly 质量摘要 | `docs/reports/nightly-100-quality-summary-20260817.md` | 质量/Token/时延/发散率/bad case |
| T9 Agent 理论 | `theory-agent-v1.md` | Chatbot / Workflow / Agent / 企业级边界 |
| T10 工程原理 | `theory-enterprise-agent-engineering-v1.md` | 状态/恢复/HITL/Policy/评测 |
| T11 评测理论 | `theory-evaluation-v1.md` | 指标/断言/Judge/bad case 闭环 |
| T13 反编造专题 | `theory-anti-hallucination-v1.md` | 证据链/数字一致性/评测器边界 |
| T13 Harness 选型专题 | `theory-harness-vs-framework-v1.md` | 成熟框架 vs 最小自研控制面 |

---

## 二、叙事主线（一句话）

**不是聊天机器人，不是简单 RAG，而是一个受限、可审计、可评测、可恢复的企业级诊断 Agent。**

核心卖点三件事：**不编造、不越权、可评测**。

---

## 三、使用顺序

1. 面试开场 → `pitch-3tier-v1.md`（三档）
2. 深聊追问 → `nine-questions-v1.md`（九问）
3. 投递定位 → `jd-hit-matrix-v1.md`（矩阵）
4. 作品集/博客 → `retrospective-v1.md`（复盘）
5. 证据引用 → `docs/reports/`（评测报告）

---

## 四、待补资产（收官剩余）

| 项 | 状态 |
|----|------|
| T2 业务案例卡（5 个） | 已完成 |
| T1 nightly 全量评测 | 已完成（100 条首跑） |
| T4 成本/时延/发散率报告 | 已完成基础摘要，后续可按需细化 |
| T9/T10/T11 理论讲解系列 | 已完成首版 + 深度修订 |
| T13 专题长文 | 已完成反编造 + Harness 选型，待多智能体专题 |
| T14 外部方案对照 | 可选增强 |
