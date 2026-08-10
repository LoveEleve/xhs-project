# P04: SPU列表 — GET /api/product/spu/list

## § 源码分析

- **Controller**: `ProductController.java:104` → `@GetMapping("/spu/list")`, 参数 `pageNum(默认1)`, `pageSize(默认10,上限50)`, `categoryId(可选)`
- **Service**: `SpuService.java` → `listSpus(pageNum, pageSize, categoryId)`
  - 分类过滤: categoryId不为null → MySQL filter
  - status过滤: 仅返回 `status=1` (上架) 的SPU
  - 分页: MyBatis-Plus Page + 按id倒序(最新在前)
  - 无Redis缓存(列表页频率低且对实时性有要求——新增SPU应立刻可见)
- **下游**: MySQL `t_spu` SELECT分页 + COUNT总数

## § 业务逻辑

用户浏览商品列表(可选分类/分页) → Controller限制pageNum≥1、pageSize 1-50 → SpuService构建查询(WHERE status=1 + 可选categoryId) → MyBatis-Plus分页 → 按id DESC排序 → 返回 `PageResult<SpuItemVO>` (total/pages/items)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| MySQL有上架SPU | `mysql -P 3306 -e "SELECT id,name FROM my_xhs_product.t_spu WHERE status=1 LIMIT 1"` | 返回空列表(非错误) |
| my_xhs_product DB可连 | `mysql -P 3306 -e "SELECT 1 FROM my_xhs_product.t_spu LIMIT 1"` | 500 |

## § ASCII流转图

```
curl GET /api/product/spu/list?pageNum=1&pageSize=10&categoryId=1
  → Gateway → my-xhs-product:19006 ProductController.listSpus()
    → pageNum = max(pageNum, 1)
    → pageSize = max(1, min(pageSize, 50))
    → MySQL: SELECT * FROM t_spu
        WHERE status=1
        AND (categoryId=? or skip)
        ORDER BY id DESC
        LIMIT pageSize OFFSET (pageNum-1)*pageSize
    → MySQL: SELECT COUNT(*) FROM t_spu WHERE status=1 ...
    → 返回 PageResult<SpuItemVO>
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -s "http://localhost:19000/api/product/spu/list?pageNum=1&pageSize=5"` | 200, `data.total`≥0, `data.records[]` |
| HTTP(分类) | `curl -s "http://localhost:19000/api/product/spu/list?pageNum=1&pageSize=5&categoryId=1"` | records仅该分类SPU |
| HTTP(第二页) | `curl -s "http://localhost:19000/api/product/spu/list?pageNum=2&pageSize=5"` | 不同offset, 无重复 |
| MySQL | `mysql -P 3306 -e "SELECT COUNT(*) FROM my_xhs_product.t_spu WHERE status=1"` | = data.total |
| Redis | `python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379); print(r.get('myxhs:product:spu:list'))"` | None(列表无缓存) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | pageSize限制≤50防大查询 | ✅ |
| 安全 | pageNum≥1防负offset SQL错误 | ✅ |
| 数据一致性 | 无缓存, 实时查MySQL(新增SPU立即可见) | ✅ |
| 微服务 | 不依赖其他服务 | ✅ |

## § curl

```bash
# 默认分页
curl -s "http://localhost:19000/api/product/spu/list?pageNum=1&pageSize=10" | python3 -m json.tool

# 按分类过滤
curl -s "http://localhost:19000/api/product/spu/list?pageNum=1&pageSize=10&categoryId=1" | python3 -m json.tool

# 边界测试: pageNum=0 应自动修正为1
curl -s "http://localhost:19000/api/product/spu/list?pageNum=0&pageSize=10" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'修正后pageNum=0, total={d[\"data\"][\"total\"]}')"
```
