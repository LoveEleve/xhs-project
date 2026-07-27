# my-xhs-product 商品服务模块

## 模块概览

| 项目 | 内容 |
|------|------|
| 模块路径 | `my-xhs-product/` |
| 端口 | 19006 |
| 服务名 | `my-xhs-product`（Nacos） |
| 数据库 | `my_xhs_product`（MySQL 13307，独立数据库） |
| Java 源文件 | 23 个 |
| 启动类 | `ProductApplication.java` |
| 扫描包 | `com.myxhs.product`, `com.myxhs.common` |

**职责边界**：商品 SPU/SKU 的 CRUD、上架/下架管理、三级分类树查询。商品详情读操作走多级缓存（BloomFilter → Redis 逻辑过期 → MySQL），写操作先更新 DB 再删除缓存。

---

## 1. 数据模型

### 1.1 数据库表（my_xhs_product 库）

**`t_spu` — 商品 SPU 表**

```sql
id            BIGINT PRIMARY KEY        -- 雪花 ID
name          VARCHAR(256)              -- 商品名称
category_id   BIGINT                    -- 分类 ID
brand_id      BIGINT                    -- 品牌 ID
description   TEXT                      -- 商品描述
images        TEXT (JSON Array)         -- 图片 URL 列表
status        TINYINT                   -- 0=下架 1=上架
deleted       TINYINT DEFAULT 0         -- 逻辑删除（@TableLogic）
created_at    DATETIME
updated_at    DATETIME
```

**`t_sku` — 商品 SKU 表**

```sql
id              BIGINT PRIMARY KEY      -- 雪花 ID
spu_id          BIGINT                  -- 所属 SPU ID
name            VARCHAR(256)            -- SKU 名称（规格描述）
price           DECIMAL                 -- 售价（BigDecimal）
original_price  DECIMAL                 -- 原价（BigDecimal）
stock           INT DEFAULT 0           -- 库存数量（冗余，实际由库存服务管理）
specs           VARCHAR(512) (JSON)     -- 规格属性（如 {颜色:红色, 尺寸:L}）
status          TINYINT                 -- 0=下架 1=上架
deleted         TINYINT DEFAULT 0
created_at      DATETIME
updated_at      DATETIME
```

**`t_category` — 商品分类表**

```sql
id            BIGINT PRIMARY KEY        -- 雪花 ID
name          VARCHAR(128)              -- 分类名称
parent_id     BIGINT DEFAULT 0          -- 父分类 ID（0=一级分类）
level         TINYINT                   -- 分类层级（1/2/3）
sort          INT DEFAULT 0             -- 排序
icon          VARCHAR(512)              -- 分类图标
status        TINYINT DEFAULT 1         -- 0=禁用 1=启用
deleted       TINYINT DEFAULT 0
created_at    DATETIME
updated_at    DATETIME
```

### 1.2 三级分类树结构

```
一级分类（parentId=0）
 ├── 二级分类（parentId=一级ID）
 │   ├── 三级分类（parentId=二级ID）
 │   └── ...
 └── ...
```

一次查全表在内存中按 `parentId` 分组递归构建，避免 N+1 查 DB。

### 1.3 SPU/SKU 关系

```
SPU（iPhone 15） ← 一个商品
 ├── SKU-1（黑色 128G ¥5999）
 ├── SKU-2（黑色 256G ¥6999）
 ├── SKU-3（白色 128G ¥5999）
 └── SKU-4（白色 512G ¥8999）
```

---

## 2. 接口清单（10 个 REST 端点）

| 方法 | 路径 | 说明 | 鉴权 | 缓存 |
|:----:|------|------|:---:|:---:|
| POST | `/api/product/spu` | 创建 SPU | X-User-Id | — |
| PUT | `/api/product/spu/{id}` | 更新 SPU | X-User-Id | 清除缓存 |
| GET | `/api/product/spu/{id}` | SPU 详情 | 无（公开） | Bloom→Redis→MySQL |
| GET | `/api/product/spu/list` | SPU 列表（分页） | 无（公开） | — |
| PUT | `/api/product/spu/{id}/status` | 上架/下架 | X-User-Id | 清除缓存 |
| POST | `/api/product/sku` | 创建 SKU | X-User-Id | 清除父 SPU 缓存 |
| GET | `/api/product/sku/{id}` | SKU 详情 | 无 | — |
| GET | `/api/product/sku/batch` | 批量 SKU 详情 | 内部 | — |
| GET | `/api/product/sku/list/{spuId}` | SPU 下所有 SKU | 无 | — |
| GET | `/api/product/category/tree` | 三级分类树 | 无 | Redis 2h |

