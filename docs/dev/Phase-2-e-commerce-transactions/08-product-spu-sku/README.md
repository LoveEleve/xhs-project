# 商品 SPU/SKU

> 所属服务：my-xhs-product (9005) | 开发阶段：Phase-2 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

商品是电商链路的起点。采用 SPU（标准产品单元）+ SKU（库存量单元）二级模型：SPU 描述商品的共有属性（名称、品牌、详情），SKU 描述规格变体（颜色+尺码组合）。三级分类树提供导航。多级缓存（Caffeine → Redis → MySQL）扛住详情页高 QPS，逻辑过期解决热点 Key 缓存击穿，布隆过滤器防止缓存穿透。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| SPU CRUD | ✅ | 创建/更新/上架/下架/查询 |
| SKU CRUD | ✅ | 创建/更新/查询/按SPU查询 |
| 三级分类树 | ✅ | 递归结构 JSON，缓存 1 小时 |
| 多级缓存 | ✅ | Caffeine(L1) → Redis(L2) → MySQL(L3) |
| 逻辑过期 | ✅ | 热点 Key 永不过期，发现逻辑过期异步刷新 |
| 布隆过滤器 | ✅ | 防止查询不存在的 SPU/SKU 穿透到 DB |
| HotKey 探测 | ✅ | 热点商品自动升级为本地缓存 |
| 商品搜索 | ❌ | Phase-3 ES 实现 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| SPU 总量 | 100 万 | 中型电商平台 |
| SKU 总量 | 1000 万 | 平均每个 SPU 10 个 SKU |
| 商品详情页 QPS | 10000 | 首页/搜索/推荐都会查商品 |
| 缓存命中率 | > 99% | 多级缓存保障 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway → my-xhs-product(9005)
                        │
                        ├── Caffeine(L1): 本地缓存（热点商品，100ms TTL）
                        ├── Redis(L2): 分布式缓存（30min TTL + 逻辑过期）
                        ├── MySQL(L3): 持久化存储
                        └── Bloom Filter: 防穿透（SPU/SKU 存在性判断）
```

### 2.2 多级缓存查询流程

```
1. Client → ProductService: GET /api/product/spu/{spuId}
2. ProductService → Caffeine: 查本地缓存
   ├── 命中 → 直接返回（微秒级）
   └── Miss → 继续
3. ProductService → Bloom Filter: 判断 spuId 是否存在
   ├── 不存在 → 返回空（防穿透）
   └── 可能存在 → 继续
4. ProductService → Redis: 查分布式缓存
   ├── 命中 → 检查逻辑过期
   │     ├── 未过期 → 返回
   │     └── 已过期 → 返回旧值 + 异步刷新
   └── Miss → 继续
