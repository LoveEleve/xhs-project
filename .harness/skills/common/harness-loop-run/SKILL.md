---
name: harness-loop-run
description: 自主收敛循环引擎——把机械收敛段（coding → testing → reviewing）烧成自主循环，从当前状态出发自动推进，直到评审通过或被 blocked。适合无人值守地把一个 change 推到可评审状态。
---

# Harness Loop-Run — 自主收敛循环引擎

把机械收敛段 `coding → testing → reviewing` 烧成一个自主循环：从 change 的当前 `status` 出发逐阶段自动推进，直到 `reviewing` 通过（状态置为 `ci`）或被 `blocked`。

> ⛔ **终态即 `ci`（设计如此）**：本引擎**只覆盖机械收敛段**。到达 `ci` 即**成功停机**，交人工决定是否进入 `/unit-test-ci` → `/deploy-verify`。**绝不**自动进入 CI / 部署 / 发布——这些是不可逆·外发操作，永远留人。PRD/设计的关键取舍同样不在本引擎范围内（归 `harnessing` / 人工）。

---

## 1. 输入

从 `$ARGUMENTS` 取 change `<id>`；可选 `--max-steps`（迭代上限，缺省 **4**）。

- 未指定 `<id>` → 扫描 `.harness/changes/*/change.md`，取 `status` 在 `[coding, testing, reviewing]` 中的 change；恰好 1 个自动选中；0 个报错停机；≥2 个停下列出候选请人类选择。
- 指定 `<id>` → 校验状态在 `[coding, testing, reviewing]`，否则报错停机。

## 2. 循环算法

```
当前 status = change.md frontmatter 的 status 字段
steps = 0
loop:
  if status == ci:           → 成功停机 <<<HARNESS done stage=ci>>>
  if status not in 收敛段:    → blocked 停机（见 §5）
  next_skill = 映射表[status]
  steps += 1
  if steps > max_steps:      → 预算耗尽，停机交还人类 <<<HARNESS budget-exhausted>>>
  # 派发 fresh subagent 执行该技能（带 --autonomous），执行后技能会更新 status
  派发 next_skill → fresh subagent
  重读 change.md 的 status
  if status 未前进（stuck）:  → stuck-stop，停机交还人类（见 §5）
```

### 阶段映射表

| 当前 status | 派发技能 | 成功后新 status |
|-------------|---------|----------------|
| `coding` | `/coding-skill <id>` | `testing` |
| `testing` | `/unit-test-write <id>` | `reviewing` |
| `reviewing` | `/expert-reviewer <id>` | `ci`（0 严重问题） |

## 3. 派发纪律

- **每个 stage 派发给一个 fresh subagent**：上下文相对隔离，防止长循环中上下文污染累积。
- 派发时带上完整上下文指针：`.harness/changes/<id>/change.md` + 相关 `.harness/wiki/*` + `.harness/rules/`，让 subagent 自行加载，不把内容塞进循环主进程。
- 每个 subagent 任务描述用 `<技能名> <id>`，并声明 `--autonomous`（不暂停、不询问、只按技能流程执行到出口门禁）。
- subagent 返回后**重读 change.md 的 status**，而不是信任其口头汇报——状态字段才是真相源（`git diff` 验证 status 行确实变了）。

## 4. 停机信号（机器可读）

循环引擎每次迭代结束输出一行机器可读信号，供外层 bash 循环 / CI 解析：

```
<<<HARNESS next=<coding|testing|reviewing> step=<n>/<max_steps> status=<当前status>>>>
<<<HARNESS done stage=ci>>>                          # 成功停机：评审通过，交人工 /unit-test-ci
<<<HARNESS blocked reason="<原因>">>>                # 被阻塞：需要人类决策
<<<HARNESS stuck  stage=<status> attempt=<n>>>       # 同阶段无进展，stuck-stop
<<<HARNESS budget-exhausted steps=<n> max=<max_steps>>>  # 预算耗尽
```

## 5. blocked / stuck 判定

- **blocked**：subagent 返回"缺前置/规格不明确/需人类审批/发现需人类决策的架构问题"等无法由引擎自行消解的信号 → 立即停机交还人类，输出 `reason`。
- **stuck**：同一 `status` 连续 **2** 次迭代未前进（技能正常执行完但 status 未变，或测试一直红）→ 停机交还人类，输出 `stage` 与 `attempt`。
- 打回循环：`reviewing` 打回 `coding` 时 status 会回退——这是**正常的收敛回路**，不算 stuck；但同一打回回路累计 > 2 次仍未到 `ci` → 停机交人类判断是否需求/设计本身有问题。

## 6. 与其余技能的边界

| 场景 | 归属 |
|------|------|
| 需求/设计关键取舍 | `harnessing` + 人工，**不在本引擎** |
| CI 门禁 / 部署 / 发布 | `/unit-test-ci` `/deploy-verify` `/harness-ship`，**留人**，不在本引擎 |
| 状态总览 | `/harness-status`（只读） |
| 变更间影响 | `/harness-relate impact <id>` |

## 完成标志

- 成功停机：`reviewing` 通过，`status: ci`，已输出 `<<<HARNESS done stage=ci>>>` 并提示交人工
- 或被 blocked / stuck / 预算耗尽停机，已输出对应机器可读信号与人类可读原因
- 全程未触碰 `ci → verifying → done` 及发布链路
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
