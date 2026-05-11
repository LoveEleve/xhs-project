# 评论系统

> 所属服务：my-xhs-note (9002) | 开发阶段：Phase-1 | 状态：⏳ 待开发

## 功能概要

- 楼中楼评论（parent_id + root_id 设计）
- 评论双排序（时间序 + 热度序）
- DFA 敏感词过滤（Trie 树 + 10万词库毫秒匹配）
- RocketMQ 顺序消息保证评论顺序
- 评论举报（9种举报类型 + 处理结果满意度反馈）
- 评论计数与笔记计数联动

## 涉及数据库表

- `t_comment` — 评论表（5亿级）
- `t_comment_report` — 评论举报表（1000万级）

## 关键技术点

- 双 ZSet 方案：`note:comment:time:{noteId}` + `note:comment:hot:{noteId}`
- **DFA 敏感词算法**：Trie 树构建1次查1遍 O(n)，比 SQL LIKE 10万次查询快100倍
  - 热更新：Redis Pub/Sub 通知刷新 Trie 树
- RocketMQ 顺序消息：同一笔记的评论按时间有序
- 评论举报：举报入库 → 审核队列 → 处理结果 → 满意度反馈
- 评论删除的级联处理

## 面试高频问题

- 楼中楼评论怎么设计的？parent_id 和 root_id 分别有什么作用？
- 评论的时间排序和热度排序怎么实现？
- 敏感词过滤为什么用 DFA 而不是 SQL LIKE？
- 评论数量很大时如何做分页？
- 评论举报流程怎么设计的？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
