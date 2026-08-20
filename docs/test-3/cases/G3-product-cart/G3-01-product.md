# G3-01 SPU/SKU 用例（多级缓存 + 布隆 + canal→ES + HotSku）

> 组：G3 商品与购物车 | 服务：product(19006) + inventory(19009) + search(19016) | 入口：**gateway(19000)**
> 依赖：G1 登录（testlib.new_user 得 token/hmacSecret/uid）
> 时间引用：矩阵 **#32**（HotSkuDetector 30s 窗口）、D 节（产品缓存 1h）、F 节（ES 可见性 1-2s）
> 前提：执行前确认 product/inventory/search 服务 UP、ES product_index 可查、canal product_instance 运行中（监听 my_xhs_product.t_spu/t_sku → PRODUCT_INDEX_TOPIC）

## 代码实证（2026-08-13，G3 梳理时核实）

### 端点与安全（gateway:19000 → product:19006；**深度 REVIEW 修正版**）
| 端点 | JWT | 额外鉴权 | HMAC | 说明（代码实证） |
|---|---|---|---|---|
| POST /api/product/spu | 需要 | **X-Admin-Call（isAdminCall 校验，非仅 JWT！）** | 免签（管理类 hmac 白名单） | @Valid SpuCreateRequest：name @NotBlank / categoryId @NotNull / brandId 可选 / description / images；**@Idempotent 10s（key=spu:create:name:categoryId）+ @RateLimit 5次/60s** |
| PUT /api/product/spu/{id} | 需要 | **X-Admin-Call** | 免签 | @Valid SpuUpdateRequest（字段均可空，有值才更新）；**@RateLimit 5次/60s** |
| PUT /api/product/spu/{id}/status | 需要 | **X-Admin-Call** | 免签 | @RequestParam status ∈ {0,1}；**Controller 层校验非法值 → 40002"商品状态无效"**（不会 500）；**@RateLimit 5次/60s** |
| POST /api/product/sku | 需要 | **X-Admin-Call** | 免签 | @Valid SkuCreateRequest：spuId/name/price @Positive/originalPrice @Positive/stock @Min(0)/specs；**@RateLimit 5次/60s**；校验 SPU 存在（不存在 → 30001） |
| GET /api/product/spu/{spuId} | **需要（不在 JWT white-list！）** | 无 | 免签（hmac 白名单含 /api/product/spu/**） | 多级缓存：L1 布隆 → L2 Redis(逻辑过期30min+物理TTL 2h) → L3 MySQL(锁防惊群)；**P2-2 已修：loadSpuDetailFromDb 过滤 status!=ON_SHELF → 下架返回 null → 30001**（非 30003！）|
| GET /api/product/spu/list | **需要（不在 JWT white-list）** | 无 | 免签 | 分页+categoryId；**仅 status=1**；orderByDesc(createdAt)；**@RateLimit 60次/60s perUser（需 X-User-Id，即需登录）**；pageNum≥1/pageSize≤50（Math 钳制非 40002）|
| GET /api/product/sku/{skuId} | **需要** | 无 | 免签 | 白名单 `/api/product/sku/**`；**SkuService.getSkuDetail 不过滤 status（下架 SKU 详情仍可查）** |
| GET /api/product/sku/batch?skuIds= | **需要（JWT）** | **X-Internal-Call（内部接口！外部调用 403"仅限内部服务调用"）** | 免签 | skuIds 1-100（**超出/空 → 40002"skuIds 数量必须在 1-100 之间"**）；仅返回 status=ON_SHELF 的 SKU；P2-1 批量预取 SPU 首图 |
| GET /api/product/sku/list/{spuId} | **需要** | 无 | 免签 | 某 SPU 的 SKU 列表；**仅 status=ON_SHELF** |
| GET /api/product/category/tree | **需要** | 无 | 免签 | 三级分类树；**缓存 2 小时** |

**深度 REVIEW 关键修正（相对初稿）**：
1. **管理写操作全部需要 X-Admin-Call**（非仅 JWT）——无 adminCall → **403"仅限管理员操作"**；gateway hmac 白名单放行 HMAC，但 JWT 仍需（不在 JWT white-list）
2. **product 读接口经 gateway 需 JWT**（JWT white-list **不含** /api/product/**）；**但 product 服务自身无鉴权拦截器——服务间 Feign 直连（如 search→product getSpuDetail）不经 gateway 完全公开**（端口信任模型，对照 G1-06-09）
3. **/api/product/sku/batch 是内部接口**（cart 通过 Feign + X-Internal-Call 调用）——外部直接调 → 403
4. **下架 SPU 详情 → 30001（非 30003）**：loadSpuDetailFromDb 过滤后返回 null → Controller 返回 PRODUCT_NOT_FOUND
5. **createSpu 分类不存在 → 40002"分类不存在"**（非 30001）
6. **listSpus 有 RateLimit 60次/60s**（perUser）；写端点各 5次/60s——限流用例需按此设计
7. **ES product_index 是 SPU 粒度**（t_sku 变更被消费者忽略）；price=首 SKU 价（非最低价）；deleted 语义=status=-1 标记
8. **布隆过滤器 @PostConstruct 启动时全量加载** t_spu id（Redisson BloomFilter；加载期间降级放行；创建 SPU afterCommit 增量 add）

