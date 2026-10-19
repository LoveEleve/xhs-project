# 11 · home BFF（my-xhs-home）· 梳理

> 系列：模块对照 · 阶段一（梳理）。**只记录，不讨论**（问题线索见 §7，待讨论）。
> 口径：标尺 = **真实 BFF / Feed 系统该有的样子**（见 §2）。
> 规模：38 文件 / 3471 行。端口 19015。深度档：**支撑轻**（但 Feed 推拉混合是内容分发核心）。
> 建立：2026-10-12。

---

## 1. 定位与职责

**纯 BFF**（无自有 MySQL 库、无实体/Mapper——数据面只有 Redis）+ **Feed 系统**（推拉混合，比一般 BFF 重）：

1. **页面聚合**：关注 Feed 流、笔记详情、商品详情、用户主页、购物车页（两大页 × 5 聚合服务，并行编排 + 分层降级）；
2. **Feed 读写扩散**：发布事件 → 普通用户推粉丝收件箱 / 大V写自己发件箱（粉丝读时拉取）→ 删除联动清理；
3. **降级纪律**：关键依赖（笔记/SPU）失败 → 503；辅助数据（计数/关系/作者）失败 → 默认值。

## 2. 应然基准（标尺）与实然对照

| 应然能力 | 我们 | 差距 |
|---|---|---|
| 页面聚合（并行 + 超时 + 降级） | ✅ 两层 CompletableFuture 编排（L1 3s / L2 2s 总超时）+ Controller 返回 `CompletableFuture`（Servlet async 释放 Tomcat 线程）+ 每客户端降级工厂 | — |
| 降级语义分层 | ✅ 关键依赖 → `DownstreamUnavailableException` → 503；辅助 → 空 Map/默认值；**404 与 503 区分**（ProductAgg 修复"超时误判 404"） | — |
| Feed 分发（推拉混合） | ✅ inbox（推）+ 大V outbox（拉）+ 合并去重游标分页；大V阈值 10 万（配置统一——原"5 万硬编码 vs 配置"双口径已修） | — |
| 推送可靠性 | ✅ 断点续推（进度 Redis，MQ 重试续传）+ T-126 已删拦截 + 三条路径都标记完成（防补偿 Job 死循环） | — |
| 读 Feed 正确性 | ✅ 游标位运算开区间（P0-C min/max 颠倒修复）+ 多取 1 条判 hasMore（T-085/T-125）+ 脏成员跳过（T-084） | — |

## 3. 数据模型

**无自有库**（resources 无 datasource 配置；无 mapper/entity）。Redis 键：

| 键 | 用途 |
|---|---|
| `myxhs:feed:inbox:{uid}`（ZSet，member=noteId，score=发布时间） | 推模式收件箱（TTL 7d；上限 500 由清理 Job 裁剪） |
| `myxhs:feed:outbox:{uid}`（ZSet） | 大V发件箱（拉模式数据源；TTL 7d） |
| `myxhs:feed:push:progress:{localMsgId}`（Hash） | 断点续推进度（cursor/status；TTL 1h） |
| `myxhs:feed:following:bigv:{uid}`（Set，5min） | 用户关注的大V缓存 |
| `myxhs:user:bigv:{uid}`（10min） | 大V标记（发布侧与读侧共用） |
| `myxhs:note:deleted:{noteId}`（7d，**注释称 5min——见 §7-1**） | 已删拦截标记（防"删后晚到的推送"复活） |
| `recommend:following:latest:{uid}`（ZSet） | 关注召回源（T-087：FeedPushConsumer 同步写；原无写入方导致 FOLLOWING 召回恒空） |

## 4. 核心链路

### A. BFF 聚合（示例：Feed；note/product/cart/user 同构）

```
HomeController（/api/home/*，CompletableFuture 异步返回，aggregatorPool 提交）
FeedService.getFollowFeed：
  读：inbox（reverseRangeByScore 开区间，多取 1 判 hasMore）＋ 大Voutbox（Pipeline 批量 ZREVRANGEBYSCORE，每大V 均摊 size）
      → 合并去重（同 noteId 保高分）→ 排序 → Top size → 游标 nextCursor
  聚合两层：
    L1 并行（3s）：笔记详情（content 批量接口）＋点赞状态（analytics 批量）＋未读数（notification）
       —— 笔记详情异常 → 503；其余降级空值
    L2 并行（2s，依赖 L1 的 authorIds）：作者信息 + 计数（counter batch-get 1/2/3 类）
  组装 NoteCardVO（isCollected=false / isFollowed=true 为硬编码简化）
  大V判定：MGET `user:bigv:*` 批量标记；缓存缺失回源 ZCARD(fans) 并回写（注释自析"否则大V被误判 → 粉丝永久看不到"）
```

### B. Feed 写扩散（FeedPushConsumer，FEED_TOPIC）

