# my-xhs-cart 模块总览

## 1. 当前模块定位

`my-xhs-cart` 是购物车服务，采用 Redis 作为在线购物车权威状态，RocketMQ 异步持久化到 MySQL，Product Feign 补充商品信息，定时对账任务修复 Redis/MySQL 差异。

## 2. 核心数据结构

```text
items:{userId}  Hash  skuId -> quantity
checked:{userId} Set   已选中的 skuId
sort:{userId}    ZSet  skuId -> 加购时间
```

三类 key 使用 `{userId}` hash tag，意图是在 Redis Cluster 下保证 Lua 多 key 操作落在同一 slot。当前实际部署使用 Sentinel/单机配置的关系仍需运行态确认。

## 3. 业务位置

```text
客户端
  -> Gateway
  -> CartController
  -> CartService
       -> Redis Lua / Pipeline
       -> Product Feign
       -> RocketMQ CART_TOPIC
            -> CartSyncConsumer -> MySQL t_cart_item
            -> CartEventSinkConsumer -> t_cart_event
       -> CartReconcileJob
```

## 4. 主要业务能力

- 加购、改数量、删除、单选、全选/取消全选
- 匿名/登录购物车合并
- 购物车列表聚合商品信息和有效性
- 清空购物车
- Redis 丢失时从 MySQL 恢复
- Redis/MySQL 定时对账

## 5. 当前关键事实

- Lua 保证单次购物车操作的 Redis 三结构原子性
- Redis 与 MySQL 不是同步事务，MQ 是异步落库通道
- Product Feign 有降级实现，但降级是否应该允许写入幽灵 SKU 是高风险契约问题
- 清空三 key 与清空标记不是同一个 Lua 原子操作
- 对账、恢复、MQ 事件依赖时间戳和状态字段，跨实例顺序与并发需要重点审查
- 本模块不包含 AI；鉴权只做基础 Header/内部调用检查
