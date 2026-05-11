# 搜索与搜索建议

> 所属服务：my-xhs-search (9007) | 开发阶段：Phase-3 | 状态：⏳ 待开发

## 功能概要

- 笔记全文搜索（标题+内容）
- 搜索建议（自动补全）
- 搜索结果排序（相关度/时间/热度）
- 搜索历史记录

## 涉及数据库表

- 无（使用 Elasticsearch）

## 关键技术点

- **Elasticsearch 索引设计**：IK 分词器、拼音分词器
- Canal 监听 MySQL Binlog → 同步到 ES
- Completion Suggester 实现搜索建议
- 深分页优化：Search After 替代 from+size
- 搜索历史：Redis List `search:history:{userId}`

## 面试高频问题

- 搜索数据怎么从 MySQL 同步到 ES？
- ES 深分页有什么问题？怎么优化？
- 搜索建议怎么实现的？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