### 错误码（ResultCode）
30001 商品不存在（含下架详情） / 30002 SKU 不存在 / 30003 商品已下架（**当前代码路径未使用?**——SkuService/SkuController 无 status 校验，下架 SKU 详情仍 200；登记观察） / 40002 参数 / 40202 限流 / 403 管理员或内部接口鉴权

### 多级缓存结构（SpuService，代码实证）
- L1 布隆过滤器：`myxhs:product:bloom:spu`（Redisson BloomFilter，启动时全量加载 t_spu id；加载期间降级放行）
- L2 Redis：`myxhs:product:spu:{spuId}`（RedisCacheData 包装：data + 逻辑过期时间戳；逻辑过期 30min + 物理 TTL 2h；空值也缓存防穿透）
- L3 MySQL：`my_xhs_product.t_spu`（**IndexRebuildJob 需跨库前缀 my_xhs_product.t_spu**——#37 教训）；加 CACHE_REFRESH_LOCK load 锁防惊群（tryLock 1s/lease 10s）
- 异步刷新：逻辑过期后返回旧值 + asyncRefreshCache（单线程刷新）
- **T-042 已修（2026-08-13）**：ProductIndexSyncConsumer 检查 deleted 列——逻辑删除 SPU 不再残留 ES（indexProductFromCanal 删除 product_index 文档）

### canal→ES 链路（G3 核心 L2）
- canal product_instance（监听 my_xhs_product.t_spu + t_sku）→ **PRODUCT_INDEX_TOPIC** → search ProductIndexSyncConsumer（product-index-sync-consumer-group）→ ES `product_index`（19200）
- **消费端只处理 t_spu 表**（handleCanalMessage 明确 `if (!"t_spu".equals(table)) return`——**t_sku 变更忽略**，SKU 信息在全量重建时关联写入）
- **ES 文档=SPU 粒度**（doc id=spuId；mapping 实证：spuId/name/categoryId/categoryName/brandName(恒 null)/price/image/sales(恒 0)/status/createdAt——**无 description/规格**）
- **price 来源**：Feign 调 product 补全时取 `skuList.get(0).get("price")`（**首 SKU 价，非最低价**——注释写"最低售价"但代码取第一项，登记观察）；product 不可用 → 字段不完整（非阻塞）
- **版本**：ExternalGte version=canal es（event sequence，无则 ts）
- **T-042 已修**：canal UPDATE 事件检查 `deleted==1` → deleteProduct（逻辑删除走 @TableLogic UPDATE 非 DELETE）
- 消费端 ES 通信异常 → `myxhs:es:sync:failed:product` Set（1h TTL）记录失败 spuId，重试
- **L2 查 ES 前 sleep 1-2s**（canal 近实时 + MQ 消费）

