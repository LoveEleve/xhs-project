# G6-search-home — 搜索/首页/推荐

> 服务：search(19016) + home(19015) + 联动 content(19002)/user(19001)/analytics(19003)/counter(19004)/notification(19013)/product(19006) | 入口：**gateway(19000)**
> 依赖：G1 登录 + G2 笔记发布/关注（Feed 推模式链路已在 G2-01 验证，G6 聚焦读取侧/大V/搜索/推荐）
> 时间引用：矩阵 **#16**（feed 清理，ZSet score 操纵 + xxl#18）、**#17**（大V/进度 TTL 查证）、**#19/20/21**（recommend×3 手动触发）、**#2**（热搜/增量补偿 @Scheduled 60s）、**#23/24**（suggest 缓存 1h/空 5min）

## 业务范围
笔记搜索 + 商品搜索（ES 8.x，Search After 深分页 + 高亮）→ 搜索建议（Completion Suggester）→ 搜索历史 → 热搜榜（滑动窗口 + 指数衰减 + 反作弊 + 置顶/屏蔽/快照）→ 索引全量重建 + 增量补偿（canal→MQ→ES）→ 首页聚合（BFF 并行聚合 5 类接口）→ 关注 Feed 流（推拉混合 + 大V 拉模式 + 游标分页）→ 推荐系统（5 路召回→粗排→精排→重排 + 行为上报 + ItemCF/特征/热池离线计算）

## 归属定时/联动任务
- **home 域**：feedCleanupJob（xxl#18，组 11，每天3点）；进程内 FeedPushConsumer（FEED_TOPIC）、NoteDeleteConsumer（SOCIAL_TOPIC:NOTE_DELETE）
- **search 域**：IndexRebuildJob（@Scheduled 每天4点 + 管理端点手动）、IncrementalIndexSyncJob（@Scheduled 60s）、HotSearchService.calculateHotSearch（@Scheduled 60s）；消费者 NoteIndexSyncConsumer（NOTE_INDEX_TOPIC）/ProductIndexSyncConsumer（PRODUCT_INDEX_TOPIC）/BehaviorReportConsumer（RECOMMEND_BEHAVIOR_TOPIC）
- **recommend 离线（xxl 组 12）**：recommendFeatureJob（#19 每小时）、recommendHotPoolJob（#20 每10分钟）、recommendItemCFJob（#21 每天2点）——全部手动触发（矩阵 #19/20/21）
- **content 域**：FeedMessageRetryJob（@Scheduled 30s/60s，G2-01 已验证，不重复）

## 用例文档
- **G6-01-search.md**：搜索/建议/历史/热搜/索引（16 用例）
- **G6-02-home-feed.md**：Feed 读取/大V/聚合/清理（10 用例）
- **G6-03-recommend.md**：推荐/行为/离线计算（12 用例）

