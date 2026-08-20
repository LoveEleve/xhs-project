# G6-02 Home 用例（Feed 流读取/大V/聚合接口 + feed 清理）

> 组：G6 搜索首页 | 服务：home(19015) BFF 层 + 联动 content/user/analytics/counter/notification（Feign）| 入口：**gateway(19000)**
> 依赖：G1 登录 + G2 关注/笔记发布（Feed 推送链路 G2-01 已验证——本组聚焦**读取侧**）
> 时间引用：矩阵 **#16**（feed 清理：ZSet score 操纵 + xxl#18）、**#17**（大V 标记 10min/推送进度 1h TTL 查证）
> 前提：home UP、xxl 任务 18 存在启用（组 11，每天3点 cron）

## 代码实证（2026-08-14，G6 梳理全量核实）

### 端点与安全（gateway:19000 → home:19015）
> **⚠️ REVIEW 运行态修正（2026-08-14 实测）**：/api/home/feed|note/**|product/**|user/** **不在 JWT white-list**（本地 yml 未配置；hmac-white-list 含但 JWT 白名单无）——**全部需 JWT（无 token → 401），免 HMAC**；原文档"公开"错误，已按运行态修正
| 端点 | 鉴权（运行态） | 说明（代码实证） |
|---|---|---|
| GET /api/home/feed | **JWT（免 HMAC）** | 关注 Feed 流；lastScore/size（size>50 或 ≤0 → 默认 20）|
| GET /api/home/note/{noteId} | **JWT（免 HMAC）** | 笔记详情聚合；userId 可选（未登录社交态 false）；null → 404"笔记不存在" |
| GET /api/home/product/{spuId} | **JWT（免 HMAC）** | 商品详情聚合；null → 404"商品不存在" |
| GET /api/home/user/{targetUserId} | **JWT（免 HMAC）** | 用户主页聚合；null → 404"用户不存在" |
| GET /api/home/cart | **JWT + HMAC** | 购物车聚合（购物车+库存+可用券）|
| /api/home/test/** | **dev profile 仅开发环境** | FeedTestController：push-inbox/push-outbox 测试端点（生产不加载）|

> home 是 BFF 层：**无数据库**（DataSourceAutoConfiguration 排除）、无 ES——全部数据经 Feign 聚合；所有聚合接口 CompletableFuture 并行 + 超时降级（下游不可用 → 字段默认值，HTTP 仍 200）

### Feed 读取链路（FeedService，代码实证）
`getFollowFeed(userId, lastScore, size)`：
1. **游标**：lastScore null/≤0 → Double.MAX_VALUE（首次从最新）；开区间 `(lastScore-1ulp, lastScore]` 防同分跳过
2. **收件箱**：`ZREVRANGEBYSCORE myxhs:feed:inbox:{uid} 0 minScore`（P0-C #55 修复：参数 (0, minScore) 倒序取 size 条）
3. **大V 发件箱**：getFollowingBigVIds（关注 ZSet `myxhs:follow:list:{uid}` + MGET `myxhs:user:bigv:{fid}`="1"）→ Pipeline ZREVRANGEBYSCORE 每个大V 取 `size/大V数` 条（maxScore=lastScore-0.001）
4. **合并**：去重（同 noteId 保留高分）+ score 降序 + limit size
5. **第 1 层并行聚合**（总超时 3s）：batch-detail（content Feign，P2-3 批量）+ 点赞状态（analytics batchCheckLikeStatus，cap 50）+ 未读数（notification getUnreadCount）
6. **第 2 层并行**（总超时 2s）：作者信息（user 逐个）+ 计数（counter batchGetCounts 1/2/3）
7. **组装 NoteCardVO**：note 缺失/已删 → 跳过（batch-detail 过滤）；isLiked 来自第 1 层；**isFollowed=true 硬编码**（关注流语义）；isCollected=false（暂不聚合，注释实证）
8. **分页**：nextCursor=最后一条 score（整数值用 long 格式，防科学计数法）；hasMore=merged.size()≥size
9. **空流**：merged 空 → notes=[] + hasMore=false + unreadCount=0

### Feed 推送侧（FeedPushConsumer，G2-01 已验证——本组补充大V 分支）
- 非大V：推模式（Pipeline ZADD 粉丝收件箱，进度 `myxhs:feed:push:progress:{localMsgId}` Hash cursor/total/status，TTL 1h）
- **大V（粉丝≥100000 或 `myxhs:user:bigv:{uid}`="1"）**：拉模式 → ZADD 发件箱 `myxhs:feed:outbox:{uid}` + EXPIRE 7 天
- 粉丝>50000 超大保护：写发件箱走拉模式（不阻塞 MQ 线程）
- **NoteDeleteConsumer**：SOCIAL_TOPIC:NOTE_DELETE → 清作者 outbox（粉丝 inbox 残留靠 7 天 TTL + FeedCleanupJob——G2 已实证为可接受行为）

### Feed 清理（FeedCleanupJob，代码实证）
- xxl#18 feedCleanupJob（组 11，`0 0 3 * * ?` 每天3点，trigger_status=1）→ XxlJobHelper.handleSuccess"Feed清理完成，清理 N 条过期数据"
- 两阶段：① SCAN `myxhs:feed:inbox:*` → **ZREMRANGEBYSCORE(0, cutoff)**（cutoff=now-7 天×24h×3600×1000）→ ② **ZCARD>500 裁剪** ZREMRANGEBYRANK(0, card-501)（M2 收件箱裁剪）；同 SCAN `myxhs:feed:outbox:*` 只清过期不裁剪
- 每 100 个 key Thread.sleep(50)

### 聚合接口（各 AggService，代码实证）
| 聚合 | 数据源（Feign）| 超时 | 降级 |
|---|---|---|---|
| note/{id} | content 详情 + analytics 点赞/收藏态 + counter 计数（1/2/3）→ 第 2 层 user 作者 + analytics 关注态 + content 热门评论 | 全局 4s / 层 1:3s、层 2:max(500,剩余) | 字段默认值 |
| product/{spuId} | product SPU 详情 + counter 商品计数（targetType=4：2 收藏/5 浏览）→ 第 2 层 inventory 各 SKU 库存 + **relatedNotes（相关笔记）** | 全局 4s / 同上 | 默认值 |
| user/{targetUserId} | user 公开信息 + analytics 粉丝/关注计数 + 关注关系（isFollowing/isMutual/isFollowBack）+ content 用户笔记列表 | **单层并行 3s**（无全局超时）| 默认值 |
| cart | cart 购物车 + coupon 可用券 → 第 2 层 inventory 库存/SKU 状态 | 层 1:3s、层 2:2s（无全局超时）| 默认值 |

## 前置准备（执行时记录快照）
```python
from testlib import *
# 1. 注册用户：g6h_ 读者（登录）、g6a_ 作者A（普通用户）、g6b_ 作者B（大V）
# 2. 关注关系：g6h_ 关注 g6a_（G2 关注接口）
# 3. 大V 操纵：SET myxhs:user:bigv:{g6b.uid} 1（10min 缓存；或构造粉丝 ZCARD≥10万——不现实，用 key 操纵）
# 4. g6a_ 发布笔记 n1（→ g6h_ 收件箱推模式）；g6b_ 发布笔记 n2（→ g6b_ 发件箱拉模式）——等 1-3s MQ+主从
# 5. 清理：DEL myxhs:feed:inbox:{uid} myxhs:feed:outbox:{uid} myxhs:feed:push:progress:* myxhs:user:bigv:{uid}
```

## 用例清单

### G6-02-01 Feed 读取全链路（核心）
- **前置**：g6h_ 关注 g6a_；g6a_ 发 n1（推模式）→ g6h_ 收件箱有 n1（ZSCORE 确认）
- **入口**：`GET /api/home/feed`（**JWT，免 HMAC**——运行态实证非公开）
- **L1**：200；data.notes 含 1 条：noteId=n1、title、coverUrl、authorId=g6a、authorNickname 非空、likeCount/collectCount/commentCount（counter 聚合）、isLiked 布尔、isFollowed=true（硬编码实证）、unreadCount 整数、hasMore 布尔
- **L2**：
  ```
  # ① 收件箱 ZSet：ZREVRANGE myxhs:feed:inbox:{g6h.uid} 0 -1 → n1 存在，score=publishTime(ms)
  # ② 卡片聚合正确：计数与 myxhs:counter:1:{n1}:{1|2|3} 一致；点赞态与 G2 like 状态一致
  # ③ nextCursor=score 毫秒字符串（整数无科学计数法）；hasMore=false（1 条 < size）
  ```
- **🔍 人工观察**：SkyWalking home→content/user/analytics/counter/notification 并行聚合 span；Kibana home 日志
- **注意**：公开端点无 userId——**搜索历史等用户态不记录**；feed 读取不记录行为（推荐曝光另行上报）

### G6-02-02 Feed 游标分页
- **前置**：g6a_ 连发 5 条（n1~n5，时间递增）→ g6h_ 收件箱 5 条
- **入口**：`GET /api/home/feed?size=2` → notes=2（最新两条 n5/n4）、nextCursor 非空、hasMore=true
- 翻页：`GET /api/home/feed?size=2&lastScore={nextCursor}` → n3/n2（无重复无遗漏）
- **L2**：score 降序连续；翻页到尾 → notes 少/空 + hasMore=false；**size=0/-1 → 默认 20**；size=51 → 默认 20（实证）
- **边界**：同 score 记录（publishTime 相同）不跳过（开区间 -1ulp 实证）
- **清理**：DEL inbox

### G6-02-03 大V 拉模式（发件箱 + 合并读取，核心）
- **前置**：g6b_ 大V（bigv key=1）发布 n2 → 发件箱 `myxhs:feed:outbox:{g6b.uid}` 有 n2（ZSCORE 确认，**粉丝收件箱无 n2**——拉模式实证）
- **入口**：`GET /api/home/feed`（g6h_ 已关注 g6b_）
- **L1**：notes 含 n2（来源=大V发件箱拉取合并）
- **L2**：
  ```
  # ① outbox ZSet：n2 score=发布毫秒；TTL≈7 天（EXPIRETIME 查证，矩阵 #17）
  # ② bigv key：myxhs:user:bigv:{g6b.uid}="1" TTL≈600（10min 缓存）
  # ③ 混合流：g6a_ 推模式 n1 + g6b_ 拉模式 n2 同时存在 → 合并按 score 降序
  ```
- **清理**：DEL outbox + bigv key

### G6-02-04 推拉合并去重
- **构造**：同一条 n1 同时存在于收件箱（推模式）和 g6b_ 发件箱（SQL/测试端点 push-outbox 写入同一 noteId）——测试端点 `POST /api/home/test/push-outbox?authorId={g6b.uid}&noteId={n1}&publishTime={大值}`
- **入口**：`GET /api/home/feed`
- **L1**：notes 中 n1 **仅 1 条**（mergeAndSort toMap 保留高分实证）；分数取二者较高
- **清理**：DEL inbox/outbox

### G6-02-05 Feed 空流与边界
- **入口**：新用户（无关注无收件箱）`GET /api/home/feed` → notes=[]、hasMore=false、unreadCount=0、nextCursor=null
- **注意**：无关注 → getFollowingBigVIds 空 → 跳过拉取（pipeline 不执行）；收件箱不存在 → reverseRangeByScoreWithScores 空

### G6-02-06 Feed 清理（矩阵 #16：ZSet score 操纵 + xxl#18）
- **构造**（③ 数据操纵）：
  ```
  # ① 过期成员：ZADD myxhs:feed:inbox:{g6h.uid} {8天前毫秒} {expiredNoteId}（score<cutoff）
  # ② 正常成员：ZADD 当前时间 {n1}；③ 超量成员：ZADD 500+1 条（验证裁剪）
  # ④ 发件箱过期成员：ZADD myxhs:feed:outbox:{g6b.uid} {8天前} {expiredNoteId2}
  ```
- **触发**：xxl admin API 手动触发 id=18（组 11，矩阵 #1）
- **L2**（触发后 1-3s）：
  ```
  # ① xxl_job_log：trigger_code=200 + handle_code=200"Feed清理完成，清理 N 条过期数据"
  # ② 收件箱 ZCARD：过期成员已删（ZREMRANGEBYSCORE 0 cutoff）、ZCARD≤500（裁剪实证）
  # ③ 发件箱过期成员已删
  ```
- **幂等**：重复触发 → 无副作用（无过期数据时 cleaned=0）
- **注意**：任务按 key 逐个处理，收件箱较多时执行时间可能 >1 个 xxl 周期（触发后等 3-5s 再断言）

### G6-02-07 NoteDeleteConsumer（大V 删笔记 → outbox 清理）
- **前置**：g6b_ 大V 发布 n2（outbox 有 n2）→ g6b_ 删除 n2（G2 删除接口）
- **L2**（sleep 2-3s MQ）：`ZSCORE myxhs:feed:outbox:{g6b.uid} {n2}` → **nil（已清理）**；SOCIAL_TOPIC:NOTE_DELETE 消息已消费（home note-delete-consumer-group）
- **对照**：普通作者（非大V）删除 → 无 outbox 可清（幂等无副作用）
- **清理**：无

### G6-02-08 笔记详情聚合（note/{noteId}）
- **前置**：g6a_ 已发布 n1 + 点赞/收藏/评论数据（G2 造数）+ 热门评论 ≥1
- **入口**：`GET /api/home/note/{n1}`（**JWT，免 HMAC**——运行态实证非公开）
- **L1**：200；data 含笔记详情（title/content/author 昵称头像）+ 计数（like/collect/comment）+ 热门评论
- **社交态**：g6h_ 登录（未对 n1 交互）→ isLiked/isCollected/isFollowed **全 false**（无社交关系实证）；g6h_ 对 n1 点赞后 → isLiked=true（真实状态联动实证）
- **负面**：`GET /api/home/note/999999999999`（带 JWT）→ **404"笔记不存在"**（null → R.fail(404)）
- **🔍 人工观察**：SkyWalking home→content→（第 2 层）user/analytics/counter 两阶段并行 span

### G6-02-09 商品/用户/购物车聚合
- **① 商品**：`GET /api/home/product/{spuId}`（**JWT，免 HMAC**）→ 200：SPU 详情（name/description/images/categoryName/status）+ **skuList（含 availableStock/hasStock）** + 计数（collectCount/viewCount）+ **relatedNotes**；不存在 → 404"商品不存在"
- **② 用户主页**：`GET /api/home/user/{g6a.uid}`（**JWT，免 HMAC**）→ 200：用户信息 + 计数（followingCount/followerCount/likeAndCollectCount/noteCount）+ **社交态 isFollowing/isMutual/isFollowBack 全 false（未登录实证）** + 用户笔记列表（已发布）；不存在 → 404"用户不存在"；登录态（JWT）→ 关注关系真实值
- **③ 购物车**：`GET /api/home/cart`（**JWT+HMAC**）→ 200：items（skuId/spuId/skuName/skuImage + 库存 valid）+ checkedCount/checkedAmount/totalCount/allChecked + **availableCouponCount/availableCoupons**；无 token → 401；无签名 → 403
- **L2**：聚合字段与各下游服务直查一致（对照 G3 cart 列表 + inventory stock 查询 + G4 可用券）
- **REVIEW 补充（代码实证）**：cart 聚合第 2 层=inventory 库存 + SKU 状态（非 product Feign）；user 聚合计数从 analytics 直取（不绕 counter）

### G6-02-10 Home 鉴权矩阵（安全）——按运行态修正
- ① `/api/home/feed`、`/api/home/note/**`、`/api/home/product/**`、`/api/home/user/**` 无 token → **401**（运行态实证；**免 HMAC**：带 JWT 无签名 → 200）
- ② `/api/home/cart` 无 token → **401**；带 token 无 HMAC → **403**
- ③ `/api/home/test/**`：生产 profile 不加载（代码实证 @Profile("dev")）——运行环境 profile 确认（dev 或 prod）
- ④ 未知路径 `/api/home/no-such` → 404（T-059 回归：gateway NoResourceFoundException 已修）

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G6-02-01 Feed 读取 | 16:27 | ⚠️（T-081 已修 / T-084 待修） | **触发 T-081（500）→ 已修**；authorId/nick/计数全对；**脏收件箱成员可致 500（T-084 健壮性待修）** |
| G6-02-02 游标分页 | 16:34 | ✅ | 3 页无重复无遗漏、size 边界（0/51→默认 20） |
| G6-02-03 大V 拉模式 | 16:35 | ✅ | outbox 写入/收件箱无/TTL 7 天/合并排序 |
| G6-02-04 推拉去重 | 16:36 | ✅ | 同 noteId 合并仅 1 条、高分优先；test 端点需 HMAC |
| G6-02-05 空流 | 16:37 | ✅ | []/hasMore=false/unread=0 |
| G6-02-06 Feed 清理 | 16:38 | ✅ | xxl#18 手动触发：过期清/500 裁剪（**副作用：低分真实数据被裁——测试方法记录**） |
| G6-02-07 NoteDelete | 16:39 | ✅ | NOTE_DELETE → outbox 清 |
| G6-02-08 笔记详情聚合 | 16:40 | ✅ | **T-081 家族修复（NoteAggService）**；likeCount=3/isLiked 联动/isFollowed 真实关系 |
| G6-02-09 商品/用户/购物车聚合 | 16:41 | ✅ | product skuList/库存、user noteCount、cart 全字段（**T-081 修复 Product/Cart/User Agg**） |
| G6-02-10 鉴权 | 16:42 | ✅ | 全端点需 JWT（免 HMAC）；cart JWT+HMAC；**T-084：脏收件箱 member → feed 500（清理恢复）** |

## 断言关键词速查
- 200 / 401 / 403 / 404（聚合不存在）
- 关键 L2：inbox/outbox ZSet score=发布毫秒、nextCursor 毫秒串、hasMore、bigv key TTL 10min、outbox TTL 7 天、push:progress status=completed、FeedCleanupJob handle 200 + ZCARD 裁剪、NOTE_DELETE 清 outbox、聚合字段与下游一致

## 深度 REVIEW 补充（2026-08-14 第一轮 + 第二轮深度 REVIEW + 第三轮运行态 REVIEW，代码实证核对）
### L0/L1 已核
- ✅ 端点安全矩阵（5 聚合端点：4 公开 + cart JWT+HMAC；test 端点 dev profile）
- ✅ Feed 读取 9 步链路（游标开区间/收件箱 ZREVRANGE 参数 #55 修复/大V Pipeline/合并去重保留高分/两层并行聚合 3s+2s 超时/空流语义）
- ✅ 大V 判定（bigv key 10min 缓存 + ZCARD≥100000；粉丝>50000 超大保护走拉模式）
- ✅ 推送进度断点续推（Hash cursor/total/status TTL 1h——G2-01 已验证）
- ✅ FeedCleanupJob 两阶段（过期 ZREMRANGEBYSCORE + 裁剪 ZREMRANGEBYRANK；outbox 只清不裁）
- ✅ 聚合四接口数据源与降级策略（BFF 无库、字段默认值、404 语义）
- ✅ xxl#18 运行库确认（组 11、每天3点、trigger_status=1）
- ✅ search/home 无 @RateLimit/@Idempotent 注解（grep 全量实证）——限流仅 gateway 路由级

### 第二轮深度 REVIEW 修正（2026-08-14，聚合服务全量代码核对）
- ✅ **聚合超时模型修正**：user 聚合=单层并行 3s（无全局 4s 超时）；cart 聚合=层1:3s + 层2:2s（无全局）；note/product=全局 4s + 层2 max(500, 剩余)——原表"全局 4s/层 3s"统一描述已按服务拆分
- ✅ **cart 聚合第 2 层数据源修正**：inventory 库存 + SKU 状态（原文档误写"product 库存/SKU"）
- ✅ **VO 字段补充**：product 含 relatedNotes、collectCount/viewCount；user 含 likeAndCollectCount/isMutual/isFollowBack；cart 含 checkedAmount/allChecked/availableCoupons——断言已按真实 VO 字段对齐
- ✅ **user 计数来源修正**：follower/following 从 analytics 直取（非 counter 服务）

### 风险/观察项
- [ ] isCollected=false 硬编码（收藏状态暂不聚合——注释实证，登记观察）
- [ ] isFollowed=true 硬编码（关注流内恒真——语义自洽，非 bug）
- [ ] 大V 判定缓存 10min 窗口（粉丝数跨越阈值后最多 10min 不准确——代码注释已声明设计取舍）
- [ ] 超大粉丝（>50000）触发拉模式阈值与 bigV 阈值（100000）不同——两套阈值并存（观察项）

### 待 L2 确认
- [ ] 游标同 score 不跳过（-1ulp 开区间）
- [ ] FeedCleanupJob 大批量耗时与 xxl 超时（executor_timeout=0 不限时）
- [ ] 聚合接口降级路径（停一个下游服务——高风险，标注可选）
- [ ] test 端点 profile 确认（dev vs prod）

### 第三轮运行态 REVIEW（2026-08-14，环境实测）
- ✅ **home 服务 UP**（actuator/health 实测）；xxl 组 11=my-xhs-home 确认（feedCleanupJob/18 挂 home 执行器）
- ✅ **Feign 降级语义实证**：FallbackFactory 返回 `R.ok(emptyMap)`（**code 200 + 空数据，非 503**）→ 聚合服务空 Map → 字段默认值/笔记跳过——与 G6-02-01 降级断言一致（停下游服务时 HTTP 仍 200）
- ✅ **Redis feed 域 key 全空**（SCAN 实测）——G6-02 执行无需额外清理
- ⚠️ **当前数据空**：t_note=0 → G6-02-01~04 依赖的 g6a_ 发布/关注数据需执行时构造（前置准备已列）
- ⚠️ **canal/推送链路运行态**：发布→FEED_TOPIC→inbox 全链路 G2-01 已验证过（数据清理后需重新构造）