### 2.1 POST /api/product/spu — 创建 SPU

```json
// 请求
POST /api/product/spu
Header: X-User-Id: 10001
{
    "name": "iPhone 15",
    "categoryId": 100,
    "brandId": 1,
    "description": "全新 A16 芯片",
    "images": ["http://img.example.com/1.jpg", "http://img.example.com/2.jpg"]
}
// 响应
{"code": 200, "data": {"spuId": 2090123456789}}
```

创建后在布隆过滤器中添加该 ID。

### 2.2 GET /api/product/category/tree — 分类树

```json
{
    "code": 200,
    "data": [{
        "id": 1, "name": "服饰", "parentId": 0, "level": 1, "sort": 1,
        "children": [{
            "id": 10, "name": "女装", "parentId": 1, "level": 2, "sort": 1,
            "children": [
                {"id": 100, "name": "连衣裙", "parentId": 10, "level": 3, "children": []}
            ]
        }]
    }]
}
```

---

## 3. 多级缓存架构

```
请求 GET /spu/{id}
  │
  ├─ L1: 布隆过滤器（防穿透）
  │     contains(id) == false → 直接返回 null
  │     contains(id) == true  → 继续（误判率 1% 可接受）
  │
  ├─ L2: Redis（逻辑过期 + 防击穿）
  │     RedisCacheData{data, logicExpire}
  │     ├─ 未过期 → 返回 data
  │     ├─ 已过期 + data!=null → 返回旧值 + 异步刷新（分布式锁）
  │     └─ data==null（空值缓存）→ 穿透到 DB
  │
  └─ L3: MySQL（兜底）
        ├─ 有数据 → 回填 L2（逻辑过期 30min）
        └─ 无数据 → 回填空值缓存（逻辑过期 2min，物理 TTL 5min）
```

**两层防穿透策略**：

| 层 | 机制 | 拦截什么 | 不可用时 |
|:--:|------|------|------|
| L1 | 布隆过滤器 | 拦截"一定不存在"的 ID | 降级跳过 |
| L2 | 空值缓存 | 兜底 L1 的 1% 误判 + 不存在 ID | 无影响 |

---

## 4. RedisCacheData — 逻辑过期设计

```java
public class RedisCacheData<T> {
    private T data;                  // 实际缓存数据（null = 空值缓存）
    private LocalDateTime logicExpire;  // 逻辑过期时间
}
```

**逻辑过期 vs 物理过期**：

| 维度 | 物理过期（SETEX） | 逻辑过期（当前方案） |
|------|:---:|:---:|
| Key 删除 | Redis 到期自动删除 | Key 永不删除 |
| 并发读 | 全部穿透 DB（缓存击穿） | 返回旧值 + 异步刷新 |
| 内存 | 到期释放 | 永不释放（依赖 update/del 清理） |
| 适用场景 | 读写都低的数据 | 高频读的数据 |

商品详情是典型的高频读场景——一个热销商品每秒可能被上千人查看。用物理过期会在过期瞬间产生缓存击穿。逻辑过期保证始终有数据返回（即使是稍旧的），同时异步刷新更新数据。

**空值缓存的特殊处理**：

```java
// 正常数据：逻辑过期 30min
RedisCacheData<SpuDetailVO> cache = RedisCacheData.of(detail, 30);

// 空值（DB 无此 SPU）：逻辑过期 2min，物理 TTL 5min
RedisCacheData<SpuDetailVO> nullCache = RedisCacheData.of(null, 2);
redisOperator.set(key, nullCache, 5, TimeUnit.MINUTES);
```