### HotSkuDetector（inventory 服务，矩阵 #32）
- `inventory:hot:window:{skuId}`（ZSet，score=秒级时间戳）；窗口 10s（WINDOW_SECONDS=10）、**阈值 100 次**（HOT_THRESHOLD=100）、TTL 30s
- recordAndCheck：ZADD → 清理 10s 前 → 计数 ≥100 → hot
- **触发方式**：高频请求触发（并发/循环）或直接操纵 ZSet 造 100 条（③ 操纵）；调用点 = InventoryService（预扣库存/查库存时记录）

### 注意（本组特有）
- product 曾有 /actuator/prometheus 67s 慢（#51/52 已修 DlqMetrics 缓存）
- 管理类端点（spu/sku 写）走 HMAC 白名单——**测试时无需签名但需 JWT**；与 G1/G2 的"写操作必须签名"不同
- 分页 total 为字符串（R4），断言 int() 转换

## 前置准备（执行时记录快照）
```python
from testlib import *
# 1. 注册用户：g3p_（商品操作者）
# 2. 基线：SELECT COUNT(*) FROM my_xhs_product.t_spu（记数）
# 3. ES：product_index/_count（记数）
# 4. canal：确认 product_instance 运行（见部署文档）
```

## 用例清单

### G3-01-01 创建 SPU + 全链路（核心）
- **前置**：g3p_ 用户登录（拿 token）+ **X-Admin-Call 头（tokens.env ADMIN_TOKEN）**
- **入口**：`POST /api/product/spu` body=`{"name":"g3p 测试商品A","categoryId":{分类id},"description":"G3测试","images":["http://x.com/a.jpg"]}`（带 JWT + X-Admin-Call，**免 HMAC**）
- **L1 断言**：200；data.spuId 为**字符串**雪花 id（R4）
- **L2 数据验证**：
  ```bash
  # ① DB 入库
  mysql -N -e "SELECT id,name,category_id,status,deleted FROM my_xhs_product.t_spu WHERE id={spuId}"
  # → status=1(ON_SHELF 默认，代码实证 setStatus(ON_SHELF)) deleted=0
  # ② 详情（需 JWT，免 HMAC）走多级缓存
  GET /api/product/spu/{spuId}（带 JWT）→ 200 name 一致
  GET myxhs:product:spu:{spuId} → 存在（RedisCacheData 包装）
  # ③ canal→ES（sleep 1-2s）
  curl -u elastic:'Xhs@2026#Elastic' http://21.130.247.89:19200/product_index/_doc/{spuId}
  # → _source 含 name/status=1
  # ④ 布隆过滤器：spuId 已加入（afterCommit add）
  # ⑤ 分类树含该分类（category/tree 200，需 JWT）
  ```
- **🔍 人工观察**：RocketMQ PRODUCT_INDEX_TOPIC 消费进度；SkyWalking product→MQ→search 链路
- **注意**：createSpu 有 @Idempotent 10s（key=name:categoryId）——同参数 10s 内重复 → 40201"请勿重复提交"（**新增断言**）

### G3-01-02 创建 SPU 参数校验（负面）
- ① 缺 name → **40002**；② 缺 categoryId → **40002**；③ name 空串 → **40002**
- ④ **分类不存在**（categoryId=999999999999）→ **40002"分类不存在"**（代码实证 createSpu 校验分类）
- **L2**：t_spu 无新增
- **注意**：参数校验在 isAdminCall 之后——**必须先带 X-Admin-Call 再测参数**，否则返回 403 而非 40002

### G3-01-03 创建 SKU + 库存
- **入口**：`POST /api/product/sku`（JWT + X-Admin-Call）body=`{"spuId":{spuId},"name":"黑色 XL","price":99.90,"originalPrice":129.00,"stock":100,"specs":"{\"颜色\":\"黑色\",\"尺码\":\"XL\"}"}`
- **L1**：200；data.skuId 字符串
- **L2**：
  ```
  SELECT id,spu_id,name,price,stock,status FROM my_xhs_product.t_sku WHERE id={skuId} → price=99.90 stock=100 status=1（默认上架，代码实证）
  GET /api/product/sku/{skuId}（带 JWT）→ 200
  GET /api/product/sku/list/{spuId} → 含该 sku
  # ⚠️ /api/product/sku/batch 是内部接口：外部调用（无 X-Internal-Call）→ 403"仅限内部服务调用"
  #   cart 通过 Feign+InternalCallFeignConfig 调用（本用例只验证外部 403 分支，见 01-10）
  # 库存联动：GET /api/inventory/stock/{skuId}（带 JWT，hmac 白名单）→ **初始化前 availableStock=?/initialized=false**（inventory 独立桶，未 init）
  #   ⚠️ 代码实证：t_sku.stock 是"创建时冗余占位值（从不更新）"，真实库存走 inventory 桶（需 /api/inventory/init，X-Admin-Call）
  #   → G3 组只验证"未 init 的行为"，init 与桶结构归 G5 交易组
  # canal→ES：sleep 1-2s 后 product_index 该 spu 文档（**SPU 粒度**，doc id=spuId，见 01-03 下方 ES 结构说明）
  ```