## 关键数据关注矩阵（代码实证 2026-08-14）
| 用例域 | Redis key | MySQL | ES/MQ |
|---|---|---|---|
| 笔记搜索 | `myxhs:search:history:{uid}`（List 20 条，30 天 TTL） | t_note（my_xhs_content） | note_index（filter status=2；删除标记 -1）|
| 商品搜索 | — | t_spu（my_xhs_product，跨库前缀） | product_index（filter status=1；price=首 SKU）|
| 建议 | `myxhs:search:suggest:cache:{md5(prefix)}`（1h；空 5min） | t_note.title | suggest_index（**仅 IndexRebuildJob 全量填充，无增量**）|
| 热搜 | `myxhs:search:window:{yyyyMMddHHmm}`（Hash 2h）、`myxhs:search:hot:realtime`（ZSet 1h）、`myxhs:search:hot:pinned/blocked`（Set）、`myxhs:search:antispam:user:{uid}:{kw}`（300s）/`ip:{ip}`（60s/10次） | t_hot_search_snapshot（content 库） | — |
| 索引重建 | `myxhs:search:index:rebuild:status`（Hash：lastNoteId/lastSpuId/totalIndexed/status，1d）| t_note + my_xhs_product.t_spu | 三索引 Bulk + 锁 `myxhs:lock:job:search:index:rebuild` |
| 增量补偿 | `myxhs:es:sync:failed:note/product`（Set 1h） | 同索引重建 | 补偿 Bulk（ExternalGte version=updated_at 毫秒）|
| Feed 收件箱 | `myxhs:feed:inbox:{uid}`（ZSet score=发布毫秒，TTL 7 天）、`myxhs:feed:push:progress:{localMsgId}`（Hash cursor/total/status，1h）| t_local_message（G2 已验） | FEED_TOPIC |
| 大V 发件箱 | `myxhs:feed:outbox:{uid}`（ZSet，TTL 7 天）、`myxhs:user:bigv:{uid}`（10min 缓存）| — | NOTE_DELETE→清 outbox |
| 推荐 | `myxhs:recommend:itemcf:{noteId}`（ZSet Top20，24h）、`myxhs:recommend:hot:global`（ZSet，1h）、`myxhs:recommend:seen:{uid}`（Set 7 天）、`myxhs:recommend:user:tags:{uid}`（Hash 12h）| t_user_behavior（7 值枚举）、**t_item_feature（⚠️ 表不存在，见 §风险）** | RECOMMEND_BEHAVIOR_TOPIC |

## 执行纪律（G1-G5 教训 + G6 特有）
- 服务重启必须带 INTERNAL_TOKEN/ADMIN_TOKEN（#79-1）；**search/home 全部内部 Feign 依赖内部令牌**
- **搜索/推荐端点无 @RateLimit**（代码实证）；gateway 路由级 rate-limit-qps：search=300/recommend=100/home=50；聚合接口 response-timeout home=5s/search=2s——**连续无间隔压测注意 #81-6 连接排队超时（间隔 0.3-0.5s）**
- **鉴权矩阵（gateway 运行态实证 2026-08-14，REVIEW 修正）**：`/api/search/note|product|suggest` 与 `/api/home/feed|note/**|product/**|user/**` **不在 JWT white-list（本地 yml 仅注释 `# - /api/search/**`；Nacos 只覆盖 secret）→ 需 JWT（无 token → 401），免 HMAC**（hmac-white-list 含）；需 **JWT+HMAC**=`/api/search/hot*`、`/api/search/history*`、`/api/recommend/**`、`/api/home/cart`；管理端点=**JWT + X-Admin-Call（HMAC 豁免）**=`/api/search/index/rebuild`、`/api/search/hot/pin|block`（PUT/DELETE）、`/api/recommend/compute`
- **ES 查询带认证**（19200 elastic/Xhs@2026#Elastic）；**L2 查 ES 前 sleep 1-2s**（refresh 默认 1s；canal→MQ→consumer 秒级）；`_delete_by_query` 清理
- **跨库前缀**（#37/#59/#71）：search 数据源=my_xhs_content（主 3306/从 3307）；查 t_spu 必须 `my_xhs_product.t_spu`
- **版本域统一**（#58）：canal ts 毫秒 ExternalGte；补偿用 updated_at 毫秒；禁止混用 es 小整数
- 搜索历史/热搜/建议缓存跨用例共享——执行前 DEL 相关 key；**热搜记录会自动累积（执行中搜索词会互相污染热搜榜断言——用例间清理 window/antispam key）**
- **大V 判定操纵**：`SET myxhs:user:bigv:{uid} 1`（10min TTL 缓存生效）或构造粉丝 ZCARD≥100000（不现实）；清理 DEL
- **行为上报是 MQ 异步落库**（等 1-3s）；t_user_behavior 无幂等（重复投递重复插——观察项，清理用 id 前缀）
- 写后读主从延迟：Redis 写后 sleep 1-2s 再断言；xxl 触发后查 xxl_job_log（trigger_code=200 + handle_code）

