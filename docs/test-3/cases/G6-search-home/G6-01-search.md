# G6-01 搜索用例（笔记/商品搜索 + 建议 + 历史 + 热搜 + 索引重建/补偿）

> 组：G6 搜索首页 | 服务：search(19016) + 联动 content/product（canal→MQ）| 入口：**gateway(19000)**
> 依赖：G1 登录 + G2 笔记发布（t_note 有已发布数据）+ G3 商品（SPU+SKU 上架）
> 时间引用：矩阵 **#2**（热搜 60s、增量补偿 60s）、**#19-21**（xxl 触发）、**#23**（suggest 缓存 1h）、**#24**（空缓存 5min）、F 节（ES 可见性 1-2s）
> 前提：search UP、ES 19200 三索引存在、canal note_instance/product_instance 运行中、xxl 19/20/21 已启用

## 代码实证（2026-08-14，G6 梳理全量核实）

### 端点与安全（gateway:19000 → search:19016）
> **⚠️ REVIEW 运行态修正（2026-08-14 实测）**：/api/search/note|product|suggest **不在 JWT white-list**（本地 yml 仅注释 `# - /api/search/**`；Nacos my-xhs-gateway.yaml 只覆盖 jwt/hmac secret 不含 white-list）——**需 JWT（无 token → 401），但免 HMAC**（hmac-white-list 含）；原文档"公开双白名单"错误，已按运行态修正
| 端点 | 鉴权（运行态） | 说明（代码实证） |
|---|---|---|
| GET /api/search/note | **JWT（免 HMAC）** | CompletableFuture 异步；keyword/sort/size/searchAfter；自动记录热搜+搜索历史 |
| GET /api/search/product | **JWT（免 HMAC）** | 同异步；keyword/categoryId/minPrice/maxPrice/sort/searchAfter |
| GET /api/search/suggest | **JWT（免 HMAC）** | prefix；Completion Suggester + Redis 缓存 1h（空 5min）|
| GET /api/search/history | JWT + HMAC | `X-User-Id` 取用户；List 最近 20 条 |
| DELETE /api/search/history | JWT + HMAC | 清空 |
| DELETE /api/search/history/{keyword} | JWT + HMAC | 删单条（LREM）|
| GET /api/search/hot | JWT + HMAC | 热搜榜 Top50（置顶优先+爆/热/新标签）|
| POST /api/search/hot/record | JWT + HMAC | 前端主动记录搜索词（同 Lua 反作弊）|
| PUT /api/search/hot/pin | **JWT + X-Admin-Call**（HMAC 豁免）| 置顶 |
| DELETE /api/search/hot/pin | **JWT + X-Admin-Call** | 取消置顶 |
| PUT /api/search/hot/block | **JWT + X-Admin-Call** | 屏蔽（同时从实时榜移除）|
| DELETE /api/search/hot/block | **JWT + X-Admin-Call** | 取消屏蔽 |
| GET /api/search/hot/snapshot | JWT + HMAC | 历史快照（date=yyyy-MM-dd 范围查询）|
| POST /api/search/index/rebuild | **JWT + X-Admin-Call** | 手动全量重建（异步，锁 2h，断点续传）|

> 管理端点 ADMIN_TOKEN 从环境变量注入（myxhs.admin.token），不带/带错 → 403"仅管理员可执行此操作"
> **gateway 限流（第三轮 REVIEW 实证）**：RateLimitFilter 委托 Sentinel GatewayFlowRule——**route ID 维度、1s 窗口**，search=300/home=50/recommend=100 QPS（metadata 唯一数据源）；限流响应 **HTTP 429 + {"code":429,"message":"请求过于频繁，请稍后再试"}**——非 @RateLimit（Redis 60s 窗口）语义，测试勿混淆

