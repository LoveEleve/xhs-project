# F-023 商品索引增量补偿会重建不完整文档（价格=0、品牌/分类名丢失）

## 严重度

High

## 涉及文件

- `my-xhs-search/src/main/java/com/myxhs/search/job/IncrementalIndexSyncJob.java:220-235`
- `my-xhs-search/src/main/java/com/myxhs/search/job/IncrementalIndexSyncJob.java:370-397`

## 现象

当商品索引同步失败、进入增量补偿时，补偿任务只从 `t_spu` 读取基础字段，构造的 ES product 文档缺少真实的 SKU 价格、品牌名、分类名和销量，直接写入占位值：

- `price = 0`
- `brandName = ""`
- `categoryName = ""`
- `sales = 0`

## 证据

1. `queryProductsByIds()` 只查询 `my_xhs_product.t_spu`：`IncrementalIndexSyncJob.java:230-234`。
2. `buildProductDocument()` 明确把多个字段写成占位值：
   - `price = 0`：`:392`
   - `brandName = ""`：`:391`
   - `categoryName = ""`：`:389`
   - `sales = 0`：`:394`
3. 注释已经承认这些字段“补偿时不补”。

## 触发条件

1. `ProductIndexSyncConsumer` 处理失败，spuId 被加入 `myxhs:es:sync:failed:product`
2. `IncrementalIndexSyncJob` 运行，触发商品增量补偿

## 影响

1. 商品 ES 文档被补偿成错误的业务快照，而不是恢复成真实文档。
2. 搜索结果可能出现价格为 0 的商品，误导用户。
3. 品牌、分类、销量相关的过滤、排序与召回质量下降。
4. 因为补偿任务本身返回成功，这类错误不会再被自动修复，除非全量重建。

## 修复建议

1. 增量补偿必须补齐完整商品文档：联查 SKU、品牌、分类，或复用 `IndexRebuildJob` 的全量构建逻辑。
2. 在无法构建完整文档时，宁可保留失败 ID 等待下次补偿，也不要写占位值污染 ES。
3. 增加补偿后文档校验，确保关键字段（至少 price、brandName、categoryName）不为空/不为 0。
4. 将“补偿成功”定义为“文档完整写入成功”，而不是“写入任意占位文档成功”。

## 是否需要补充验证

需要触发一次商品索引失败补偿，核对 ES 中对应 product 文档是否被写成 price=0、brand/category 为空。