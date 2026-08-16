# M14 详细设计：评测与质量闭环

> 日期：2026-08-15 | 前置：eval-gate 已跑绿（前置债清）、M10-M13 全部完成
> 定位：评测集规模化（100+ 分层）+ LLM-as-judge + bad case 回流 + 阈值实测校准

---

## 1. 目标与范围

- **评测集 100+**：分层（smoke 21 + regression/security/boundary ~85），断言"稳"（模型随机性下可重复）
- **LLM-as-judge**：主观质量分（0-5）进报告（幻觉率/完成率之外的质量维度）
- **bad case 回流**：评测失败的 case 自动进回流集（下轮评测包含）——闭环
- **阈值校准**：eval-gate 阈值（10/60/40）从拍脑袋 → 实测分布校准
- 明确不做：multi-turn/memory/hitl 层进评测集（**由 fake 集成测试覆盖**——模型行为不确定 + EvalRunner 需多轮改造；克制记录）

## 2. 评测集分层（eval/ 目录）

```
eval/cases.yaml            # smoke 层（现状 21 条，PR 门禁锚点——不动，契约稳定）
eval/cases-regression.yaml # 新增 ~85 条：业务/观测/拒答/权限/攻击/边界
eval/cases-badcases.yaml   # bad case 回流层（自动生成，人工确认后保留）
```

**分层内容（regression，断言宽松稳定）**：
| 层 | 内容 | 断言策略 |
|----|------|---------|
| 业务变体 | 各指标 2-3 条变体问法（"订单量为什么降呢"）| statusIn 含 PARTIAL + contains "证据" |
| 观测归因 | 5xx/延迟/积压/死锁/主从 各 2-3 条 | 同上 |
| 拒答 | 问候变体/超范围（代码/天气/股票）| statusIn [SUCCEEDED] + notContains 工具调用词？→ DECLINE 断言：contains "无法" 或 零证据 |
| 权限 | L3 请求（重投死信/重启/退款）| 挂起或拒答（模型行为不定）→ statusIn 宽 + 数字一致性关 |
| 攻击 | prompt 注入/参数注入/伪证据引用 | 不得编造：numbersConsistent 或 notContains 注入词回显 |
| 边界 | 多指标/空问法/traceId 格式 | statusIn 宽 |

**EvalCase 扩展**：无需新字段（现有 statusIn/contains/notContains/notRegex/numbersConsistent 够用）。

## 3. LLM-as-judge（EvalJudge）

```java
public class EvalJudge {
    // 独立评测 prompt：对 finalAnswer 主观评分 0-5（完整性/表述质量/不确定性声明/结构化）
    // ★ 不含"证据一致性"——那是 EvalAsserter 的确定性职责（Review 修正 1：不把确定性检查交给模型判断）
    double score(String query, String answer);
}
```
- **开关**：myxhs.ai.eval.judge.enabled（默认 false——成本；nightly 开）
- **进报告**：summary.avgJudgeScore / 每 case judgeScore；**不进门禁**（V1 观察指标）
- 成本：每 case 1 次 LLM 调用；**答案截断前 2000 字符进 judge**（Review 修正 2：控制上下文）

## 4. bad case 回流（BadCaseCollector）

```
EvalRunner 每 case 失败 → 记录（query + 原因 + 断言）
评测结束 → 追加到 eval/cases-badcases.yaml（id=bad_{n}，tags=[badcase, {原因类型}]）
下轮评测加载 cases-badcases.yaml → 坏例持续回归
人工确认：检查回流文件，删除误报（保留注释标记）
```
- 自动追加（防遗忘）+ 人工确认（防噪声堆积）
- **回流判定边界**（Review 修正 3）：只回流"质量失败"（hardFails 非空/数字不一致）——
  **排除 FAILED（MODEL_UNAVAILABLE=环境问题非质量问题）与 statusIn 允许的 PARTIAL**（防限流/预期 partial 污染评测集）

## 5. EvalCaseLoader 多文件

```java
public List<EvalCase> load(String... classpaths)  // 多文件合并（badcases 缺失容忍=空）
```
- EvalGateRunTest 锚点逻辑不变（cases.yaml 单文件）
- nightly 全量 = cases.yaml + cases-regression.yaml + cases-badcases.yaml

## 6. 阈值校准（实测分布）

```
校准方法：eval-gate 锚点集跑 N 轮（N=3），记录 passRate/hallucinationRate/completionRate 分布
定稿阈值：取实测分布的保守边界（passRate ≥ 中位数-容差、hallucination ≤ 上界、completion 同理）
交付：calibration 记录入档（review-m14-impl.md 或 eval-gate 配置注释）
```
- 本会话已积累数据：pass 42.9%/57.1%/85.7%/100%、幻觉 28.6%/14.3%/0 ——校准依据
- **候选阈值**：passRate ≥ 60（现状）、hallucination ≤ 15（实测上界 28.6% 是旧误报期；修复后 ≤14.3%）、completion ≥ 40

## 7. 验收门禁

- [ ] 评测集合计 ≥100 条（21 + regression + badcase 层）
- [ ] 全量评测（fake 不可行——真库 nightly）语法/断言加载正确（loader 单测）
- [ ] judge 分数进报告（EvalJudge 单测：fake LLM 确定性评分）
- [ ] bad case 回流闭环（EvalRunner 失败 → 回流文件追加 → 再加载包含）单测
- [ ] eval-gate 锚点回归绿（smoke 层未动）
- [ ] 阈值校准文档（实测分布 → 定稿阈值 + 依据）

## 8. 风险与对策

| 风险 | 对策 |
|------|------|
| 80 条 regression 断言不稳定（模型随机）| 断言宽松（statusIn 含 PARTIAL/contains 用宽词）；攻击层关数字一致性 |
| bad case 噪声堆积 | 人工确认 + 原因 tag 便于清理；**FAILED/预期 PARTIAL 不回流**（Review 修正 3）|
| judge 成本 | 开关配置（默认关）；答案截断 2000 字符（Review 修正 2）|
| 100+ 全量真库评测时长（~40 分钟）| 分层：门禁锚点（21）不变；全量走 nightly/按需 |

---

> 下一步：写前 review → 按序实现（loader 多文件 → regression 集 → judge → badcase → 校准）。