### 笔记搜索（NoteSearchService，代码实证）
- 查询：multi_match（title^3 + content，ik_smart）+ **filter status=2**（只搜已发布）
- 排序：relevance（_score）/ time（createdAt DESC, noteId DESC）/ hot（likeCount DESC, noteId DESC）
- 分页：size 默认 20 上限 50（normalizeSize）；Search After（**sort 值序列化 JSON 数组**，翻页传 searchAfter 原样回传）
- 高亮：title/content `<em>` 包裹（content fragmentSize=150）
- 搜索历史：`Lua(LREM+LPUSH+LTRIM+EXPIRE)` 写 `myxhs:search:history:{uid}`（20 条、30 天）；userId!=null 且 keyword 非空才记录
- **热搜记录**：searchNotes 异步任务内 `recordSearchKeyword`（搜索即记录，与 /hot/record 同入口）
- 失败降级：ES 异常 → 返回空 items/total=0（**HTTP 200 + code 200**，非 500）

### 商品搜索（ProductSearchService，代码实证）
- 查询：match name（ik_smart）+ **filter status=1**（上架）+ categoryId + price range（gte/lte）
- 排序：relevance / price_asc（price,spuId）/ price_desc / sales（sales,spuId）
- 文档字段：spuId/skuId/name/categoryId/categoryName/brandName/price（scaled_float）/image/sales/status/createdAt
- 高亮：name 字段

### 建议（SuggestService，代码实证）
- ES Completion Suggester（field=keyword，analyzer=ik_max_word，size=10，skipDuplicates）
- 缓存 `myxhs:search:suggest:cache:{md5(prefix)}`：命中直接返回；空结果缓存 5min（防穿透）；非空 1h
- 超长 prefix 截断 50；空/空白 → 空列表

### 搜索历史（SearchHistoryService，代码实证）
- key `myxhs:search:history:{uid}`（List）；Lua 保证 LREM 去重 + LPUSH 置顶 + LTRIM 20 + EXPIRE 30 天

### 热搜（HotSearchService，代码实证）
- **记录 Lua（原子）**：① 屏蔽词 SISMEMBER（SEARCH_HOT_BLOCKED）→ -1 拦截；② IP 限频 INCR `myxhs:search:antispam:ip:{ip}`（>10/分钟 → 0 拦截）；③ 用户同词 300s（`myxhs:search:antispam:user:{uid}:{keyword}` 或 anon:{ip}:{keyword}）→ 0 拦截；④ HINCRBY 分钟桶 `myxhs:search:window:{yyyyMMddHHmm}`（TTL 2h）
- **计算 @Scheduled fixedRate=60s**（注释"测试环境快速验证"）：Redisson 锁 `myxhs:lock:job:search:hot:calculate`（600s lease）→ Pipeline 取最近 60 个桶 → **Score=Σ(count×e^(-0.1×Δt))** → 过滤屏蔽词 → Top50 → 临时 key RENAME 原子替换 `myxhs:search:hot:realtime`（TTL 1h）→ 快照写 t_hot_search_snapshot（content 库）
- **榜单**：置顶词优先（score=0、tag=置顶、pinned=true，不占 topSize 计数）+ 实时榜（排除已置顶）；标签：rank≤3 且 score≥max×0.8 → 爆；rank≤10 → 热；否则新
- **快照查询**：date 严格校验（LocalDate.parse，非法 → 空列表）；snapshot_time 范围 [当天0点, 次日0点) ORDER BY snapshot_time DESC, rank_no ASC LIMIT 50

### 索引全量重建（IndexRebuildJob，代码实证）
- 触发：@Scheduled cron 每天4点（search.rebuild.cron）+ **POST /api/search/index/rebuild**（CompletableFuture.runAsync + 锁 `myxhs:lock:job:search:index:rebuild` tryLock 5s/lease 7200s）
- 阶段（每段独立 try-catch，一段失败不影响其他）：
  1. **note_index**：`SELECT id,user_id,title,content,cover_url,status,created_at FROM t_note WHERE id>? AND deleted=0 ORDER BY id ASC LIMIT 500`（断点 lastNoteId）
  2. **suggest_index**：`SELECT id,title FROM t_note WHERE id>? AND deleted=0 AND status=2`（标题非空；doc id=`note_{noteId}`；keyword/weight=1）
  3. **product_index**：`SELECT ... FROM my_xhs_product.t_spu WHERE id>? AND deleted=0`（**跨库前缀 #59**；categoryName/price/sales 全默认值——观察项）
