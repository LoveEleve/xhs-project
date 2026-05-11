# 商品 SPU/SKU

> 所属服务：my-xhs-product (9006) | 开发阶段：Phase-2 | 状态：⏳ 待开发

## 功能概要

- SPU/SKU 商品模型
- 多级分类管理
- 多级缓存：Caffeine(L1) → Redis(L2) → MySQL(L3)
- HotKey 热点探测

## 涉及数据库表

- `t_spu` — 商品SPU表
- `t_sku` — 商品SKU表
- `t_category` — 分类表
- `t_spu_detail` — 商品详情表

## 关键技术点

- **多级缓存架构**：Caffeine 本地缓存(100ms) → Redis 分布式缓存(1ms) → MySQL
- Caffeine 策略对比：maximumSize vs expireAfterWrite vs refreshAfterWrite
- HotKey 探测自动升级为本地缓存
- 商品详情页静态化

## 面试高频问题

- 多级缓存怎么保证一致性？
- 为什么不全部用 Redis，还要加 Caffeine？
- 热点商品怎么处理？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
