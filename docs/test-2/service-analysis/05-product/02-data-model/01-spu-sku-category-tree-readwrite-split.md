# SPU-SKU-Category 数据模型：一对多 + 自引用树 + 读写分离

> **源码**: Spu(37行)/Sku(43行)/Category(39行)/BaseEntity(49行)/CategoryService(131行)  
> **设计核心**: SPU↔SKU 无外键一对多 / Category parent_id 自引用三级树 / MySQL 读写分离  
> **关键修复**: DC2 stock注释 / CC3 CategoryStatus语义解耦 / IV2 SKU status过滤

---

## 1. 数据模型总览

```
┌─────────────────────┐
│     t_spu (SPU)     │  ← 商品（Standard Product Unit）
│  id (雪花)          │
│  name, description  │
│  category_id ───────┼──→ t_category (分类)
│  brand_id           │
│  images (JSON数组)  │
│  status (0下架/1上架)│
└──────┬──────────────┘
       │ 1:N（无外键约束）
       ▼
┌─────────────────────┐
│     t_sku (SKU)     │  ← 规格变体（Stock Keeping Unit）
│  id, spu_id         │
│  name, price        │
│  stock (冗余占位)   │  ← 实际库存由 inventory 服务独立维护
│  specs (JSON)       │
│  status (0下架/1上架)│
└─────────────────────┘

┌─────────────────────┐
│  t_category (分类)  │
│  id, name           │
│  parent_id ─────────┼──→ 自引用（parent_id=0 为根）
│  level (冗余存储)   │
│  sort               │
│  status (1=启用)    │  ← 注意：语义 ≠ ProductStatus（商品上下架）
└─────────────────────┘
```

### 1.1 为什么 SPU↔SKU 无外键约束？

`Sku.spuId` 只是一个 `Long` 字段，DB 层无 `FOREIGN KEY`。原因：
- **分库分表预留**：外键在分库场景下是不可行的（跨库约束无法保证）
- **SPU 缓存粒度**：SKU 没有独立缓存，SPU 缓存内嵌整个 SKU 列表——SKU 变更触发 SPU 缓存整体重建
- **查询模式**：SKU 总是通过 SPU 聚合查询（`WHERE spu_id=?`），从不需要反向查"某个 SKU 属于哪个 SPU"

---

## 2. SPU 实体

```java
// Spu.java — 继承 BaseEntity（id/雪花,deleted/@TableLogic,createdAt,updatedAt）
@TableName("t_spu")
public class Spu extends BaseEntity {
    private String name;
    private Long categoryId;
    private Long brandId;
    private String description;
    private String images;   // JSON 数组字符串，如 '["url1","url2"]'
    private Integer status;  // 0=下架 1=上架 (ProductStatus)
}
```

### 2.1 images 列：JSON 数组 vs 关联表

| 方案 | 实现 | 优劣势 |
|------|------|------|
| JSON 数组 | `images = JSON.toJSONString(request.getImages())` | 读取时一次拿到全部，更新后整体替换 |
| 关联表 | `t_spu_image(spu_id, url, sort)` | 支持单张增删、排序，但多一次 JOIN |

选择 JSON：商品图片通常整体管理（批量上传、整体替换），不需要精细到单张的增删。JSON 序列化/反序列化由 `fastjson2` 在 Service 层完成，DB 里存字符串。

### 2.2 status 字段

`ProductStatus` 枚举（`ProductStatus.java`）定义 `ON_SHELF(1)/OFF_SHELF(0)`。区别于 `BaseEntity.deleted`（`@TableLogic`）——下架 ≠ 逻辑删除。下架的商品仍可被管理员查询和重新上架，逻辑删除的商品对全系统不可见。

---

## 3. SKU 实体

```java
// Sku.java
@TableName("t_sku")
public class Sku extends BaseEntity {
    private Long spuId;
    private String name;
    private BigDecimal price;
    private BigDecimal originalPrice;  // 划线价
    private Integer stock;             // 冗余占位——实际库存以 inventory 为准
    private String specs;              // JSON 规格，如 {"颜色":"红色","尺码":"XL"}
    private Integer status;            // 0=下架 1=上架
}
```

