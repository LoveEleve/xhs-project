# 17 商品与目录

> 复审维度 17 | 覆盖模块：05-product, 11-search | 领域专属检查项
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。本维度覆盖商品 SPU/SKU 管理、分类树、ES 搜索索引、布隆过滤器等独有问题。
> 通用规则：缓存见 05、MQ见 04、数据一致性见 03。

---


**执行本维度后，必须在审查报告中输出 `[17] 17 商品与目录：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [17]）。**
## 检查项

### 17.1 SPU/SKU 关系与一致性 | 透镜：业务/工程

**必须检查**：SPU 下架→旗下所有 SKU 是否同步下架；SKU 属性变更→SPU 的聚合信息是否更新。

**怎么查**：
```bash
grep -rn 'updateSpuStatus\|updateSkuStatus\|updateSku\|listSku\|getSkuDetail' my-xhs-product/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| SPU 下架/SKU 仍可选 | `updateSpuStatus(SPU, DOWN)`→SKU status 不变→前端仍可加购用 |
| SKU 价格更新未传播 | 单个 SKU 调价→SPU 的 minPrice/maxPrice 聚合不更新→搜索显示旧价 |
| 删除分类/SPU 残留 SKU | 父级删除→子级没删→孤儿数据 |

**案例**：`updateSpuStatus` 用 `updateById` 全字段回写→并发覆盖 updatedAt（`SpuService.java:355` 修复 LambdaUpdateWrapper）。

---

### 17.2 ES 搜索索引一致性 | 透镜：分布式/业务

**必须检查**：商品上架/下架/属性变更后，ES 索引是否同步更新；ES 和 MySQL 之间有没有对账机制。

**怎么查**：
```bash
grep -rn 'Elasticsearch\|RestHighLevelClient\|index\(\)\|syncToEs\|search\|全文' my-xhs-product/src/main/java/ my-xhs-search/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 商品下架/ES 仍可搜索 | 下架→MySQL `status=0`→ES 未更新→搜索仍返回→点击 404 |
| 商品新增/ES 无索引 | 上架→MySQL 插入→ES 写失败无补偿→永久搜不到 |
| ES mapping 未更新 | 新增字段→ES mapping 不变→按新字段查询报错 |
| 无 ES 重建机制 | ES 数据丢失→无法从 MySQL 全量重建→搜索永远空 |

**案例**：11-search 模块的 ES 索引由商品上下架 MQ 触发更新——如果 MQ 丢失 or Consumer 宕掉→ES 和 MySQL 永久不一致→需对账 Job 定期从 MySQL 全量重建（my-xhs 当前无此机制）。

---

### 17.3 搜索排序与聚合正确性 | 透镜：业务/工程

**必须检查**：搜索结果的排序因子（销量/评分/价格/时间）是否合理——刷单/虚假评分能否操纵排序；聚合结果（分类筛选/价格区间）数据是否正确。

**怎么查**：
```bash
grep -rn 'sort\|排序\|score\|评分\|relevance\|boost\|aggregation\|聚合\|facet\|range' my-xhs-search/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 纯销量排序无衰减 | 爆款永远爆款→新品永远排不到前面→冷启动问题 |
| 聚合用 ES | ES 的 `terms aggregation` 在数据量大时有近似误差→筛选数量不准确 |
| 无个性化推荐 | 全局排序→所有用户看到同一结果→转化率低 |

**案例**：（全特性面预置检查项——11-search 模块的排序算法/个性化推荐待审计。my-xhs 当前搜索结果排序因子未知。）

---

### 17.4 分类树一致性 | 透镜：业务/工程

**必须检查**：分类树的增删改是否保持了树结构一致性；是否支持多级分类；缓存和 DB 的分类树是否一致。

**怎么查**：
```bash
grep -rn 'category\|Category\|分类\|parentId\|tree\|children' my-xhs-product/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 删父分类/子分类残留 | 删除父分类→子分类 `parentId` 指向不存在→展示异常 |
| 分类层级无上限 | 支持无限层级→查询递归炸栈 |
| 缓存过期/分类不一致 | 修改分类→删缓存→并发回填旧值→展示旧分类 |

**案例**：`CategoryService` `groupingBy parentId` 未处理 null parentId→null 分类进入分组→展示异常（修复加 null 防御）。

---

### 17.5 库存占位误导弹 | 透镜：业务/盲区

**必须检查**：商品详情/列表返回的 `stock` 字段是真实库存还是占位值——如果是占位值，前端是否被误导为真实库存。

**怎么查**：
```bash
grep -rn 'stock\|库存\|getStock\|setStock\|Stock' my-xhs-product/src/main/java/com/myxhs/product/dto/
```

**判定**：
- `SkuVO.stock` 字段永远返回固定值（如 999）→前端据此显示"库存充裕"→而实际库存已空→超卖
- 占位库存未注释→新开发者当真实库存→业务判断失误

**案例**：05-product `SkuVO` 的 `stock` 字段占位返回，上游误以为有库存（修复：剔除 stock 字段，标注"真实库存以 inventory 为准"）。

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| 布隆过滤器 | 05.3 | 误判率/重建机制/故障降级 |
| 缓存穿透/击穿 | 05.1 | 分布式锁+双重检查 |
| 缓存更新时机 | 05.6 | afterCommit 删缓存 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl my-xhs-product,my-xhs-search -am
mvn test -pl my-xhs-product,my-xhs-search

# SPU/SKU 关联
grep -rn 'updateSpuStatus\|updateSku\|listSku\|getSkuDetail' my-xhs-product/src/main/java/

# ES 索引
grep -rn 'Elasticsearch\|index()\|syncToEs\|全文' my-xhs-product/src/main/java/ my-xhs-search/src/main/java/

# 分类树
grep -rn 'category\|Category\|分类\|parentId\|tree' my-xhs-product/src/main/java/

# 占位库存
grep -rn 'stock\|库存\|setStock\|Stock' my-xhs-product/src/main/java/com/myxhs/product/dto/
```