```
T-126：`note:deleted:{noteId}` 存在 → 跳过并**必须标记完成**（否则补偿 Job 每 60s 重投）
大V（粉丝≥阈值，10min 缓存）→ ZADD 作者 outbox（拉模式）
普通用户 → 遍历粉丝（ZRANGE 500/批）：Pipeline 一次写三件事：
    fan inbox ZADD + EXPIRE 7d
    recommend:following:latest ZADD（T-087 召回源）
  每批写进度 cursor（断点续推，P0-3；ZADD 幂等故续推不重）
超大粉丝量保护（>阈值）→ 改写 outbox（防阻塞 MQ 线程）
三路径统一 markPushCompleted（进度 status=completed；失败容忍——content 补偿 Job 读 MySQL push_status 兜底）
```

### C. Feed 删除联动（NoteDeleteConsumer，SOCIAL_TOPIC:NOTE_DELETE）

```
清 outbox + SCAN `recommend:following:latest:*` 清所有召回源（修复前只清 outbox：T-126 关联）
+ 设 `note:deleted:{noteId}` 标记（**代码 7d**）供晚到的 FEED_TOPIC 消息拦截
收件箱里已推的 member 不清（读侧"笔记不存在跳过"兜底——软删除语义）
```

## 5. 对外契约

| 方向 | 内容 |
|---|---|
| 端点 | `/api/home`：feed（游标分页）、note/{id}、product/{spuId}、user/{id}、cart（**均无 @RateLimit——见 §7-5**）；`/api/home/test`（@Profile("dev") + X-Internal-Call） |
| MQ（入） | `FEED_TOPIC`（feed-push-consumer-group）；`SOCIAL_TOPIC:NOTE_DELETE` |
| Feign（出） | content / user / analytics / counter / notification / product / cart / inventory / coupon（9 个客户端，均带降级工厂：关键 503、辅助空值） |
| 数据源 | 无（纯聚合 + Redis） |

## 6. Jobs 与关键机制

| Job/机制 | 调度 | 职责 |
|---|---|---|
| `feedCleanupJob` | XXL `0 0 3 * * ?`（FIRE_ONCE_NOW，SERIAL） | 两阶段：超期 ZREMRANGEBYSCORE + 超限 ZREMRANGEBYRANK（裁剪从推送链路分离） |
| 线程池（aggregatorPool/batchFeignPool） | — | **CallerRunsPolicy**（队列满降级不丢弃）+ **MdcAwareExecutorService**（每任务捕获 MDC——注释辨析 ThreadFactory 级透传会 TraceId 错乱） |

## 7. 现状边界与问题线索（仅记录，待讨论）

1. **T-126 注释与代码漂移**：FeedPushConsumer 注释称已删标记"5min"，NoteDeleteConsumer 实设 **7 天**（`Duration.ofDays(7)`）——口径不一致（拦截窗口差 2000 倍，行为以代码为准）；
2. **大V判定双处同构**：发布侧 `checkBigV`（10min 缓存）与读侧 `getFollowingBigVIds`（5min 缓存 + 回源 ZCARD）各自实现阈值判定——逻辑重复；粉丝数跨阈值的缓存窗口（注释自析"最多 10 分钟内标记不准"）是已知取舍；
3. **Feed 卡片硬编码简化**：`isCollected=false`（"需额外接口暂不聚合"）、`isFollowed=true`（"Feed 流都是关注的人发的"）——字段语义半成品（关注流成立，推荐流复用时会失真）；
4. **断点续推进度 1h TTL vs MQ 重试窗口**：若 MQ 重试晚于 1h，进度丢失从头推（ZADD 幂等仅浪费）；markPushCompleted 失败容忍依赖 content 补偿 Job 双源兜底；
5. **公开聚合端点零限流**：`/api/home/*` 全部无 `@RateLimit`（对比 counter/search 的"部分有"）——首页/详情是最高频公开面（爬虫放大：一次 feed 请求扇出 9 个下游 Feign）；**网关侧是否兜底待 12 梳理确认**；
6. 小项：`recommend:following:latest` 无 myxhs 前缀（跨域键命名不一）；home 无自有库（BFF 正确姿势，面试可讲"该不该给 BFF 建库"）；7 个 Feign 客户端 fallback 语义分档（503/空值）值得作为降级模板。

## 8. 阶段二讨论结论（2026-10-19 · C-014 home BFF 生产化改造）

> 阶段二（讨论）：§7 六条线索逐条核实（三轮深探：38 文件全读 + 跨模块契约核验 + 线程池/降级链推演）。

### 8.1 线索裁决（§7 六条）

