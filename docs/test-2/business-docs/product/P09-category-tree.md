# P09: 分类树 — GET /api/product/category/tree

## § 源码分析

- **Controller**: `ProductController.java:194` → `@GetMapping("/category/tree")`
- **Service**: `CategoryService.java:46` → `getCategoryTree()`
  - L2: `redisOperator.get(CATEGORY_TREE_REDIS_KEY)` → 命中返回(反序列化)
  - Redis不可用: catch Exception → `log.warn` 降级直查DB(不报错)
  - L3: `buildCategoryTree()` — 一次查出所有启用分类(status=1) → `groupingBy parentId` 内存构建 → 按sort+id排序
  - parentId=null(一级分类) → 防御NPE: `c.getParentId() != null ? c.getParentId() : 0L`
  - 回写Redis: `redisOperator.set(key, tree, 2, TimeUnit.HOURS)` TTL=7200
  - 空树不缓存(数据清空后仍返回空列表, 但下次仍查DB)
- **下游**: Redis key `myxhs:product:category:tree` TTL=7200 + MySQL `t_category` 全量查(status=1)

## § 业务逻辑

获取商品三级分类树 → 先查Redis(2h缓存) → Redis命中直接返回 → 未命中/不可用: 查MySQL t_category全量(WHERE status=1) → 内存按parentId分组构建树 → 非空树回写Redis(2h) → 空树不写(防止缓存空结果阻塞恢复) → 返回分类树

注意: 无分类写接口(只读), DB修改分类最长2h生效(需手动DEL Redis key或等TTL过期)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| MySQL有分类数据 | `mysql -P 3306 -e "SELECT id,name,level FROM my_xhs_product.t_category WHERE status=1 LIMIT 1"` | 返回空树(不是错误) |
| Redis可连 | `python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379); r.ping()"` | 降级直查DB(仍正常返回) |
| my_xhs_product DB可连 | `mysql -P 3306 -e "SELECT 1 FROM my_xhs_product.t_category LIMIT 1"` | 500 |

## § ASCII流转图

```
curl GET /api/product/category/tree
  → Gateway → my-xhs-product:19006 ProductController.getCategoryTree()
    → Redis GET myxhs:product:category:tree
    → Redis命中? → 直接返回(反序列化)
    → 未命中/Redis不可用?
      → MySQL: SELECT * FROM t_category WHERE status=1 ORDER BY sort,id
      → 内存: groupingBy parentId 构建三级树
      → 树非空? → Redis SET myxhs:product:category:tree TTL=7200
      → 返回分类树
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -s http://localhost:19000/api/product/category/tree` | 200, `data[]`: 一级分类含children |
| HTTP结构 | `curl -s ... \| python3 -c "import sys,json; t=json.load(sys.stdin)['data']; print(len(t), t[0].keys())"` | N≥1, 含id/name/children |
| Redis | `python3 -c "import redis,json; r=redis.Redis(host='21.130.247.89',port=6379); t=r.get('myxhs:product:category:tree'); print(type(t), len(json.loads(t)) if t else 0)"` | JSON字符串, len≥1 |
| Redis TTL | `python3 -c "...; print(r.ttl('myxhs:product:category:tree'))"` | 0~7200 |
| MySQL | `mysql -P 3306 -e "SELECT id,name,level,parent_id FROM my_xhs_product.t_category WHERE status=1 ORDER BY sort,id LIMIT 5"` | 多行 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | Redis 2h缓存, 一次全量查DB非递归N+1 | ✅ |
| 可扩展 | 递归构建, 深度受level字段限制(当前3级) | ✅ |
| 微服务 | 不依赖其他服务 | ✅ |
| 安全 | 公开读接口, 无认证 | ✅ |
| 弹性 | Redis不可用降级查DB不报错 | ✅ |
| 幂等 | 空树不缓存(防止数据清空后阻塞恢复) | ✅ |

## § curl

```bash
# 获取三级分类树
curl -s http://localhost:19000/api/product/category/tree | python3 -m json.tool
# 预期: { "code": 200, "data": [ { "id": 1, "name": "一级类目", "children": [ { ... } ] } ] }

# 验证Redis缓存是否写入
python3 -c "
import redis, json
r = redis.Redis(host='21.130.247.89', port=6379)
val = r.get('myxhs:product:category:tree')
if val:
    tree = json.loads(val)
    print(f'缓存命中, 分类数量: {len(tree)}, TTL: {r.ttl(\"myxhs:product:category:tree\")}s')
else:
    print('缓存未命中')
"
```
