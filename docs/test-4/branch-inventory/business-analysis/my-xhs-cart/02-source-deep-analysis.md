# my-xhs-cart 源码深度分析

## 1. 操作流转

```text
HTTP
  -> CartController
  -> CartService
       -> Product Feign（需要商品存在/状态）
       -> Redis Lua / Pipeline
       -> RocketMQ 事件
            -> CartSyncConsumer -> MySQL
            -> CartEventSinkConsumer -> CartEvent
  -> CartReconcileJob 对账
```

## 2. Redis 三结构与 Lua

当前配置明确包含 Sentinel/直连 Redis 字段，代码中的 `{userId}` hash tag 是为 Cluster 多 key Lua 兼容保留的设计；不能仅凭 hash tag 断言当前运行在 Cluster，实际拓扑仍以部署配置和运行态为准。


`items` 保存数量，`checked` 保存勾选，`sort` 保存排序时间。所有涉及多个 key 的单项操作使用 Lua，避免 Hash/Set/ZSet 之间出现中间状态。

### 加购

Lua 先判断商品是否存在，再检查品种数上限，已有商品累加并截断数量，新商品写入 Hash、checked 和 sort。Java 通过返回值区分新商品与已有商品，再决定发送 ADD 或 UPDATE 事件。

### 改数量

脚本先 `HEXISTS`，不存在直接返回失败，避免删除与改数量并发时商品被复活；存在时才设置数量。

### 删除/单选

删除脚本同步清理 Hash、Set、ZSet；单选脚本先确认 Hash 中存在，再执行 SADD/SREM，避免勾选集合出现幽灵 SKU。

### 全选/合并

全选脚本遍历 Hash field 重建 checked Set，取消全选直接删除 Set。合并按 SKU 逐项执行 Product 校验，但 Product 列表校验走批量 Feign；每个 SKU 仍独立执行一次 Lua 和一次持久化事件，因此 50 项合并会产生一次批量商品查询、约 50 次 Redis 脚本和多条 MQ 事件。

## 3. 列表与恢复

列表通过 Pipeline 一次取得三类 Redis 数据，再一次调用 Product 批量接口获取 SKU 信息；失效商品仍返回但标记 `valid=false`，金额只统计有效且勾选项。合并场景则按 SKU 逐项执行 Lua 和事件发送，不能与列表的批量 Feign 混为一谈。

Redis key 缺失时，Service 查询 MySQL 并回写三类 Redis 结构。清空 marker 用于区分“用户主动清空”和“Redis 故障/丢失”；marker 只有有限 TTL，与 MySQL 长期数据生命周期不一致，过期后可能恢复历史购物车。

## 4. 清空与并发

当前清空使用普通多 key delete，再写 cleared marker，最后发送 CLEAR 事件，不是一个 Lua 原子操作。并发加购可能与删除交错，marker 也可能覆盖新状态。清空应使用单 Lua 同时删除三结构、写 marker、设置版本/TTL，或定义清晰的用户级屏障。

## 5. MQ 持久化与消费者

Redis 是在线权威状态，MQ 异步同步 MySQL。生产事件携带时间戳，消费者先查询 MySQL 再决定 INSERT/UPDATE/DELETE，存在 TOCTOU：两个消费者可以基于同一旧状态判断。时间戳只在单 JVM 递增或依赖系统时间，跨实例可能乱序/倒序。

`CartEventSinkConsumer` 先写幂等标记再落库；进程若在两步之间崩溃，重试可能被标记为重复。数据库唯一消息 ID 能部分兜底，但两者没有同一事务。

## 6. 对账任务

定时 `reconcile()` 通过 Redis lock 尝试保证只有一个全量任务运行，但实现注释称 XXL-Job 已只调度一个 Executor、无需分布式锁，与实际加锁代码矛盾。当前锁使用固定值、无条件 DEL；锁 TTL 为 600 秒且没有续租，长任务可能自然失锁，旧实例 finally 还可能删除新实例的锁。管理端点直接调用 `reconcileUser()`，不经过该全量锁，因此手动单用户对账仍可与定时全量对账并发。应使用随机 token + compare-and-delete Lua，并对长任务续租。

任务读取 Redis 和 MySQL不是快照，也没有用户写锁；用户操作可能发生在读取和修复之间，旧读数可能覆盖新状态。Redis-only 用户扫描与 MySQL 用户遍历还可能重复处理。

## 7. Product Feign

加购前单 SKU 校验，列表批量查询。Fallback 对明确的失败响应会返回 `false`，因此通常会拒绝加购；但 fallback 抛异常或调用异常进入 catch 时，当前代码可能返回 `true`，仍可能在 Product 不可用期间写入幽灵 SKU。两条降级路径语义不一致，必须统一为“依赖不可用不放行、明确不存在也不放行”。

## 8. 分类问题

以下问题均基于源码静态确认；真实 Redis、MySQL、RocketMQ 和多实例并发结果仍需运行验证。

### 代码 Bug/逻辑
- 清空三结构和 marker 非原子
- 时间戳判断依赖先查后写，存在 TOCTOU
- 锁无 token，可能误删其他实例的新锁
- null/缺失 `updatedAt` 的 MySQL 行可能无法被条件更新/删除
- Product Feign 异常 fallback 放行幽灵 SKU
- Redis 恢复与清空 marker 生命周期不一致

### 分布式
- 事件时间戳跨实例不提供全序
- Redis 三结构和 MySQL/MQ 状态非原子
- 对账任务读取/修复没有用户级并发屏障
- sink 幂等标记与 DB 写入非事务绑定
- MQ 乱序时旧事件可能覆盖新状态

### 微服务
- cart 依赖 product 的可用性和字段契约
- Product 单查/批量状态语义需和 cart 统一
- Product fallback 对明确失败返回 false，但异常路径可能返回 true，依赖故障处理语义不统一
- 内部批量接口的 token 和直连暴露需由 Gateway/部署保护

### 性能
- 合并逐 SKU 串行 Feign/Lua/MQ
- 全选 Lua 为 O(N)，会占 Redis 主线程
- 列表 Pipeline 后仍有商品信息聚合成本
- 对账可能重复扫描和重复修复
- Redis 故障时恢复路径会打 MySQL

### 工程/可观测性
- Dockerfile 依赖外部基础镜像契约
- 配置包含明文密码、JWT、XXL-Job token
- 核心 Redis/MQ/对账差异没有统一指标
- 事件延迟、丢弃、重试、DLQ 缺少完整监控
- Lua 真实语义尚未通过集成测试验证

## 9. 运行态待确认

- 实际 Redis 是 Sentinel 还是 Cluster，hash tag 是否仍为必要设计
- CART topic/tag 与两个消费者订阅是否完全匹配
- t_cart_item 时间字段和唯一键约束
- Product Feign 失败时的真实 fallback 返回
- CartEventSink 幂等组件的 Redis/TTL 语义
- 对账锁超时、续租和多实例行为
- Redis 丢失恢复与 cleared marker 的真实业务结果
