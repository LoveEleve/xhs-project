# my-xhs-product 测试执行计划

> 10端点 | 链2 | 参照执行模板 execution/TEMPLATE.md

---

## 一、前置准备

### 1.1 环境确认

```bash
curl -s "http://21.130.247.89:18848/nacos/v1/ns/instance/list?serviceName=my-xhs-product&namespaceId=my-xhs" | python3 -c "import json,sys;print('product:',len(json.load(sys.stdin)['hosts']),'instance')"
python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis'); r.ping(); print('redis OK')"
mysql -h 21.130.247.89 -P 3306 -u root -p'Xhs@2026#MySQL' -e "SELECT COUNT(*) FROM my_xhs_product.t_spu" 2>/dev/null
```

### 1.2 获取管理 Token

```bash
ADMIN_TOKEN="my-xhs-admin-token-2026"
TOKEN=$(cat /tmp/test_token.txt)
```

### 1.3 确认布隆过滤器就绪

```bash
python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis'); print('bloom count:',r.execute_command('BF.CARD','myxhs:product:bloom:spu'))"
```

---

## 二、执行顺序

| 顺序 | 端点 | 前置依赖 | 认证 | 异常 |
|:--:|------|------|:--:|:--:|
| 1 | P09-category-tree | 无 | 无 | ⚠️ Redis不可用 |
| 2 | P01-spu-create | U03(Token) | Admin | ⚠️ 缺Admin/重复创建 |
| 3 | P03-spu-detail | P01 | 无 | ⚠️ 不存在/Redis不可用 |
| 4 | P06-sku-create | P01 | Admin | ⚠️ SPU不存在/缺Admin |
| 5 | P04-spu-list | P01 | 无 | — |
| 6 | P02-spu-update | P01 | Admin | ⚠️ 缺Admin |
| 7 | P05-spu-status | P01 | Admin | ⚠️ status非法值 |
| 8 | P07-sku-detail | P06 | 无 | ⚠️ 下架SKU |
| 9 | P08-sku-batch | P06 | Internal | ⚠️ 缺Internal/超100 |
| 10 | P10-search-product | P01+Canal同步 | 无 | ⚠️ Canal延迟/ES不可用 |

---

## 三、异常场景矩阵

### 3.1 认证

| 场景 | 端点 | curl | 预期 |
|------|------|------|------|
| 缺 Admin-Call | P01 | `POST /api/product/spu` 不带 X-Admin-Call | 403 |
| 缺 Internal-Call | P08 | `GET /api/product/sku/batch` 不带 X-Internal-Call | 403 |
| 缺 JWT | P04 | `GET /api/product/spu/list` 不带 Authorization | 200(公开接口) |

### 3.2 业务规则

| 场景 | 端点 | curl | 预期 |
|------|------|------|------|
| 创建不存在的 SPU | P03 | `GET /spu/99999` | PRODUCT_NOT_FOUND |
| 批量查超过100 | P08 | `GET /sku/batch?skuIds=1,2,...,101` | 参数校验失败 |
| SKU 创建参照不存在的 SPU | P06 | `POST /sku` 带不存在的 spuId | "SPU不存在" |
| Status 非法值 | P05 | `PUT /spu/1/status?status=2` | Controller 拦截 400 |

### 3.3 数据一致性

| 场景 | 验证 | 预期 |
|------|------|------|
| 创建后立即读 | P01→P03: 创建 SPU 后立即 GET | status=1, skus 数组非空 |
| 更新后立即读 | P02→P03: 更新名称后立即 GET | 新名称(延迟双删后生效) |
| 下架后搜索不可见 | P05(status=0)→P10: 搜索 | 搜索结果不含该 SPU(Canal 同步后) |

### 3.4 缓存一致性

| 场景 | 验证 | 预期 |
|------|------|------|
| 更新后缓存失效 | P02→Redis GET | `myxhs:product:spu:{id}` 不存在(delayed) |
| 首次读回填 | P03→Redis GET | myxhs:product:spu:{id} 存在 TTL≈1800 |
| 分类树缓存 | P09→Redis GET | myxhs:product:category:tree 存在 TTL≈7200 |

---

## 四、测试数据速查

| 数据 | 值 | 来源 |
|------|------|------|
| TOKEN | `cat /tmp/test_token.txt` | 链1 U03 登录 |
| ADMIN_TOKEN | `my-xhs-admin-token-2026` | 环境配置 |
| INTERNAL_TOKEN | `my-xhs-internal-token-2026` | 环境配置 |
| SPU_ID | P01 返回 | P01 创建后保存 |
| SKU_ID | P06 返回 | P06 创建后保存 |
| CATEGORY_ID | P09 返回的第一个分类树节点 id | P09 列表 |
