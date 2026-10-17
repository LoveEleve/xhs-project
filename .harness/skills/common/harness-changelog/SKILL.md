---
name: harness-changelog
description: 变更日志——按语义化版本把已 done 的 change 汇总进 CHANGELOG，条目链接到 review/verify 留档。当需要"为这次发布整理变更记录"时使用。
---

# Harness Changelog — 变更日志维护

把 `status: done` 的 change 汇总进 `CHANGELOG.md`：按语义化版本归类、按类型分组、条目链接到对应的 review.md/verify.md 留档。

> **定位**: 发布的前置整理（`/harness-ship` 的 Step 3 专用），也可独立运行。核心价值：**每个条目都能追溯到原始变更卡**，不写模糊的"若干优化"。

---

## 1. 前置

1. 用 `/harness-status` 列出 `status: done` 的 change。
2. 读取 `CHANGELOG.md` 现有头部（确认当前版本与格式约定）。
3. 无 CHANGELOG.md → 按以下模板创建（Keep a Changelog 风格）。

## 2. 模板

```markdown
# Changelog

本项目所有值得记录的变更。

## [Unreleased]

## [0.2.0] - <date>
### Added
- <功能>（[C-0NN](.harness/changes/C-0NN/change.md) · [review](.harness/changes/C-0NN/review.md)）

### Changed
- <变更>（[C-0NN](…)）

### Fixed
- <修复>（[C-0NN](…)）

### Removed / Deprecated / Security
- …
```

## 3. 执行流程

### Step 1: 收集
- 从 `git log` 或 change 目录列出本次窗口内 done 的 change。
- 每个 change 读取 `change.md` 标题 + 摘要，决定归类（Added/Changed/Fixed/Removed/Deprecated/Security）。

### Step 2: 归类
| 类型 | 适用 |
|------|------|
| Added | 新功能、新接口、新能力 |
| Changed | 行为变化、接口兼容性调整 |
| Fixed | 缺陷修复 |
| Removed / Deprecated | 移除 / 废弃 |
| Security | 安全修复 |

- 同一 change 可拆多条（一个 change 既有新功能又有修复）。
- **只写行为可见的变更**：内部重构、格式调整、CI 改动等不面向使用者的，不写进面向用户的 CHANGELOG（或归到技术性小节）。

### Step 3: 落盘
- 在 `[Unreleased]` 下暂存新条目；发布时（`/harness-ship` Step 3）再收口为正式版本条目（`[0.2.0] - <date>`）。
- 每条必须带 change ID 链接（指向 `.harness/changes/<id>/change.md`，review/verify 亦可附）。

## 4. 纪律

- **可追溯**：每条含 change ID，禁止无出处条目
- **面向使用者**：写"用户/调用方能感知"的变更，不堆内部噪音
- **不替发布做版本决策**：版本号由 `/harness-ship` 与人类确定，本技能只整理条目
- **不编造**：未完成的 change 不写"已完成"条目；条目内容忠于 change.md 摘要

## 5. 与相邻技能

| 场景 | 归属 |
|------|------|
| 变更条目整理 | **本技能**（`/harness-changelog`） |
| 版本号决策 + tag | `/harness-ship` |
| 未发布 change 清单 | `/harness-status` |

## 完成标志

- 已 done change 全部归入 CHANGELOG 条目（带 ID 链接）
- 条目类型归类正确，无编造、无内部噪音
- `[Unreleased]` 已更新（或本次条目已收口为版本小节）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
