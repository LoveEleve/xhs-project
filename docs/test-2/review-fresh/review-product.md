# Product 模块 Review

## 性能 / 数据库
1. **[高] batchGetSkuDetails 的 N+1 SPU 查询** (SkuService.java:96-107, toSkuVO:133 → resolveSpuImage:141-157)
   SKU 批量查询本身用 `IN` 一次取回，但每个 SKU 的 image 解析 `resolveSpuImage` 单独 `spuMapper.selectById`。
   N 个 SKU → N+1 次 SPU 单查。购物车/下单热路径尤其明显。应收集 distinct spuId → 一次 IN 查询 → 本地 map 聚合。

2. **[中] getSkuDetail / batchGetSkuDetails 无缓存** (SkuService.java:82-107)
   每次调用直接打 DB；SPU detail 有缓存但 SKU 读路径裸查。热路径需评估。

## 业务逻辑 / 缓存一致性
3. **[中] getSpuDetail 不过滤下架状态** (SpuService.java:loadSpuDetailFromDb:612-629)
   DB 加载不校验 status==ON_SHELF，下架 SPU 详情仍对外返回（含公开接口）。若期望"下架不可见"则为 bug；当前 Bloom 只 add 不删，下架商品长期留在 bloom 中 → 继续可查。需明确产品语义并加状态过滤。

4. **[中] 布隆过滤器只 add 不 remove** (SpuService.java:createSpu:272, 无删除路径)
   下架/逻辑删除的 SPU 永久留在 bloom（含已被物理删除的）。配合 #3 放大"已删除仍可查"问题。Bloom 对"只增不删"场景 OK，但删除后应清对应缓存（evictSpuCache 未用于删除场景，delete 走 SpuMapper 的 @TableLogic 但未调 evict）。检查是否有删除 SPU 端点及缓存清理。

5. **[低] listSpus 无 pageSize 上限/无深分页优化** (SpuService.java:529-542)
   offset 分页，大页深翻页性能差；建议游标或限制 pageSize（对齐 content 的 MAX_PAGE_SIZE 做法）。

6. **[中] SPU_ASYNC_EXECUTOR 裸线程池丢 traceId (MDC)** (SpuService.java:75-81)
   `new Thread` 未传播 MDC，异步缓存刷新/布隆加载日志丢失 traceId → 全链路断链。对应运维缺口 O2。建议 MdcAware executor。

## 工程
7. 逻辑过期 + 延迟双删 + 空值缓存 + 布隆过滤器组合防穿透/击穿/雪崩，设计成熟。
   - 空值物理 TTL 5min 兜底，防止不存在 ID 打爆内存 —— 好。
   - 注意：updateSpu 延迟双删(立即+1s) 与逻辑过期刷新并存，靠延迟双删兜底异步刷新窗口 —— 合理但依赖 1s 窗口假设。
