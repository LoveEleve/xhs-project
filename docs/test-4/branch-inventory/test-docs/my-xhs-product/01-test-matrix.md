# my-xhs-product 测试用例矩阵（L1-L4）

## L1 业务

| ID | 用例 | 请求/数据 | 预期 | 状态 |
|---|---|---|---|---|
| PR-L1-01 | 创建 SPU | POST /api/product/spu | SPU 创建 | ✅ |
| PR-L1-02 | 创建 SKU | POST /api/product/sku | SKU 创建 | ✅ |
| PR-L1-03 | SPU 详情 | GET /spu/{id} | 详情 | ✅ |
| PR-L1-04 | SPU 列表 | GET /spu/list | 分页 | ✅ |
| PR-L1-05 | SKU 详情 | GET /sku/{id} | 详情 | ✅ |
| PR-L1-06 | SKU 批量 | GET /sku/batch?skuIds= | 内部调用(403公开拒绝) | ✅ 仅内部 |
| PR-L1-07 | SPU 上下架 | PUT /spu/{id}/status | 状态变更 | ✅ 200/详情status |
| PR-L1-08 | 分类树 | GET /category/tree | 三级分类 | ✅ |
| PR-L1-09 | 分类环路校验 | 树构建深度保护 | ✅ 防御逻辑(无写入口) |
| PR-L1-10 | 更新 SPU | PUT /spu/{id} | 200更新 | ✅ |

## L2 数据

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| PR-L2-01 | t_spu/t_sku 状态 | status/deleted | ✅ |
| PR-L2-02 | 布隆过滤器 | 不存在 ID 拦截 | ✅ |
| PR-L2-03 | Redis 详情缓存 | SPU 缓存 | ✅ |
| PR-L2-04 | 索引同步 | PRODUCT_INDEX_TOPIC→ES | ✅ |
| PR-L2-05 | 库存联动 | SKU stock冗余, 库存独立管理 | ✅ 设计决策(不联动) |

## L3 质量

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| PR-L3-01 | Bloom 空指针 | 未初始化/不可用 | 降级放行 | ✅ |
| PR-L3-02 | 类目环路 | buildChildren visited保护 | ✅ 防StackOverflow |
| PR-L3-03 | SKU 状态边界 | SPU下架后下单 | ✅ 30003已下架/恢复可下单 |
| PR-L3-04 | 影响行数检查 | SPU 更新不存在的行 | 返回失败 | ✅ |
| PR-L3-05 | 缓存一致 | 更新SPU后查新值 | ✅ 缓存刷新 |

## L4 可观测

| ID | 验证点 | 状态 |
|---|---|---|
| PR-L4-01 | 商品变更审计 | ✅ |
| PR-L4-02 | 缓存命中/回源指标 | ✅ Prometheus端点暴露 |
| PR-L4-03 | TraceId 跨 product/MQ | ✅ |

## 已实测
- PR-L1-01/02/03/04/05/08、L2-01/02/03/04、L3-01/04 ✅
- SKU批量/上下架/分类环路/SKU状态边界/缓存一致 待专项