### 3.1 stock 的双系统独立维护

**设计决策**：`Sku.stock` 是创建时的初始值（随后不维护），实际库存由 inventory 服务管理。两个系统独立：

```
创建 SKU → stock 写入初始值（SkuService.createSku）
库存变更 → inventory 服务独立维护 Redis + MySQL
查询库存 → 通过 Feign 调 inventory（不读 Sku.stock）
```

**为什么不从 inventory 同步？** inventory 的库存模型更精细（锁定库存/可用库存/总库存），Sku.stock 只有单个数值。保持两系统独立避免了库存服务成为 product 的紧耦合依赖。

### 3.2 SKU 无独立缓存

`RedisKeyConstants.PRODUCT_SKU`（`myxhs:product:sku:`）虽然定义了，但**全模块零引用**。所有 SKU 查询走 DB 直查——因为：
- SKU 的查询频率远低于 SPU（主要路径是购物车 Feign 调用）
- SKU 变动频率高（价格调整、上下架），独立缓存的一致性成本高
- 创建 SKU 时通过 afterCommit 清除 SPU 缓存实现级联刷新

### 3.3 batchGetSkuDetails 状态过滤 (`SkuService.java:94-105`)

```java
public List<SkuVO> batchGetSkuDetails(List<Long> skuIds) {
    LambdaQueryWrapper<Sku> wrapper = new LambdaQueryWrapper<Sku>()
            .in(Sku::getId, skuIds)
            .eq(Sku::getStatus, ProductStatus.ON_SHELF.getCode());  // 修复 IV2
    return skuMapper.selectList(wrapper).stream()
            .map(this::toSkuVO).collect(Collectors.toList());
}
```

修复前用 `selectBatchIds` 不过滤 status → 下架 SKU 也返回给购物车。修复后加 `.eq(status, ON_SHELF)`。

---

## 4. Category 三级分类树

### 4.1 实体

```java
// Category.java
@TableName("t_category")
public class Category extends BaseEntity {
    private String name;
    private Long parentId;   // 0=一级分类, 非0=上级分类ID
    private Integer level;   // 冗余存储：1/2/3
    private Integer sort;    // 同级排序
    private Integer status;  // 1=启用（非 ProductStatus，纯字面常量）
}
```

**status 语义解耦（修复 CC3）**：修复前 `buildCategoryTree` 用 `ProductStatus.ON_SHELF.getCode()` 过滤，但 `ProductStatus` 是商品的上下架概念。修复后改为直接字面常量 `1` + 注释说明。

### 4.2 构建算法 (`CategoryService.java:82-111`)

```java
// 1 条 SQL 查全部
List<Category> all = categoryMapper.selectList(
    new LambdaQueryWrapper<Category>()
        .eq(Category::getStatus, 1)
        .orderByAsc(Category::getSort)
        .orderByAsc(Category::getId));

// 内存分组 + 递归
Map<Long, List<Category>> byParent = all.stream()
    .collect(Collectors.groupingBy(Category::getParentId));

List<CategoryTreeVO> roots = byParent.getOrDefault(0L, List.of()).stream()
    .map(c -> buildChildren(c, byParent, 0))
    .collect(Collectors.toList());
```

```
分类数据（DB 全量）          parent_id=0     递归构建
┌────────────────────┐      ┌─────────────┐
│ 服装 (id=1, pid=0) │      │ 服装         │
│ 男装 (id=2, pid=1) │  →   │ ├─ 男装      │  MAX_DEPTH=10
│ 女装 (id=3, pid=1) │      │ │  ├─ 上衣    │  防循环引用
│ 上衣 (id=4, pid=2) │      │ │  └─ 裤子    │  StackOverflow
│ 裤子 (id=5, pid=2) │      │ └─ 女装
└────────────────────┘      └─────────────┘
```

**为什么一次查全量而不是逐层递归？** 递归查 DB 会有 N+1 问题——三级 × 每级平均 10 个分类 = 最多 111 次 SQL。全量查出后内存分组是 1 次 SQL + O(N) 内存操作。