### G3-01-04 SKU 参数校验（负面）
- ① 缺 spuId → 40002；② price=0 → 40002（@Positive）；③ stock=-1 → 40002；④ name 空 → 40002
- **L2**：t_sku 无新增

### G3-01-05 更新 SPU（字段级 + 缓存一致性）
- **入口**：`PUT /api/product/spu/{spuId}`（JWT + X-Admin-Call）body=`{"description":"G3更新描述"}`
- **L1**：200
- **L2**：
  ```
  SELECT description FROM my_xhs_product.t_spu WHERE id={spuId} → 更新值
  # 缓存：afterCommit evictSpuCache（立即删 + 1s 后二次删，延迟双删）
  GET myxhs:product:spu:{spuId} → 0（缓存已删；**注意异步二次删——立即查可能读到并发重建的旧值，等 1.5s 再断言 = 0**）
  # ES 同步：sleep 1-2s → product_index 文档（SPU 粒度）
  #   ⚠️ ES 无 description 字段——更新 name 才能验证 ES 同步（UPDATE 事件重索引）；description 更新 ES 不可见（mapping 无此字段，登记观察）
  ```
- **空更新**：body=`{}` → 200（hasUpdate=false 不执行 UPDATE——代码实证；**记录无副作用**）

### G3-01-06 上架/下架（updateSpuStatus）
- **前置**：所有请求带 **JWT + X-Admin-Call**（否则 403"仅限管理员操作"）
- ① 下架：`PUT /api/product/spu/{spuId}/status?status=0` → 200
- **L2**：
  ```
  SELECT status FROM t_spu → 0
  GET /api/product/spu/{spuId}（带 JWT）→ **30001 商品不存在**（P2-2 已修：loadSpuDetailFromDb 过滤下架 → null → PRODUCT_NOT_FOUND；**非 30003**）
  GET /api/product/spu/list → 不再含该 spu（列表仅 status=1）
  GET myxhs:product:spu:{spuId} → 0（afterCommit evictSpuCache）
  # ES：sleep 1-2s → product_index 该 spu status=0（**mapping 含 status，可验证下架同步**；canal UPDATE 事件重索引）
  ```
- ② 上架：status=1 → 200；列表恢复出现；详情恢复 200
- ③ status=2（非法）→ **40002"商品状态无效"**（Controller 层校验，不会 500）
- ④ 下架后 SKU 行为：`GET /api/product/sku/{skuId}` → **200（SkuService.getSkuDetail 不过滤 status——代码实证，登记观察：下架 SPU 的 SKU 详情仍可查）**；`GET /api/product/sku/list/{spuId}` → **仍返回（过滤的是 SKU.status 非 SPU.status——运行态实测：下架 SPU 后 SKU 列表仍 1 条；登记观察：SKU 无独立下架入口，SPU 下架不影响 SKU 列表）**

