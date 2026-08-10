# 分类树构建算法

> 源码：`CategoryService.buildCategoryTree()` + `buildChildren()`
> 验证：`02-product-test.md` §3.1

---

## 1. 数据模型：邻接表

商品分类在 `t_category` 表中用 `parent_id` 自关联存储：

```sql
SELECT * FROM t_category WHERE status=1 ORDER BY sort, id;

 id | name       | parent_id | level
  1 | 服饰       | 0         | 1
 11 | 女装       | 1         | 2
111 | 连衣裙     | 11        | 3
 12 | 男装       | 1         | 2
  2 | 电子产品   | 0         | 1
 21 | 手机       | 2         | 2
```

这种存储方式叫**邻接表（Adjacency List）**——每个节点只记录父节点的引用。优点：结构简单、插入/移动节点只需修改 `parent_id`（O(1)）。缺点：查子树需要递归查询。但分类表只有三层且总量 < 1000 行，缺点可忽略。

---

## 2. 核心算法：一次全查 + 内存分组 + 递归构建

```java
// CategoryService.java:67-85
private List<CategoryTreeVO> buildCategoryTree() {
    // ① 一次查出所有启用分类
    List<Category> allCategories = categoryMapper.selectList(
        new LambdaQueryWrapper<Category>()
            .eq(Category::getStatus, 1)
            .orderByAsc(Category::getSort)
            .orderByAsc(Category::getId));

    // ② 按 parentId 分组
    Map<Long, List<Category>> parentMap = allCategories.stream()
        .collect(Collectors.groupingBy(Category::getParentId));

    // ③ 递归构建树（从根节点 parentId=0 开始）
    return buildChildren(parentMap, 0L);
}

// CategoryService.java:90-108
private List<CategoryTreeVO> buildChildren(
        Map<Long, List<Category>> parentMap, Long parentId) {
    List<Category> children = parentMap.get(parentId);
    if (children == null || children.isEmpty()) {
        return Collections.emptyList();
    }

    List<CategoryTreeVO> result = new ArrayList<>();
    for (Category category : children) {
        CategoryTreeVO vo = new CategoryTreeVO();
        vo.setId(category.getId());
        vo.setName(category.getName());
        vo.setParentId(category.getParentId());
        vo.setLevel(category.getLevel());
        vo.setSort(category.getSort());
        vo.setIcon(category.getIcon());
        vo.setChildren(buildChildren(parentMap, category.getId()));  // 递归
        result.add(vo);
    }
    return result;
}
```

### 时间复杂度分析

| 步骤 | 操作 | 复杂度 |
|------|------|:---:|
| `selectList`（全表查询） | 1 次 SQL，MySQL 索引扫描 | O(n) 磁盘 |
| `groupingBy` | 遍历 + HashMap 插入 | O(n) |
| `buildChildren` | 每个节点被递归访问恰好 1 次 | O(n) |
| **总计** | | **O(n)** |

n = 分类总数（通常 < 1000），总耗时 < 5ms。

### 为什么一次全查而不是逐层查询？

逐层查询（先查一级 → 再查每个一级的二级 → 再查每个二级的三级）产生 N+1 问题——1 次根查询 + N 次子查询。全表一次查 + 内存分组的成本远低于 N+1 的 DB 往返。

---

## 3. Redis 2h 缓存

```java
// CategoryService.java:42-58
public List<CategoryTreeVO> getCategoryTree() {
    // L2: Redis
    List<CategoryTreeVO> redisCached = redisOperator.get("myxhs:product:category:tree");
    if (redisCached != null) return redisCached;

    // L3: MySQL → 内存构建
    List<CategoryTreeVO> tree = buildCategoryTree();

    // 回填 Redis（物理 TTL 2h）
    redisOperator.set("myxhs:product:category:tree", tree, 2, TimeUnit.HOURS);
    return tree;
}
```

**为什么是物理 TTL 而不是逻辑过期？**

分类数据和商品详情不同——分类数量小（< 1000），整棵树序列化后体积小（~10KB）。即使 1000 个请求同时 miss 穿透 DB，也只是 1 次 `buildCategoryTree` 的 O(n) 遍历——远不如商品详情同时 miss 时的 3 表 JOIN 压力大。物理过期在此处足够，不需要逻辑过期 + RLock 的复杂度。

