# my-xhs-product 测试重点

## 测试范围

本目录只记录 product 后续测试依据，不替代源码分析；AI 不属于本模块范围。

## L1 业务

- SPU 创建、详情、列表、更新、上下架
- SKU 创建、详情、批量查询
- 类目树查询
- 商品浏览行为

## L2 数据

- MySQL：SPU/SKU/类目/行为表、状态字段、逻辑删除、索引
- Redis：Bloom Filter、SPU 详情缓存、空值缓存、逻辑过期、类目缓存、加载锁
- MQ/Canal/ES：SPU/SKU 增量索引同步和失败补偿
- Feign：Order/Cart/Search 获取商品详情的字段契约

## L3 质量

- 相同幂等键的合法商品创建是否被误拦
- 下架 SPU/SKU 的单查和批量查询边界
- 并发更新、上下架、SKU 创建
- 缓存击穿、Redis 故障、刷新失败和主从延迟
- 类目环路、非法深度、空树和动态数据
- 商品索引漏更新、重复投递、消费重试
- 批量/深分页、相关 SQL 和大文件/行为事件压力

## L4 可观测性

- 商品缓存命中/回源/刷新失败指标
- 浏览事件丢弃率和队列深度
- Canal/MQ/ES 同步、补偿、死信日志
- TraceId 跨 product、Feign、MQ、Search
- 配置和日志中的凭据、用户信息、商品内容脱敏

## 当前状态

- product 静态分析已完成，候选文件 30/30 已读取；逐文件状态见 `../business-analysis/my-xhs-product/03-file-review-matrix.md`
- product 测试已真正执行：14 个测试全部通过，但只证明当前 Mockito 主路径，不代表真实中间件链路通过
- 本轮已修复 Bloom 空指针、SKU 状态边界、类目环路和 SPU 更新影响行数问题
- product 已重新打包并重启，19006 健康检查 `UP`
- 测试仍未覆盖 Feed/Canal/ES、真实 Redis/MySQL 和并发边界
- 业务测试应等待核心模块分析完成后统一执行