| # | 线索 | 裁决 | 落地 |
|---|------|------|------|
| 1 | T-126 注释漂移（5min vs 7d） | **修** | 注释对齐 7 天 + `note:deleted` 提常量（`NOTE_DELETED_MARK`，原裸字符串重复 ×2） |
| 2 | 大V判定双处同构 | **修（边界）** | `>` 改 `>=`（对齐 checkBigV）；保护路径补 markPushCompleted（"三路径"注释→实为四路径） |
| 3 | Feed 卡片硬编码 | 讲（保留） | isCollected/isFollowed/onSale/relatedNotes 半成品——记录（关注流成立，推荐流复用会失真） |
| 4 | 断点续推 1h TTL | 记录（讲） | 范围校正：**索引游标在粉丝集合变更时可能跳过**（ZADD 幂等只掩盖重复，掩盖不了跳过；演进=值游标） |
| 5 | 公开聚合端点零限流 | **修** | feed 30/min、note/product/user 60/min、cart 30/min（perUser）——一次 feed 扇出 9 个 Feign 的放大面 |
| 6 | 小项 | **§7-6 勘误** | `recommend:following:latest` **有 myxhs 前缀**（原梳理结论错——评论文字误导）；其余（无库/BFF 正确/fallback 分档）成立 |

### 8.2 三轮深探新增（F1~F9 + G1~G7）

| # | 发现 | 裁决 | 落地 |
|---|------|------|------|
| **F1** | **A3 池隔离回旋镖**：NoteAgg 全迁 batchFeignPool；FeedService（字段注入未用）/UserProfileAgg（无池）/Product·Cart L1 仍在 aggregatorPool → 外层占满线程等内层、内层饿死 | **修** | 15 处内层 future 全迁 batchFeignPool（A3 模式补全） |
| **F2** | **likeAndCollectCount 恒 0（真 bug，T-119 修复不彻底）**：① id 只认 Number（R4 String → 恒空）；② >100 篇超 counter 校验上限 | **修** | String-aware 提取 + 分批 ≤100 + 部分成功累计 + 页 ≤10（≈500 篇近似） |
| **F3** | 关键依赖超时语义不一致（Feed 空流/Cart 空车 vs Product/Note/UserProfile 的 503） | **修** | `!isDone` → DownstreamUnavailable（不把"依赖超时"伪装成"没有数据"） |
| **F4** | DLQ 缺口 ×2 组 | **修** | 重放 ×2（feed-push → FEED_TOPIC；note-delete → SOCIAL_TOPIC:NOTE_DELETE，tag 保留必须） |
| **F5** | 大V空缓存不缓存（无大V关注时每次全量重扫 FOLLOW_LIST+MGET） | **修** | sentinel "-1" + pipeline 写入 |
| **F7** | 阈值边界 `>` + 保护路径漏 markPushCompleted | **修** | `>=` + 补标 |
| **G1** | 卡片 createdAt/score 从未填充 | **修** | createdAt 解析 + score 映射（noteId→score） |
| **G2** | 空 Feed unreadCount 恒 0（两处 early-return 写死） | **修** | 抽 fetchUnreadCount 共用（降级返 0） |
| **G3** | 大V缓存 add+expire 非原子（崩溃窗口 → 无 TTL 永不过期） | **修** | SADD+EXPIRE pipeline |
| **G4** | "逐个调用，后续可优化为批量接口"注释陈旧（P2-3 已批量） | 修 | 注释对齐 |
| **G6** | NoteDelete 标记在清理后设置；zCard 双调用 | 修 | 标记先行 + 单调用 |
| F6 | 分页开区间**注释与行为相反**（开区间保翻页进度，代价=同分组跨页剩余跳过） | **注释勘误 + 讲** | 演进=复合游标 (score,member) |
| G5/G7 | 索引分页跳过 / noteCountHolder 可见性 / 主页卡片无计数 | 记录（讲） | — |

### 8.3 核心设计（学习要点）

- **线程池隔离完整模式（A3 补全）**：外层编排 = aggregatorPool，**所有内层 future = batchFeignPool**——同池嵌套在高并发下必然"外层占满线程等内层、内层排队饿死"；配套 MdcAware（任务级 MDC）+ CallerRuns（队列满不丢）；
- **超时语义 = 降级语义的一部分**：关键依赖超时必须是 503（"服务繁忙"），不能落成空数据（"没有笔记/空购物车"会误导用户）——与 404（真实不存在）严格区分；
- **统计聚合的两道坎**：R4 全局 Long→String（id/total 都要 String-aware）+ 下游批量接口的校验上限（>100 要分批）——修复要修全（T-119 只修了 total）；
- **DLQ 家族收官**：14 模块 2+2+3+5+5+2+2 组重放覆盖；tag 保留是 selector 路由消息的生命线。

### 8.4 记录不做（讲）

BFF 建库（无库是正确姿势）；卡片半成品字段补全；索引分页跳过（值游标演进）；复合游标；SCAN 全量清理（低频删除可接受）；断点 1h TTL；大V双缓存双 TTL（10min/5min，有界不一致）。