空值缓存必须有物理 TTL——如果不设 TTL，攻击者用大量不存在 ID 遍历，每个 ID 都产生一个永不过期的空值 Key，Redis 内存会被打爆。5min 物理 TTL 确保空值 Key 在合理时间内自动清理。

**ID 格式防御**：`getSpuDetail(@PathVariable Long spuId)` 中的 `Long` 类型由 Spring MVC 在参数绑定时自动校验——非法格式直接返回 400 Bad Request，请求不会进入 Service 层。这条防线不需要额外代码，生命周期比缓存层更短。

---

## 5. 布隆过滤器：懒加载 + 容错降级

```java
@PostConstruct
public void initBloomFilter() {
    spuBloomFilter = redissonClient.getBloomFilter("myxhs:product:bloom:spu");
    boolean isNew = spuBloomFilter.tryInit(1_000_000L, 0.01);  // 容量 100w，误判率 1%

    if (isNew) {
        asyncLoadBloomFilter();  // 异步加载，不阻塞启动
    } else {
        // 已有布隆过滤器，但检查是否为空（上一次加载可能失败）
        if (spuBloomFilter.count() == 0) {
            asyncLoadBloomFilter();  // 重新加载
        } else {
            bloomFilterReady.set(true);  // 已有数据，立即可用
        }
    }
}
```

**异步加载机制**：

```
asyncLoadBloomFilter():
  ├─ 分布式锁（RLock.tryLock）→ 多实例只有一个执行
  ├─ 二次检查（count > 0）→ 防止重复加载
  ├─ 游标分页（id > lastId, LIMIT 5000）→ 每批 5000 条，sleep 100ms
  ├─ bloomFilterReady = false（加载期间）→ 跳过布隆过滤器，降级到 L2+L3
  └─ 加载完成 → bloomFilterReady = true → 防穿透保护激活
```

**容错降级**：`bloomFilterReady` 为 `AtomicBoolean`。异步加载期间或加载失败时，跳过布隆过滤器——所有查询走 L2+L3。功能正确（只是没有防穿透优化），不会因为布隆过滤器异常导致商品查不到。

**持久化**：`tryInit` 是幂等的——Redis 中已存在则复用，不会每次启动重建。只有首次部署或 Redis 数据被清空时才全量加载。

---

## 6. 缓存一致性：写后删除

```
updateSpu():
  1. UPDATE t_spu SET ... WHERE id = ?  (@Transactional)
  2. redisOperator.delete(redisKey)      // 删 Redis

createSku():
  1. INSERT t_sku (...)
  2. spuService.evictSpuCache(spuId)    // 删除父 SPU 缓存（SKU 变更影响 SPU 详情）
```

**策略**：先更新 DB → 删除缓存（Cache Aside 的变体："先 DB 后删除"）。

**为什么是删除而不是更新缓存？**

| 操作 | 并发风险 |
|------|------|
| 更新缓存 | 线程 A 更新 DB=100 → 线程 B 更新 DB=200 → 线程 B 更新缓存=200 → 线程 A 更新缓存=100（旧的覆盖新的） |
| 删除缓存 | 下次读请求触发回填，肯定读到最新 DB 值 |

删除缓存避免了并发写缓存的乱序问题。

**为什么没有延迟双删（先删 → 写 DB → 再删）？**

商品场景的并发写入远低于并发读取。先更新 DB 再删缓存的窗口（DB 已更新、缓存未删除的短暂间隙内读到旧值）在实际中几乎不会发生——一个 SKU 同一时刻只有一个运营人员在编辑。对于商品这种 OLTP 场景，简单策略足够。

---

## 7. 分类树：一次查询 + 内存构建

```java
buildCategoryTree():
  // 1. 查全表
  List<Category> all = categoryMapper.selectList(...)

  // 2. 按 parentId 分组
  Map<Long, List<Category>> parentMap = all.stream()
      .collect(Collectors.groupingBy(Category::getParentId))

  // 3. 递归构建树
  return buildChildren(parentMap, 0L)  // 从根节点开始
```

**为什么一次查全表而不是逐层查询？**

| 方案 | SQL 次数 | 总行数 | 问题 |
|------|:---:|------|------|
| 逐层查 | 1（根）+ N（子）= N+1 | 低 | N+1 问题 |
| 一次全查 | 1 | 全表 | 分类表小（通常 < 1000 行），全表扫描可接受 |

