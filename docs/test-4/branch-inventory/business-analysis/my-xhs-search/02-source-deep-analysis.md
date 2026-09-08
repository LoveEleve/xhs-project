# my-xhs-search 模块深度分析

## 1. 当前模块定位

search（19016）是搜索/推荐服务：ES 全文搜索（笔记/商品）、热搜、建议、搜索历史、个性化推荐（特征/热池/ItemCF/召回策略）。通过 MQ 消费笔记/商品变更同步 ES 索引，消费行为上报驱动推荐特征。

## 2. 当前代码事实

- 启动入口 `SearchApplication`；Controller：SearchController（note/product/suggest/history/hot/index-rebuild/hot-snapshot）、RecommendController（feed/similar/behavior/compute）。
- 搜索：NoteSearchService、ProductSearchService、AbstractSearchService（通用）。
- 推荐：RecommendService、6 个 RecallStrategy（Content/Following/Geo/Hot/ItemCF/组合）、RecommendComputeJob。
- 索引：IndexInitializer、NoteIndexSyncConsumer、ProductIndexSyncConsumer、IncrementalIndexSyncJob、IndexRebuildJob、ProductIndexDocumentBuilder。
- 其他：BehaviorReportConsumer、LikeCountSyncConsumer、SearchHistoryService、SuggestService、HotSearchService。
- 配置：ElasticsearchConfig、RecommendThreadPoolConfig。

## 3. 关键业务链路与源码流转

### 3.1 搜索

```text
GET /api/search/note|product?keyword
→ AbstractSearchService: 分词/过滤/分页/高亮
→ ES query（note_index/product_index）
→ 返回 SearchResultVO + highlight
```

### 3.2 索引同步

```text
笔记/商品变更 → 生产者MQ(PRODUCT_INDEX_TOPIC/NOTE_INDEX_TOPIC) 或 Canal
→ NoteIndexSyncConsumer/ProductIndexSyncConsumer: build文档 → ES index(ExternalGte版本防乱序)
→ IncrementalIndexSyncJob/IndexRebuildJob: 兜底补偿/全量重建
```

### 3.3 推荐

```text
行为上报(RECOMMEND_BEHAVIOR_TOPIC) → BehaviorReportConsumer → 行为特征
RecommendComputeJob: 特征/热池/ItemCF 预计算 → Redis
RecommendController.feed/similar: 组合多个 RecallStrategy → 推荐列表
```

### 3.4 热搜

```text
GET /hot + POST /hot/record → HotSearchService(Redis ZSet 实时 + 快照)
```

## 4. 数据流转

| 中间件 | key/表/index/topic | 说明 |
|---|---|---|
| ES | note_index、product_index、suggest_index | 搜索/建议 |
| Redis | 热池、热搜、推荐结果缓存、搜索历史 | 缓存/实时 |
| MQ | PRODUCT_INDEX_TOPIC/NOTE_INDEX_TOPIC/RECOMMEND_BEHAVIOR_TOPIC/SOCIAL_TOPIC | 索引同步/行为/点赞 |
| XXL-Job | recommendFeature/HotPool/ItemCF、增量同步 | 预计算/兜底 |

## 5. 跨模块与分布式行为

- 消费 product/content 的索引变更与行为上报。
- ES 版本控制（ExternalGte）防乱序覆盖。
- 推荐预计算 + Redis 缓存，避免实时计算。

## 6. 性能与工程质量

- 搜索分页/深分页 search_after。
- 推荐线程池隔离。
- 索引双通道（MQ + Canal）+ 增量兜底。

## 7. 鉴权基础检查

- 搜索/推荐公开读；index/rebuild、compute 为管理/内部调用。

## 8. 当前分支/改动点

- ES JavaTimeModule 修复（LocalDateTime 序列化）、单条 SPU 脏数据不阻塞批次。

## 9. 风险与测试重点

- 代码：索引文档构建、版本乱序。
- 业务：搜索相关性、推荐质量、热搜冷启动。
- 分布式：ES 与 MySQL 一致性、双通道同步。
- 可观测：索引失败/DLQ、推荐耗时。

## 10. 覆盖对账

Controller/Consumer/索引构建/推荐结构已深读；搜索/推荐算法实现细节、DTO 随调用链核对。
