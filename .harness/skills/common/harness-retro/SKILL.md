---
name: harness-retro
description: 迭代复盘——读取 .harness/changes/ 历史与状态留档，聚合出阶段分布、打回率、覆盖率与技术债趋势报告。当需要"这个迭代做得怎么样"时使用。
---

# Harness Retro — 迭代复盘

读取 `.harness/changes/` 的历史留档（change.md 状态变化 / review.md / verify.md / quality-report.md / TECH-DEBT.md），聚合出**迭代质量趋势报告**，回答"这个迭代/阶段我们做得怎么样"。

> **定位**: 只读聚合技能，产出复盘报告，不改任何状态。报告用于：迭代回顾、把趋势问题转化为改进项（可登记为技术债或新 change）。

---

## 1. 何时使用

- 迭代结束/阶段收口时
- 质量门禁反复打回，需要定位瓶颈
- 需要评估技术债与架构熵趋势

## 2. 输入源

| 数据源 | 提取内容 |
|--------|---------|
| `.harness/changes/*/change.md` | 状态流转（id/status/created）、打回痕迹 |
| `.harness/changes/<id>/review.md` | 评审结论、🔴/🟡 问题分布 |
| `.harness/changes/<id>/verify.md` | 部署验证结论、回滚记录 |
| `.harness/changes/<id>/quality-report.md` | 覆盖率、flow→test 映射、放行签字 |
| `.harness/changes/TECH-DEBT.md` | 技术债条目与优先级 |
| `.harness/changes/*/archive.md` | 知识回流清单（本次迭代确认了哪些业务规则→wiki、链路→tech） |
| 手记 / AI Trace 日志（可选） | 对话内补充上下文次数、同一问题纠正次数 |
| git 历史 | 提交节奏、commit message 含 change ID 的比例 |

## 3. 执行流程

### Step 1: 界定时间窗
按 `$ARGUMENTS` 或对话上下文确定复盘范围：`last-iteration`（近 N 个 change）/ 指定 id 区间 / 指定日期段。无参数 → 默认最近 10 个 change。

### Step 2: 聚合指标
对时间窗内的 change 计算：

| 指标 | 计算方式 | 信号 |
|------|---------|------|
| 交付数/打回数 | done 数 vs 被打回次数 | 打回率高 → 需求/设计阶段质量差 |
| 各阶段停留 | 每个 change 在 analyzing/coding/reviewing 停留时间 | 某阶段集中卡住 → 瓶颈 |
| 评审问题分布 | review.md 中 🔴/🟡 问题的类型归类 | 同类问题反复 → 系统性问题 |
| 覆盖率趋势 | quality-report.md 的覆盖率数字 | 下降 → 测试保护在退化 |
| 技术债变化 | TECH-DEBT.md 增删 | 债只增不减 → 熵失控 |
| 提交质量 | commit message 含 change ID 的比例 | 低 → 追溯断裂 |
| 知识回流率 | archive.md 中「是否已更新」勾选比例 | 低 → 知识库与代码漂移 |
| AI Trace 信号 | 对话内补充上下文次数 / 同一问题纠正次数（可选，需 AI Trace 日志） | 高 → 项目 Harness 有待完善 |

### Step 3: 出报告（写 `.harness/retro/<日期>.md`）

```markdown
# 迭代复盘: <时间窗>

## 总览
- 交付 change: X 个（done）/ 进行中: Y
- 打回率: Z%（N 次打回 / M 次评审）
- 平均停留: 各阶段分布 <表或条形图>

## 趋势
<各指标随时间变化的关键观察，附真实数字>

## 问题定位
- 🔴 <严重趋势问题>（证据: <数字/文件>）
- 🟡 <次要问题>
- 🟢 <做得好、值得保持的>

## 改进项（可执行）
| 优先级 | 改进项 | 来源证据 | 登记方式 |
|--------|--------|---------|---------|
| P0 | <…> | <指标> | 新建 change / TECH-DEBT |
| P1 | <…> | <…> | <…> |
```

### Step 4: 转化
- 把可执行的改进项**当场登记**：写入 `TECH-DEBT.md`（按优先级）或建议新建 change。
- 系统性问题（如"每个 change 都在 reviewing 被打回"）→ 建议检查需求阶段是否充分（`harnessing`）或补充设计阶段。
- 知识漂移信号（wiki/tech 出现重复主题、冲突 status、失效链接、无来源结论）→ 建议运行 `/knowledge-health` 做知识健康检查。

## 4. 纪律

- **一切数字来自真实留档**：不编造覆盖率/打回率；留档缺失处如实标注"该阶段无记录"
- **不留空泛结论**：每条结论必须带证据（数字或文件引用）
- **复盘是为了行动**：报告末尾必须有可执行的改进项，不写成"都挺好"

## 5. 与相邻技能

| 场景 | 归属 |
|------|------|
| 当前状态总览 | `/harness-status` |
| 历史趋势复盘 | **本技能**（`/harness-retro`） |
| 知识健康检查 | `/knowledge-health` |
| 改进项落实 | 新建 change（`/harnessing`）或技术债登记 |

## 完成标志

- 报告已落盘 `.harness/retro/<日期>.md`，含总览/趋势/问题定位/改进项
- 数字均来自真实留档，缺失已如实标注
- 改进项已登记（TECH-DEBT.md 或新 change 建议）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