- 断点：全部成功才推进（部分失败 break 不推进）；状态 `myxhs:search:index:rebuild:status`（Hash：lastNoteId/lastSpuId/totalIndexed/status=COMPLETED/completedAt，TTL 1d）
- 前置检查：t_note 不存在 → 跳过（log"data sync not ready"）

### 增量补偿（IncrementalIndexSyncJob，代码实证）
- @Scheduled fixedRate=60s；消费失败 Set `myxhs:es:sync:failed:note|product`（消费者 catch 块写入，TTL 1h）
- distinctRandomMembers(50) → 从 MySQL 查最新（**note 无库前缀、product 跨库前缀**）→ Bulk 写 ES（**ExternalGte version=updated_at 毫秒**，与 canal ts 版本域统一 #58）→ 失败 SADD 回 Set

### canal → ES 同步（消费者，代码实证）
- note_instance：监听 `my_xhs_content.t_note` → NOTE_INDEX_TOPIC → NoteIndexSyncConsumer（**版本=canal ts 毫秒**；ExternalGte；deleted=1 → 标记删除 status=-1；UPDATE/INSERT 写全量字段）
- product_instance：监听 `my_xhs_product.t_spu,my_xhs_product.t_sku` → PRODUCT_INDEX_TOPIC → ProductIndexSyncConsumer（**只处理 t_spu 表**；Feign product 补全 categoryName/price=首SKU/image；deleted=1 → status=-1）
- 幂等：doc id=noteId/spuId，upsert 天然幂等

### 错误码
200 成功 / 403 管理员鉴权失败 / 401 无 JWT / 40002 参数（ES 搜索无强校验——非法 size 自动截断）

## 前置准备（执行时记录快照）
```python
from testlib import *
# 1. 注册用户 g6s_（搜索用户，g6a_ 作者用于发笔记——复用 G2 已发布笔记亦可）
# 2. 数据：G2/G3 已有 t_note（status=2）若干 + t_spu（status=1）+ SKU；或新发布 2-3 条带独特关键词（如 g6sea关键词）
# 3. 清理：
#    - Redis: DEL myxhs:search:history:{uid} myxhs:search:hot:realtime myxhs:search:hot:pinned myxhs:search:hot:blocked
#      + SCAN myxhs:search:window:* myxhs:search:antispam:* 后 DEL（注意 window 是分钟桶，清理避免旧桶污染热度）
#    - ES: 查总数基线（note_index/product_index/suggest_index count 记快照）
#    - MySQL: t_hot_search_snapshot 清理 g6 测试行（DELETE WHERE keyword LIKE 'g6%'）
```

## 用例清单

### G6-01-01 笔记搜索全链路（核心）
- **前置**：t_note 有 2 条已发布笔记（status=2，标题含关键词 `g6sea关键词`；1 条草稿 status=0 作对照）+ 1 条已删（deleted=1）作对照
- **入口**：`GET /api/search/note?keyword={g6sea关键词}`（**JWT，免 HMAC**——运行态实证非公开）
- **L1**：200；data.items 非空；每条含 noteId/title/content/coverImage/likeCount/createdAt；data.total ≥ 2；data.hasMore 按 size 判断
- **L2**（sleep 1-2s）：
  ```
  # ① ES note_index 核对：GET note_index/_doc/{noteId} → title/content 与 DB 一致
  # ② 过滤正确性：返回 items 不含草稿(status=0)/已删(deleted=1)（filter status=2 + 删除标记 -1）
  # ③ 标题命中优先：构造标题含关键词 vs 仅内容含关键词 → 标题命中在前（title^3 权重）
  # ④ 搜索历史已记录：LRANGE myxhs:search:history:{uid} 0 -1 → 含该关键词（仅登录时；公开请求无 userId 不记录）
  ```
