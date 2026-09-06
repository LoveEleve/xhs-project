# my-xhs-product 文件覆盖基线

## 顶层盘点

依据：`docs/test-4/branch-inventory/project-directories/my-xhs-product/inventory.md`

- `Dockerfile`
- `pom.xml`
- `src/`
- `target/`（构建生成物，不纳入源码逻辑分析）

## 候选文件总数

当前非 `target/` 文件共 30 个：
- 顶层构建文件：2 个
- `src/main/java`：22 个
- `src/main/resources`：3 个
- `src/test/java`：3 个

## 分析重点

- SPU/SKU 创建、更新、上下架、详情和批量查询
- 类目树
- Redis 缓存、多级缓存、Bloom Filter、逻辑过期和缓存一致性
- 商品行为和索引同步边界
- 商品被购物车、库存、搜索等模块依赖的 Feign/数据契约
- 限流、幂等、数据库读写、并发和测试实现一致性

## 覆盖要求

- 每个候选文件必须逐一阅读并记录准确 `file:line`
- DTO、Entity、Enum、Mapper、配置和测试不能仅列文件名
- `target/` 只记录为构建产物，不作为当前源码事实
- 分析完成后必须与 `project-directories/my-xhs-product/inventory.md` 和实际扫描结果对账
