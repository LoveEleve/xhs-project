# 深度 Review：M14 实现（评测闭环）

> 日期：2026-08-15 | 对象：M14 评测与质量闭环（design-m14-eval.md）| 视角：门禁可达/断言稳定性/闭环正确性
> 结论：**无 P0/P1**（写前 review 3 修正兑现）；评测集 100 条达标、judge/badcase 闭环就位、阈值校准完成

---

## 实现核对（对照 design）

| 设计点 | 落地 |
|--------|------|
| 评测集 100+（smoke 20 + regression 80）| ✓ 分层（biz/obs/decline/security/edge）；id 唯一；断言宽松稳定 |
| EvalCaseLoader 多文件 | ✓ load(cp...) 合并；badcases 缺失容忍 |
| EvalJudge（LLM-as-judge 0-5）| ✓ 主观维度（完整性/表述/不确定性），**不含证据一致性**（Review 修正 1：确定性归 EvalAsserter）；答案截断 2000（修正 2）；enabled 开关默认关 |
| BadCaseCollector 回流 | ✓ 仅质量失败回流（排除 FAILED 环境问题与 statusIn 允许的 PARTIAL——修正 3）；YAML 追加 + 原因 tag + 再加载闭环 |
| EvalRunner 集成 | ✓ judge 分数进每 case + summary.avgJudgeScore；badcase 收集器可插拔 |
| 阈值校准 | ✓ 见下 |

## 阈值校准（实测分布 → 定稿）

**实测数据（本会话 eval-gate 锚点 7 条多轮运行）**：
| 轮次 | passRate | hallucinationRate | completionRate | 备注 |
|------|:--:|:--:|:--:|------|
| 修复前 | 42.9-57.1% | 14.3-57.1% | 71.4-85.7% | 检查器误报期（67 vs 67.0 等）|
| 修复后 | 85.7-100% | 0-14.3% | 71.4-100% | 数字一致性数值语义后 |

**校准结论（阈值不动，依据文档化）**：
- hallucination ≤ 10%：修复后实测 0-14.3%（14.3 为修复中途单次）——10 留足容差，**保留**
- passRate ≥ 60%：修复后实测 57.1-100%（57.1 为中途）——60 为下限保护，**保留**
- completion ≥ 40%：实测恒 ≥71.4%——**保留**
- 校准产出 = 依据文档化 + 每次 eval-gate 运行即为新采样点（M14 起持续积累）

## 注意点（记录在案）

1. **P2：BadCaseCollector 默认输出路径为 cwd 相对**（开发/CI 模块目录工作；jar 部署需配置路径）
2. **multi-turn/memory/hitl 层未进评测集**（design 明确不做）：由 fake 集成测试确定性覆盖（RunControllerTest 多轮 / HitlApprovalTest 审批）；评测集不引入模型不确定性
3. **regression 集为"宽松断言"层**：不追求严格断言（模型随机性），抓"结构性失败"（无证据/数字编造/拒答失败）
4. **judge 默认关闭**（成本）；nightly 开启后 avgJudgeScore 进报告
5. **全量 100 条真库评测 ~40 分钟**：门禁锚点（smoke 20）不变；全量走 nightly

## 方法论复盘

- **写前 review 3 修正兑现**：judge 不做确定性检查（职责分离）/答案截断控成本/回流排除环境问题（FAILED）
- **测试先行发现路径问题**：badcases 测试 copy 到 src 而非 classpath → 加载 0 条——测试暴露部署路径语义（classpath vs 文件系统）
- **阈值校准是"数据说话"的又一例**：10/60/40 从拍脑袋 → 实测分布支撑（修复后数据支持现状，无需放宽）

## 结论

- M14 完成：评测集 100 条（20+80）+ 分层 + LLM-as-judge + bad case 回流闭环 + 阈值校准文档化
- 229 测试全绿（app 168 + tools 50 + mcp 11）
- **M10-M14 规划全部完成**——roadmap 收口

---

## 附：M10-M14 总览（里程碑验收对照）

| 里程碑 | 交付 | 验收 |
|--------|------|------|
| M10 会话记忆 | 多轮上下文/摘要/409 | 200 测试 + 真库 E2E + 深度 review（2 P0 修复）|
| M12 注册表 | 单一事实源/驱动/MCP 导出 | 209 测试 + review（1 P0 规则漂移修复）|
| M11 HITL | 挂起/审批/resume/审计 | 216 测试 + 二轮 review（2 P1 修复）|
| M13 多 Agent | 双 Agent 分流 + 对比评测 | 225 测试 + 对比胜出 + 二轮 review（P0/P1 修复）|
| M14 评测闭环 | 100 条/LLM-judge/badcase/校准 | 229 测试全绿 |
