---
id: C-NNN
slug: <slug>
status: draft            # draft / done
version: 1
created: <date>
updated: <date>
# 归档：change 进入 done 后执行，把确认后的长期知识回流 wiki/tech（知识飞轮）
---

# 归档: <需求标题>

> 变更归档产物。变更收口后，把本次确认的**长期知识**回流知识库，
> 让知识建设成为产品迭代的副产物（见 .harness/rules/知识库治理规则.md）。

## 本次交付清单

- [ ] 代码已提交，commit message 含 change ID（C-NNN）
- [ ] 测试证据齐备（change.md 走单文件协议；若走 iterations 协议则为 test/report.md）
- [ ] 发布后工作项与协同状态回查一致（见 harness-ship）

## 知识回流清单（知识飞轮）

> 逐项判断：本次迭代确认了哪些「代码之外、影响业务/技术判断」的信息？回流到对应分层。

| 类型 | 内容 | 回流到 | 是否已更新 |
|------|------|--------|-----------|
| 业务规则/口径 | <…> | `.harness/wiki/` | ☐ |
| 跨系统链路/观测方式 | <…> | `.harness/tech/` | ☐ |
| 架构决策（难逆转/权衡） | <…> | `.harness/wiki/架构决策.md`（ADR） | ☐ |
| 术语/别名 | <…> | `.harness/CONTEXT.md` | ☐ |

## 未回流项（明确不沉淀的）

- <…，及理由：如一次性实现细节、已过时>

## 结论

✅ 归档完成，长期知识已回流 / ☐ 待补齐回流