- **🔍 人工观察**：Kibana 按 traceId 查 search 日志（esClient 查询耗时 took 字段）
- **清理**：DEL 搜索历史 key

### G6-01-02 笔记搜索排序与深分页（Search After）
- **前置**：构造 ≥3 条已发布笔记（created_at 不同、likeCount 不同——likeCount 走 counter，直接操纵 Redis 计数 `myxhs:counter:1:{noteId}:1` 或依赖 G2 数据）
- **入口**：`GET /api/search/note?keyword=X&sort=time&size=2` → **L1**：items=2、searchAfter 非空
- 翻页：`GET /api/search/note?keyword=X&sort=time&size=2&searchAfter={上页 searchAfter}` → 无重复、按 createdAt DESC 衔接
- **L2**：sort=hot → likeCount 降序；sort=relevance（默认）→ _score 降序；**searchAfter 翻页到尾页 → items 空 + hasMore=false**
- **负面**：size=999 → 截断 50（normalizeSize 实证）；size=-1/0 → 默认 20
- **清理**：无（只读）

### G6-01-03 笔记搜索高亮
- 搜索含关键词的笔记 → items 内 highlightTitle/highlightContent 含 `<em>关键词</em>`；仅标题命中时无 highlightContent
- **负面**：无关关键词 → items 空 + total=0 + HTTP 200（降级非 500）

### G6-01-04 商品搜索全链路
- **前置**：G3 已有 SPU（status=1，name 含 `g6product关键词`，SKU 有价）+ 1 个下架 SPU（status=0）作对照
- **入口**：`GET /api/search/product?keyword={g6product关键词}`（**JWT，免 HMAC**）
- **L1**：200；items 含 spuId/name/categoryId/categoryName/price（scaled_float）/image/sales；total≥1
- **L2**：过滤正确性——不含下架 SPU（filter status=1）；**price 与 SKU 首价一致**（product_index 文档核对）
- **筛选**：`&categoryId=X` → 仅该分类；`&minPrice=10&maxPrice=100` → 价格区间命中
- **清理**：无

### G6-01-05 商品搜索排序
- **入口**：`GET /api/search/product?keyword=X&sort=price_asc` → price 升序（同价按 spuId）；`sort=price_desc` → 降序；`sort=sales` → sales 降序
- **L2**：价格排序需构造 ≥2 个不同价 SPU（G3 建 2 个 SKU 价 9.9/99.9 的 SPU）
- **清理**：无

### G6-01-06 搜索建议（suggest）
- **前置**：索引重建已跑过（G6-01-12）或先触发一次，使 suggest_index 有数据（t_note status=2 标题）
- **入口**：`GET /api/search/suggest?prefix={标题前2字}`（**JWT，免 HMAC**）
- **L1**：200；data 为 string 列表，含预期标题词
- **L2**：① 缓存写入 `myxhs:search:suggest:cache:{md5}` 存在（TTL≈3600）；② 二次请求仍正确（命中缓存）；③ **空结果缓存 5min**（TTL≈300）；④ prefix 空白 → data=[]
- **负面**：prefix 超长（>50 字符）→ 截断不报错
- **注意**：suggest_index 无增量——**新发布笔记标题必须重建后才出现**（登记观察项）；测试后 DEL 缓存 key

### G6-01-07 搜索历史（记录/去重/裁剪/删除）
- **入口**（JWT+HMAC）：
  - ① 搜索 2 个词 → `GET /api/search/history` → 2 条（最近在前）
  - ② 再搜旧词 → 去重置顶（LREM+LPUSH 实证：列表头部、无重复）
  - ③ 连续搜 >20 词 → 仅保留最近 20 条（LTRIM 实证，Redis LLEN=20）
  - ④ `DELETE /api/search/history/{keyword}` → 该词移除；⑤ `DELETE /api/search/history` → 空列表
- **L2**：key `myxhs:search:history:{uid}` TTL≈2592000（30 天）；`DEL` 后查询 → []
- **负面**：无 token → 401；无签名 → 403（HMAC）
- **清理**：DEL key

