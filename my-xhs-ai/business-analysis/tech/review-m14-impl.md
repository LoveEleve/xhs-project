# 深度 Review：M14 实现（评测闭环）

> 日期：2026-08-15 | 对象：M14 评测与质量闭环（design-m14-eval.md）| 视角：门禁可达/断言稳定性/闭环正确性
> 结论：**二轮 review 发现 1 个 P0（检查器 traceId 误报）+ 2 个 P1（judge 解析/badcase id）+ 断言过严**——全部修复，抽样冒烟 8/8

---

## P0-1（二轮 review）：检查器对 traceId 32hex 误报幻觉

**问题链**：regression 集抽样（真库）暴露——traceId 查询用例中模型把请求 ID（32 位 hex）原样写进结论 → 数字提取把 hex 拆成多段数字 → 无证据匹配 → **幻觉误报**（`unmatched=[0123456789]`）。eval-gate 锚点集无 traceId 用例 → 从未暴露。

**修复**：EvalAsserter 的 DATETIME 剥离模式加 `[0-9a-fA-F]{32}`（traceId/请求 ID 非业务数字，答案侧+证据侧）。回归测试：答案含 32hex + 3 处错误 → 只提取业务数字 3；traceId 查询场景数字一致性通过。

## P1-1（二轮 review）：judge 分数解析脆弱

**问题链**：`split()[0]` 取首个 token——模型输出"评分：4.5" → 取到"评分" → parse 失败 → -1（not scored）丢失评分。

**修复**：正则提取首个数字（`\d+(\.\d+)?`）。

## P1-2（二轮 review）：badcase 回流 id 冲突

**问题链**：append 的 id 从固定 startIndex 递增——多次回流后 `bad_1` 重复（评测集 id 唯一性破坏，报告混淆）。

**修复**：append 自动计算现有文件 `bad_N` 最大值+1。

## P1-3（二轮 review）：regression 断言过严（真库抽样暴露）

**问题链**：抽样 6/8——① 超范围层 `contains "无法"` 不保证（模型拒答措辞自由：可能"能力范围是…"无"无法"）；② traceId 用例误报（见 P0-1）。

**修复**：超范围断言改为 `statusIn [SUCCEEDED] + notContains [证据链]`（零证据=拒答语义，与问候层一致）；检查器修复后 traceId 用例保留 numbersConsistent（验证修复）。

**验证**：修复后抽样冒烟 **8/8 通过**（SampledRegressionTest，真库 eval-gate tag——兼作 regression 集质量快速反馈）。

## 实现核对（对照 design）

| 设计点 | 落地 |
|--------|------|
| 评测集 100+（smoke 20 + regression 80）| ✓ 分层（biz/obs/decline/security/edge）；id 唯一；断言经真库抽样验证 |
| EvalCaseLoader 多文件 | ✓ load(cp...) 合并；badcases 缺失容忍 |
| EvalJudge（LLM-as-judge 0-5）| ✓ 主观维度；不含证据一致性（修正 1）；答案截断 2000（修正 2）；enabled 开关默认关；正则解析（P1-1 修复）|
| BadCaseCollector 回流 | ✓ 仅质量失败（排除 FAILED/预期 PARTIAL——修正 3）；id 自动递增（P1-2 修复）|
| EvalRunner 集成 | ✓ judgeScore 进每 case + summary.avgJudgeScore；badcase 收集可插拔 |
| 阈值校准 | ✓ 实测分布文档化（修复后 pass 57-100/幻觉 0-14.3，10/60/40 保留）|

## 注意点（记录在案）

1. **P2：judge 无 Spring 装配**（配置项 myxhs.ai.eval.judge.enabled 定义了但无 bean 载体）——nightly 集成需补装配
2. **P2：BadCaseCollector 默认输出路径为 cwd 相对**（开发/CI 工作；jar 部署需配置路径）
3. **multi-turn/memory/hitl 层未进评测集**（design 明确不做）：fake 集成测试确定性覆盖
4. **回归集 80 条全量真库未跑**（~2.5h）：抽样 8 条验证断言策略；全量走 nightly
5. **judge 默认关闭**（成本）；nightly 开启后 avgJudgeScore 进报告

## 方法论复盘

- **抽样验证 > 静态断言**：regression 断言"宽松稳定"是设计声称——真库抽样立即暴露 2 个真实缺陷（检查器 traceId 误报 + 断言过严）——**评测集本身必须被评测**
- **SampledRegressionTest 是"评测的评测"**：8 条代表性用例快速反馈断言策略，比全量 2.5h 高效
- **回归输入要覆盖特殊形态**：traceId（32hex）是日志/链路场景的独特输入形态——通用检查器（数字一致性）必须认知它，否则场景功能与评测互相打架

## 结论

- M14 完成（含二轮 review P0/P1 修复）：评测集 100 条 + judge + badcase 闭环 + 阈值校准 + 抽样冒烟 8/8
- 226 测试全绿（app 165 + tools 50 + mcp 11）
- **M10-M14 规划全部完成**——roadmap 收口

---

## 附：M10-M14 总览（里程碑验收对照）

| 里程碑 | 交付 | 验收 |
|--------|------|------|
| M10 会话记忆 | 多轮上下文/摘要/409 | 200 测试 + 真库 E2E + 深度 review（2 P0 修复）|
| M12 注册表 | 单一事实源/驱动/MCP 导出 | 209 测试 + review（1 P0 规则漂移修复）|
| M11 HITL | 挂起/审批/resume/审计 | 216 测试 + 二轮 review（2 P1 修复）|
| M13 多 Agent | 双 Agent 分流 + 对比评测 | 225 测试 + 对比胜出 + 二轮 review（P0/P1 修复）|
| M14 评测闭环 | 100 条/judge/badcase/校准 | 226 测试 + 抽样冒烟 8/8 + 二轮 review（P0/P1 修复）|
