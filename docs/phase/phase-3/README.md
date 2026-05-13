# Phase 3：内容分发 — 详细梳理

> 🎯 目标：跑通内容分发链路，用户有个性化Feed流和搜索体验
>
> ⚠️ **前置条件**：Phase 1 + Phase 2 全部功能开发完成并验收通过

---

## 一、Phase 3 概览

| 序号 | 功能 | 涉及服务 | 核心技术 |
|------|------|----------|----------|
| 14 | Feed流首页 | 首页聚合服务 | 推拉混合模式、大V优化、CompletableFuture并行聚合 |
| 15 | 搜索与搜索建议 | 搜索服务 | ES双索引设计、Completion Suggester、Canal增量同步 |
| 16 | 热搜榜 | 搜索服务 | ZSet滑动窗口实时计算、时间衰减算法、防刷策略 |
| 17 | 推荐系统 | 搜索服务 | 5路召回（协同过滤+内容+热门+关注+地理）、粗排精排 |

---

## 二、涉及的模块与端口

| 服务 | 端口 | 数据库 | 本阶段新增 | 说明 |
|------|------|--------|-----------|------|
| my-xhs-home | 9005 | 无（聚合服务） | ✅ | BFF聚合层（Feed流/详情聚合） |
| my-xhs-search | 9011 | 无（ES） | ✅ | 搜索服务（全文搜索/搜索建议/热搜/推荐） |

> **注意**：
> - `my-xhs-home` 是 BFF（Backend For Frontend）聚合层，**不拥有数据库**，只聚合其他服务数据
> - `my-xhs-search` 使用 MySQL + Elasticsearch，MySQL 存储热搜快照/用户行为/推荐数据，ES 存储搜索索引
> - 所有端口已统一为 9000-9015 连续分配，技术规格大纲已同步更新

### 2.1 端口对照说明

| 服务 | 技术规格大纲端口 | 实际端口 | 说明 |
|------|-----------------|---------|------|
| my-xhs-gateway | 9000 | **9000** | ✅ 一致 |
| my-xhs-user | 9001 | **9001** | ✅ 一致 |
| my-xhs-content | 9002 | **9002** | ✅ 一致 |
| my-xhs-analytics | 9003 | **9003** | ✅ 一致 |
| my-xhs-counter | 9004 | **9004** | ✅ 一致 |
| my-xhs-home | 9005 | **9005** | ✅ 一致 |
| my-xhs-product | 9006 | **9006** | ✅ 一致 |
| my-xhs-search | 9007 | **9007** | ✅ 一致 |
| my-xhs-cart | 9008 | **9008** | ✅ 一致 |
| my-xhs-inventory | 9009 | **9009** | ✅ 一致 |
| my-xhs-coupon | 9010 | **9010** | ✅ 一致 |
| my-xhs-order | 9011 | **9011** | ✅ 一致 |
| my-xhs-payment | 9012 | **9012** | ✅ 一致 |
| my-xhs-notification | 9013 | **9013** | ✅ 一致 |
| my-xhs-im | 9014 | **9014** | ✅ 一致 |

> **原则**：所有端口已统一为 9000-9015 连续分配，代码和文档保持一致。

---

## 三、功能详细梳理

### 功能 14：Feed流首页

#### 3.14.1 功能描述

首页 Feed 流是用户打开 APP 后的第一个页面，采用**推拉混合模式**：普通用户发笔记时推模式（写扩散），将笔记ID推送到所有粉丝的收件箱；大V（粉丝数 > 10万）发笔记时拉模式（读扩散），粉丝读取时实时拉取大V的最新笔记。Feed 流使用游标分页（基于时间戳），避免 offset 深分页性能问题。

my-xhs-home 作为 BFF 聚合层，通过 CompletableFuture 并行调用 6 个服务（Note + User + Social + Counter + Search + Notification），将首页 RT 从串行 320ms 优化到并行 100ms。聚合层含 5 秒本地缓存，重复请求直接返回缓存。

#### 3.14.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-home | 主服务（BFF聚合层） | 聚合多服务数据，裁剪字段，缓存结果 |
| my-xhs-content | 被调用 | 获取笔记列表/详情 |
| my-xhs-user | 被调用 | 获取用户基本信息 |
| my-xhs-analytics | 被调用 | 获取关注关系/点赞状态 |
| my-xhs-counter | 被调用 | 获取点赞/收藏/评论计数 |
| my-xhs-notification | 被调用 | 获取未读通知数 |
| my-xhs-search | 被调用 | 获取推荐笔记（发现流） |

#### 3.14.3 API 接口清单

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/home/feed` | 关注Feed流（关注的人的笔记） | ✅ |
| GET | `/api/home/discover` | 发现流（推荐笔记） | ✅ |
| GET | `/api/home/note/{id}` | 笔记详情聚合（笔记+计数+社交状态+关联商品） | ❌ |
| GET | `/api/home/product/{id}` | 商品详情聚合（商品+库存+计数+关联笔记） | ❌ |
| GET | `/api/home/user/{id}` | 用户主页聚合（用户信息+计数+社交状态） | ❌ |

#### 3.14.4 数据库表

> my-xhs-home 为聚合服务，**不拥有数据库**。Feed 数据来自 Redis 收件箱 + 各服务 API。

#### 3.14.5 Redis Key 清单

| Key | 数据结构 | TTL | 说明 |
|-----|---------|-----|------|
| `feed:inbox:{userId}` | ZSet | 7d | 用户收件箱（推模式写入），score=发布时间戳，member=noteId |
| `feed:outbox:{userId}` | ZSet | 7d | 用户发件箱（拉模式读取大V），score=发布时间戳 |
| `home:feed:{userId}:{feedType}:{page}` | String | 5s | 首页Feed聚合缓存（Caffeine本地缓存） |
| `home:note:detail:{noteId}` | String | 30s | 笔记详情聚合缓存（Redis） |
| `home:product:detail:{productId}` | String | 30s | 商品详情聚合缓存（Redis） |
| `user:bigv:{userId}` | String | 1h | 大V标记（粉丝数>10万） |

#### 3.14.6 Java 文件清单

**controller/**
```
HomeFeedController.java       — 首页Feed流（关注流/发现流）
HomeNoteController.java       — 笔记详情聚合
HomeProductController.java    — 商品详情聚合
HomeUserController.java       — 用户主页聚合
```

**aggregator/**
```
HomeFeedAggregator.java       — Feed聚合核心（CompletableFuture并行编排6个服务）
NoteDetailAggregator.java     — 笔记详情聚合器
ProductDetailAggregator.java  — 商品详情聚合器
UserProfileAggregator.java    — 用户主页聚合器
```

**fallback/**
```
ServiceFallbackFactory.java   — 通用降级工厂（Counter→0计数, Social→未关注, Notification→0未读）
```

**config/**
```
AggregatorThreadPoolConfig.java — BFF专用线程池配置（核心20/最大50/队列200）
CaffeineCacheConfig.java       — 本地缓存配置（Feed 5秒, 热点 30秒）
RedisCacheConfig.java          — Redis分布式缓存配置
WebMvcConfig.java              — CORS + 拦截器注册
```

**dto/**
```
FeedVO.java                   — 首页Feed响应（笔记列表+社交状态+未读数）
FeedNoteVO.java               — Feed笔记项（笔记+作者+计数+社交状态）
NoteDetailVO.java             — 笔记详情聚合VO
ProductDetailVO.java          — 商品详情聚合VO
UserProfileVO.java            — 用户主页聚合VO
SocialStatusDTO.java          — 社交状态DTO（是否关注/是否点赞）
```

**feign/**
```
NoteFeignClient.java          — 笔记服务Feign（列表/详情/批量）
UserFeignClient.java          — 用户服务Feign（基本信息/批量）
SocialFeignClient.java        — 社交服务Feign（关注状态/点赞状态）
CounterFeignClient.java       — 计数服务Feign（批量计数）
NotificationFeignClient.java  — 通知服务Feign（未读数）
SearchFeignClient.java        — 搜索服务Feign（推荐/搜索）
ProductFeignClient.java       — 商品服务Feign（商品详情）
InventoryFeignClient.java     — 库存服务Feign（库存状态）
```

**feed/**
```
FeedPushService.java          — 推模式服务（写扩散：发布笔记→推送粉丝收件箱）
FeedPullService.java          — 拉模式服务（读扩散：大V笔记实时拉取）
BigVDetector.java             — 大V检测器（粉丝数>10万标记为拉模式）
FeedMergeService.java         — 推拉合并服务（收件箱+发件箱合并排序分页）
```

#### 3.14.7 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 推拉混合 | 普通用户推模式，大V拉模式 | 发布笔记时判断粉丝数：>10万标记大V，粉丝读取时实时拉取大V发件箱 |
| 游标分页 | 基于时间戳的游标 | lastId + lastScore 替代 offset，避免深分页性能问题 |
| BFF并行聚合 | CompletableFuture | 2层并行：第1层(笔记+社交+通知)，第2层(计数+用户信息)，RT=max(服务RT) |
| 独立线程池 | ThreadPoolExecutor | 不用 ForkJoinPool.commonPool()，独立配置(20核心/50最大)避免阻塞其他业务 |
| 降级兜底 | FallbackFactory | Counter降级→0计数，Social降级→未关注，Notification降级→0未读 |
| 本地缓存 | Caffeine 5秒 | Feed结果缓存5秒，重复请求直接返回，减少后端调用 |
| Feed推模式 | Redis ZSet写扩散 | 笔记发布→MQ→Social消费→ZADD到粉丝收件箱 |
| Feed拉模式 | 大V发件箱实时拉 | 粉丝读Feed→合并收件箱(ZRANGE) + 大V发件箱(ZRANGE)→排序→分页 |

#### 3.14.8 推拉混合 Feed 流流程

```
笔记发布流程（推模式 - 普通用户）：
┌────────┐   ┌─────────┐   ┌─────────┐   ┌──────────┐
│ 作者发布 │──▶│ Content │──▶│ RocketMQ│──▶│ Social   │
│ 笔记    │   │ 入库    │   │ PUBLISH │   │ 推送到粉丝│
└────────┘   └─────────┘   └─────────┘   └────┬─────┘
                                                  │
                              ZADD feed:inbox:{followerId} noteId timestamp
                              （遍历粉丝列表，写入每个粉丝的收件箱）

