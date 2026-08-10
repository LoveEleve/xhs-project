# my-xhs-product 产品服务

> 9个端点(SPU 5 + SKU 3 + 分类 1) + 搜索端点 1个(在search服务) | ProductController + SearchController

---

## 架构概览

```
curl → Gateway → product(19006) → MySQL(t_spu/t_sku/t_category)
                                 → Redis(布隆过滤器/CacheAside 逻辑过期)
                                 → Canal → MQ → ES product_index
                                                    ↓
                                    /api/search/product → ES查询+高亮
```

## 端点清单

### SPU (ProductController: `/api/product/spu`)

| ID | 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|:--:|
| P01 | POST | `/spu` | 创建SPU | Admin-Call |
| P02 | PUT | `/spu/{spuId}` | 更新SPU | Admin-Call |
| P03 | GET | `/spu/{spuId}` | SPU详情 | 无 |
| P04 | GET | `/spu/list` | SPU列表(分页) | 无 |
| P05 | PUT | `/spu/{spuId}/status` | 上下架(0/1) | Admin-Call |

### SKU (ProductController: `/api/product/sku`)

| ID | 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|:--:|
| P06 | POST | `/sku` | 创建SKU | Admin-Call |
| P07 | GET | `/sku/{skuId}` | SKU详情 | 无 |
| P08 | GET | `/sku/batch` | 批量查SKU | X-Internal-Call |

### SKU列表 (ProductController: `/api/product/sku/list`)

| ID | 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|:--:|
| P08b | GET | `/sku/list/{spuId}` | 按SPU查SKU列表 | 无 |

### 分类

| ID | 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|:--:|
| P09 | GET | `/category/tree` | 三级分类树 | 无 |

### 搜索 (SearchController: `/api/search`)

| ID | 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|:--:|
| P10 | GET | `/product` | ES产品搜索 | 无 |

## Key Redis

| Key | 类型 | TTL | 用途 |
|------|------|:--:|------|
| `myxhs:product:bloom:spu` | Bloom | — | SPU ID布隆过滤器(防穿透) |
| `myxhs:product:spu:{spuId}` | String | 30min | SPU详情缓存(逻辑过期) |
| `myxhs:product:lock:spu:{spuId}` | String | — | 缓存重建互斥锁 |
| `myxhs:product:category:tree` | String | 2h | 分类树缓存 |
| `myxhs:product:create:{userId}` | Counter | 60s | 创建SPU限流(5/min) |
| `myxhs:product:update:{userId}` | Counter | 60s | 更新SPU限流 |

## Key MySQL

| 表 | 字段 | 说明 |
|------|------|------|
| t_spu | name, category_id, status(0/1) | 商品SPU |
| t_sku | spu_id, name, price, stock | 商品SKU |
| t_category | name, parent_id, level(1/2/3) | 三级分类树 |

## 写→ES同步链路

```
P01 创建SPU → MySQL t_spu INSERT
    → Canal 监听 binlog → RocketMQ PRODUCT_INDEX_TOPIC
    → ProductIndexSyncConsumer → ES product_index DOCUMENT
    → P10 GET /api/search/product → ES查询返回
```