### G3-01-07 多级缓存验证（布隆/防穿透/逻辑过期）
- ① **布隆拦截**：GET /api/product/spu/999999999999（带 JWT）→ **30001**（且 Redis 无 key——布隆层拦截，连缓存都不写；观察 product 日志"[多级缓存] 布隆过滤器拦截"）
- ② **空值防穿透**：GET /api/product/spu/999999000001 → 布隆拦截 30001 或空值缓存（**实测**：布隆初始化加载全部 t_spu id，不存在 id 均被布隆拦截——预期无空值缓存；若布隆未加载（降级）则走空值缓存路径，记 TTL 5min）**
- ③ **逻辑过期异步刷新**：`UPDATE my_xhs_product.t_spu SET name='逻辑过期改' WHERE id={spuId}`（**绕过缓存直接改 DB**）→ 立即 GET 详情 → 旧值（缓存未逻辑过期）；`DEL myxhs:product:spu:{spuId}` → GET → 新值
- ④ **物理 TTL**：TTL myxhs:product:spu:{spuId} ≈ 7200s（2h 物理兜底）
- ⑤ **恢复**：改回原 name + evictSpuCache（执行记录标注）

### G3-01-08 列表 + 分页 + 分类树
- `GET /api/product/spu/list?pageNum=1&pageSize=10`（带 JWT）→ 200；仅 status=1；total 字符串（R4）；orderByDesc(createdAt)（最新在前）
- `GET /api/product/spu/list?categoryId={分类id}` → 仅该分类
- pageSize=100 → **钳制为 50**（Math.min，非报错）；pageNum=0 → 钳制为 1
- **列表限流**：连打 61 次 → 第 61 次 **40202**（@RateLimit 60次/60s perUser）；清理限流 key
- **分类树**：`GET /api/product/category/tree` → 200；**缓存 2h**：`myxhs:product:category:tree` TTL≈7200；**缓存一致性（③ 操纵）**：SQL 改 t_category 名称 → 立即查仍旧值（缓存）→ DEL 缓存 key → 新值（记录）

### G3-01-09 批量 SKU 详情（内部接口鉴权）
- ① **外部调用（无 X-Internal-Call）**：`GET /api/product/sku/batch?skuIds={sku1},{sku2}`（带 JWT）→ **403"仅限内部服务调用"**（内部接口实证）
- ② 内部调用（带 X-Internal-Call：tokens.env INTERNAL_TOKEN）→ 200；仅返回 ON_SHELF 的 SKU
- ③ skuIds>100 或空 → **40002"skuIds 数量必须在 1-100 之间"**
- **说明**：该接口是 cart 列表的 Feign 依赖——鉴权错误将导致购物车列表失效（G3-02 联动）

### G3-01-10 商品不存在/越界
- GET /api/product/spu/999999999999（带 JWT）→ **30001**（布隆拦截路径）
- GET /api/product/sku/999999999999（带 JWT）→ **30002**（SKU 不存在）
- **L2**：Redis 无污染 key（布隆拦截不写缓存）

### G3-01-11 管理端点鉴权（X-Admin-Call 双层）
- ① 带 JWT **无 X-Admin-Call** 调 POST /api/product/spu → **403"仅限管理员操作"**
- ② 带 JWT + 错误 X-Admin-Call → **403**
- ③ 带 JWT + 正确 X-Admin-Call → 200
- ④ **无 JWT 无签名** 经 gateway 调读接口 GET /api/product/spu/{id} → **401**（JWT 白名单不含 product——与初稿"公开"相反，实证）
- ⑤ 无 JWT 调写接口 → 401（AuthFilter 先拦）
- ⑥ **直连 product(19006) 读接口（无 JWT）** → **200**（端口信任模型：服务自身无鉴权拦截器，search→product Feign 依赖此；对照 G1-06-09）
- **对照**：经 gateway GET /api/product/spu/{id} 带 JWT 无 HMAC 签名 → **200**（hmac 白名单放行——管理/读接口免签）