### G6-01-08 热搜记录与反作弊（Lua 原子）
- **入口**：`POST /api/search/hot/record?keyword={kw}`（JWT+HMAC）
- **L1**：200
- **L2**：
  ```
  # ① 分钟桶写入：HGET myxhs:search:window:{yyyyMMddHHmm} {kw} → 1；桶 TTL≈7200
  # ② 用户限频：同用户同词 5s 内再 record → 桶计数仍 1（antispam user key 300s 拦截，Lua 返回 0）
  # ③ IP 限频：同 IP 连打 11 个不同词 → 第 11 次被拦（myxhs:search:antispam:ip:{ip} INCR>10）
  #    注意：本机出口 IP 相同——不同用户同 IP 也共享计数（ipKey 不带用户）
  # ④ 屏蔽词：先 block（G6-01-10）→ record 该词 → 桶不计数（SISMEMBER 返回 -1）
  ```
- **清理**：DEL `myxhs:search:antispam:user:*`、`myxhs:search:antispam:ip:*`、`myxhs:search:window:*`（防污染榜断言）

### G6-01-09 热搜榜（计算 + 标签 + 置顶）
- **前置**：G6-01-08 已构造 3-4 个词计数（或直接往 2 个分钟桶 HINCRBY 造数，注意桶 key 格式 yyyyMMddHHmm）
- **等待**：等 calculateHotSearch 周期（@Scheduled 60s，等 60-90s）——**不要手动触发（无 API，锁在进程内）**
- **入口**：`GET /api/search/hot`（JWT+HMAC）
- **L1**：200；data 含 keyword/rank/score/pinned/tag；计数最高词 rank=1；pinned=false、tag∈{爆,热,新}
- **L2**：① `myxhs:search:hot:realtime` ZSet 存在（TTL≈3600）；② 分数符合指数衰减（同词最新桶贡献 e^0=1×count）；③ **快照落库**：t_hot_search_snapshot 该分钟有行（rank/score/search_count/snapshot_time）
- **负面**：未登录 → 401；无 HMAC → 403
- **清理**：DEL realtime/window 相关 key + t_hot_search_snapshot 测试行

### G6-01-10 热搜管理（置顶/屏蔽 + 管理鉴权）
- **入口**（JWT + **X-Admin-Call** 头，值=ADMIN_TOKEN）：
  - ① `PUT /api/search/hot/pin?keyword=X` → 200；L2：`SISMEMBER myxhs:search:hot:pinned X`=1
  - ② `GET /api/search/hot` → X 在榜首（pinned=true、tag=置顶、rank=1）
  - ③ `PUT /api/search/hot/block?keyword=Y` → 200；L2：blocked Set=1 + **实时榜已移除**（ZREM 实证）
  - ④ `GET /api/search/hot` → Y 不出现；record Y → 反作弊拦截（Lua 步骤④）
  - ⑤ `DELETE /api/search/hot/block?keyword=Y` → 可再记录上榜；⑥ `DELETE /api/search/hot/pin?keyword=X` → 置顶移除
- **负面（鉴权）**：无 X-Admin-Call → 403"仅管理员可执行此操作"；X-Admin-Call=错误值 → 403；**无 token → 401**
- **清理**：DEL pinned/blocked Set

### G6-01-11 热搜快照查询
- **前置**：G6-01-09 已产生快照（或用 SQL 直接插一条 g6 测试快照行，snapshot_time=今天）
- **入口**：`GET /api/search/hot/snapshot?date={今天 yyyy-MM-dd}`（JWT+HMAC）
- **L1**：200；data 含 keyword/rank/score；与当天快照一致（ORDER BY snapshot_time DESC, rank_no ASC）
- **负面**：date=非法（如 `2026-13-99`）→ data=[]（严格格式校验实证）；date=无数据日期 → data=[]
- **L2**：快照行查询验证（snapshot_time 范围查询非 DATE() 函数）
- **清理**：无

