---
iteration: <ITER-ID>
status: draft            # draft / confirmed
version: 1
created: <date>
updated: <date>
# 阶段间接口：每条 TC 归属 prd.md 中一条 AC；上游 AC 变更时本文件必须同步
---

# 测试用例: <需求标题>

> 机器可读的迭代协议产物之一。TC 编号归属 AC，供 test/report.md 与 harness-quality 的 flow→test 对账使用。

## 用例清单

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | <…> | <…> | <…> | 是 |
| TC-2 | AC-1 | <…> | <…> | <…> | 是 |
| TC-3 | AC-2 | <…> | <…> | <…> | 否（手工） |

## 边界与降级用例

| TC | 归属 AC | 场景 | 期望 |
|----|---------|------|------|
| TC-N | AC-x | <边界/降级路径> | <…> |

## 对账检查

> 提交测试前自检：每条 AC 至少 1 个 TC；每个 TC 归属已确认的 AC；无孤立 TC。
