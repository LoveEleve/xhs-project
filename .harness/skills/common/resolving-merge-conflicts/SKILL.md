---
name: resolving-merge-conflicts
description: 解决进行中的 git merge/rebase 冲突。当需要解决未完成的合并/变基冲突时使用。
---

# Resolving Merge Conflicts — 解决合并冲突

1. **看当前状态**：检查 merge/rebase 状态、git 历史、冲突文件。

2. **为每个冲突找一手来源**：深入理解每个改动为什么发生、原始意图是什么。读 commit message、查 PR、查原始 issue/ticket。

3. **逐个解决每个 hunk**：尽可能保留双方意图。不兼容时，选择符合 merge 目标的一方并记录权衡。**不要发明新行为**。始终解决，绝不 `--abort`。

4. **发现项目的自动化检查并运行**：通常是先 typecheck、再 tests、再 format。修复 merge 破坏的任何东西。

5. **完成 merge/rebase**：stage 所有内容并 commit。若是 rebase，继续 rebase 直到所有 commit 都被 rebase。

---

## 核心原则

- **保留双方意图**：解决冲突时优先同时满足两边
- **不发明新行为**：冲突解决不引入需求外逻辑
- **绝不一跑了之**：不 `--abort`，除非人类明确要求
- **修复合并副产物**：merge 后必须跑自动化检查

## 完成标志

- 所有冲突 hunk 已解决
- 变更已 stage 并 commit（或 rebase 完成）
- 自动化检查通过
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