### G6-01-12 索引全量重建（管理端点，核心）
- **前置**：t_note 有 ≥5 条（含草稿/已删）+ t_spu 有 ≥2；**⚠️ 先清理 suggest_index 残留（`_delete_by_query`——重建是 upsert 不清旧文档，2026-08-14 实测残留 47 条）**；ES 三索引当前数据基线记录
- **入口**：`POST /api/search/index/rebuild`（JWT + X-Admin-Call）
- **L1**：200"索引重建任务已启动"（异步返回）
- **L2**（等 5-15s——500 批/秒级）：
  ```
  # ① 断点状态：HGETALL myxhs:search:index:rebuild:status → status=COMPLETED、totalIndexed>0、lastNoteId/lastSpuId=最大 id
  # ② note_index：count 与 t_note deleted=0 行数一致；status 字段=DB status（草稿 0/已发布 2）
  # ③ suggest_index：count=已发布(status=2)非空标题笔记数（清理残留后）；doc id=note_{id}；keyword=title
  # ④ product_index：count=my_xhs_product.t_spu deleted=0 行数
  ```
- **幂等/锁**：连续触发两次 → 第二次"任务正在执行中，跳过本次手动触发"（tryLock 5s 实证）或执行完再触发可再跑
- **负面**：无 X-Admin-Call → 403；无 token → 401
- **清理**：记录基线（重建后 ES 计数=基线+新数据）；执行后 DEL rebuild:status key（1d 自动过）

### G6-01-13 增量补偿（canal 失败 → failed Set → 60s 补偿）
- **构造失败**：SADD `myxhs:es:sync:failed:note` {不存在的 noteId 如 999999999999} + SADD `myxhs:es:sync:failed:product` {不存在 spuId}
- **等待**：等 IncrementalIndexSyncJob 60s 周期（等 60-90s）
- **L2**：① 补偿执行后失败 Set 被移除（查询 MySQL 无该行 → log"笔记数据不存在，可能已被删除"）；② 补偿成功场景：停 product 服务制造 canal 消费失败（**高风险，慎做**）→ failed Set 记录 → 恢复 product → 补偿把 SPU 索引写全
- **L2 正常路径核对**：补偿 Bulk 的 version=updated_at 毫秒（ExternalGte，#58 版本域一致）
- **清理**：DEL failed Set key

### G6-01-14 canal→ES 实时同步（note/product 状态流转）
> G2-01 已验发布→note_index；本用例聚焦**状态变更流转 + product 域**
- **① 下架**：G3 已上架 SPU → product 下架（updateSpuStatus=0）→ sleep 1-2s → ES product_index `_doc/{spuId}` status=0（filter status=1 后搜索不可见）
- **② 逻辑删除**：G2 笔记删除 → note_index status=-1（标记删除实证）→ 搜索 keyword 不含该笔记
- **③ SPU 逻辑删除**：SPU deleted（若产品接口支持）→ product_index status=-1
- **④ 编辑**：改笔记 title → note_index title 更新（ExternalGte 版本防旧覆盖）
- **L2**：ES `_version` 与 canal ts（毫秒）对比——**新写入 version 必须 ≥ 旧值**（#58 防乱序实证）
- **注意**：canal 消息延迟（binlog→MQ→消费）秒级，sleep 2-3s 再断言
- **⚠️ product 侧附加断言（观察项验证）**：记录 product_index `_doc/{spuId}` 的 `_version`（canal es 小整数）→ 触发增量补偿写该文档（G6-01-13 构造 failed Set）→ 再改 SPU 触发 canal → **若 canal 写入被拒（version 不回增）即复现 #58 残留**（见 REVIEW 风险项）

### G6-01-15 搜索降级（ES 不可用场景——标注不实测）
- **L1 代码实证**：searchNotes/searchProducts catch Exception → 空 items + total=0 + HTTP 200（降级不报错）
- 运行态不构造（停 ES 影响面大）——登记 L1 结论

