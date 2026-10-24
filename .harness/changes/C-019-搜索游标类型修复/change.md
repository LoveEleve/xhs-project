---
id: C-019
slug: 搜索游标类型修复
status: done
created: 2026-10-23
updated: 2026-10-23
relations:
  relates_to:
    - slug: docs/学习/搜索-讲解/04-深分页与相关性
      reason: Search After 游标类型歧义修复
---

# C-019 搜索游标类型修复

## 问题

`AbstractSearchService.parseSearchAfter` 用 `doubleValue() != longValue()` 判断类型：
`1.0`/`100.0` 这种整数值 double 被判成 long；`_score`/`price`（double）排序的
Search After 游标类型不匹配，第二页可能异常/跳页。

## 修复

- 游标序列化改为**类型标签**：`{"t":"d","v":1.0}` / `{"t":"l","v":10086}` / `s/b/n`；
- 解析优先读标签；裸数字保留旧格式启发式（历史游标兼容）；
- 新增 `SearchAfterCodecTest`：整数值 double、long/string/boolean/null、旧格式。

## 验证

- search 69 全绿（原 66 + 3）；
- 全仓回归：见提交记录。
