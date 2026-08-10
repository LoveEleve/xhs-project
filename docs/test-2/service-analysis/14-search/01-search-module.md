# 14-search 搜索服务 — 架构文档

> 端口：19016 | 数据库：ES 8.12 + my_xhs_search | 更新时间：2026-07-30

---

## 1. 模块定位

搜索服务是 my-xhs 平台的统一搜索与推荐引擎，提供笔记/商品全文搜索、搜索联想、热搜榜单、个性化推荐四大核心能力。

核心能力：
- **ES 8.12 全文搜索**：笔记 + 商品双索引，IK 分词，Search After 深分页，高亮
- **搜索建议**：Completion Suggester + Redis 缓存
- **热搜榜单**：滑动窗口 + Lua 原子防刷 + 定时降级计算
- **个性化推荐**：4 层 Pipeline（召回→粗排→精排→重排），5 种召回策略
- **索引同步**：Canal → RocketMQ（NoteIndexSync/ProductIndexSync），ExternalGte 防乱序

---

## 2. 架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     客户端                                                │
│  搜索/商品/建议/热搜/推荐/行为上报/相似                                    │
└──────────────────┬──────────────────────────────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────────────────────────────┐
│               Gateway (19000) → my-xhs-search (19016)                   │
├──────────────────┬─────────────────────────────────────────────────────┤
│  SearchController  │  RecommendController                               │
│  /api/search/*     │  /api/recommend/*                                  │
└─────────┬─────────┴──────────┬──────────────────────────────────────────┘
          │                    │
┌─────────▼────────────────────▼────────────────────────────────────────┐
│                         Service 层                                     │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────┐ ┌─────────────┐     │
│  │NoteSearch│ │Product   │ │Suggest   │ │Hot   │ │Recommend    │     │
│  │Service   │ │Search    │ │Service   │ │Search│ │Service      │     │
│  │          │ │Service   │ │          │ │Service│ │(4层Pipeline)│     │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └──┬───┘ └──────┬──────┘     │
│       │            │            │          │             │            │
│       └────────────┴────────────┴──────────┴─────────────┘            │
│                            │                                          │
│               AbstractSearchService (模板方法)                        │
├────────────────────────────┼──────────────────────────────────────────┤
│                            ▼                                          │
│              Elasticsearch Client (ES 8.x)                            │
│  note_index(3shards) │ product_index(3shards) │ suggest_index(1shard)              │
│  IK ik_smart/ik_max_word           │ Completion Suggester            │
├───────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  索引同步: Canal → RocketMQ → NoteIndexSync/ProductIndexSync → ES    │
│     ExternalGte 版本控制防乱序 → 失败入 Redis Set → 增量Job补偿       │
│                                                                       │
│  推荐召回策略: ItemCF / Content / Hot / Following / Geo               │
│  离线计算: XXL-Job (ItemCF/Feature/HotPool)                          │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

---

## 3. 源码清单

| 文件 | 职责 |
|---|---|
| `SearchApplication.java` | 启动类 |
| `controller/SearchController.java` | 搜索/建议/历史/热搜/索引重建 |
| `controller/RecommendController.java` | 推荐 Feed/相似/行为上报/离线计算 |
| `service/NoteSearchService.java` | 笔记搜索（multi_match + 高亮 + 排序 + SearchAfter） |
| `service/ProductSearchService.java` | 商品搜索（分类/价格过滤） |
| `service/AbstractSearchService.java` | ES 搜索模板（SearchAfter 解析/搜索历史 Lua 脚本） |
| `service/SuggestService.java` | 搜索建议（Completion Suggester + Redis 缓存） |
| `service/HotSearchService.java` | 热搜（滑动窗口 + Lua 防刷 + 定时聚合） |
| `service/SearchHistoryService.java` | 搜索历史 CRUD |
| `service/RecommendService.java` | 推荐 4 层 Pipeline |
| `consumer/NoteIndexSyncConsumer.java` | Canal→MQ 笔记索引同步 |
| `consumer/ProductIndexSyncConsumer.java` | Canal→MQ 商品索引同步 |
| `consumer/BehaviorReportConsumer.java` | 用户行为上报 → MySQL |
| `job/IndexRebuildJob.java` | 全量重建（每天 4 点 SCAN DB → Bulk ES） |
| `job/IncrementalIndexSyncJob.java` | 增量补偿（每 5 分钟重试失败 Set） |
| `job/RecommendComputeJob.java` | XXL-Job 离线计算（ItemCF/Feature/HotPool） |
| `config/ElasticsearchConfig.java` | ES 8.x 客户端配置 |
| `config/IndexInitializer.java` | 启动自动创建索引 |
| `config/RecommendThreadPoolConfig.java` | 推荐线程池 |
| `recommend/*RecallStrategy.java` | 5 种召回策略 |

---

## 4. 数据模型

### 4.1 ES 索引

**note_index** (3 shards, 1 replica, IK 分词)

| 字段 | 类型 | 分词器 | 说明 |
|---|---|---|---|
| noteId | long | — | 笔记 ID |
| userId | long | — | 作者 ID |
| title | text | ik_max_word / ik_smart | 标题（搜索权重×3） |
| content | text | ik_smart | 正文 |
| coverImage | keyword | index: false | 封面图 |
| likeCount | long | — | 点赞数 |
| collectCount | long | — | 收藏数 |
| commentCount | long | — | 评论数 |
| status | integer | — | 1-正常 -1-删除 |
| createdAt | date | — | 创建时间 |

**product_index** (3 shards, 1 replica)

| 字段 | 类型 | 说明 |
|---|---|---|
| spuId | long | SPU ID |
| skuId | long | SKU ID |
| name | text (ik_max_word/ik_smart) | 商品名称 |
| categoryId | long | 类目 ID |
| categoryName | keyword | 类目名称 |
| brandName | keyword | 品牌 |
| price | scaled_float (factor=100) | 价格（精确到分） |
| image | keyword (index: false) | 主图 |
| sales | long | 销量 |
| status | integer | 1-正常 -1-删除 |
| createdAt | date | 创建时间 |

**suggest_index** (1 shard, 1 replica)

| 字段 | 类型 | 说明 |
|---|---|---|
| keyword | completion (ik_max_word/ik_smart) | 建议词 |
| weight | long | 权重 |

### 4.2 MySQL (my_xhs_search)

| 表 | 说明 |
|---|---|
| t_user_behavior | 用户行为（曝光/点击/点赞/收藏/评论/分享/停留） |
| t_item_feature | 物品特征标签（内容召回） |

### 4.3 Redis Key 设计

| Key 模式 | 类型 | TTL | 说明 |
|---|---|---|---|
| `myxhs:search:history:{userId}` | List | 30 天 | 搜索历史 20 条 |
| `myxhs:search:suggest:cache:{md5(prefix)}` | String | 1h（空结果 5min） | 建议缓存 |
| `myxhs:search:hot:realtime` | ZSet | 永久 | 热搜排名 |
| `myxhs:search:hot:pinned` | Set | 永久 | 置顶关键词 |
| `myxhs:search:hot:blocked` | Set | 永久 | 屏蔽关键词 |
| `myxhs:search:window:{yyyyMMddHHmm}` | Hash | 5min | 分钟级滑动窗口 |
| `myxhs:search:antispam:user:{userId}:{kw}` | String | 300s | 用户防刷 |
| `myxhs:search:antispam:ip:{ip}` | String | 60s | IP 防刷 |
| `myxhs:es:sync:failed:note` | Set | 1h | 索引同步失败笔记 |
| `myxhs:es:sync:failed:product` | Set | 1h | 索引同步失败商品 |
| `myxhs:search:index:rebuild:status` | Hash | 重建期间 | 全量重建进度 |
| `recommend:itemcf:{noteId}` | ZSet | 永久 | Item-CF 相似矩阵 |
| `recommend:hot:global` | ZSet | 永久 | 全局热池 |
| `recommend:user:tags:{userId}` | Hash | 永久 | 用户兴趣标签 |
| `recommend:seen:{userId}` | Set | 7d | 已曝光去重 |
| `user:geo:{userId}` | String | 永久 | 用户 GeoHash |

---

## 5. API 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/search/note` | 笔记搜索（keyword + sort + searchAfter + highlight） |
| GET | `/api/search/product` | 商品搜索（category + price 过滤） |
| GET | `/api/search/suggest?prefix=` | 搜索建议 Completion Suggester |
| GET | `/api/search/history` | 搜索历史（最近 20 条） |
| DELETE | `/api/search/history` | 清空历史 |
| DELETE | `/api/search/history/{keyword}` | 删除单条 |
| POST | `/api/search/index/rebuild` | 手动全量重建 |
| GET | `/api/search/hot` | 热搜 Top 50 |
| POST | `/api/search/hot/record` | 上报搜索词 |
| PUT/DELETE | `/api/search/hot/pin` | 置顶/取消 |
| PUT/DELETE | `/api/search/hot/block` | 屏蔽/取消 |
| GET | `/api/search/hot/snapshot` | 历史快照 |
| GET | `/api/recommend/feed` | 个性化推荐 Feed |
| GET | `/api/recommend/similar/{noteId}` | 相似笔记 |
| POST | `/api/recommend/behavior` | 行为上报 |
| POST | `/api/recommend/compute` | 触发离线计算 |

---

## 6. 核心流程

### 6.1 笔记搜索

```
GET /api/search/note?keyword=春日穿搭&sort=relevance&size=20
    │
    ├─ 记录搜索词到热搜窗口
    ├─ 记录搜索历史（Lua LREM+LPUSH+LTRIM+EXPIRE）
    │
    ├─ 构建 BoolQuery
    │   ├─ must: multi_match(title^3, content) ik_smart
    │   └─ filter: status=1
    │
    ├─ 排序 tiebreaker: noteId DESC + _id ASC
    ├─ SearchAfter（深分页 > 10000 行）
    ├─ Highlight: title/content <em>标签
    │
    └─ ES 响应 → SearchResultVO
```

### 6.2 索引同步（Canal → MQ → ES）

```
MySQL Binlog
    ↓
Canal Server（监听 t_note / t_spu）
    ↓
RocketMQ（NOTE_INDEX_TOPIC / PRODUCT_INDEX_TOPIC）
    ↓
Consumer（NoteIndexSyncConsumer）
    ├─ ExternalGte 版本控制（opseq 事件序号）
    ├─ INSERT/UPDATE → IndexRequest
    ├─ DELETE → UpdateRequest(status=-1)
    └─ 失败 → Redis Set（补偿用）
    ↓
IncrementalIndexSyncJob（@Scheduled 5min）
    └─ 重试失败 Set → MySQL 查最新 → Bulk API
```

### 6.3 热搜机制

```
用户搜索 → POST /api/search/hot/record
    ↓
Lua 脚本原子执行：
  ├─ blocked Set 检查
  ├─ IP 限速（10次/分钟）
  ├─ 用户去重（同词 300s 冷却）
  └─ 分钟桶 INCR: window:{HHmm} keyword +1
    ↓
HotSearchService @Scheduled 5min：
  ├─ 取过去 N 个分钟桶
  ├─ score = Σ(count_i × decayFactor(age))
  ├─ 原子 RENAME 到 realtime ZSet
  └─ 合并 pinned Set → Top 50
```

### 6.4 推荐 4 层 Pipeline

```
冷启动？→ Hot(60%) + Geo(30%) + 随机(10%)
    ↓
Recall（5路并行，各 100 条）
  ├─ ItemCF → Redis ZSet
  ├─ Content → MySQL 标签 LIKE
  ├─ Hot → Redis 全局热池
  ├─ Following → 关注者最新笔记 ZSet
  └─ Geo → MySQL GeoHash 匹配
    ↓
Coarse Rank（Top 100）
  score = recallScore × sourceWeight
  权重：ItemCF(1.0) > Following(0.9) > Content(0.8) > Hot(0.6) > Geo(0.5)
    ↓
Fine Rank（Top 50）
  score = sourceWeight×25% + preference×25% + quality×30% + timeDecay×20%
    ↓
Re-Rank（Final 20）
  Seen dedup + Category interleave（最多连续 2 篇同品类）
```

---

## 7. 幂等设计

| 场景 | 机制 |
|---|---|
| Canal MQ 重复消息 | ExternalGte 版本号控制 |
| 索引同步失败 | Redis Set 记录 → 定时补偿 |
| 热搜用户/IP 防刷 | Lua SET NX EX 原子操作 |

---

## 8. 配置要点

| 配置项 | 值 | 说明 |
|---|---|---|
| 端口 | 19016 | |
| ES URI | http://21.130.247.89:19200 | 8.12 |
| ES 连接池 | max-conn-total=100, max-conn-per-route=50 | Java config |
| note_index | 3 shards, 1 replica | IK 分词 |
| product_index | 3 shards, 1 replica | |
| suggest_index | 1 shard, 1 replica | Completion |
| RocketMQ NS | 9876;9877 | NOTE_INDEX/PRODUCT_INDEX/RECOMMEND_BEHAVIOR |
| XXL-Job | appname=my-xhs-search, port=9997 | `xxl.job.enabled=true` |
| 推荐线程池 | core=10, max=20, queue=100 | |
| 热搜衰减 | lambda=0.1, window=60min | |
| 防刷 IP | 10次/分钟 | |
| 防刷用户 | 同词 300s 冷却 | |

---

## 9. 依赖关系

### 上游
- **ES 8.12 (19200)**：核心搜索引擎
- **MySQL (13306)**：my_xhs_search（行为表 + 特征表）
- **Redis (16381)**：热搜/建议缓存/失败 Set/推荐数据
- **RocketMQ**：索引同步 + 行为消费
- **Nacos**：服务注册

### 下游
- **前端**：搜索/建议/热搜/推荐 API
- **home BFF**：通过 Feign 调用搜索服务

---

## 10. 关键设计决策

| 决策 | 选择 | 原因 |
|---|---|---|
| ES 客户端 | 8.x 官方 Java Client | 跟随版本，支持新特性 |
| 分页 | Search After | 深分页友好（vs from/size 1万限制） |
| 索引同步 | Canal → RocketMQ → ES | 解耦、异步、可补偿 |
| 防乱序 | ExternalGte | 防止 Canal 事件乱序覆盖 |
| 软删 | status=-1 | 保留 version info |
| 热搜防刷 | Lua 原子脚本 | 防 TOCTOU 竞态 |
| 推荐 | 4 层 Pipeline | Facebook 经典架构 |

---

## 11. 测试场景建议

1. 笔记搜索关键词 → 高亮结果
2. 商品搜索 + 分类/价格过滤
3. Search After 翻页（翻 5+ 页）
4. 搜索建议（Completion Suggester）
5. 热搜上报 → 5min 后热榜更新
6. IP 防刷验证（10次/分钟限速）
7. 推荐 Feed（冷启动/正常用户）
8. 相似笔记推荐
9. 索引同步（修改笔记 → ES 同步）