**为什么是 2h？** 分类按周/月变更——新增大促类目、调整排序权重。2h 是"几乎不过期"的保守选择，运维可以手动 DEL Key 强制立即刷新。

---

## 4. 发散：四种树形数据存储模型

分类树选择了最简单的邻接表，但还有三种生产级方案：

### 4.1 嵌套集（Nested Set）

```
  id | lft | rgt | name
   1 |   1 |  10 | 服饰
  11 |   2 |   7 | 女装
 111 |   3 |   4 | 连衣裙
  12 |   5 |   6 | 男装
   2 |  11 |  14 | 电子产品
  21 |  12 |  13 | 手机
```

查子树：`WHERE lft BETWEEN parent.lft AND parent.rgt` → O(log n)。查祖先：`WHERE lft < node.lft AND rgt > node.rgt` → O(log n)。但插入/移动节点需重算区间——O(n)。适合分类只读的场景（如文章分类），不适合商品类目频繁调整。

### 4.2 闭包表（Closure Table）

```
  ancestor | descendant | depth
  ──────────────────────────
     1     |     1      |   0     ← 自己
     1     |    11      |   1     ← 服饰 → 女装
     1     |   111      |   2     ← 服饰 → 连衣裙
    11     |    11      |   0
    11     |   111      |   1     ← 女装 → 连衣裙
```

查子树：`WHERE ancestor = 11` → O(1)。查祖先：`WHERE descendant = 111` → O(1)。缺点：需要维护独立的关系表（n²/2 行），插入/删除 OK，移动 O(n)。适合需要频繁查询子树/祖先的复杂层级场景。

### 4.3 物化路径（Materialized Path）

```
  id | path        | name
   1 | /1/         | 服饰
  11 | /1/11/      | 女装
 111 | /1/11/111/  | 连衣裙
```

查子树：`WHERE path LIKE '/1/%'` → O(log n)（前缀索引）。查询直观，但移动节点需更新所有后代的路径字符串（O(n)）。

### 对比总结

| 模型 | 查子树 | 查祖先 | 插入 | 移动 | 适合 |
|------|:---:|:---:|:---:|:---:|------|
| **邻接表**（当前） | O(n) | O(n) | O(1) | O(1) | 层数少、节点少 |
| 嵌套集 | O(log n) | O(log n) | O(n) | O(n) | 只读为主 |
| 闭包表 | O(1) | O(1) | O(1) | O(n) | 频繁查子树/祖先 |
| 物化路径 | O(log n) | O(log n) | O(1) | O(n) | 需要路径展示 |

商品分类明确限制三层、节点数 < 1000、一次内存构建成本极低。简单方案（邻接表）比复杂方案（闭包表）的总成本更低——不需要多维护一张关系表的同步逻辑。

---

## 5. 发散：递归深度安全

三层分类树的递归深度最大为 3——安全。如果分类层级扩展到 10 层：

```java
// 防御性写法（当前未采用，三层够用）
private List<CategoryTreeVO> buildChildren(
        Map<Long, List<Category>> parentMap, Long parentId, int depth) {
    if (depth > MAX_DEPTH) {
        log.warn("分类树深度超限: parentId={}, depth={}", parentId, depth);
        return Collections.emptyList();
    }
    // ... 正常逻辑
}
```

加入深度保护能防止数据异常（如 `parentId` 循环引用导致无限递归）导致的 `StackOverflowError`。当前三层不需要——但如果未来支持自定义多级分类，这是必须加的安全措施。

---

## 6. 已知限制

| 限制 | 说明 | 影响 |
|------|------|------|
| 无递归深度保护 | `parentId` 循环引用会栈溢出 | 当前三层，发生概率极低 |
| 全表一次加载 | 分类过多时 JVM 内存压力 | 当前 < 1000 行，无问题 |
| 分类无 CRUD API | 不提供分类的增删改接口 | 运维用 SQL 直接操作 |
| 无布隆过滤器 | 不需要（分类树是单 Key 全量数据） | 无影响 |

---

## 关联文档

- `01-product-module.md` — §7 分类树
- `02-product-test.md` — §3.1 分类树查询
- `05-multi-level-cache.md` — 分类树在缓存分层中的位置
