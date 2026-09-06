# my-xhs-product 源码深度分析

## 1. 事实范围

本报告基于当前工作区源码，不把历史 review 或注释直接当作运行事实。AI 不属于本模块范围；鉴权只做基础边界检查。

Product 是 SPU/SKU/Category 的商品事实源，详情读取会同时影响购物车、库存、订单和搜索索引；因此重点不是 CRUD，而是状态、缓存、索引和跨服务契约的一致性。

## 2. 入口与状态流转

```text
管理/客户端
  -> Gateway
  -> ProductController
       -> 参数校验/基础管理或内部令牌检查
       -> SpuService / SkuService / CategoryService
            -> MySQL
            -> Bloom Filter / Redis / Redisson
            -> ProductBehavior 异步写入
       -> afterCommit 缓存删除
  -> Canal 监听商品表
  -> RocketMQ
  -> Search Consumer
  -> ES product_index
```

### SPU 状态

- 创建时当前实现直接设为上架，未经过人工审核
- 更新为局部字段更新，但没有版本号/CAS
- 上下架只改变商品状态并删除详情缓存
- 详情、列表和分享等入口的 SKU/SPU 状态过滤不完全一致

### SKU 状态

- SKU 创建要求所属 SPU 处于上架状态
- SKU 单查要求 SKU 与所属 SPU 都处于上架状态
- SKU 批量查询过滤 SKU 状态，但仍返回下架 SPU 所属的上架 SKU，并回填 `spuStatus`
- 按 SPU 列表查询会同时过滤 SPU 和 SKU 状态，三条查询路径语义不同
- 这会给 cart/order/search 带来不同的商品可售语义

## 3. SPU 创建、更新和上下架

### 创建

`ProductController` 先做管理调用、限流和幂等入口处理，`SpuService` 再校验类目、生成 ID、插入 SPU。创建事务提交后加入 Bloom Filter。当前 afterCommit 已对 `spuBloomFilter == null` 做保护，因此不会因 Bloom 初始化失败直接 NPE；此时的实际代价是新 SPU 未加入 Bloom，查询会降级为 Redis/DB 路径。

创建幂等键使用商品名称和类目，不含用户、请求号或内容指纹；两个合法商品只要名称和类目相同就可能被误判为重复。该问题是幂等设计问题，不是普通重复提交保护。另需注意：`IdempotentAspect` 在 Redis 不可用时直接放行原方法，Redis 故障窗口内创建接口会失去幂等保护，可能重复创建；这属于已确认的故障降级风险。

### 更新

更新使用局部字段 wrapper，避免全量实体覆盖；当前已检查影响行数，但没有版本号/更新时间条件，两个并发更新同一字段仍可能最后写覆盖先写。更新后主要删除详情缓存，没有同步 Feed、Search/ES 或推荐侧商品摘要。

### 上下架

上下架由 Controller 和 Service 双重检查状态码。状态改变后 afterCommit 删除详情缓存，但没有可靠的二次删除或持久化失效消息。缓存删除失败时，旧商品详情可能在物理 TTL 内继续可见。

## 4. SKU 与下游商品契约

`getSkuDetail`、`batchGetSkuDetails`、`listSkusBySpuId` 现在分别执行状态校验：单查要求 SKU/SPU 上架，批量仍只过滤 SKU 并返回 `spuStatus`，按 SPU 列表同时过滤 SPU/SKU。三者不是同一套契约，后续应统一业务定义或明确调用方责任。

SKU 创建写入所属 SPU 的聚合数据缓存失效，但没有独立 SKU 缓存。单 SKU 详情、批量 SKU 详情和 SPU 聚合详情的状态判断不同，导致下游必须自行再次检查 `spuStatus`。

Search 的客户端使用 Map 结构回查 Product 详情，字段名和类型在运行期决定；这是弱类型微服务契约。Order 的最小 SKU 字段目前与 Product 返回结构基本兼容，但状态过滤不一致仍是业务边界风险。

## 5. Category 类目树

类目树读取采用 Redis 缓存，miss 后查询全量类目并在内存构树。当前没有分布式加载锁、single-flight 或空结果缓存，因此 key 过期时多个请求会同时查库并构树。

递归构树当前同时有最大深度保护和当前递归路径 `HashSet` 环路检测；A->B->A 会被识别并跳过当前分支。仍需注意：超过最大深度、孤立 parentId 和异常层级会被静默截断/丢弃，类目 parentId 完整性依赖应用逻辑，数据库未见外键约束。

## 6. 详情缓存、Bloom 与逻辑过期

实际链路：

```text
Bloom Filter
  -> Redis RedisCacheData
       -> 未过期：返回缓存
       -> 逻辑过期：返回旧值并异步刷新
       -> 空值/不存在：锁后查 DB
  -> SPU/SKU/Category 多次查询
  -> 回填 Redis
```

这不是严格的本地 L1 + Redis + DB 多级缓存，而是 Bloom + Redis + MySQL。逻辑过期刷新失败只记录日志，没有退避、失败计数、最大旧值容忍时间或告警。逻辑过期时间依赖本机时钟，多实例时钟偏差可能造成不同实例判断不一致。

冷 key 未拿到锁时等待 50ms 后仍可能查 DB，Redis 故障时也会绕过缓存打 DB，故障期间可能造成数据库放大。Bloom 固定容量一百万、误判率 1%，没有容量监控或重建方案；只增不减，删除和下架依赖缓存失效与 DB 状态再次判断。

## 7. ProductBehavior