## 风险/前置检查（执行前确认）
0. **运行态基线（2026-08-14 第三轮 REVIEW 实测）**：search/home 15 服务 UP ✅；**t_note=0、my_xhs_product.t_spu=0（当前数据全空——G6 执行前需 G2/G3 造数据）**；**ES：note_index=0 / product_index=0 / suggest_index=47 残留（HANDOFF"双索引 0"未含 suggest——重建是 upsert 不清旧文档，执行前必须 `_delete_by_query` 清 suggest_index）**；Redis G6 域 key 全空 ✅；**canal 11111 端口不可达（可能容器内监听）——执行前以 NOTE_INDEX_TOPIC 消息/ES 文档变化验证 canal 是否在运行**；xxl 组 11=home/组 12=search 执行器确认 ✅；gateway 限流=Sentinel route 维度（1s 窗口，429 响应，search=300/home=50/recommend=100）确认 ✅
1. **⚠️ t_item_feature 表不存在（2026-08-14 运行环境实证）**：recommendFeatureJob INSERT 目标表缺失（catch 后任务仍 handle 200，但特征不落库）→ 精排 category 恒 unknown/quality 恒 0.3、品类打散失效、兴趣标签不更新、**CONTENT/GEO 两路召回空**——**执行前向对方确认 DDL 或登记问题（T-080+）**
2. **⚠️ FOLLOWING 召回恒空（REVIEW 实证）**：`myxhs:recommend:following:latest:{uid}` 无任何生产者（FeedPushConsumer 只写 inbox/outbox）→ reason"你关注的人发布了"永不出现——登记观察项（修复=FeedPushConsumer 同步写 following:latest 或移除策略）
3. **suggest_index 无增量写入**（仅 IndexRebuildJob 每日4点/手动重建填充）——新发布笔记标题需手动触发重建后才进建议
4. IndexRebuildJob 商品重建字段**不全**（categoryName/price/sales 默认值）——product_index 以 ProductIndexSyncConsumer（Feign 补全）为准，重建会覆盖为默认值（观察项）
5. 推荐精排 `batchGetQualityScores` 查 t_item_feature——表缺失时降级 0.3（同 #1）
6. HotSearchService 快照 SQL 以**当前分钟**快照——calculate 周期内重复触发会插重复快照行（观察项，查询按 snapshot_time 取最新）
7. **⚠️ product 侧 canal 版本域疑似 #58 残留**（REVIEW 实证）：ProductIndexSyncConsumer 优先 es（小整数）而 NoteIndexSyncConsumer 已统一 ts（毫秒）——补偿写入后 canal 增量可能被 ExternalGte 拒绝（G6-01-14 附加断言验证）
8. **ItemCF 召回/计算口径不一致**：召回正向=3/4 或 7>10s（不含评论/分享），计算任务=3/4/5/6 或 7>10s；时效衰减默认 24h（publishTime 仅 GEO 设置）——G6-03 断言按此

## 执行记录
| 文档 | 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|---|
| G6-01-search.md | 16 用例 | 2026-08-15 15:34~15:43 | ✅ 16/16 | 回归（Task9 后）；同秒 UPDATE 边界观察；未知路径 403 语义 |
| G6-02-home-feed.md | 10 用例 | 2026-08-15 15:44~15:52 | ✅ 10/10 | **T-112 新发现并修复（ProductAggService skuName→name）** |
| G6-03-recommend.md | 12 用例 | 2026-08-15 15:53~16:00 | ✅ 12/12 | T-087/A-1 修复确认（FOLLOWING 召回出现/特征表可用）；打散 T-082 修复验证 |

> 执行中发现并修复：**T-081（P1，home 聚合 String→Number 强转 500，已打包重启验证）**；登记观察 T-082~T-090。
> **2026-08-14 回归（第二次全量）38/38 全绿**：修复 **T-091**；新观察 T-092/T-093。
> **2026-08-14 第三轮全量回归（第三次，脏数据再次清零后）38/38 全绿零失败**：断言按运行态事实固化（T-082/092/093）、T-091 稳定性多轮验证、环境基线全 0。**当前 home（T-081）/search（T-091）运行 jar 均为修复后版本**