### G6-01-16 搜索端点鉴权矩阵（安全）——按运行态修正
- ① `GET /api/search/note|product|suggest` 无 token → **401**（JWT white-list 未含 search——运行态实证；**免 HMAC**：带 JWT 无签名 → 200）
- ② `GET /api/search/hot`、`GET /api/search/history` 无 token → **401**；带 token 无 HMAC → **403**
- ③ `POST /api/search/index/rebuild` 带 token 无 X-Admin-Call → **403**；带错误值 → **403**；无 token → 401
- ④ `POST /api/search/hot/record` 无签名 → 403
- ⑤ 未知路径 `/api/search/no-such` → 404（T-059 回归）

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G6-01-01 笔记搜索全链路 | 15:55 | ✅ | total/highlight/history；**断言修正：total 为字符串（R4 int()）** |
| G6-01-02 排序与深分页 | 15:56 | ⚠️（T-092） | time/size 边界/降级 ✅；**hot 排序=缺陷行为（ES likeCount 恒 0→noteId 降序，T-092 待修）**；searchAfter 编码教训；T-085 |
| G6-01-03 高亮 | 15:59 | ✅ | title^3 权重实证（内容命中排后） |
| G6-01-04 商品搜索 | 16:00 | ✅ | price=首SKU 99.9/9.9、categoryId、价格区间；**VO 无 status 字段（断言修正）** |
| G6-01-05 商品排序 | 16:01 | ✅ | price_asc/desc/sales |
| G6-01-06 suggest | 16:02 | ✅ | 缓存 1h/空 5min（先执行 12 重建填充 suggest_index） |
| G6-01-07 搜索历史 | 16:03 | ✅ | 去重置顶/20 条裁剪/单删/清空/30 天 TTL |
| G6-01-08 热搜反作弊 | 16:04 | ✅ | 分钟桶 2h TTL、用户同词 300s、IP 10/min（IP key=127.0.0.1,127.0.0.1 逗号串） |
| G6-01-09 热搜榜 | 16:06 | ✅ | 爆/热标签、指数衰减 5/3/1、ZSet 1h、快照落库 |
| G6-01-10 热搜管理 | 16:07 | ✅ | pin/block/unpin/unblock/管理鉴权 403/屏蔽反作弊联动 |
| G6-01-11 快照查询 | 16:08 | ✅ | **T-089 快照重复行实证**；非法/空日期 → [] |
| G6-01-12 索引重建 | 16:01 | ✅ | 管理鉴权 403/触发/断点 COMPLETED（totalIndexed=5 与三索引实际写入不符——观察项）；**前置清理 suggest 残留 47** |
| G6-01-13 增量补偿 | 16:15 | ✅ | 不存在 id 补偿清 Set（60s 周期） |
| G6-01-14 canal→ES | 16:17 | ✅ | SPU 下架 status=0→搜索不可见→恢复；编辑 title 更新；**T-088：canal 无 es 字段→版本实际 ts 毫秒** |
| G6-01-15 降级 | — | L1 | 代码实证标注，不实测 |
| G6-01-16 鉴权矩阵 | 16:20 | ✅ | **鉴权矩阵按运行态修正（JWT white-list 未含 search——无 token 401，免 HMAC）**；未知路径 404 需 token+HMAC 才到路由层 |

## 断言关键词速查
- 200 / 401 未认证 / 403 HMAC或管理鉴权 / 降级空结果（HTTP 200 code 200）
- 关键 L2：note_index filter status=2、product_index filter status=1+price=首SKU、suggest 缓存 1h/空 5min、搜索历史 20 条 30 天、热搜窗口/榜/快照、rebuild 断点 COMPLETED、补偿 failed Set 清空、ExternalGte 版本

