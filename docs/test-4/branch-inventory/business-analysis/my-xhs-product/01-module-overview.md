# my-xhs-product 模块总览

## 1. 当前模块定位

`my-xhs-product` 是商品域核心服务，维护 SPU、SKU、类目和商品浏览行为，是购物车、库存、订单和搜索索引的商品事实来源。

核心职责：
- SPU 创建、更新、上下架、详情和列表
- SKU 创建、详情和批量查询
- 三级类目树
- SPU 详情缓存、Bloom Filter、逻辑过期和 DB 回源
- 商品浏览行为异步落库
- 为 cart/order/search 提供商品数据契约

## 2. 业务位置

```text
管理端/客户端
  -> Gateway
  -> product
       -> MySQL: t_spu / t_sku / category / behavior
       -> Redis: Bloom / detail cache / category cache / lock
       -> 下游: cart / inventory / order / search
       -> 外部: Canal -> RocketMQ -> Search ES
```

## 3. 当前重要事实

- SPU 创建当前默认直接上架，不经过人工审核
- SKU 详情、批量查询和 SPU 详情的状态过滤规则不完全一致
- 商品缓存实际是 Bloom Filter -> Redis -> MySQL，不是本地缓存 + Redis 的严格多级缓存
- Product 源码本身没有 Canal Client 或 Elasticsearch Client，索引增量同步属于外部部署/消费者链路
- Canal 配置监听 SKU 变化，但当前消费者会忽略 `t_sku` 事件，SKU 价格/状态变更不能自动刷新商品索引
- 类目树、商品详情和索引同步均存在缓存或跨服务一致性边界
- 商品行为代码存在，但根初始化 SQL 未找到 `t_product_behavior` 建表语句，部署可用性需先补齐

## 4. 分析范围

当前非 `target/` 文件共 30 个：
- 顶层 `Dockerfile`、`pom.xml`：2 个
- `src/main/java`：22 个
- `src/main/resources`：3 个
- `src/test/java`：3 个

AI 不属于本模块范围；鉴权只做管理/内部调用的基础边界检查。
