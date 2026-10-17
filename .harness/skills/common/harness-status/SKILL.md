---
name: harness-status
description: 项目变更状态总览——扫描 .harness/changes/*/change.md，输出全项目 PDLC 进度、阶段分布与待办建议。当你想知道"现在做到哪了 / 还有哪些变更卡着"时使用。
---

# Harness Status — 项目变更状态总览

读取 `.harness/changes/` 下所有变更卡，输出项目当前的开发进度总览：哪些 change 在进行中、哪些已完成、哪些异常、下一步该做什么。

> **定位**: 这是**只读**的聚合总览技能，不修改任何 change 状态。状态迁移由流水线技能（coding-skill / unit-test-write / expert-reviewer / unit-test-ci / deploy-verify）负责。

---

## 1. 扫描变更卡

1. 列出 `.harness/changes/*/change.md` 所有文件（跳过 `_relations.json`、`_graph.md`、`TECH-DEBT.md` 等非变更卡文件）。
2. 若无任何 change.md → 输出：`📭 尚无变更记录。运行 /harnessing 创建第一个需求变更。`
3. 逐个解析 frontmatter 中的 `status:` 字段与 `slug:`。

## 2. 解析与分类

按 `status` 字段分组：

| 分组 | status 值 |
|------|----------|
| 🚧 进行中 | `analyzing` / `coding` / `testing` / `reviewing` / `ci` / `verifying` |
| ⏸ 草稿 | `draft` |
| ✅ 已完成 | `done` |
| ❌ 异常 | 无法解析 JSON/frontmatter，或 status 值未知 |

## 3. 输出概览

输出以下三块内容：

### 3.1 总览表

```
📊 变更总览：N 个（进行中 X · 草稿 Y · 已完成 Z · 异常 W）
```

按 id 升序列出每个 change：`C-001 <slug> — <status 徽标> <标题首句>`

### 3.2 阶段分布

```
阶段分布：
🚧 analyzing  ██▁▁▁▁▁▁ 2
🚧 coding     ▁▁▁▁▁▁▁▁ 0
...
✅ done       ▁▁▁▁▁▁▁▁ 1
```

用条形图直观展示各阶段 change 数量（条形长度 ∝ 数量）。

### 3.3 待办建议（每条一行，机器可读）

按当前阶段输出下一步建议：

| 当前 status | 下一步 | 建议命令 |
|-------------|--------|---------|
| `draft` | 需求未启动 | `/harnessing` 细化或删除 |
| `analyzing` | 需求卡待审批 | 提示人类审批后置为 `coding` |
| `coding` | 编码实现 | `/coding-skill <id>` |
| `testing` | 单测编写 | `/unit-test-write <id>` |
| `reviewing` | 专家评审 | `/expert-reviewer <id>` |
| `ci` | CI 门禁 | `/unit-test-ci <id>` |
| `verifying` | 部署验证 | `/deploy-verify <id>` |

对每个进行中的 change 输出：
```
<id> → <下一步建议命令>  (<标题>)
```

## 4. 异常处理

- 某 change.md 无法解析 → 列入 ❌ 异常，并输出文件路径与解析错误（不要静默跳过）
- status 值未知（不在上述列表）→ 列入 ❌ 异常，标注"未知状态: <值>"
- 无进行中 change 且存在已 done change → 提示："当前无进行中变更，可启动新需求或 /harness-retro 复盘"

## 完成标志

- 已输出总览表 + 阶段分布 + 待办建议
- 异常 change 已显式列出（若有）
- 未修改任何 change 文件
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