### G3-01-12 HotSkuDetector 热点（矩阵 #32）
- **代码实证（本轮核实）**：`inventory:hot:window:{skuId}` ZSet（member=秒级时间戳:线程id:nanoTime 防覆盖）；窗口 **10s**（WINDOW_SECONDS=10，非文档 30s）、阈值 **100**（HOT_THRESHOLD=100）、TTL **30s**
- **触发点（关键）**：**inventory 预扣库存 preDeduct 内**（InventoryService:235 `recordAndCheck(skuId) && bucketCount < hotBucketCount` → 异步扩容桶）——**不是查询/详情路径**！
- **preDeduct 是内部接口**（需 X-Internal-Call：`/api/inventory/preDeduct` 403"仅限内部服务调用"）——外部不可直接调
- **用例设计（运行态实证修正）**：
  - **触发限制**：recordAndCheck 在**库存初始化检查之后**（InventoryService:224 未初始化直接 40002"库存未初始化"→ 235 才 recordAndCheck）——**未 init 的 SKU 无法触发热点检测**；完整扩容行为归 G5（需先 init）
  - L2 已实证：member 格式=`时间戳:线程id:nano`、score=秒级时间戳、TTL 30s 语义、ZCARD 计数（注入 100 条验证）
  - ③ 操纵（G5 内做）：init 后 ZADD 100 条 → preDeduct → 观察"[库存] 热点扩容"
- **矩阵 #32 修正**：交接文档/矩阵写"30s 窗口"有误，实际 WINDOW_SECONDS=10（**已在本轮核实**）

### G3-01-13 逻辑删除 SPU（T-042 回归）
- **代码实证**：ProductController **无 @DeleteMapping**（SPU 无删除端点）——逻辑删除只能 SQL 操纵
- **入口**：SQL `UPDATE my_xhs_product.t_spu SET deleted=1 WHERE id={spuId}`（操纵快照：记录原 deleted）
- **L2**：
  ```
  SELECT deleted FROM t_spu → 1
  GET /api/product/spu/{spuId}（带 JWT）→ 30001（@TableLogic 过滤 deleted，loadSpuDetailFromDb selectById 返回 null）
  # T-042 回归：sleep 1-2s → product_index 该 spu 文档 status=-1（**T-042 语义=标记删除非物理删**——deleteProduct 写 status=-1，搜索时过滤；初稿"文档已删除"表述修正）
  ```
- **恢复**：UPDATE deleted=0 + 重索引（canal UPDATE 事件 → status 恢复真实值）；**注意**：布隆过滤器已含该 id（创建时 afterCommit add），deleted 恢复后详情可查

### G3-01-14 创建 SPU 幂等（@Idempotent 10s）
- 同参数（name+categoryId 相同）10s 内重复 POST /api/product/spu → **40201"请勿重复提交"**（@Idempotent 实证；key=spu:create:name:categoryId）
- 10s 后同参数重发 → 200（**但会创建重复 SPU**——幂等窗口过期，登记观察：无业务去重）
- **L2**：t_spu 行数记录

### G3-01-15 写端点限流（5次/60s）
- createSpu 连打 6 次（不同 name 避开幂等）→ 第 6 次 **40202**；`ZCARD myxhs:product:create:ProductController:createSpu:{uid}`=5
- updateSpuStatus 连打 6 次（不同 status 或循环 0/1）→ 第 6 次 40202
- **清理**：DEL 对应限流 key（每个端点独立）

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G3-01-01~15 | 2026-08-15 11:54~12:02 | ✅ 15/15 | 回归（Task9 后）；幂等40201/窗口过期200/限流5次/逻辑删除T-042/布隆拦截 全部验证 |

## 断言关键词速查
- 200 成功 / 30001 商品不存在 / 30002 SKU 不存在 / 30003 已下架 / 40002 参数
- 关键 L2：t_spu/t_sku 落库、myxhs:product:spu:{id} 缓存、product_index ES、布隆拦截、TTL 2h、HotSku window TTL 30s