## 深度 REVIEW 补充（2026-08-14 第一轮 + 第二轮深度 REVIEW + 第三轮运行态 REVIEW，代码实证核对）
### L0/L1 已核
- ✅ 端点安全矩阵（13 端点：3 公开 / 6 JWT+HMAC / 4 管理 JWT+X-Admin-Call，HMAC 豁免仅管理端点）
- ✅ 笔记搜索查询策略（title^3+content、filter status=2、三排序、Search After JSON 数组、高亮 <em>）
- ✅ 商品搜索（filter status=1、categoryId/价格区间、四排序、scaled_float price）
- ✅ suggest（completion field=keyword、缓存 md5 key 1h/空 5min、前缀截断 50）
- ✅ 热搜全链路（Lua 反作弊三级、分钟桶 2h、60s 计算+锁、指数衰减 λ=0.1、置顶/屏蔽、快照表）
- ✅ 重建三阶段（note/suggest/product、断点续传、锁 2h、跨库前缀 #59、字段默认值观察项）
- ✅ 增量补偿（60s、SPOP 50、ExternalGte updated_at 毫秒、失败回填）
- ✅ canal 两实例（note: t_note；product: t_spu/t_sku 但只消费 t_spu）+ ts 版本域（#58）
- ✅ 搜索无 @RateLimit；gateway 路由 QPS search=300（实证）

### 风险/观察项（执行前关注）
- [ ] **t_item_feature 缺失**（属 G6-03 推荐域，README §风险 已登记）
- [ ] suggest_index 无增量——重建前新发布笔记不在建议中
- [ ] IndexRebuildJob 商品字段默认值（categoryName/price/sales=0）覆盖 canal 补全值（观察项，登记 T- 系列）
- [ ] 热搜快照重复行（同分钟多次计算重复插入——按 snapshot_time 取最新可接受）
- [ ] 热搜榜 GET 需 JWT（未登录前端无法看榜——产品语义确认项）
- [ ] **⚠️ product 侧 canal 版本域疑似 #58 残留**：NoteIndexSyncConsumer 已 P1-4 统一 `ts`（毫秒），但 ProductIndexSyncConsumer `es`（Canal 小整数序列号）优先、`ts` 降级（代码实证两消费者版本策略不一致）——若 IncrementalIndexSyncJob（updated_at 毫秒 ~1.7e12）先写过该 spuId 文档，后续 canal es（小整数）会被 ES ExternalGte 永久拒绝 → product_index 增量冻结。**G6-01-14 的 ②③ 需实测 ES `_version` 递增性；如复现登记 T-080+（修复=product 侧对齐 ts 版本域）**

### 待 L2 确认
- [ ] Search After 翻页衔接（sort=time 时 createdAt 相同记录不跳过）
- [ ] 热搜 60s 计算周期实测（@Scheduled 周期）
- [ ] 反作弊 IP 限频实测（同 IP 共享计数语义）
- [ ] 管理端点 403 文案与状态码
- [ ] rebuild 二次触发锁行为

### 第三轮运行态 REVIEW（2026-08-14，环境实测）
- ✅ **search/home 15 服务 UP**（actuator/health 实测）
- ✅ **xxl 组 11=my-xhs-home、组 12=my-xhs-search**（xxl_job_group 运行库确认——feedCleanupJob/18 归 home、recommend×3 归 search 执行器）
- ✅ **gateway 限流机制实证**：RateLimitFilter→Sentinel GatewayFlowRule（route ID/1s/429），search=300、recommend=100（yml metadata 运行态加载）
- ✅ **Redis G6 域 key 全空**（search/recommend/feed 前缀 SCAN 实证）——环境干净
- ⚠️ **ES 运行态**：note_index=0 / product_index=0 / **suggest_index=47 残留**（t_note=0 但 suggest 有历史文档——HANDOFF"双索引 0"未含 suggest；**执行前 `_delete_by_query` 清理，否则 G6-01-12 ③ 断言失真**）
- ⚠️ **t_note=0、my_xhs_product.t_spu=0**（当前数据全空）——G6 用例执行前必须 G2/G3 造数据
- ⚠️ **canal 11111 端口不可达**（可能容器内监听/未暴露）——canal 运行态执行前验证方法：发布笔记 → NOTE_INDEX_TOPIC 消费 → ES 文档出现（或 xxl/Kibana 查 canal 日志）
- ✅ **t_note 含 tags 列**（15 列实测）——recommendFeatureJob/ContentRecallStrategy 依赖有效
