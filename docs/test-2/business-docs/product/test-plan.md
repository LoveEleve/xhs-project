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

| 顺序 | 端点 | 前置依赖 | 认证 | 产出 | 异常 |
|:--:|------|------|:--:|------|:--:|
| 1 | P09-category-tree | 无 | 无 | execution/product/P09-category-tree.md | ⚠️ Redis不可用 |
| 2 | P01-spu-create | U03(Token) | Admin | execution/product/P01-spu-create.md | ⚠️ 缺Admin/重复创建 |
| 3 | P03-spu-detail | P01 | 无 | execution/product/P03-spu-detail.md | ⚠️ 不存在/Redis不可用 |
| 4 | P06-sku-create | P01 | Admin | execution/product/P06-sku-create.md | ⚠️ SPU不存在/缺Admin |
| 5 | P04-spu-list | P01 | 无 | execution/product/P04-spu-list.md | — |
| 6 | P02-spu-update | P01 | Admin | execution/product/P02-spu-update.md | ⚠️ 缺Admin |
| 7 | P05-spu-status | P01 | Admin | execution/product/P05-spu-status.md | ⚠️ status非法值 |
| 8 | P07-sku-detail | P06 | 无 | execution/product/P07-sku-detail.md | ⚠️ 下架SKU |
| 9 | P08-sku-batch | P06 | Internal | execution/product/P08-sku-batch.md | ⚠️ 缺Internal/超100 |
| 10 | P10-search-product | P01+Canal同步 | 无 | execution/product/P10-search-product.md | ⚠️ Canal延迟/ES不可用 |

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

---
## 测试要点补充（2026-08-10，实测修正）

- **认证**：经 gateway 19000。P01/P06/P02/P05 管理端点需 JWT + `X-Admin-Call`；**P09/P03/P04 实际也需 JWT**（/api/product/** 未进 JWT 白名单，非文档标注的"认证:无"）。
- **P08-sku-batch 是内部端点**：走 `X-Internal-Call` 直连 19006，不走 gateway。
- **创建响应字段**：`data.spuId` / `data.skuId`（不是 id）。
- **P07/P03 sku 含 image**（继承 SPU 主图，#46 已修）。
- **P10-search-product**：需 ES 索引有数据（先 S07-rebuild 重建）；中文 keyword 需 URL 编码。

---
## L0-L4 逐端点核对清单

### P01-spu-create / P06-sku-create (Admin)
- [ ] L0: token + X-Admin-Call
- [ ] L1正常: 200 返回 `data.spuId/skuId`; 异常: 缺Admin→403
- [ ] L2: MySQL `t_spu`/`t_sku` 新增行
- [ ] L3: 创建后立即读(P03)一致

### P03-spu-detail
- [ ] L1: 200 name/images/skuList; 异常: 不存在→PRODUCT_NOT_FOUND
- [ ] L2: Redis `myxhs:product:spu:{id}` 回填; `skuList[].image` 非空
- [ ] L3: 一致性(缓存回填) / 性能

### P02/P05 更新/下架
- [ ] L1: PUT → 200
- [ ] L2: Redis 延迟双删; 下架后 P10 搜索不可见(Canal)
- [ ] L3: 缓存一致性

### P10-search-product
- [ ] L1: GET → 200 total>0
- [ ] L2: ES `product_index` 有数据
- [ ] L3: 性能(ES)
