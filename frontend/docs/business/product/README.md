# 商品模块（my-xhs-product）

> 来源：`my-xhs-product` + `my-xhs-home`（商品详情聚合）源码直读。

## 一、业务边界
- **product**：SPU/SKU 的详情、列表、分类树。网关前缀 `/api/product/**`。
- **home**：商品详情聚合（SPU+SKU+库存+计数+相关笔记）。`/api/home/product/{spuId}`。
- **前端商品详情页应优先用 home 聚合接口**（含库存与计数）。

## 二、核心概念
- **SPU**：标准产品单元（商品），如「iPhone 15 Pro」。
- **SKU**：库存单元（具体规格），如「iPhone 15 Pro / 256G / 蓝色」。
- **CategoryTree**：三级分类树，`level: 1/2/3`。

## 三、接口

### SPU 详情
```
GET /api/product/spu/{spuId}   → SpuDetailVO
```
```
id, name, categoryId, categoryName, brandId, description, images[], status,
skuList[], createdAt, updatedAt
```

### SPU 列表（分页）
```
GET /api/product/spu/list?pageNum&pageSize&categoryId   → PageResult<SpuItemVO>
```
```
SpuItemVO: id, name, categoryId, images[], status
```
> ⚠️ **参数名**：这里是 `pageNum/pageSize`（**不是** `page/size`）。前端 `api/product.ts` 已修正为 `pageNum/pageSize`。

### 分类树
```
GET /api/product/category/tree   → List<CategoryTreeVO>（缓存2小时）
```
```
CategoryTreeVO: id, name, parentId, level, sort, icon, children[]
```

### SKU
```
GET /api/product/sku/{skuId}            → SkuVO
GET /api/product/sku/list/{spuId}       → List<SkuVO>
GET /api/product/sku/batch?skuIds=1,2   → List<SkuVO>  (内部接口，需 X-Internal-Call，前端别用)
```
```
SkuVO: id, spuId, name, price, originalPrice, specs(JSON字符串), status
```

### 商品详情聚合（前端用这个）
```
GET /api/home/product/{spuId}   → ProductDetailAggVO
```
```
spuId, name, description, images[], categoryId, categoryName, status,
skuList[]: [ { skuId, skuName, price, image, specValues(对象), availableStock, hasStock } ],
collectCount, viewCount, relatedNotes[]: NoteCardVO[]
```
- `specValues` 是**对象**（如 `{"颜色":"红色","尺码":"M"}`），前端据此做规格选择。
- 库存/是否可售已在聚合里带好。

## 四、状态与展示
- SPU/SKU `status`：`0=下架, 1=上架`。
- 商品列表通常只展示上架商品（后端列表默认已过滤）。

## 五、前端接入注意汇总
1. 商品详情用 `/home/product/{spuId}`（含库存/计数/相关笔记），不用 `/product/spu/{id}`（无库存）。
2. SKU 规格来自 `specValues` 对象；选完所有规格后匹配到唯一 skuId 才能加购/下单。
3. 列表分页参数 `pageNum/pageSize`。
4. `relatedNotes` 直接是 `NoteCardVO[]`，可复用 `NoteCard` 组件。
5. 图片在 `images[]`（数组），封面取 `images[0]`。