5. ProductService → MySQL: 查 DB
6. ProductService → Redis: 回填缓存（设置逻辑过期时间）
7. ProductService → Caffeine: 回填本地缓存
8. ProductService → Client: 返回结果
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
-- 商品SPU表（实际 DDL 来自 init-databases.sql）
CREATE TABLE IF NOT EXISTS t_spu (
    id           BIGINT       NOT NULL COMMENT 'SPU ID',
    name         VARCHAR(256) NOT NULL COMMENT '商品名称',
    category_id  BIGINT       NOT NULL COMMENT '分类ID',
    brand_id     BIGINT       DEFAULT NULL COMMENT '品牌ID',
    description  TEXT         DEFAULT NULL COMMENT '商品描述',
    images       TEXT         DEFAULT NULL COMMENT '商品图片列表(JSON)',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-下架 1-上架',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_category_id (category_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品SPU表';

-- 商品SKU表
CREATE TABLE IF NOT EXISTS t_sku (
    id             BIGINT        NOT NULL COMMENT 'SKU ID',
    spu_id         BIGINT        NOT NULL COMMENT 'SPU ID',
    name           VARCHAR(256)  NOT NULL COMMENT 'SKU名称',
    price          DECIMAL(10,2) NOT NULL COMMENT '价格',
    original_price DECIMAL(10,2) DEFAULT NULL COMMENT '原价',
    stock          INT           NOT NULL DEFAULT 0 COMMENT '库存(冗余，实际由库存服务管理)',
    specs          VARCHAR(1024) DEFAULT NULL COMMENT '规格属性(JSON)',
    status         TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0-下架 1-上架',
    deleted        TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_spu_id (spu_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品SKU表';
```

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `product:spu:{spuId}` | Hash | 30min（逻辑过期） | SPU 详情缓存 |
| `product:sku:{skuId}` | Hash | 30min（逻辑过期） | SKU 详情缓存 |
| `product:category:tree` | String(JSON) | 1h | 分类树缓存 |
| `product:bloom:spu` | Bloom Filter | 永久 | SPU 存在性布隆过滤器 |
| `product:bloom:sku` | Bloom Filter | 永久 | SKU 存在性布隆过滤器 |

### 4.2 Caffeine 本地缓存配置

```java
Cache<Long, SpuVO> spuCache = Caffeine.newBuilder()
    .maximumSize(5000)           // 最多缓存 5000 个 SPU
    .expireAfterWrite(5, TimeUnit.MINUTES) // 5 分钟过期
    .recordStats()               // 开启统计（命中率监控）
    .build();
```

---

## 📡 五、接口设计

### 5.1 接口列表

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/product/spu` | 创建 SPU | ✅ |
| PUT | `/api/product/spu` | 更新 SPU | ✅ |
| GET | `/api/product/spu/{spuId}` | SPU 详情 | ❌ |
| GET | `/api/product/spu/list` | SPU 列表（分页） | ❌ |
| PUT | `/api/product/spu/{spuId}/status` | 上架/下架 | ✅ |
| POST | `/api/product/sku` | 创建 SKU | ✅ |
| GET | `/api/product/sku/{skuId}` | SKU 详情 | ❌ |
| GET | `/api/product/sku/list/{spuId}` | 按 SPU 查 SKU 列表 | ❌ |
| GET | `/api/product/category/tree` | 三级分类树 | ❌ |

---

## 💻 六、核心代码实现

### 6.1 逻辑过期缓存（防缓存击穿）

```java
/**
 * 逻辑过期：Key 永不物理过期，Value 中包含逻辑过期时间
 * 查询时发现逻辑过期 → 返回旧值（保证可用性）→ 异步刷新
 * 优势：热点 Key 永远有值可返回，不会出现缓存击穿
 */
public SpuVO getSpuWithLogicalExpire(Long spuId) {
    String key = "product:spu:" + spuId;

    // 1. 布隆过滤器防穿透
    if (!bloomFilter.mightContain(spuId)) {
        return null;
    }

    // 2. 查 Redis
    String json = redisOperator.get(key);
    if (json == null) {
        // 缓存不存在，查 DB 并回填
        return loadAndCacheSpu(spuId);
    }

    // 3. 检查逻辑过期
    RedisCacheData<SpuVO> cacheData = JSON.parseObject(json, ...);
    if (cacheData.getLogicExpire().isAfter(LocalDateTime.now())) {
        return cacheData.getData(); // 未过期，直接返回
    }

    // 4. 逻辑过期 → 返回旧值 + 异步刷新（只有一个线程刷新）
    String lockKey = "product:lock:spu:" + spuId;
    if (redisOperator.tryLock(lockKey, 10)) {
        CompletableFuture.runAsync(() -> {
            try {
                SpuVO fresh = spuMapper.selectById(spuId);
                cacheData.setData(fresh);
                cacheData.setLogicExpire(LocalDateTime.now().plusMinutes(30));
                redisOperator.set(key, JSON.toJSONString(cacheData));
            } finally {
                redisOperator.unlock(lockKey);
            }
        });
    }

    return cacheData.getData(); // 返回旧值
}
```

---

## ⚖️ 七、方案对比

### 7.1 缓存击穿方案：逻辑过期 vs 互斥锁 vs 永不过期

| 维度 | 逻辑过期（✅ 选定） | 互斥锁 | 永不过期 |
|------|-------------------|--------|---------|
| 可用性 | 高（总有旧值返回） | 中（等锁） | 高 |
| 一致性 | 最终一致（异步刷新） | 强一致 | 需手动更新 |
| 复杂度 | 中 | 中 | 低 |
| 适用场景 | 商品详情（允许短暂不一致） | 库存（要求强一致） | 配置数据 |

**选择理由**：商品详情页允许短暂的数据不一致（价格变更延迟几秒用户无感知），但不允许缓存击穿导致 DB 被打挂。

---

## 🐛 八、踩坑记录

### 8.1 布隆过滤器误判导致缓存穿透

- **现象**：布隆过滤器判断"可能存在"，但 DB 中确实不存在
- **原因**：布隆过滤器有误判率（约 1%）
- **解决**：布隆过滤器判断"可能存在"后，仍需查 DB；DB 查不到则缓存空值 60 秒
- **教训**：布隆过滤器只能过滤"一定不存在"，不能确认"一定存在"

### 8.2 Caffeine 本地缓存与 Redis 不一致

- **现象**：商品下架后，部分实例仍返回上架状态
- **原因**：Caffeine 是 JVM 级别缓存，多实例之间不共享
- **解决**：商品状态变更时发 MQ 广播消息，所有实例收到后清除本地缓存
- **教训**：本地缓存适合"允许短暂不一致"的场景，强一致场景不能用

---

## 📊 九、测试验证

### 9.1 功能测试

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 创建 SPU | 合法参数 | 返回 SPU ID | ⬜ |
| 查询 SPU（缓存命中） | 已缓存的 spuId | 从 Caffeine/Redis 返回 | ⬜ |
| 查询不存在的 SPU | 不存在的 spuId | 布隆过滤器拦截，返回空 | ⬜ |
| SPU 上架/下架 | 状态切换 | 清除缓存，状态更新 | ⬜ |
| 三级分类树 | 无参数 | 返回递归 JSON | ⬜ |
| 逻辑过期刷新 | 过期的缓存 | 返回旧值 + 异步刷新 | ⬜ |

---

## 🎤 十、面试考察点

### Q1: 多级缓存怎么保证一致性？

**推荐回答思路**：

> 1. "三级缓存：Caffeine(5min) → Redis(30min逻辑过期) → MySQL"
> 2. "写操作：先更新 DB → 删 Redis → MQ 广播删 Caffeine"
> 3. "读操作：逻辑过期方案——发现过期返回旧值 + 异步刷新"
> 4. "最终一致性：商品场景允许秒级延迟，不需要强一致"

### Q2: 为什么不全部用 Redis，还要加 Caffeine？

**推荐回答思路**：

> 1. "Redis 有网络开销（1ms），Caffeine 零网络（微秒级）"
> 2. "商品详情页 QPS 万级，全走 Redis 会打满带宽"
> 3. "Caffeine 承担 80% 热点流量，Redis 承担 19%，MySQL 承担 1%"
> 4. "代价：多实例 Caffeine 不一致，但商品场景可接受"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-2/README.md | §3.8 | 商品SPU-SKU完整设计 |
| 📄 02-module-detailed-design.md | §7 | 商品服务/多级缓存/布隆过滤器 |