笔记发布流程（拉模式 - 大V）：
┌────────┐   ┌─────────┐   ┌─────────┐
│ 大V发布 │──▶│ Content │──▶│ 写入发件箱│
│ 笔记    │   │ 入库    │   │ ZADD feed:outbox:{bigVId} │
└────────┘   └─────────┘   └─────────┘
                              （不推送到粉丝收件箱，粉丝读取时实时拉取）

读取 Feed 流：
┌────────┐   ┌──────────┐   ┌──────────────┐   ┌──────────┐
│ 用户刷新│──▶│ 拉取收件箱│──▶│ 拉取关注大V的 │──▶│ 合并+排序 │
│ 首页   │   │ ZRANGE   │   │ 发件箱ZRANGE  │   │ +分页    │
└────────┘   └──────────┘   └──────────────┘   └──────────┘
```

---

### 功能 15：搜索与搜索建议

#### 3.15.1 功能描述

搜索服务基于 Elasticsearch 8.x（使用 RestClient，非 Spring Data Elasticsearch），提供笔记和商品的双索引搜索。搜索建议使用 ES Completion Suggester 实现输入联想。数据同步采用 Canal 监听 MySQL Binlog → RocketMQ → 消费写入 ES 的增量同步方案，保证准实时性（延迟 < 5秒）。全量索引用 Spring @Scheduled 分页扫描+批量写入+断点续传。搜索历史使用 Redis List 存储，保留最近 20 条。

#### 3.15.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-search | 主服务 | 搜索/搜索建议/搜索历史/索引管理 |
| my-xhs-content | 数据源 | 笔记数据（通过Canal同步到ES） |
| my-xhs-product | 数据源 | 商品数据（通过Canal同步到ES） |

#### 3.15.3 API 接口清单

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/search/note` | 笔记搜索（关键词+筛选+排序） | ❌ |
| GET | `/api/search/product` | 商品搜索（关键词+分类+价格区间+排序） | ❌ |
| GET | `/api/search/suggest` | 搜索建议（自动补全） | ❌ |
| GET | `/api/search/hot` | 热搜榜（Top 50） | ❌ |
| GET | `/api/search/history` | 搜索历史（最近20条） | ✅ |
| DELETE | `/api/search/history` | 清空搜索历史 | ✅ |
| DELETE | `/api/search/history/{keyword}` | 删除单条搜索历史 | ✅ |
| POST | `/api/search/index/rebuild` | 手动触发全量索引重建（管理员） | ✅ |

#### 3.15.4 数据库表

> my-xhs-search 使用 Elasticsearch 存储索引数据，**不使用 MySQL**。

#### 3.15.5 ES 索引设计

**笔记索引 (note_index)**

| 字段 | ES类型 | 分词器 | 说明 |
|------|--------|--------|------|
| noteId | long | — | 笔记ID |
| userId | long | — | 作者ID |
| title | text | ik_max_word | 标题（搜索时用ik_smart） |
| content | text | ik_smart | 正文 |
| coverImage | keyword(index=false) | — | 封面图（不索引） |
| likeCount | long | — | 点赞数 |
| collectCount | long | — | 收藏数 |
| commentCount | long | — | 评论数 |
| status | integer | — | 状态（1=已发布） |
| createdAt | date | — | 创建时间 |

**商品索引 (product_index)**

| 字段 | ES类型 | 分词器 | 说明 |
|------|--------|--------|------|
| spuId | long | — | SPU ID |
| skuId | long | — | SKU ID |
| name | text | ik_max_word | 商品名称 |
| subTitle | text | ik_smart | 副标题 |
| categoryId | long | — | 分类ID |
| categoryName | keyword | — | 分类名 |
| brandId | long | — | 品牌ID |
| brandName | keyword | — | 品牌名 |
| price | scaled_float(100) | — | 价格 |
| image | keyword(index=false) | — | 主图 |
| sales | long | — | 销量 |
| status | integer | — | 状态 |
| createdAt | date | — | 创建时间 |

**搜索建议索引 (suggest_index)**

| 字段 | ES类型 | 分词器 | 说明 |
|------|--------|--------|------|
| keyword | completion | ik_max_word | 搜索建议词（Completion Suggester专用） |
| weight | long | — | 权重（搜索频次） |

**订单索引 (order_index)**（Phase 3 预留，Phase 5 启用）

| 字段 | ES类型 | 分词器 | 说明 |
|------|--------|--------|------|
| orderId | long | — | 订单ID |
| orderNo | keyword | — | 订单号 |
| userId | long | — | 用户ID |
| status | integer | — | 状态 |
| totalAmount | scaled_float(100) | — | 总金额 |
| payAmount | scaled_float(100) | — | 实付金额 |
| createdAt | date | — | 创建时间 |
| items | nested | — | 订单明细（嵌套） |

#### 3.15.6 Redis Key 清单

| Key | 数据结构 | TTL | 说明 |
|-----|---------|-----|------|
| `search:history:{userId}` | List | 30d | 搜索历史（最近20条），LPUSH + LTRIM 20 |
| `search:suggest:cache:{prefix}` | String | 1h | 搜索建议缓存 |
| `search:hot:realtime` | ZSet | 1h | 实时热搜（score=热度值） |
| `search:hot:pinned` | Set | 永久 | 人工置顶的热搜词 |
| `search:hot:blocked` | Set | 永久 | 人工屏蔽的热搜词 |
| `search:hot:snapshot:{date}` | String | 7d | 热搜快照（每5分钟） |
| `search:index:rebuild:status` | Hash | 1d | 全量重建进度（lastId/totalCount/startTime） |