## 深度 REVIEW 补充（2026-08-13 第二轮，全量代码核对后收敛）
### 本轮 L0/L1 已核（代码实证，消除初稿全部"实测"模糊项）
- ✅ **createSpu 默认 status=ON_SHELF(1)**；createSku 默认 status=1、stock 默认 0
- ✅ **下架 SPU 详情 → 30001**（loadSpuDetailFromDb 过滤 status!=ON_SHELF 返回 null → PRODUCT_NOT_FOUND）；列表仅 ON_SHELF
- ✅ **updateSpu/updateSpuStatus/createSku/createSpu 全部需要 X-Admin-Call**（isAdminCall）；无 → 403"仅限管理员操作"
- ✅ **batchGetSkuDetails 需 X-Internal-Call**（内部接口）+ skuIds 1-100
- ✅ **product 读接口均需 JWT**（JWT white-list 不含 /api/product/**）；免 HMAC（hmac-white-list 含 spu/sku/category）
- ✅ **updateSpu 延迟双删**（afterCommit 立即删 + 1s 二次删）；updateSpuStatus afterCommit 删缓存（无二次删）
- ✅ **createSku afterCommit evictSpuCache**（SKU 列表变化清 SPU 缓存）
- ✅ **HotSkuDetector**：窗口 10s（矩阵 #32 的 30s 有误）、阈值 100、TTL 30s、触发点=inventory preDeduct（非详情/查询）
- ✅ **createSpu 分类校验**：分类不存在 → 40002"分类不存在"
- ✅ **listSpus @RateLimit 60次/60s**；写端点 5次/60s；createSpu @Idempotent 10s
- ✅ **SKU 详情不过滤下架**（SkuService.getSkuDetail 无 status 判断）——**登记观察项**（下架 SPU 的 SKU 详情仍可查，可能用于下单前校验?G5 关联）
- ✅ **t_sku.stock 是冗余占位**（创建时写入后从不更新；真实库存走 inventory）——G3 不验证一致性
- ✅ **SPU 无删除端点**（无 @DeleteMapping）——逻辑删除仅 SQL 操纵
- ✅ **30003 商品已下架错误码在 product 读路径未使用**（下架→30001）——登记观察（G5 下单路径可能使用）

### 待 L2 运行态确认（无法纯静态判定）
- [ ] ES product_index 文档实际结构验证（**已由 mapping 实证=SPU 粒度**，运行态确认字段值）——含 status=-1 标记语义
- [ ] inventory /api/inventory/stock/{skuId} 未初始化响应（initialized=false?）
- [ ] 布隆过滤器未加载（降级）时空值缓存行为（TTL 5min）
- [ ] HOT 扩容的 hotBucketCount 具体行为（G5 交易域验证）
- [ ] product 详情缓存反序列化正确性（activateDefaultTyping NON_FINAL 已实证安全，运行态观察）

## 第三轮补充（2026-08-13，二次全量核对）
- ✅ **ES product_index=SPU 粒度**（handleCanalMessage 只处理 t_spu；t_sku 变更忽略）；mapping：spuId/name/categoryId/categoryName/brandName(恒null)/price/image/sales(恒0)/status/createdAt——**无 description/规格**
- ✅ **price=skuList.get(0).price**（首 SKU 价，注释"最低售价"与代码不符——登记观察）
- ✅ **T-042 deleted 语义=ES status=-1 标记**（deleteProduct 写 status=-1 非物理删；搜索时过滤）——**修正初稿"删除文档"表述**
- ✅ **product 服务无鉴权拦截器**——服务间 Feign 直连公开（search→product getSpuDetail 无 X-Internal-Call 配置）；仅 gateway 入口强 JWT（端口信任模型）
- ✅ **布隆过滤器 @PostConstruct 启动时全量加载**；创建 SPU afterCommit 增量 add；加载失败降级放行
- ✅ **Redis 序列化安全**：activateDefaultTyping(NON_FINAL, As.PROPERTY) 带 @class——RedisCacheData<SpuDetailVO> 泛型还原无 T-045 风险（L1 推断，L2 观察）
- ✅ **数据库表结构核对**：t_spu/t_sku/t_category 均有 deleted 字段（@TableLogic 逻辑删除）；t_sku.specs varchar(1024)；t_cart_item 有 uk_user_sku 唯一索引语义（consumer UPSERT 依赖）
- ✅ **ES 索引已存在**（mapping 查询实证；此前 product_index/_count=21 文档为旧数据——回归前清理）

### 已知风险
- product 曾有 prometheus 67s 慢（已修，回归时留意）
- 改动 product 相关代码必须 rm -rf target 重打包（交接纪律 §七-2）
- **执行时每用例带 X-Admin-Call（写）+ JWT（读写）**——参数校验断言在 admin 鉴权之后，先带全头再测负面
- ES product_index 有历史文档（旧数据 status 可能不干净）——回归前 `_delete_by_query` 清理