### 4.3 缓存策略差异

| 数据类型 | 缓存策略 | 原因 |
|------|------|------|
| SPU 详情 | 逻辑过期 30min + 物理 TTL 2h | 高频读 + 需即时反映变更 |
| 分类树 | 简单 TTL 2h | 低频变更（分类结构基本不变） |
| 空分类树 | **不缓存** | 防止清空后 2h 无法恢复 |

**分类树为什么不走逻辑过期？** 分类树极少变更（没有写 API），逻辑过期的异步刷新机制在这里用不上。简单的 2h TTL 更简单、资源占用更少。两种策略共存是有意的差异化设计。

---

## 5. 读写分离

```properties
# application-datasource.properties
master.jdbc-url=mysql://21.130.247.89:13307/my_xhs_product
slave.jdbc-url =mysql://21.130.247.89:13311/my_xhs_product
```

通过 MyBatis Executor 拦截器实现：写事务内保持主连接，非事务 SELECT 自动路由到从库。

**afterCommit 与读写分离的交互**：

```
updateSpu: @Transactional → 主库连接
  spuMapper.updateById → 主库写
  afterCommit → evictSpuCache → 删 Redis

getSpuDetail（并发）:
  BloomFilter → Redis miss（刚被删）
  → loadSpuDetailFromDb → spuMapper.selectById → 从库（非事务读）
  从库已同步主库 → 读到新值 → 回填 Redis = 新值 ✅
```

修复 B2 之前，evictSpuCache 在事务内执行 → 并发读到旧值回填。afterCommit 修复后不存在此窗口。

---

## 6. 工程维度审查

### 6.1 表结构演进风险

| 变更 | 影响 | 缓解 |
|------|------|------|
| `t_spu` 加列 | Spu 实体需同步加字段 | MyBatis-Plus 自动映射 |
| `images` JSON 格式变 | 旧数据解析失败 | fastjson2 异常 catch 静默降级 |
| `specs` JSON 格式变 | SkuVO 反序列化 | 同上 |
| 分类改名 | SPU 缓存 categoryName 陈旧 | 最长 30min 窗口，逻辑过期后自愈 |

### 6.2 无分类写 API

当前版本 Category 只读——无创建/更新/删除端点。DB 修改后需等 2h TTL 或手动清 Redis Key。这是已知限制（`CategoryService.java` 注释标注），不是 bug。

---

## 7. 面试 Q&A

### Q1: 为什么 SPU 和 SKU 是两张表而不是一张宽表？

**陷阱答案**：因为规范化设计。

**正确答案**：一个 SPU 会有多个 SKU（如红色+XL、蓝色+M）。宽表方案（SPU 行 × SKU 列）会在加新的规格维度时改表结构（ALTER TABLE）。两张表方案下，加新 SKU 只需 INSERT 一行。另外：SPU 的缓存粒度是整个对象（含 skuList），两张表各司其职更容易做缓存控制。

### Q2: `level` 字段是冗余的（可以通过 `parent_id` 计算），为什么还存？

去冗余化（3NF）在 OLTP 中是对的，但 `level` 的使用场景是展示"当前是第几级分类"——如果每次渲染都要递归计算深度，N 个分类 = N 次递归。冗余存储 `level` 是空间换时间，一次 SQL 拿到的数据已经包含层级信息。

### Q3: 读写分离 + 缓存 = 双倍复杂度，值不值？

在 my-xhs 这个体量（日活万级），读写分离的最大价值不是性能——是 **灾备**。主库故障时可以手动切换从库为主库。缓存（Redis）的价值才是性能。两者解决的问题不同，不是"二选一"。

### Q4: 分类改名为啥不触发 SPU 缓存刷新？

没有跨表级联刷新机制。分类改名是低频操作（可能几个月一次），为它建立一套复杂的级联通知（MQ → product → 逐 SPU 清缓存）的工程成本远高于 30min 的陈旧容忍。如果一定要实时生效——手动清 SPU 缓存即可。