#### 3.15.7 Java 文件清单

**controller/**
```
NoteSearchController.java     — 笔记搜索
ProductSearchController.java  — 商品搜索
SearchSuggestController.java  — 搜索建议
SearchHistoryController.java  — 搜索历史
SearchAdminController.java    — 索引管理（重建/状态）
```

**service/**
```
NoteSearchService.java        — 笔记搜索业务接口
NoteSearchServiceImpl.java    — 笔记搜索实现（ES RestClient查询）
ProductSearchService.java     — 商品搜索业务接口
ProductSearchServiceImpl.java — 商品搜索实现
SearchSuggestService.java     — 搜索建议业务接口
SearchSuggestServiceImpl.java — 搜索建议实现（Completion Suggester）
SearchHistoryService.java     — 搜索历史业务接口
SearchHistoryServiceImpl.java — 搜索历史实现（Redis List）
IndexService.java             — 索引管理业务接口
IndexServiceImpl.java         — 索引管理实现（全量/增量同步）
```

**config/**
```
ElasticsearchConfig.java      — ES RestClient配置（节点/超时/认证）
RedisConfig.java              — Redis序列化配置
WebMvcConfig.java             — CORS + 拦截器
```

**es/**
```
EsIndexInitializer.java       — ES索引初始化（创建mapping/setting）
NoteDocumentMapper.java       — 笔记↔ES文档转换器
ProductDocumentMapper.java    — 商品↔ES文档转换器
SearchQueryBuilder.java       — ES查询构建器（match/filter/sort/after）
```

**mq/**
```
NoteIndexSyncConsumer.java    — 笔记索引同步消费（Canal→MQ→ES）
ProductIndexSyncConsumer.java — 商品索引同步消费（Canal→MQ→ES）
```

**entity/**
```
NoteDocument.java             — 笔记ES文档实体
ProductDocument.java          — 商品ES文档实体
SuggestDocument.java          — 搜索建议ES文档实体
```

**dto/**
```
NoteSearchRequest.java        — 笔记搜索请求(keyword+sort+page)
ProductSearchRequest.java     — 商品搜索请求(keyword+categoryId+priceRange+sort)
SearchSuggestRequest.java     — 搜索建议请求(prefix)
SearchResultVO.java           — 搜索结果VO（分页+高亮）
SearchHistoryVO.java          — 搜索历史VO
```

**job/**
```
EsFullSyncJob.java            — ES全量索引重建（Spring @Scheduled，分页+断点续传）
```

#### 3.15.8 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| ES双索引 | note_index + product_index | 笔记用 ik_max_word 分词，商品用 ik_smart，Completion Suggester专用索引 |
| 增量同步 | Canal监听Binlog→MQ→ES | Canal监听t_note/t_spu变更→RocketMQ→Search消费写入ES，延迟<5秒 |
| 全量重建 | @Scheduled分页扫描 | 分页查DB（SearchAfter游标），每批500条写入ES，记录lastId断点续传 |
| 搜索建议 | ES Completion Suggester | 前缀匹配+权重排序，比前缀匹配查询性能高10倍+ |
| 深分页 | Search After | 替代 from+size（ES默认max_result_window=10000），基于排序值游标 |
| 搜索历史 | Redis List | LPUSH + LTRIM 20 保留最近20条，LRANGE 0 -1 获取全部 |
| 高亮 | ES highlight | 搜索结果关键词高亮返回，前端直接渲染 |
| 防空搜索 | 空关键词处理 | 空关键词返回热搜词推荐，不执行ES查询 |

---

### 功能 16：热搜榜

#### 3.16.1 功能描述

热搜榜实时统计最近1小时的搜索词频，使用 Redis ZSet + 滑动窗口计算热度。热度公式采用时间衰减算法：`Score = Σ(count × e^(-λ×Δt))`，近期搜索权重更高。支持反作弊策略（同IP/同用户频率限制、异常波动检测），人工置顶/屏蔽热搜词，定时快照保存历史数据。

#### 3.16.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-search | 主服务 | 热搜计算/排行榜/反作弊/人工干预 |

#### 3.16.3 API 接口清单

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/search/hot` | 热搜榜Top50 | ❌ |
| POST | `/api/search/hot/record` | 记录搜索词（搜索时调用） | ❌ |
| PUT | `/api/search/hot/pin` | 人工置顶热搜词（管理员） | ✅ |
| PUT | `/api/search/hot/block` | 人工屏蔽热搜词（管理员） | ✅ |
| GET | `/api/search/hot/snapshot` | 历史热搜快照（按日期） | ❌ |

#### 3.16.4 数据库表

**t_hot_search**（预估 10万/月）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 记录ID |
| keyword | VARCHAR(64) | 搜索关键词 |
| heat_score | DECIMAL(16,4) | 热度分数 |
| search_count | INT | 搜索次数 |
| note_count | INT | 关联笔记数 |
| interaction_count | INT | 互动量(赞+评+藏) |
| status | TINYINT | 状态:0正常1置顶2屏蔽 |
| snapshot_date | DATE | 快照日期 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

> UNIQUE KEY `uk_keyword_date` (keyword, snapshot_date)

#### 3.16.5 Redis Key 清单

| Key | 数据结构 | TTL | 说明 |
|-----|---------|-----|------|
| `search:hot:realtime` | ZSet | 1h | 实时热搜 score=热度值 member=关键词 |
| `search:hot:window:{minute}` | Hash | 2h | 滑动窗口（每分钟一个桶）field=keyword value=count |
| `search:hot:pinned` | Set | 永久 | 人工置顶词集合 |
| `search:hot:blocked` | Set | 永久 | 人工屏蔽词集合 |
| `search:hot:antispam:{userId}` | String | 1min | 用户搜索频率限制（1分钟内同一词只计1次） |
| `search:hot:antispam:ip:{ip}` | String | 1min | IP搜索频率限制 |

#### 3.16.6 Java 文件清单

**service/**
```
HotSearchService.java         — 热搜业务接口
HotSearchServiceImpl.java     — 热搜业务实现
```

**hot/**
```
HotSearchCalculator.java      — 热度计算器（时间衰减公式：Score = Σ(count × e^(-λ×Δt))）
SlidingWindowCounter.java     — 滑动窗口计数器（Redis分钟桶）
HotSearchAntiSpam.java        — 反作弊过滤器（用户/IP频率限制+异常波动检测）
```

**mapper/**
```
HotSearchMapper.java          — 热搜快照Mapper
```

**entity/**
```
HotSearch.java                — 热搜实体
```

**dto/**
```
HotSearchVO.java              — 热搜项VO（排名+关键词+热度+状态）
HotSearchRecordRequest.java   — 记录搜索词请求
```

**job/**
```
HotSearchSnapshotJob.java     — 热搜快照定时任务（每5分钟ZREVRANGE→写入DB）
HotSearchCleanJob.java        — 过期数据清理（每天凌晨3点清理7天前数据）
```

#### 3.16.7 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 滑动窗口 | Redis分钟桶 | 每分钟一个Hash桶{keyword:count}，统计最近60分钟合并计算 |
| 时间衰减 | e^(-λ×Δt) | 近1分钟权重1.0，30分钟前权重0.5，1小时前权重0.25 |
| 反作弊 | 频率限制+异常检测 | 同一用户1分钟内同一词只计1次；单IP每分钟搜索<10次；环比增长>500%标记异常 |
| 人工干预 | Set置顶/屏蔽 | pinned优先展示，blocked从排行榜中过滤 |
| 快照持久化 | @Scheduled每5分钟 | ZREVRANGE 0 49 → 批量写入t_hot_search表 |
| 窗口清理 | 过期桶自动过期 | 每个分钟桶TTL=2h，自然淘汰 |

#### 3.16.8 热度计算流程

```
用户搜索 → 记录搜索词
┌────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│ 搜索请求│──▶│ 反作弊过滤   │──▶│ 写入滑动窗口 │──▶│ 定时计算热度 │
│        │   │ 用户/IP限频  │   │ HINCRBY      │   │ 衰减公式     │
└────────┘   └──────────────┘   └──────────────┘   └──────┬───────┘
                                                         │
                                              ZADD search:hot:realtime keyword score
                                              （每5分钟重算热度值并更新排行榜）
```

---

### 功能 17：推荐系统

#### 3.17.1 功能描述

推荐系统采用多路召回+粗排精排架构，5路召回策略并行执行：协同过滤（Item-CF）、内容召回（TF-IDF标签匹配）、热门召回（全局热度Top-N）、关注召回（关注用户最新内容）、地理位置召回（GeoHash同城内容）。粗排阶段多路归并+简单打分，精排预留ML模型接口，重排阶段去重+多样性（品类打散）+已读过滤。冷启动策略：新用户基于热门+地理推荐，新内容基于内容特征推荐。

#### 3.17.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-search | 主服务 | 5路召回+粗排+重排 |
| my-xhs-content | 数据源 | 笔记内容/标签 |
| my-xhs-analytics | 数据源 | 用户行为/关注关系 |
| my-xhs-counter | 数据源 | 热度计数 |

#### 3.17.3 API 接口清单

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/recommend/feed` | 个性化推荐Feed（发现页） | ✅ |
| GET | `/api/recommend/similar/{noteId}` | 相似笔记推荐 | ❌ |
| POST | `/api/recommend/behavior` | 上报用户行为（曝光/点击/停留时长） | ✅ |
| GET | `/api/recommend/coldstart` | 冷启动推荐（新用户） | ✅ |

#### 3.17.4 数据库表

**t_user_behavior**（预估 100亿，分表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 记录ID |
| user_id | BIGINT | 用户ID |
| note_id | BIGINT | 笔记ID |
| behavior_type | TINYINT | 行为类型:1曝光2点击3点赞4收藏5评论6分享7停留 |
| duration | INT | 停留时长(秒)，仅停留行为有值 |
| created_at | DATETIME | 行为时间 |

> KEY `idx_user_id` (user_id), KEY `idx_note_id` (note_id), KEY `idx_created_at` (created_at)

**t_item_feature**（预估 5000万）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 记录ID |
| note_id | BIGINT | 笔记ID |
| tags | VARCHAR(512) | 标签列表JSON ["美妆","护肤"] |
| category_id | BIGINT | 分类ID |
| tfidf_vector | TEXT | TF-IDF向量JSON |
| geohash | VARCHAR(12) | 地理位置GeoHash |
| updated_at | DATETIME | 更新时间 |

> UNIQUE KEY `uk_note_id` (note_id)

**t_recommend_result**（预估 1000万/天，分表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 记录ID |
| user_id | BIGINT | 用户ID |
| note_ids | TEXT | 推荐笔记ID列表JSON |
| strategy | VARCHAR(32) | 召回策略 |
| created_at | DATETIME | 创建时间 |

> KEY `idx_user_created` (user_id, created_at)

#### 3.17.5 Redis Key 清单

| Key | 数据结构 | TTL | 说明 |
|-----|---------|-----|------|
| `recommend:itemcf:{itemId}` | Set | 24h | Item-CF相似物品集合 |
| `recommend:usercf:{userId}` | Set | 24h | User-CF推荐集合 |
| `recommend:hot:notes` | ZSet | 1h | 热门笔记排行 score=热度 |
| `recommend:following:latest:{userId}` | ZSet | 1h | 关注用户最新笔记 score=发布时间 |
| `recommend:geo:{geohash}` | ZSet | 1h | 同城内容 score=距离权重 |
| `recommend:seen:{userId}` | HyperLogLog | 7d | 用户已曝光内容（去重用） |
| `recommend:coldstart` | ZSet | 1h | 冷启动推荐池（新用户） |
| `recommend:feature:{noteId}` | Hash | 24h | 内容特征缓存 field=tags/category/geohash |

#### 3.17.6 Java 文件清单

**controller/**
```
RecommendController.java      — 推荐Feed/相似推荐/行为上报
```

**service/**
```
RecommendService.java         — 推荐业务接口
RecommendServiceImpl.java     — 推荐业务实现（编排5路召回+粗排+重排）
UserBehaviorService.java      — 用户行为业务接口
UserBehaviorServiceImpl.java  — 用户行为实现（记录+查询）
ItemFeatureService.java       — 内容特征业务接口
ItemFeatureServiceImpl.java   — 内容特征实现
```

**recall/**
```
RecallStrategy.java           — 召回策略接口
ItemCFRecallStrategy.java     — 协同过滤召回（基于Item-CF，Redis矩阵）
ContentRecallStrategy.java    — 内容召回（TF-IDF标签匹配）
HotRecallStrategy.java        — 热门召回（全局热度Top-N）
FollowingRecallStrategy.java  — 关注召回（关注用户最新内容）
GeoRecallStrategy.java        — 地理位置召回（GeoHash同城）
RecallMerger.java             — 多路召回合并器
```

**rank/**
```
RoughRanker.java              — 粗排（多路归并+简单打分）
FineRanker.java               — 精排（预留ML模型接口，当前用规则排序）
ReRanker.java                 — 重排（去重+多样性+已读过滤）
```

**coldstart/**
```
ColdStartStrategy.java        — 冷启动策略接口
NewUserColdStart.java         — 新用户冷启动（热门+地理+注册时选择兴趣）
NewItemColdStart.java         — 新内容冷启动（内容特征匹配+流量池测试）
```

**mapper/**
```
UserBehaviorMapper.java       — 用户行为Mapper
ItemFeatureMapper.java        — 内容特征Mapper
RecommendResultMapper.java    — 推荐结果Mapper
```

**entity/**
```
UserBehavior.java             — 用户行为实体
ItemFeature.java              — 内容特征实体
RecommendResult.java          — 推荐结果实体
```

**dto/**
```
RecommendFeedVO.java          — 推荐Feed响应
SimilarNoteVO.java            — 相似笔记VO
BehaviorReportRequest.java    — 行为上报请求(type+noteId+duration)
```

**job/**
```
ItemCFComputeJob.java         — 协同过滤矩阵计算（@Scheduled每天凌晨4点）
FeatureExtractJob.java        — 内容特征提取（@Scheduled每小时）
```

#### 3.17.7 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 5路召回 | 并行CompletableFuture | 5路策略并行执行，每路返回100条，总计500条候选 |
| Item-CF | Redis相似矩阵 | 用户行为→计算物品相似度→Redis存储相似物品集合→召回时读取 |
| 内容召回 | TF-IDF标签匹配 | 笔记标签→TF-IDF向量→余弦相似度匹配→Redis缓存特征 |
| 热门召回 | ZSet Top-N | counter服务提供热度排序，ZREVRANGE取Top100 |
| 关注召回 | ZSet最新内容 | 关注用户的最新笔记，ZRANGE按时间取 |
| 地理召回 | GeoHash | 用户位置→GeoHash前缀→匹配同城内容 |
| 粗排 | 多路归并+加权打分 | 5路结果去重+加权分数（热度0.3+相似度0.3+时效0.2+距离0.2） |
| 精排 | 预留ML接口 | FeatureStore→Model→Score，当前用规则排序替代 |
| 重排 | 去重+多样性+已读过滤 | HyperLogLog判断已读，品类打散（同品类不超过2个连续） |
| 冷启动 | 热门+地理+兴趣标签 | 新用户无行为数据时，热门+同城+注册兴趣标签推荐 |

#### 3.17.8 推荐系统架构流程

```
用户请求推荐Feed
       │
       ▼
┌──────────────────────────────────────────────────────┐
│ 5路召回（CompletableFuture并行）                        │
│  ┌──────────┐ ┌──────────┐ ┌────────┐ ┌────────┐ ┌────┐ │
│  │ Item-CF  │ │ 内容召回  │ │热门召回│ │关注召回│ │地理│ │
│  │ 100条    │ │ 100条    │ │ 100条  │ │ 100条  │ │100 │ │
│  └────┬─────┘ └────┬─────┘ └───┬────┘ └───┬────┘ └─┬──┘ │
│       └─────────────┴───────────┴───────────┴───────┘   │
│                        │ 合并去重 ≈300条                    │
│                        ▼                                   │
│              ┌─────────────────┐                          │
│              │   粗排（加权打分）│                          │
│              │   Top 100       │                          │
│              └────────┬────────┘                          │
│                       ▼                                   │
│              ┌─────────────────┐                          │
│              │   精排（规则/ML） │                          │
│              │   Top 50        │                          │
│              └────────┬────────┘                          │
│                       ▼                                   │
│              ┌─────────────────┐                          │
│              │   重排           │                          │
│              │ 去重+多样性+已读 │                          │
│              │   Top 20        │                          │
│              └─────────────────┘                          │
└──────────────────────────────────────────────────────┘
```

---

## 四、Phase 3 新增公共组件

| 组件 | 所属服务 | 说明 |
|------|---------|------|
| AggregatorThreadPool | my-xhs-home | BFF专用线程池（不共享ForkJoinPool） |
| CaffeineLocalCache | my-xhs-home | 本地缓存管理器（Feed 5秒/详情 30秒） |
| ServiceFallbackFactory | my-xhs-home | 通用降级工厂（Counter→0, Social→未关注, Notification→0未读） |
| ElasticsearchRestClient | my-xhs-search | ES RestClient封装（查询/索引/批量写入） |
| SlidingWindowCounter | my-xhs-search | 滑动窗口计数器（热搜分钟桶） |
| HotSearchCalculator | my-xhs-search | 热度计算器（时间衰减公式） |
| RecallStrategy | my-xhs-search | 召回策略接口（5路召回实现） |

---

## 五、中间件需求（Phase 3 新增）

| 中间件 | 版本 | 用途 | Phase 3 必须 |
|--------|------|------|-------------|
| Elasticsearch | 8.12.x | 全文搜索/搜索建议/搜索推荐 | ✅ 必须 |
| Canal | 1.1.7 | MySQL Binlog→MQ→ES增量同步 | ✅ 必须 |
| IK Analysis | 8.12.x | ES中文分词插件 | ✅ 必须 |

> **注意**：Elasticsearch 和 Canal 在 Phase 1/2 未使用，Phase 3 首次引入。

---

## 六、MQ Topic 清单（Phase 3 新增）

| Topic | Tag | 生产者 | 消费者 | 消息类型 | 说明 |
|-------|-----|-------|--------|---------|------|
| NOTE_TOPIC | PUBLISH | content | home(Feed推), search(索引同步) | 普通 | 笔记发布事件 |
| PRODUCT_TOPIC | CHANGE | canal | search(索引同步) | 普通 | 商品变更事件 |
| NOTE_TOPIC | UPDATE/DELETE | content | search(索引更新/删除) | 普通 | 笔记修改/删除 |
| SEARCH_TOPIC | HOT_RECORD | search | search | 普通 | 搜索词记录（热搜统计） |
| RECOMMEND_TOPIC | BEHAVIOR | home/search | search | 普通 | 用户行为上报 |

> **复用 Phase 1/2 的 Topic**：NOTE_TOPIC(PUBLISH/COMMENT) 和 PRODUCT_TOPIC(CHANGE) 已在 Phase 1/2 定义，Phase 3 新增消费者。

---

## 七、开发顺序与依赖关系

### 7.1 依赖关系

```
Phase 1 (完成) ──▶ Phase 2 (完成) ──▶ Phase 3
                                       │
                    ┌──────────────────┼──────────────────┐
                    ▼                  ▼                  ▼
               Step 1            Step 2+3            Step 4
           功能15-搜索          功能14-Feed          功能17-推荐
           （搜索先做，        功能16-热搜          （依赖搜索+Feed）
            Feed需要搜索）     （依赖搜索）
```

### 7.2 推荐开发顺序

| Step | 功能 | 预估工时 | 前置依赖 | 说明 |
|------|------|---------|---------|------|
| Step 1 | 功能15-搜索与搜索建议 | 5天 | Phase 2 | ES索引设计+增/全量同步+搜索建议+搜索历史 |
| Step 2 | 功能14-Feed流首页 | 4天 | Step 1 | BFF聚合层+推拉混合+CompletableFuture并行 |
| Step 3 | 功能16-热搜榜 | 2天 | Step 1 | 滑动窗口+时间衰减+反作弊 |
| Step 4 | 功能17-推荐系统 | 5天 | Step 2+3 | 5路召回+粗排精排+冷启动 |

> **说明**：搜索服务是 Feed 和热搜的前置依赖（Feed 的发现流需要搜索结果，热搜是搜索的子功能），推荐系统是最复杂的模块，需要搜索和Feed的基础设施。

---

## 八、服务间调用关系（Phase 3）

### 8.1 Feign 调用关系表

| 调用方 | 被调用方 | 方法 | 说明 |
|--------|---------|------|------|
| Home | Content | `getNotes(userId, feedType, page, size)` | 获取笔记列表 |
| Home | Content | `getNoteDetail(noteId)` | 获取笔记详情 |
| Home | User | `batchGetUsers(userIds)` | 批量获取用户信息 |
| Home | Analytics | `getSocialStatus(userId, noteIds)` | 获取社交状态(关注/点赞) |
| Home | Counter | `batchGetCounters(bizType, bizIds)` | 批量获取计数 |
| Home | Notification | `getUnreadCount(userId)` | 获取未读通知数 |
| Home | Search | `getRecommendFeed(userId, page, size)` | 获取推荐Feed |
| Home | Product | `getProductDetail(productId)` | 获取商品详情 |
| Home | Inventory | `getStockStatus(skuIds)` | 批量获取库存状态 |
| Search | Content | `batchGetNotes(noteIds)` | 全量同步时批量获取笔记 |
| Search | Product | `batchGetProducts(spuIds)` | 全量同步时批量获取商品 |
| Search | Counter | `batchGetCounters(bizType, bizIds)` | 获取热度计数 |

### 8.2 MQ 消费关系

| 消费者 | Topic:Tag | 说明 |
|--------|-----------|------|
| Home: NotePublishFeedConsumer | NOTE_TOPIC:PUBLISH | 笔记发布→推送到粉丝Feed收件箱 |
| Search: NoteIndexSyncConsumer | NOTE_TOPIC:PUBLISH/UPDATE/DELETE | 笔记变更→同步ES索引 |
| Search: ProductIndexSyncConsumer | PRODUCT_TOPIC:CHANGE | 商品变更→同步ES索引 |

---

## 九、数据库初始化 SQL 清单

### 9.1 my_xhs_search 数据库

> my-xhs-search 主要使用 ES，MySQL 仅用于热搜快照和推荐数据。

```sql
CREATE DATABASE IF NOT EXISTS my_xhs_search DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE my_xhs_search;

-- 热搜快照表
CREATE TABLE t_hot_search (
    id BIGINT PRIMARY KEY,
    keyword VARCHAR(64) NOT NULL COMMENT '搜索关键词',
    heat_score DECIMAL(16,4) NOT NULL DEFAULT 0 COMMENT '热度分数',
    search_count INT NOT NULL DEFAULT 0 COMMENT '搜索次数',
    note_count INT NOT NULL DEFAULT 0 COMMENT '关联笔记数',
    interaction_count INT NOT NULL DEFAULT 0 COMMENT '互动量(赞+评+藏)',
    status TINYINT DEFAULT 0 COMMENT '状态:0正常1置顶2屏蔽',
    snapshot_date DATE NOT NULL COMMENT '快照日期',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_keyword_date (keyword, snapshot_date),
    KEY idx_snapshot_date (snapshot_date),
    KEY idx_heat_score (heat_score)
) ENGINE=InnoDB COMMENT='热搜快照表';

-- 用户行为表
CREATE TABLE t_user_behavior (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    note_id BIGINT NOT NULL COMMENT '笔记ID',
    behavior_type TINYINT NOT NULL COMMENT '行为类型:1曝光2点击3点赞4收藏5评论6分享7停留',
    duration INT DEFAULT 0 COMMENT '停留时长(秒)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id),
    KEY idx_note_id (note_id),
    KEY idx_created_at (created_at),
    KEY idx_user_behavior (user_id, behavior_type, created_at)
) ENGINE=InnoDB COMMENT='用户行为表';

-- 内容特征表
CREATE TABLE t_item_feature (
    id BIGINT PRIMARY KEY,
    note_id BIGINT NOT NULL COMMENT '笔记ID',
    tags VARCHAR(512) COMMENT '标签列表JSON',
    category_id BIGINT COMMENT '分类ID',
    tfidf_vector TEXT COMMENT 'TF-IDF向量JSON',
    geohash VARCHAR(12) COMMENT '地理位置GeoHash',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_note_id (note_id),
    KEY idx_category_id (category_id),
    KEY idx_geohash (geohash)
) ENGINE=InnoDB COMMENT='内容特征表';

-- 推荐结果表
CREATE TABLE t_recommend_result (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    note_ids TEXT COMMENT '推荐笔记ID列表JSON',
    strategy VARCHAR(32) COMMENT '召回策略',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_created (user_id, created_at)
) ENGINE=InnoDB COMMENT='推荐结果表';
```

---

## 十、配置文件清单

### 10.1 my-xhs-home（✅ 已创建模块）

```yaml
server:
  port: 9015

spring:
  application:
    name: my-xhs-home
  data:
    redis:
      host: localhost
      port: 6379
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
      config:
        server-addr: localhost:8848
        file-extension: yaml
  cache:
    type: caffeine

# BFF聚合线程池
home:
  aggregator:
    core-pool-size: 20
    max-pool-size: 50
    queue-capacity: 200
    keep-alive-seconds: 60
    thread-name-prefix: "home-aggregator-"
```

### 10.2 my-xhs-search（✅ 已补充配置）

```yaml
server:
  port: 9011

spring:
  application:
    name: my-xhs-search
  datasource:
    url: jdbc:mysql://localhost:3306/my_xhs_search?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: localhost
      port: 6379
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
      config:
        server-addr: localhost:8848
        file-extension: yaml

# Elasticsearch配置
elasticsearch:
  host: localhost
  port: 9200
  scheme: https
  username: elastic
  password: changeme
  connect-timeout: 5000
  socket-timeout: 60000
  max-conn-total: 50
  max-conn-per-route: 10
```

---

## 十一、骨架问题清单（Phase 3 待解决）

| # | 问题 | 说明 | 状态 |
|---|------|------|------|
| 1 | my-xhs-home 模块未创建 | 需新建BFF聚合服务模块（端口9015） | ✅ 已创建 |
| 2 | search pom 缺少 ES 依赖 | 需添加 elasticsearch-java | ✅ 已补充 |
| 3 | search pom 缺少 MySQL 依赖 | 热搜快照/用户行为/推荐数据需要MySQL存储 | ✅ 已补充 |
| 4 | search pom 缺少 MyBatis-Plus 依赖 | MySQL数据操作需要MP | ✅ 已补充 |
| 5 | search 端口 9011 与技术规格一致 | 端口无冲突 | ✅ 无问题 |
| 6 | home 模块需要 OpenFeign 依赖 | BFF层通过Feign调用其他服务 | ✅ 已补充 |
| 7 | home 模块需要 Caffeine 依赖 | 本地缓存使用Caffeine | ✅ 已补充 |
| 8 | IK 分词器需预装到 ES | ES中文分词需要 ik_max_word/ik_smart | ❌ 待安装 |
| 9 | search application.yml 缺少 datasource 配置 | MySQL数据源未配置 | ✅ 已补充 |
| 10 | search application.yml 缺少 Redis 配置 | Redis未配置 | ✅ 已补充 |
| 11 | 文档中 PushFeignClient 应改为 NotificationFeignClient | 项目中推送服务实际叫 notification，不叫 push | ✅ 已修复 |
| 12 | 配置文件清单中 home YAML 有重复 spring 顶级key | spring.data.redis 和 spring.cache 在两个独立的 spring: 块中 | ✅ 已修复（application.yml 中合并） |
| 13 | 端口总表中 admin 端口写为 9014，实际为 9013 | 已移除admin模块 | ✅ 已处理 |

---

## 十二、文档索引

### 文档编写顺序

| 顺序 | 文档 | 说明 |
|------|------|------|
| 1 | [14-Feed流首页](../dev/Phase-3-content-distribution/14-feed-homepage/README.md) | 推拉混合+BFF聚合 |
| 2 | [15-搜索与搜索建议](../dev/Phase-3-content-distribution/15-search-and-suggestion/README.md) | ES双索引+Canal同步 |
| 3 | [16-热搜榜](../dev/Phase-3-content-distribution/16-hot-search-ranking/README.md) | 滑动窗口+时间衰减 |
| 4 | [17-推荐系统](../dev/Phase-3-content-distribution/17-recommendation-system/README.md) | 5路召回+粗排精排 |

---

## 十三、完整 DDL SQL（写代码时直接复制执行）

### 13.1 my_xhs_search

```sql
CREATE DATABASE IF NOT EXISTS my_xhs_search DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE my_xhs_search;

-- 热搜快照表
CREATE TABLE t_hot_search (
    id BIGINT PRIMARY KEY,
    keyword VARCHAR(64) NOT NULL COMMENT '搜索关键词',
    heat_score DECIMAL(16,4) NOT NULL DEFAULT 0 COMMENT '热度分数',
    search_count INT NOT NULL DEFAULT 0 COMMENT '搜索次数',
    note_count INT NOT NULL DEFAULT 0 COMMENT '关联笔记数',
    interaction_count INT NOT NULL DEFAULT 0 COMMENT '互动量',
    status TINYINT DEFAULT 0 COMMENT '状态:0正常1置顶2屏蔽',
    snapshot_date DATE NOT NULL COMMENT '快照日期',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_keyword_date (keyword, snapshot_date),
    KEY idx_snapshot_date (snapshot_date),
    KEY idx_heat_score (heat_score)
) ENGINE=InnoDB COMMENT='热搜快照表';

-- 用户行为表
CREATE TABLE t_user_behavior (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    note_id BIGINT NOT NULL COMMENT '笔记ID',
    behavior_type TINYINT NOT NULL COMMENT '行为类型:1曝光2点击3点赞4收藏5评论6分享7停留',
    duration INT DEFAULT 0 COMMENT '停留时长(秒)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id),
    KEY idx_note_id (note_id),
    KEY idx_created_at (created_at),
    KEY idx_user_behavior (user_id, behavior_type, created_at)
) ENGINE=InnoDB COMMENT='用户行为表';

-- 内容特征表
CREATE TABLE t_item_feature (
    id BIGINT PRIMARY KEY,
    note_id BIGINT NOT NULL COMMENT '笔记ID',
    tags VARCHAR(512) COMMENT '标签列表JSON',
    category_id BIGINT COMMENT '分类ID',
    tfidf_vector TEXT COMMENT 'TF-IDF向量JSON',
    geohash VARCHAR(12) COMMENT '地理位置GeoHash',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_note_id (note_id),
    KEY idx_category_id (category_id),
    KEY idx_geohash (geohash)
) ENGINE=InnoDB COMMENT='内容特征表';

-- 推荐结果表
CREATE TABLE t_recommend_result (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    note_ids TEXT COMMENT '推荐笔记ID列表JSON',
    strategy VARCHAR(32) COMMENT '召回策略',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_created (user_id, created_at)
) ENGINE=InnoDB COMMENT='推荐结果表';
```

---

## 十四、错误码枚举定义（Phase 3 新增，写代码时直接对照）

> **继承 Phase 1+2 的系统错误码和业务错误码（User:2xxxx, Content:3xxxx, Analytics:4xxxx, Counter:5xxxx, Product:6xxxx, Cart:7xxxx, Inventory:8xxxx, Coupon:9xxxx, Order:10xxxx, Payment:11xxxx, Notification:12xxxx）**

### 14.1 Home 服务（13xxxx）

| 错误码 | 枚举名 | 说明 |
|--------|--------|------|
| 130001 | FEED_LOAD_FAILED | Feed流加载失败 |
| 130002 | FEED_TYPE_INVALID | Feed类型无效(只支持follow/discover) |
| 130003 | NOTE_DETAIL_AGGREGATE_FAILED | 笔记详情聚合失败 |
| 130004 | PRODUCT_DETAIL_AGGREGATE_FAILED | 商品详情聚合失败 |
| 130005 | SERVICE_UNAVAILABLE | 依赖服务不可用（降级也失败） |

### 14.2 Search 服务（14xxxx）

| 错误码 | 枚举名 | 说明 |
|--------|--------|------|
| 140001 | SEARCH_FAILED | 搜索失败 |
| 140002 | SEARCH_KEYWORD_TOO_SHORT | 搜索关键词太短(最少2个字符) |
| 140003 | SUGGEST_FAILED | 搜索建议失败 |
| 140004 | ES_INDEX_NOT_FOUND | ES索引不存在 |
| 140005 | ES_INDEX_CREATE_FAILED | ES索引创建失败 |
| 140006 | INDEX_REBUILD_IN_PROGRESS | 索引重建进行中 |
| 140007 | HOT_SEARCH_LOAD_FAILED | 热搜榜加载失败 |
| 140008 | SEARCH_HISTORY_LIMIT_EXCEEDED | 搜索历史超限 |
| 140009 | RECOMMEND_FAILED | 推荐服务失败 |
| 140010 | BEHAVIOR_REPORT_FAILED | 行为上报失败 |

---

## 十五、MQ 消息体格式定义（Phase 3 新增）

### 15.1 通用消息信封（复用 Phase 1+2 格式）

```json
{
  "msgId": "唯一消息ID（雪花算法）",
  "bizType": "NOTE_PUBLISH/NOTE_UPDATE/NOTE_DELETE/PRODUCT_CHANGE/HOT_SEARCH_RECORD/USER_BEHAVIOR",
  "fromUserId": 1234567890,
  "toUserId": 0,
  "bizId": 111222333,
  "timestamp": 1715409600000,
  "extra": {}
}
```

### 15.2 各场景消息体

**笔记发布事件（NOTE_PUBLISH）— 新增 home 消费者**
```json
{
  "msgId": "5001",
  "bizType": "NOTE_PUBLISH",
  "fromUserId": 123,
  "toUserId": 0,
  "bizId": 100001,
  "timestamp": 1715409600000,
  "extra": {
    "noteId": 100001,
    "userId": 123,
    "title": "好物推荐",
    "status": 1,
    "createdAt": 1715409600000
  }
}
```

**笔记更新事件（NOTE_UPDATE）— Phase 3 新增**
```json
{
  "msgId": "5002",
  "bizType": "NOTE_UPDATE",
  "fromUserId": 123,
  "toUserId": 0,
  "bizId": 100001,
  "timestamp": 1715409600000,
  "extra": {
    "noteId": 100001,
    "userId": 123,
    "title": "好物推荐（已更新）",
    "status": 1
  }
}
```

**笔记删除事件（NOTE_DELETE）— Phase 3 新增**
```json
{
  "msgId": "5003",
  "bizType": "NOTE_DELETE",
  "fromUserId": 123,
  "toUserId": 0,
  "bizId": 100001,
  "timestamp": 1715409600000,
  "extra": {
    "noteId": 100001,
    "userId": 123
  }
}
```

**搜索词记录事件（HOT_SEARCH_RECORD）— Phase 3 新增**
```json
{
  "msgId": "5004",
  "bizType": "HOT_SEARCH_RECORD",
  "fromUserId": 123,
  "toUserId": 0,
  "bizId": 0,
  "timestamp": 1715409600000,
  "extra": {
    "keyword": "美妆推荐",
    "userId": 123,
    "ip": "192.168.1.100"
  }
}
```

**用户行为上报事件（USER_BEHAVIOR）— Phase 3 新增**
```json
{
  "msgId": "5005",
  "bizType": "USER_BEHAVIOR",
  "fromUserId": 123,
  "toUserId": 0,
  "bizId": 100001,
  "timestamp": 1715409600000,
  "extra": {
    "userId": 123,
    "noteId": 100001,
    "behaviorType": 2,
    "duration": 30
  }
}
```

---

## 十六、包结构规范（Phase 3 补充）

Phase 3 新增服务的包结构遵循 Phase 1+2 规范，补充以下特殊包：

```
com.myxhs.home/
├── aggregator/                — 聚合器（仅 home 模块）
│   ├── HomeFeedAggregator.java    — Feed聚合核心
│   ├── NoteDetailAggregator.java  — 笔记详情聚合
│   ├── ProductDetailAggregator.java — 商品详情聚合
│   └── UserProfileAggregator.java — 用户主页聚合
├── fallback/                  — 降级工厂（仅 home 模块）
│   └── ServiceFallbackFactory.java — 通用降级（Counter→0, Social→未关注）
├── feed/                      — Feed流核心（仅 home 模块）
│   ├── FeedPushService.java       — 推模式（写扩散）
│   ├── FeedPullService.java       — 拉模式（读扩散）
│   ├── BigVDetector.java          — 大V检测器
│   └── FeedMergeService.java      — 推拉合并
├── feign/                     — Feign客户端（仅 home 模块）
│   ├── NoteFeignClient.java       — 笔记服务
│   ├── UserFeignClient.java       — 用户服务
│   ├── SocialFeignClient.java     — 社交服务
│   ├── CounterFeignClient.java    — 计数服务
│   ├── NotificationFeignClient.java — 通知服务（未读数）
│   ├── SearchFeignClient.java     — 搜索服务
│   ├── ProductFeignClient.java    — 商品服务
│   └── InventoryFeignClient.java  — 库存服务

com.myxhs.search/
├── es/                        — ES封装（仅 search 模块）
│   ├── EsIndexInitializer.java    — ES索引初始化
│   ├── NoteDocumentMapper.java    — 笔记↔ES文档转换
│   ├── ProductDocumentMapper.java — 商品↔ES文档转换
│   └── SearchQueryBuilder.java    — ES查询构建器
├── hot/                       — 热搜核心（仅 search 模块）
│   ├── HotSearchCalculator.java   — 热度计算器（时间衰减）
│   ├── SlidingWindowCounter.java  — 滑动窗口计数器
│   └── HotSearchAntiSpam.java     — 反作弊过滤器
├── recall/                    — 召回策略（仅 search 模块）
│   ├── RecallStrategy.java        — 召回策略接口
│   ├── ItemCFRecallStrategy.java  — 协同过滤召回
│   ├── ContentRecallStrategy.java — 内容召回
│   ├── HotRecallStrategy.java     — 热门召回
│   ├── FollowingRecallStrategy.java — 关注召回
│   ├── GeoRecallStrategy.java     — 地理位置召回
│   └── RecallMerger.java          — 多路召回合并器
├── rank/                      — 排序层（仅 search 模块）
│   ├── RoughRanker.java           — 粗排
│   ├── FineRanker.java            — 精排
│   └── ReRanker.java              — 重排
├── coldstart/                 — 冷启动（仅 search 模块）
│   ├── ColdStartStrategy.java     — 冷启动策略接口
│   ├── NewUserColdStart.java      — 新用户冷启动
│   └── NewItemColdStart.java      — 新内容冷启动
```

---

## 十七、Feign 调用规范（Phase 3 补充）

### 17.1 Home 服务 Feign 拦截器

Home 服务通过 Feign 调用其他服务时，需要透传当前用户上下文（复用 Phase 2 的 FeignUserContextInterceptor）：

```java
@Component
public class FeignUserContextInterceptor implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate template) {
        UserContext ctx = UserContextHolder.get();
        if (ctx != null) {
            template.header("X-User-Id", String.valueOf(ctx.getUserId()));
            template.header("X-Trace-Id", ctx.getTraceId());
        }
    }
}
```

### 17.2 Home 服务降级规范

```java
// 通用降级包装器
public <T> T callWithFallback(Supplier<T> supplier, Supplier<T> fallback, String serviceName) {
    try {
        return supplier.get();
    } catch (Exception e) {
        log.warn("服务调用降级: {}, 原因: {}", serviceName, e.getMessage());
        return fallback.get();
    }
}
```

| 服务 | 降级策略 | 降级数据 |
|------|---------|---------|
| Note | 不降级，失败直接返回错误 | — |
| User | 不降级，失败直接返回错误 | — |
| Counter | 降级返回0计数 | `{likeCount:0, favoriteCount:0, commentCount:0}` |
| Social | 降级返回未关注状态 | `{isFollowed:false, isLiked:false}` |
| Notification | 降级返回0未读 | `{unreadCount:0}` |
| Search | 降级返回热门推荐 | `{results: cachedHotNotes}` |
| Product | 不降级，失败直接返回错误 | — |
| Inventory | 降级返回"查看库存" | `{inStock: null, showCheckButton: true}` |

---

## 十八、BFF 聚合层架构说明

> **架构说明**：my-xhs-home 是 BFF（Backend For Frontend）聚合层，**不拥有数据库**，不写业务逻辑，只做聚合+裁剪+缓存+降级。通过 CompletableFuture 并行调用 6-8 个后端服务，将首页 RT 从串行 320ms 优化到并行 100ms。使用独立 ThreadPoolExecutor（不共享 ForkJoinPool），避免聚合调用阻塞其他业务线程。

### 18.1 聚合接口性能目标

| 接口 | 目标RT(P99) | 并行服务数 | 缓存策略 |
|------|------------|-----------|---------|
| GET /api/home/feed | < 200ms | 6 | Caffeine 5秒 |
| GET /api/home/note/{id} | < 150ms | 4 | Redis 30秒 |
| GET /api/home/product/{id} | < 150ms | 4 | Redis 30秒 |
| GET /api/home/user/{id} | < 100ms | 3 | Redis 60秒 |

### 18.2 线程池配置

```yaml
home:
  aggregator:
    core-pool-size: 20       # 核心线程数
    max-pool-size: 50        # 最大线程数
    queue-capacity: 200      # 队列容量
    keep-alive-seconds: 60   # 空闲线程存活时间
    thread-name-prefix: "home-aggregator-"
```

> **为什么不用 ForkJoinPool.commonPool()**：
> 1. 线程数=CPU核数-1，8核只有7线程，高并发不够
> 2. 所有 CompletableFuture 共享，BFF聚合可能阻塞其他业务
> 3. 无法独立监控和调优

---

## 十九、每个 Step 的验收 Checklist

### Step 1: 功能15-搜索与搜索建议

- [ ] ES索引创建：note_index + product_index + suggest_index
- [ ] IK分词器安装并验证：ik_max_word/ik_smart 分词效果
- [ ] 笔记搜索：关键词匹配+高亮+分页
- [ ] 商品搜索：关键词+分类筛选+价格区间+排序
- [ ] 搜索建议：Completion Suggester 前缀匹配
- [ ] 搜索历史：Redis List 存储，最多20条，支持删除
- [ ] Canal增量同步：Binlog→MQ→ES，延迟<5秒
- [ ] 全量索引重建：@Scheduled分页扫描+断点续传
- [ ] 深分页优化：Search After 替代 from+size
- [ ] Gateway 路由到 search 服务

### Step 2: 功能14-Feed流首页

- [ ] BFF聚合层：CompletableFuture 并行调用6个服务
- [ ] 推模式：普通用户发笔记→推送到粉丝收件箱
- [ ] 拉模式：大V发笔记→粉丝读取时实时拉取
- [ ] 大V检测：粉丝数>10万标记为拉模式
- [ ] 推拉合并：收件箱+发件箱合并排序分页
- [ ] 游标分页：基于时间戳的游标分页
- [ ] 降级兜底：Counter→0, Social→未关注, Notification→0未读
- [ ] 本地缓存：Caffeine 5秒缓存Feed结果
- [ ] 独立线程池：不共享 ForkJoinPool
- [ ] 笔记详情聚合：笔记+计数+社交状态+关联商品
- [ ] 商品详情聚合：商品+库存+计数+关联笔记
- [ ] Gateway 路由到 home 服务

### Step 3: 功能16-热搜榜

- [ ] 搜索词记录：用户搜索时记录到滑动窗口
- [ ] 滑动窗口：Redis分钟桶统计
- [ ] 时间衰减：e^(-λ×Δt) 近期搜索权重更高
- [ ] 热搜排行：ZSet Top50
- [ ] 反作弊：同用户/IP频率限制
- [ ] 人工干预：置顶/屏蔽热搜词
- [ ] 快照持久化：@Scheduled每5分钟写入DB
- [ ] 历史热搜查询：按日期查询快照

### Step 4: 功能17-推荐系统

- [ ] 5路召回并行执行：Item-CF+内容+热门+关注+地理
- [ ] Item-CF协同过滤：用户行为→物品相似度→Redis矩阵
- [ ] 内容召回：TF-IDF标签匹配
- [ ] 热门召回：ZSet Top-N
- [ ] 关注召回：关注用户最新笔记
- [ ] 地理召回：GeoHash同城内容
- [ ] 粗排：多路归并+加权打分
- [ ] 精排：规则排序（预留ML接口）
- [ ] 重排：去重+多样性+已读过滤
- [ ] 冷启动：新用户热门+地理+兴趣标签
- [ ] 用户行为上报：曝光/点击/停留时长
- [ ] 内容特征提取：@Scheduled每小时

---

## 二十、端口分配总表（含 Phase 1 + Phase 2 + Phase 3）

| 服务 | 端口 | 数据库 | Phase |
|------|------|--------|-------|
| my-xhs-gateway | 9000 | — | 1 |
| my-xhs-user | 9001 | my_xhs_user | 1 |
| my-xhs-content | 9002 | my_xhs_note | 1 |
| my-xhs-analytics | 9003 | my_xhs_social | 1 |
| my-xhs-counter | 9004 | my_xhs_counter | 1 |
| my-xhs-product | 9005 | my_xhs_product | 2 |
| my-xhs-order | 9006 | my_xhs_order (分库) | 2 |
| my-xhs-payment | 9007 | my_xhs_payment | 2 |
| my-xhs-inventory | 9008 | my_xhs_inventory | 2 |
| my-xhs-cart | 9009 | my_xhs_cart | 2 |
| my-xhs-coupon | 9010 | my_xhs_coupon (分库) | 2 |
| my-xhs-search | 9011 | my_xhs_search (ES+MySQL) | 3 |
| my-xhs-notification | 9012 | my_xhs_notification | 2 |
| my-xhs-im | 9014 | my_xhs_im | 4 |
| my-xhs-home | 9015 | 无（BFF聚合） | 3 |
