# Canal 数据同步

> 跨服务专题 | 开发阶段：Phase-5 | 状态：⏳ 待开发

## 专题概要

本专题聚焦 Canal 监听 MySQL Binlog 实现数据同步的完整实践，涉及商品→ES、笔记→ES、订单→ES 等多路同步场景。

## 涉及服务

- my-xhs-product（商品变更 → ES 商品索引）
- my-xhs-note（笔记变更 → ES 笔记索引）
- my-xhs-order（订单变更 → ES 订单索引）
- my-xhs-search（消费 Canal 消息更新索引）

## 核心内容

| 维度 | 内容 |
|------|------|
| Canal 部署 | Docker 单节点，监听 MySQL 主库 Binlog |
| 同步链路 | Canal → RocketMQ → 消费写入 ES |
| 增量同步 | 实时 Binlog 变更 → 准实时同步 |
| 全量重建 | XXL-Job 每周日凌晨全量重建 ES 索引 |
| 同步校验 | XXL-Job 每天对账 MySQL 和 ES 数据量 |
| 失败处理 | 同步失败 → 重试3次 → 死信队列 → 告警 |

## 重点覆盖

- Canal Server/Client 配置详解
- Binlog 格式（ROW 格式要求）
- Canal → MQ 消息格式解析
- ES 增量写入（Index/Update/Delete 三种操作）
- 全量重建断点续传
- 同步延迟监控告警

## 面试高频问题

- 数据库和 ES 怎么保持同步？
- Canal 同步延迟怎么处理？
- 增量同步可能丢数据吗？怎么兜底？
- 全量重建索引怎么做到不影响线上查询？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
