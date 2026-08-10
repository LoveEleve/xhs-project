# my-xhs-product 业务逻辑分析

## 一、SPU生命周期

```
创建(POST /spu) → 默认上架(status=1)
   ├── 更新(PUT /spu/{id}) → 删Redis缓存
   ├── 上下架(PUT /spu/{id}/status) → status=0|1, 删缓存
   └── 查询(GET /spu/{id}) → 布隆→Redis逻辑过期→MySQL
```

## 二、三级分类树

```
t_category表
├── level=1(parent_id=null): 一级类目(如"服装")
│   ├── level=2(parent_id=1): 二级类目(如"女装")
│   │   └── level=3(parent_id=2): 三级类目(如"连衣裙")

查询策略: Redis缓存2h(CategoryService递归构建树) → 未命中查MySQL全量
```

## 三、SKU与SPU关系

```
1 SPU : N SKU (同一商品的不同规格:颜色/尺寸)
P08 批量查询: order/cart服务通过X-Internal-Call调用
限制: 单次1-100个SKU ID(limit 100条, 防止WHERE id IN(...)过大)
```

## 四、ES搜索 vs MySQL查询

| 功能 | MySQL | ES |
|------|:--:|:--:|
| 精确查SPU详情 | ✅ `/spu/{id}` | — |
| 分页列表 | ✅ `/spu/list` | — |
| 全文搜索+排序 | — | ✅ `/search/product` |
| 高亮 | — | ✅ ik_smart分词 |
| 数据同步 | — | Canal→MQ→Consumer全量/增量 |

## 五、故障降级

```
布隆过滤器缺失 → 异步重建(锁保护), 期间直接查Redis→MySQL
Redis key过期   → 逻辑过期: 返回旧值+异步重建(非阻塞)
MySQL不可用     → 所有读返回500, 写直接失败
Canal→ES延迟   → 搜索可能查到旧数据(正常延迟<1s)
```