分类表数量极小，一次全查后内存构建的成本远低于 N+1 的 DB 往返。Redis 缓存 2h TTL 后，99.9% 的请求走缓存。

---

## 8. 资源配置

| 组件 | 配置 | 说明 |
|------|------|------|
| Tomcat | 线程 150/15, 连接 8192 | 同 counter |
| Redis | Sentinel(mymaster) + cache(16380) + business(16381) | 默认走 Sentinel Master |
| Redisson | `RBloomFilter`, `RLock` | 布隆过滤器 + 异步刷新锁 |
| 自定义线程池 | core=2, max=8, queue=100, daemon | SPU 异步操作专用 |

---

## 9. 已知问题与改进

| # | 问题 | 严重性 | 可修复性 |
|:--:|------|:------:|:------:|
| p1 | `getCategoryName` 逐行查 DB——在 `loadSpuDetailFromDb` 中每次 SPU 详情都单独查一次 Category 表。应为 `categoryService.getCategoryTree()` 批量加载后内存缓存 | 低 | 可优化 |
| p2 | SPU 列表 `listSpus` 无缓存——每次翻页都直查 MySQL。如果首页有多品类商品列表，DB 压力会线性增长 | 中 | 需评估（列表缓存带来一致性问题） |
| p3 | `createSku` 无 `@RateLimit`——对比 `createSpu` 有 `product:create 10/60s`，SKU 创建缺少限流保护 | 低 | 可立即修复 |
| p4 | 布隆过滤器误判率 1%——在 100 万 SPU 规模下，有 1 万个不存在 ID 会穿透到 DB | 低 | 定量分析后决定是否降低误判率（增大 bit 数组） |
| p5 | RedisCacheData 依赖 `LocalDateTime.now()`——如果服务器时钟回拨，逻辑过期判断可能错误 | 低 | 极低概率，可迁为 `System.currentTimeMillis() + offset` |
| p6 | ProductApplication/Controller 的 Javadoc 宣称"Caffeine → Redis → MySQL"三级缓存，但实现中不含 Caffeine——这是有意取舍，非遗漏。多实例 Caffeine 本地缓存的失效广播一致性成本太高，当前 Redis 单层分布式缓存已满足需求。注释应同步更新 | 低 | 更新 Javadoc，删除 Caffeine 引用 |

---

## 10. 模块文件清单

```
my-xhs-product/src/main/java/com/myxhs/product/
├── ProductApplication.java            # 启动类
├── controller/
│   └── ProductController.java         # 10 个 REST 端点
├── service/
│   ├── SpuService.java                # SPU 核心：多级缓存 + 布隆过滤器
│   ├── SkuService.java                # SKU CRUD + 批量查询
│   └── CategoryService.java           # 分类树构建 + 缓存
├── cache/
│   └── RedisCacheData.java            # 逻辑过期缓存包装
├── entity/
│   ├── Spu.java                       # SPU 实体
│   ├── Sku.java                       # SKU 实体
│   └── Category.java                  # 分类实体
├── mapper/
│   ├── SpuMapper.java                 # MyBatis-Plus BaseMapper
│   ├── SkuMapper.java
│   └── CategoryMapper.java
├── dto/
│   ├── request/
│   │   ├── SpuCreateRequest.java
│   │   ├── SpuUpdateRequest.java
│   │   └── SkuCreateRequest.java
│   └── response/
│       ├── SpuDetailVO.java
│       ├── SpuItemVO.java
│       ├── SkuVO.java
│       └── CategoryTreeVO.java
└── enums/
    └── ProductStatus.java             # 0=下架 1=上架
```

---

## 关联文档

- `02-product-test.md` — curl 测试用例与结果记录
- `03-bloom-filter.md` — 布隆过滤器懒加载 + 容错降级
- `04-logical-expire.md` — RedisCacheData 逻辑过期 vs 物理过期
- `05-multi-level-cache.md` — 三级缓存全链路分析
- `06-category-tree.md` — 分类树构建算法
- `07-cache-consistency.md` — 写后删除一致性策略
