# my-xhs-search 模块分析

## 1. 模块定位
搜索/推荐域（19016）：ES 全文搜索（笔记/商品）、热搜、推荐（特征/热池/ItemCF）、索引同步。含 SearchController、RecommendController、ES 配置（ElasticsearchConfig/IndexInitializer）、4 个 MQ Consumer（索引同步/行为上报/点赞同步）、XXL-Job（特征/热池/ItemCF 更新）。

## 2. 代码事实
- 37 个 java：2 Controller、4 Consumer（NoteIndexSync/ProductIndexSync/BehaviorReport/LikeCountSync）、ES 3（Config/Initializer/IncrementalJob）、推荐 DTO/线程池

## 3. 核心链路
```text
笔记/商品变更 → CANAL/ES 索引同步消息 → NoteIndexSyncConsumer/ProductIndexSyncConsumer
   → ES 索引写入(增量/删除)
用户行为(RECOMMEND_BEHAVIOR_TOPIC) → BehaviorReportConsumer → 行为特征
GET /api/search/note|product → ES query（分词/过滤/分页）
GET /api/search/hot + POST /api/search/hot/record → 热搜(Redis)
推荐: 特征/热池/ItemCF XXL-Job 预计算 → Redis → RecommendController
```

## 4. 数据流转
| 项 | 内容 |
|---|---|
| ES | note_index、product_index（IndexInitializer 启动建索引） |
| Redis | 热池、热搜、推荐结果缓存 |
| MQ | 消费 PRODUCT_INDEX_TOPIC、NOTE_INDEX_TOPIC、RECOMMEND_BEHAVIOR_TOPIC、SOCIAL_TOPIC(LIKE) |
| XXL-Job | recommendFeatureJob/recommendHotPoolJob/recommendItemCFJob（已注册） |

## 5. 关键决策
- ES 索引同步双通道（生产者 MQ + CANAL canalMsg）；版本号 ts/es 防乱序
- 推荐预计算 + 缓存（避免实时计算）
- LikeCountSync 同步点赞数到索引

## 6. 运行态验证
- **未全面实测**（ES 索引内容/搜索需真实索引数据）
- 服务 UP、topic 已创建、3 个 XXL-Job 已注册、consumer group 在线
- 依赖 ES 容器健康（中间件 28 容器 healthy）

## 7. 鉴权基础
- 搜索/推荐公开读（gateway 白名单或 JWT）；index/rebuild 管理端点需 X-Admin-Call

## 8. 风险
- ES 索引与 MySQL 数据一致性（双通道 + 版本号，对账缺运行验证）
- 索引重建（IncrementalIndexSyncJob/rebuild）生产可用性
- 推荐结果质量依赖行为数据量

## 9. 覆盖对账
- Controller/Consumer/索引同步/推荐结构已读；ES query 细节标注
- 推荐算法（ItemCF）实现标注
- **运行态未全面实测**（需真实索引数据驱动搜索/推荐）
