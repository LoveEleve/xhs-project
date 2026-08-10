# my-xhs-product 架构分析

## 一、服务拓扑

```
端口: 19006
JVM:  -Xms512m -Xmx512m (BASE)
日志: /tmp/r_product.log
SkyWalking: my-xhs-product → OAP 21.130.247.89:11800
Nacos:     namespace=my-xhs, server-addr=21.130.247.89:18848
```

## 二、依赖图

```
my-xhs-product (19006)
├── MySQL   my_xhs_product.t_spu/t_sku/t_category (3306读写 / 3307只读)
├── Redis   6379 (布隆过滤器 + CacheAside + 限流计数器)
└── 无Feign/MQ直调用
```

search服务(19016) → ES product_index ← Canal ← MySQL(3306)

## 三、Controller 清单

| Controller | 路径前缀 | 端点数 | 认证 |
|------|------|:--:|------|
| ProductController (SPU) | `/api/product/spu` | 5 | P01/P02/P05: Admin-Call, P03/P04: 公开 |
| ProductController (SKU) | `/api/product/sku` | 3 | P06: Admin-Call, P07: 公开, P08: Internal-Call |
| ProductController (分类) | `/api/product/category` | 1 | P09: 公开 |
| SearchController | `/api/search` | 1 | P10: 公开 |

## 四、数据流

### SPU创建 (写操作)
```
POST /api/product/spu + X-Admin-Call + X-User-Id
  → ProductController.createSpu() → SpuService.createSpu()
    → MySQL: SELECT t_category WHERE id=? (校验分类存在)
    → Snowflake生成ID
    → MySQL: INSERT INTO t_spu(...)
    → afterCommit: Redis BF.ADD myxhs:product:bloom:spu {spuId} (布隆注册)
    → Canal监听到INSERT → RocketMQ PRODUCT_INDEX_TOPIC → ES product_index写入
    → 返回 {spuId}
```

### SPU更新 (写操作)
```
PUT /api/product/spu/{spuId}
  → SpuService.updateSpu()
    → MySQL: SELECT t_category (校验分类) → LambdaUpdate t_spu (防并发丢失)
    → afterCommit: Redis DEL myxhs:product:spu:{spuId} (清缓存)
    → Canal监听到UPDATE → ES product_index更新
```

### SKU创建
```
POST /api/product/sku + X-Admin-Call
  → SkuService.createSku()
    → MySQL: SELECT FROM t_spu WHERE id=? (校验SPU存在)
    → MySQL: INSERT INTO t_sku(...)
    → afterCommit: Redis DEL myxhs:product:spu:{spuId} (清SPU缓存，使下次读重建含新SKU)
```

### SPU读取 (三级缓存)
```
GET /api/product/spu/{spuId}
  → SpuService.getSpuDetail(spuId)
    → 1. 布隆过滤器: BF.EXISTS? NO → PRODUCT_NOT_FOUND
    → 2. Redis GET myxhs:product:spu:{id}
         → 命中 + 未过期 → 直接返回
         → 命中 + 逻辑过期(30min) → 返回旧值 + 异步重建(加锁→MySQL JOIN→回写Redis)
         → 未命中 → 加锁→MySQL→回写→解锁
    → 3. MySQL: SELECT t_spu LEFT JOIN t_sku LEFT JOIN t_category
```

### 分类树查询
```
GET /api/product/category/tree
  → CategoryService.getCategoryTree()
    → Redis GET myxhs:product:category:tree (TTL=7200)
      → 命中 → 直接返回 JSON树
      → 未命中 → MySQL: SELECT * FROM t_category ORDER BY level, id
                 → 内存递归构建三级树(parent_id=null为根)
                 → Redis SET TTL=7200
      → 返回 CategoryTreeVO
```

## 五、Canal→ES同步链路

```
MySQL t_spu/t_sku 变更(INSERT/UPDATE)
  → Canal监听binlog → RocketMQ PRODUCT_INDEX_TOPIC
  → ProductIndexSyncConsumer(search服务) → ES product_index写入
  → /api/search/product → ES查询(productSearchService)
```

全量重建兜底: `IndexRebuildJob` 从MySQL分页读取 → 批量ES bulk写入(凌晨4点定时)

## 六、安全机制

| 机制 | 适用 | 说明 |
|------|------|------|
| X-Admin-Call | P01/P02/P05/P06 | adminToken校验, 非管理员403 |
| X-Internal-Call | P08 | internalToken校验, 非内部调用403 |
| X-User-Id | P01/P02/P05/P06 | Gateway JWT注入, 创建商品记录操作用户 |
| @RateLimit | P01/P02/P04/P05/P06 | Redis计数器, 限流5-60/min per user |

## 七、Redis Key 完整清单

| Key | 类型 | TTL | 数量 | 用途 |
|------|------|:--:|--|------|
| `myxhs:product:bloom:spu` | Bloom | — | 1 | SPU ID布隆，防穿透，创建时afterCommit注册 |
| `myxhs:product:spu:{spuId}` | String | 30min | 1 per SPU | SPU详情缓存，逻辑过期，更新时主动删除 |
| `myxhs:product:lock:spu:{spuId}` | String | — | 缓存重建时 | Redisson互斥锁，防缓存击穿 |
| `myxhs:product:category:tree` | String | 2h | 1 | 三级分类树全量缓存 |
| `myxhs:product:create:{userId}` | Counter | 60s | per user | 创建SPU限流@RateLimit |
| `myxhs:product:update:{userId}` | Counter | 60s | per user | 更新SPU限流@RateLimit |
