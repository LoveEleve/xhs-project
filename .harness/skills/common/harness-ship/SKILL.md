---
name: harness-ship
description: 发布工作流——跑测试 → bump VERSION → 更新 CHANGELOG → 打 tag → 触发 CI/CD，全程可追溯、不可逆操作留人确认。当需要"正式发布一版"时使用。
---

# Harness Ship — 发布工作流

把已完成验证的 change 汇总为一次**正式发布**：测试全绿 → 版本号递增 → 更新 CHANGELOG → 打 git tag → 触发 CI/CD 发布流水线。

> ⚠️ **发布是不可逆·外发操作，永远留人确认**。本技能每一步都产出可追溯证据，关键节点（版本号确定、tag 推送、生产触发）必须人类确认后才执行。

---

## 1. 前置检查（全部满足才继续）

- [ ] 待发布 change 全部 `status: done`（`/harness-status` 确认）
- [ ] `/unit-test-ci` 门禁全绿（或该次发布验证已通过）
- [ ] `/harness-quality` 报告已落盘且有**人类放行签字**
- [ ] CHANGELOG 已汇总本次变更（可用 `/harness-changelog`）
- [ ] 数据库迁移（若有）已按 `database-migration-toolkit` 执行完毕且可回滚

任一不满足 → 停止，列出缺口清单交人类。

## 2. 执行流程

### Step 1: 版本号决策（留人）
按项目约定（`编码规范.md` / CHANGELOG 头部）确定本次版本号：
- 建议用 **SemVer**（主.次.补丁），结合 break 变更/新功能/修复判定
- 将候选版本号 + 变更摘要呈现给人类，**确认后才继续**

### Step 2: bump VERSION（机器执行）
- 更新 `VERSION` / `package.json version` / `pom.xml <version>` 等版本文件（按语言包约定）
- 提交：`chore(C-NNN): bump version to <ver>`

### Step 3: 更新 CHANGELOG
- 追加新版本条目（链接到对应 change 的 review.md/verify.md）
- 格式遵循 `/harness-changelog` 的规范

### Step 4: 打 tag（留人确认推送）
- 本地打 annotated tag：`git tag -a v<ver> -m "release v<ver>: <摘要>"`
- 呈现 tag 内容给人类确认，**确认后** `git push origin v<ver>`

### Step 5: 触发 CI/CD（留人确认）
- 调用发布流水线/发布命令（语言包 `deploy-verify` 技能中已渲染的发布入口）
- 记录触发回执（job URL / 命令退出码）
- 不自动推进生产环境放量——生产放量比例/时间由人类决定

### Step 6: 验证与收尾
- 触发后按 `/deploy-verify` 做冒烟/健康检查
- 在 `.harness/changes/<id>/verify.md` 记录发布结果（版本号、时间、回滚点）
- 更新 `TECH-DEBT.md`：本次发布引入/偿还的债

### Step 7: 发布后回查（代码合入 ≠ 完成）
代码合入主干只是其中一步，之后必须**回查工作项与协同状态**，三者一致才算迭代结束：

| 回查项 | 依据 | 来源（见 .harness/动态事实来源.md） |
|--------|------|-------------------------------------|
| 代码版本 | tag / 构建产物已上线 | git / CI/CD 回执 |
| 业务状态 | 工作项已流转为「已上线/已验证」 | 工作项平台（MCP/CLI） |
| 交付证据 | verify.md 冒烟 + 健康检查 + 回滚点齐全 | `.harness/changes/<id>/verify.md` |

> 接口成功 ≠ 异步链路完成。发现工作项状态滞后/协同未闭环 → 退回 `verifying` 补齐，不擅自标 `done`。

## 3. 不可逆操作清单（每题必须人类 yes/no）

| 操作 | 确认点 |
|------|--------|
| 版本号确定 | 候选版本呈现给人类 |
| tag 推送 | `git push origin v<ver>` 前确认 |
| 生产发布触发 | 调用发布入口前确认 |
| 生产放量 | 比例/时间由人类决策 |

## 4. 回滚预案

- 每次发布记录**回滚点**：上一个 tag + 数据库回滚脚本
- 发布失败/线上异常 → 按预案回滚到上一 tag，记录到 verify.md，不掩盖
- 回滚后回到 `verifying` 状态，定位根因再重发（`diagnosing-bugs`）

## 5. 与相邻技能

| 场景 | 归属 |
|------|------|
| 发布流程 | **本技能**（`/harness-ship`） |
| 发布后冒烟/健康检查 | `/deploy-verify` |
| CHANGELOG 汇总 | `/harness-changelog` |
| 质量放行签字 | `/harness-quality` |
| 回滚/线上异常定位 | `diagnosing-bugs` |

## 完成标志

- 版本文件已 bump、CHANGELOG 已更新、tag 已推送（留人确认后）
- CI/CD 已触发并记录回执，发布后冒烟通过
- verify.md 已记录版本号/时间/回滚点
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