详情读取在存在用户 ID 时异步记录商品浏览行为，使用第一个 SKU 作为行为 SKU。第一个 SKU 不一定是用户实际选择的 SKU，因此 SKU 维度行为统计可能失真。线程池使用丢弃策略，事件落库失败或队列满时不阻塞详情请求，但当前没有丢弃计数、队列深度或重试补偿。

当前仓库的 root `sql/init-all.sql`、模块初始化 `sql/mysql-content-init.sql`、`sql/migration/**` 以及部署包对应 SQL 中都未找到 `t_product_behavior` 建表语句；只有历史 review DDL 中存在定义。应把它列为当前初始化/迁移入口缺失的部署 schema 缺口，但不能据此断言运行中的数据库一定缺表，最终仍需实际 schema 核验。

## 8. Canal、RocketMQ 与 ES

Product 本身没有 Canal Client 和 ES Client，增量同步由外部 Canal、RocketMQ 和 Search Consumer 完成。Canal 配置监听 `t_spu,t_sku`，但 Search Consumer 对 `t_sku` 事件直接忽略，因此 SKU 新增、价格和状态变化不会触发聚合商品 ES 文档更新。

Search 通过 Feign 回查 Product 详情生成索引，这保证聚合字段来源集中，但把索引消费变成同步依赖 Product 在线、Redis 可用和 DB 可用的长链路。Product 不可用时，代码路径会抛异常并交给外部消费重试；真实 RocketMQ 重试/DLQ 仍需运行确认，不能直接把“代码抛异常”写成“重试一定成功”。

索引文档构建器在 categoryName 或 price 的全部 fallback 都缺失时直接抛异常，不是默认填充；单个 SKU 价格格式错误则会跳过该 SKU。增量删除已确认采用 `status=-1` tombstone；全量重建只 upsert 有效 SPU，没有清理历史孤儿文档，因此孤儿文档风险已确认。

## 9. 分布式与性能问题

### 已确认问题

- SKU 批量查询仍可能返回下架 SPU 所属的上架 SKU，需统一调用方契约
- 类目树无击穿保护；环路已增加路径检测，但深度/孤立节点仍会静默截断
- SPU 创建幂等键可能误判合法商品
- 更新、上下架和 SKU 写入没有统一索引/缓存/事件一致性
- 逻辑过期刷新失败无退避、计数和告警
- Canal 监听 SKU 但消费者忽略 SKU 事件
- ProductBehavior 事件可被 DiscardPolicy 静默丢弃
- `t_product_behavior` 建表未在主初始化 SQL 中形成明确契约
- 详情、列表和 SKU 查询缺少适配条件的联合索引
- 文件和配置中存在固定地址/敏感 fallback 的工程风险

### 待运行确认

- 实际数据库是否存在 `t_product_behavior`
- V1/V2/部署包最终采用哪套 schema
- Redis Sentinel 与直连配置实际采用哪种
- `@Idempotent`、`@RateLimit` 实际 key 和失败语义
- Canal、RocketMQ、Search Consumer 的真实重试/DLQ/幂等
- ES 删除 tombstone 已由消费者源码确认；全量重建不清理孤儿文档，仍需运行态确认实际索引残留
- Product 服务是否可被绕过 Gateway 直连
- 缓存序列化、主从延迟、时钟同步和上传卷共享

## 10. 当前覆盖对账补充

完整逐文件矩阵见 `03-file-review-matrix.md`。本模块候选池共 30 个文件：顶层 2、主源码 22、resources 3、tests 3。30 个文件均已读取，但状态区分为核心逻辑深度审查、契约审查、测试已执行和运行态待确认；`target/` 为构建产物，不纳入源码覆盖。

## 11. 运行验证结果

- `mvn -pl my-xhs-product -am test` 已执行成功
- common 测试 53 个通过，product 测试 14 个通过
- 本轮未执行真实 MySQL/Redis/MQ/Canal/ES 联调
- 本轮未启动或处理 AI 服务

## 11. 本轮修复与验证

### 已修复
- Bloom Filter 初始化失败时，创建后的 afterCommit 不再直接调用空对象。
- SKU 创建要求所属 SPU 处于上架状态。
- SKU 单查要求 SKU 与所属 SPU 都处于上架状态，并返回所属 SPU 状态。
- 类目树构建增加当前递归路径的环路检测。
- SPU 更新和上下架检查数据库影响行数，避免未更新却返回成功。

修复后的当前源码证据：`SpuService.java:285-289,344-351,388-395`、`SkuService.java:53-56,87-96`、`CategoryService.java:107-136`。

### 验证结果
- `mvn -pl my-xhs-product -am test`：common 53 个、product 14 个测试全部通过。
- `product` 服务已重新打包并重启，19006 健康检查 `UP`。
- 当前未执行真实 Canal/MQ/ES、数据库 schema 和并发联调。

### 暂未修复
- SPU 幂等键设计和 Redis 故障时幂等降级
- SKU 事件被 Search 消费者忽略
- `t_product_behavior` 初始化入口缺失
- ES 全量重建孤儿文档
- 缓存双删、行为事件丢弃和多实例一致性问题
- 批量 SKU 查询仍返回下架 SPU 所属的上架 SKU，需先明确 cart/order 契约后统一

## 12. 鉴权基础检查

- 管理接口使用 `X-Admin-Call`
- 内部批量 SKU 接口使用 `X-Internal-Call`
- Feign 通过公共拦截器发送内部 token
- Product Controller 的管理/内部 token 校验不能为空，否则相关接口拒绝
- 本轮不展开 JWT/HMAC 算法细节，重点关注 Header 是否可被直连伪造和下游是否二次校验
