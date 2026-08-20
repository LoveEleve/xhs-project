# G2-03 点赞/收藏用例（点赞/取消 + 收藏/取消 + 计数联动 + 幂等 + MQ 落库）

> 组：G2 内容与社交 | 服务：analytics(19003) + counter(19004) + content(19002) + notification(19013) | 入口：**gateway(19000)**
> 依赖：G1 登录（testlib.new_user）+ G2-01 笔记 + G2-02 评论（本用例前置内联造数，避免跨用例耦合）
> 时间引用：矩阵 **#26**（限流 60s）、B 节（CounterBuffer 5s）、#25（@Idempotent 窗口 5s）
> 前提：15 服务 UP；analytics/counter/notification 运行中

## 代码实证（2026-08-13，本轮核实）

### 端点与安全（gateway:19000 → analytics:19003）
| 端点 | 鉴权 | HMAC | 说明（代码实证） |
|---|---|---|---|
| POST /api/social/like | JWT | **必须签名** | RateLimit **30次/60s** prefix=`social:like`；**@Idempotent 5s**（key=`like:{uid}:{bizType}:{bizId}`，prefix=`idempotent`）|
| DELETE /api/social/like | JWT | 必须签名 | 同参数结构；RateLimit 30次/60s prefix=`social:unlike`（无 @Idempotent）|
| GET /api/social/like/status | JWT | **必须签名** | **不在 HMAC/JWT 白名单**（同 G2-01 /my 教训）|
| GET /api/social/like/batch-status | JWT | 必须签名 | bizType @Min1@Max2；bizIds 逗号分隔（非法项静默过滤）≤100 |
| GET /api/social/like/count | **公开** | 免签 | JWT/HMAC 双白名单；SCARD 实时 |
| POST /api/social/favorite | JWT | 必须签名 | RateLimit 30次/60s prefix=`social:favorite`；@Idempotent 5s（key=`favorite:{uid}:{noteId}`）|
| DELETE /api/social/favorite | JWT | 必须签名 | RateLimit prefix=`social:unfavorite` |
| GET /api/social/favorite/status | JWT | 必须签名 | 不在白名单 |
| GET /api/social/favorite/list | JWT | 必须签名 | ZSet reverseRange 分页，size≤50 |

- `LikeRequest`：bizType **@NotNull @Min(1) @Max(2)**（1=笔记 2=评论）、bizId @NotNull @Positive
- `FavoriteRequest`：noteId @NotNull @Positive
- 错误码：**40201** 请勿重复操作（@Idempotent，HTTP 200 body code）/ **40202** 限流 / 40002 参数（HTTP 400）/ 500 取消操作 MQ 失败回滚（INTERNAL_ERROR）

### Redis Key（代码实证）
| Key | 类型 | 说明 |
|---|---|---|
| `myxhs:like:note:{noteId}` | Set | 笔记点赞者（member=userId）— **权威数据源** |
| `myxhs:like:comment:{commentId}` | Set | 评论点赞者（bizType=2）|
| `myxhs:like:user:{userId}:note` | Set | 反向索引（member=noteId，非原子，对账兜底）|
| `myxhs:favorite:{userId}` | ZSet | 收藏列表（member=noteId，score=收藏时间 ms）|
| `myxhs:like:set:{targetType}:{targetId}` | Set | **counter 服务**点赞计数 Set（H2 修复，Set-based 消除乱序）|
| `myxhs:counter:{targetType}:{targetId}:{countType}` | string | 计数 key：点赞=countType 1、收藏=2 |
| `analytics:event:version:like:{uid}:{bizType}:{bizId}` | string | 消费乱序防护（actionTime 版本，24h TTL）|
| `analytics:event:version:favorite:{uid}:{noteId}` | string | 同上（收藏）|
| `idempotent:like:{uid}:{bizType}:{bizId}` | string | @Idempotent 5s 窗口 |

### 流程（代码实证）
- **like**：Lua SADD（幂等：已存在直接返回成功）→ 反向索引 → MQ **syncSend** `SOCIAL_TOPIC:LIKE`（3s 超时；**失败不回滚 Redis**——靠消费端幂等/对账）
- **unlike**：Lua SREM（未点赞幂等返回）→ 反向索引移除 → MQ `SOCIAL_TOPIC:UNLIKE`（**失败回滚 Redis + 抛 500**——与 like 不对称，观察项）
- **favorite**：Lua ZSCORE+ZADD（幂等）→ MQ `SOCIAL_TOPIC:FAVORITE`（失败不回滚）
- **unfavorite**：Lua ZSCORE+ZREM（幂等）→ MQ `SOCIAL_TOPIC:UNFAVORITE`（失败回滚 ZADD 原始 score + 抛 500）
- **消费端**：
  - LikeUnlikeConsumer（like-unlike-consumer-group，LIKE\|\|UNLIKE）：**actionTime 版本号防乱序**（旧事件跳过）→ t_like INSERT（uk_user_biz 幂等）/ DELETE
  - FavoriteUnlikeConsumer（favorite-unlike-consumer-group，FAVORITE\|\|UNFAVORITE）：同上 → t_favorite INSERT（uk_user_note 幂等）/ DELETE
  - CounterEventConsumer（counter，selector 含 10 个 tag）：LIKE(bizType=1)→targetType=1 / (bizType=2)→targetType=3，**Set-based 计数**（SADD/SCARD 写 `myxhs:counter:*`）；FAVORITE→targetType=1 countType=2（INCR+dedup）
- **表**：`my_xhs_analytics.t_like`（uk_user_biz）/ `t_favorite`（uk_user_note）——MQ 异步落库，**Redis 为权威**，DB 是持久化兜底

### ⚠️ 点赞/收藏计数双轨（重要，代码实证）
- `GET /api/social/like/count` = **SCARD myxhs:like:note:{noteId}**（analytics 权威 Set，实时）
- `myxhs:counter:{targetType}:{targetId}:1` = counter 服务 **自己的 Set**（myxhs:like:set:*）SCARD——两套 Set 各自维护
- **一致性窗口**：MQ 异步（毫秒~秒级）；断链时靠 counterReconcileJob 对账

### ⚠️ 通知链路（O-Like-1 **已修复** 2026-08-13）
- **原缺陷**：t_push_template 有 like/follow 模板（运行态启用），但 analytics 全服务无 NOTIFICATION_TOPIC 发送——点赞/关注不产生通知（P1 功能回归）
- **修复**：
  - analytics 新增 `ContentFeignClient.batchGetNoteDetail`（**用批量接口无 VIEW 副作用**，X-Internal-Call 由 InternalCallFeignConfig 注入）
  - `LikeService.like()` SADD 新增后（bizType=1）→ 查笔记作者/标题 → 发 type=1 通知（自赞排除；已删/草稿笔记不通知；失败不影响点赞）
  - `FollowService.follow()` 成功后 → 发 type=3 通知（targetType=2 用户）
  - **注意 R4 坑**：Feign 返回的 userId 是 String（全局 Long→ToStringSerializer），解析需兼容 Number/String（toLongSafe）
- **运行态已验证**：点赞 → type=1 新增 1 ✅；自赞 → 不新增 ✅；关注 → type=3 新增 1 ✅；已删笔记点赞 → 不通知 ✅
- **遗留**：评论点赞（bizType=2）通知未做（content 无单条评论详情接口，需新增）；收藏无模板不通知——均记录

## 前置准备（执行时记录快照）
```python
from testlib import *
# 1. 注册 2 用户：g2e_（笔记作者） g2f_（互动者）
# 2. g2e_ 发布 1 篇笔记 → {noteId}
# 3. g2f_ 发 1 条评论 → {commentId}
# 4. 基线：SELECT COUNT(*) FROM my_xhs_analytics.t_like / t_favorite（记数）
```

## 用例清单

### G2-03-01 笔记点赞全链路（核心用例）
- **入口**：`POST /api/social/like` body=`{"bizType":1,"bizId":{noteId}}`（g2f_ 签名）
- **L1 断言**：200（message="点赞成功"）
- **L2 数据验证**（关键）：
  ```
  SISMEMBER myxhs:like:note:{noteId} {g2f.uid}       # → 1
  SISMEMBER myxhs:like:user:{g2f.uid}:note {noteId}  # → 1（反向索引）
  GET /api/social/like/status?bizType=1&bizId={noteId}（JWT+HMAC）  # → true
  GET /api/social/like/count?bizType=1&bizId={noteId}（公开）        # → 1（SCARD）
  # 计数联动（counter 服务 Set-based，等 1-3s MQ 消费）：
  GET myxhs:counter:1:{noteId}:1    # → "1"（counter 的 SCARD）
  SCARD myxhs:like:set:1:{noteId}   # → 1
  # DB 落库（等 1-3s MQ）：
  SELECT user_id,biz_type,biz_id FROM my_xhs_analytics.t_like WHERE user_id={g2f.uid} AND biz_id={noteId}
  # → 1 行（biz_type=1）
  # 乱序防护版本 key：
  GET analytics:event:version:like:{g2f.uid}:1:{noteId}   # → 存在（actionTime）
  ```

### G2-03-02 点赞幂等双态（#25 @Idempotent + SADD 双层）
- ① **@Idempotent（5s 窗口）**：紧接 01 再发同参数 POST → **40201**"请勿重复点赞"（SET NX 拦截）
- ② **SADD 幂等（>5s 后）**：等 6s 再发同参数 → **200**（已点赞幂等返回成功，无副作用）
- **L2**：计数不变（`GET myxhs:counter:1:{noteId}:1` 仍 = 1）；t_like 仅 1 行

### G2-03-03 取消点赞
- **入口**：`DELETE /api/social/like` body=`{"bizType":1,"bizId":{noteId}}`
- **L1 断言**：200
- **L2**：SISMEMBER=0；status=false；count=0；`myxhs:counter:1:{noteId}:1`=0（SREM 后 SCARD）；t_like 该行删除（等 1-3s）；反向索引移除
- **幂等**：未点赞再 DELETE → 200（SREM 幂等）；**注意 unlike 无 @Idempotent**（可直接连发）

### G2-03-04 评论点赞（bizType=2 分支）
- **入口**：`POST /api/social/like` body=`{"bizType":2,"bizId":{commentId}}`
- **L2**：`SISMEMBER myxhs:like:comment:{commentId} {g2f.uid}`=1；`GET myxhs:counter:3:{commentId}:1`=1（**targetType=3 评论**，H4 修复实证）；t_like biz_type=2 行
- **无反向索引**（bizType=2 时 userLikeKey=null，代码实证）
- 清理：DELETE like(bizType=2) → 计数归 0

### G2-03-05 点赞数公开接口
- `GET /api/social/like/count?bizType=1&bizId={noteId}` **无 token 无签名** → 200（双白名单实证）
- `GET /api/social/like/count?bizType=2&bizId={commentId}` → 200

### G2-03-06 状态/批量状态（JWT+HMAC）
- ① `GET /api/social/like/status?bizType=1&bizId={noteId}`（g2f_ token+secret 签名）→ false/true
- ② **无签名 → 403**（不在 HMAC 白名单——负向实证）
- ③ `GET /api/social/like/batch-status?bizType=1&bizIds=1,2,{noteId}` → map 含 3 键（**键为字符串**，R4）；1/2 均 false（未点赞的笔记）
- ④ `bizType=3` → **40002**（@Min1@Max2）；`bizIds=abc` → 空 map（非法项静默过滤实证）
- ⑤ `bizIds=1,2,...101个` → 只查前 100（MAX_BATCH_SIZE 实证，可选）

### G2-03-07 收藏全链路
- **入口**：`POST /api/social/favorite` body=`{"noteId":{noteId}}`（g2f_ 签名）
- **L1 断言**：200
- **L2**：
  ```
  ZSCORE myxhs:favorite:{g2f.uid} {noteId}     # → 收藏时间戳（≈now ms）
  GET /api/social/favorite/status?noteId={noteId}（JWT+HMAC）  # → true
  GET /api/social/favorite/list?page=1&size=20（JWT+HMAC）     # → list 含 noteId，total=1
  GET myxhs:counter:1:{noteId}:2    # → "1"（收藏 countType=2）
  SELECT user_id,note_id FROM my_xhs_analytics.t_favorite WHERE user_id={g2f.uid}
  # → 1 行（等 1-3s MQ 落库）
  ```

### G2-03-08 收藏幂等双态
- ① 5s 内同参数重复 POST → **40201**"请勿重复收藏"
- ② 等 6s 再发 → 200（ZSCORE 判断幂等）；计数仍 1；t_favorite 1 行

### G2-03-09 取消收藏
- `DELETE /api/social/favorite` body=`{"noteId":{noteId}}` → 200 → ZSCORE=null；status=false；list 空；`myxhs:counter:1:{noteId}:2`=0；t_favorite 行删除
- 幂等：未收藏再 DELETE → 200（Lua ZSCORE+ZREM 幂等）
- **回滚语义观察**：unfavorite 的 MQ 失败会回滚 ZADD（原始 score）+ 500——正常链路不触发，记录

### G2-03-10 收藏列表分页（倒序）——**响应字段 {total, list:[noteId]}（T-104 实证，非 records）**
- 造 3 篇新笔记 {n1}{n2}{n3}（g2e_ 发布）+ 收藏 → `GET /api/social/favorite/list?page=1&size=2` → 2 条**最新收藏在前**（score 倒序实证）→ page=2 → 剩余；total=3；`size=100` → ≤50（MAX_PAGE_SIZE 实证）

### G2-03-11 参数校验（负面）
- ① like body 缺 bizType → **40002**；② bizType=3 → **40002**；③ bizId=0 → **40002**；④ favorite noteId=0 → **40002**
- 均 HTTP 400 + body 40002

### G2-03-12 限流（矩阵 #26，30次/60s）
- ① like：连续 31 次（不同 bizId，避免 @Idempotent 干扰——**注意同 bizId 会被 40201 拦**，用递增 bizId）→ 第 31 次 **40202**；`ZCARD social:like:LikeController:like:{uid}`=30
- ② favorite：同法 31 次 → 第 31 次 **40202**
- **清理**（测完立即）：`DEL social:like:LikeController:like:{uid}`、`DEL social:favorite:FavoriteController:favorite:{uid}`（还有 unlike/unfavorite key 若无则跳过）

### G2-03-13 无签名写操作 → **403**（带 JWT 无签名头，回归）
### G2-03-14 ✅ 点赞通知验证（O-Like-1 修复回归）
- **前置**：基线 `SELECT COUNT(*) FROM my_xhs_notification.t_notification WHERE user_id={g2e.uid}` 记数
- **入口**：g2f_ 点赞 g2e_ 的笔记（01 已做过，此处重新点赞一次并等 1-3s）
- **L1/L2 断言**（修复后预期）：
  ```
  SELECT sender_id,target_id,target_type,title FROM my_xhs_notification.t_notification WHERE user_id={g2e.uid} AND type=1 ORDER BY id DESC LIMIT 1
  # → sender_id={g2f.uid} target_id={noteId} target_type=1（点赞通知生成 ✅）
  # 自赞排除：g2e_ 赞自己 → 无新增
  # 关注通知：g2f_ 关注 g2e_ → type=3 新增（target_type=2）
  # 已删笔记点赞 → 不通知（batch-detail 降级跳过）
  ```

### G2-03-15 乱序防护回归（B11/H4，代码审查级 + 运行态 key）
- ① 版本 key 存在性：01 后 `GET analytics:event:version:like:{g2f.uid}:1:{noteId}` 非空（24h TTL）
- ② **投递乱序构造不实做**（Broker 侧操纵复杂）——代码审查：versionCheck Lua 原子 GET+compare+SET；LikeUnlikeConsumer/FavoriteUnlikeConsumer 同组消费保证顺序（注释实证）
- ③ 计数 Set-based 防乱序：SCARD 天然正确（H2 注释实证）

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G2-03-01 | 2026-08-13 | ✅ | 点赞全链路 9 项断言全过（含 O-Like-1 通知回归 type=1）|
| G2-03-02 | | ✅ | @Idempotent 40201（**需同脚本 5s 内连发**，跨脚本已过期——用例设计修正）+ SADD 幂等 200 |
| G2-03-03 | | ✅ | 取消：Set/status/counter/t_like 全归零 + 幂等 |
| G2-03-04 | | ✅ | 评论点赞 bizType=2 → counter:3:{id}:1 |
| G2-03-05 | | ✅ | 公开计数无 token 200 |
| G2-03-06 | | ✅ | status 无签名 403 / batch-status 键字符串(R4) / bizType=3 40002 / abc 过滤 |
| G2-03-07 | | ✅ | 收藏全链路（score/status/list/counter:2/t_favorite）|
| G2-03-08 | | ✅ | 幂等 200/40201 + ZSCORE 幂等 |
| G2-03-09 | | ✅ | 取消收藏全归零 + 幂等 |
| G2-03-10 | | ✅ | 列表倒序 + 分页 + size≤50 |
| G2-03-11 | | ✅ | 4 项参数校验 40002 |
| G2-03-12 | | ✅ | like/favorite 限流干净窗口 30+第31拒（**首轮被服务堆积干扰：31 次 like 同步 MQ+Feign 致超时堆积，favorite 窗口被未超时请求占满——O-Note-3**）|
| G2-03-13 | | ✅ | 无签名 403 |
| G2-03-14 | | ✅ | 关注通知 type=3 + R5 target_type=NULL + 重复关注 41001 |
| G2-03-15 | | ✅ | version key 存在 + 代码审查 |

### 执行期发现（补充 REVIEW）
- **O-Note-3（观察）**：连续 31 次 like（每次 syncSend MQ + Feign batch-detail + 通知）造成服务瞬时堆积，客户端 8s 超时但服务端仍处理 → 后续限流窗口被"超时未确认"请求占满 → **限流/压力类用例需控制请求速率（间隔 ≥200ms）或降低批量**；超时异常不等于服务端未处理
- **O-Note-4（观察，@Idempotent 与 @RateLimit 叠加）**：被 @Idempotent 拦截的请求（40201）也计入 @RateLimit 窗口（RateLimitAspect order=10 先于 IdempotentAspect order=100）——同参数连点会加速耗尽限流额度（设计如此，测试需知悉）

## 清理清单（执行后）
- 用户：g2e_/g2f_；数据：t_like/t_favorite（user_id 行）、t_note/t_comment（g2e 相关）、t_local_message（body LIKE %noteId%）、t_notification（g2e/g2f 行）
- Redis：`myxhs:like:note:*`、`myxhs:like:comment:*`、`myxhs:like:user:*`、`myxhs:favorite:*`、`myxhs:like:set:*`、`myxhs:counter:1:{noteId}:*`、`analytics:event:version:*`、`idempotent:like:*`、`idempotent:favorite:*`、限流 key（social:like/unlike/favorite/unfavorite）
- ES：note_index 测试文档（可选）

## 深度 REVIEW 记录（2026-08-13，第三轮）

### L0/L1 已核
- ✅ 全部端点/限流/幂等/白名单对照源码与 gateway 配置（**关键：like/favorite 的 GET 读接口也不在 HMAC 白名单 → 需签名**）
- ✅ 计数映射：LIKE(bizType=1)→`myxhs:counter:1:{noteId}:1`（Set-based）；(bizType=2)→`myxhs:counter:3:{commentId}:1`；FAVORITE→`myxhs:counter:1:{noteId}:2`
- ✅ @Idempotent：prefix=`idempotent`，key=`like:{uid}:{bizType}:{bizId}`/`favorite:{uid}:{noteId}`，5s 窗口 → **40201**
- ✅ t_like uk_user_biz / t_favorite uk_user_note 幂等落库；actionTime 版本号防乱序

### 发现的问题（✅=已修复并验证）
| # | 问题 | 定性 | 处理 |
|---|---|---|---|
| **O-Like-1** | **点赞/关注通知链路缺失（P1）**：模板就绪但 analytics 无 NOTIFICATION_TOPIC 发送 | ✅ **已修复（2026-08-13）** | ContentFeignClient（批量详情，无 VIEW 副作用）+ LikeService type=1 + FollowService type=3；运行态 4 项验证全过（点赞/自赞/关注/已删笔记）|
| O-Like-2 | like/favorite 的 MQ 失败**不回滚** Redis，unlike/unfavorite 的 MQ 失败**回滚 + 抛 500**——行为不对称 | 观察 | 各有兜底（消费端幂等/对账 vs 回滚），记录 |
| O-Like-3 | **点赞/收藏不校验 bizId 存在性**：点赞不存在的笔记/评论 → 200 成功 + 计数 + 落库（对照：评论创建有 note 校验）| 观察 | 前端不可见已删内容；API 层容忍脏数据，对账兜底 |
| O-Like-4 | **评论点赞通知未做**（bizType=2）：需通知评论作者，content 无单条评论详情接口 | 观察/遗留 | 本轮未实现（跨服务接口成本），记录待后续 |
| O-Like-5 | **关注非幂等**：重复关注 → **41001**"已关注该用户"（实测），与点赞/收藏幂等返回成功不对称 | 观察 | 产品语义（小红书重复关注一般幂等成功），待产品决策 |
| O-Like-6 | like 有 @Idempotent(5s)，unlike/unfavorite **无** @Idempotent | 观察 | SREM/ZREM 本身幂等，@Idempotent 仅防抖；覆盖不对称 |
| O-Like-7 | 收藏 ZSet `myxhs:favorite:{uid}` 无 TTL（收藏需永久保留）| 观察 | 合理设计（收藏列表不应过期），内存增长由产品容量规划 |

### R5 修正（2026-08-13 第三轮 REVIEW）
- **关注通知 targetType=2 → 不填（null）**：NotificationEventDTO/t_notification 的 targetType 语义为 1-笔记 2-**商品** 3-订单，无"用户"类型——填 2 会被语义化为商品。已改+重启+验证（t_notification.target_type=NULL ✅）
- **断言核对通过**：`myxhs:counter:1:{noteId}:1` 由 LIKE_SET_SCRIPT Lua 实写（`SCARD → SET KEYS[3]`，CounterService:111-126）✅；限流 key `social:like:LikeController:like:{uid}`（prefix 无 myxhs 前缀）✅；重复关注 41001 实测 ✅

### 乱序一致性分析（L1 结论，非 bug）
- LikeUnlikeConsumer/FavoriteUnlikeConsumer 的 actionTime 版本号机制：乱序时以**最后 actionTime 动作**为准，残余 Redis/DB 窗口不一致由对账任务兜底（H2 Set-based 计数天然防乱序）——记录，不修

### L2 待实测清单
- 通知缺失实证（G2-03-14）——若 O-Like-1 修复后，改为验证通知生成
- counter Set 与 analytics Set 的一致时间窗（1-3s 内）
- 收藏 list 的 score 排序精确性

## 断言关键词速查
- 200 成功 / 40201 @Idempotent 重复 / 40202 限流 / 40002 参数（HTTP 400）/ 403 无签名或越权
- 点赞 key：`myxhs:like:note:{id}` / `myxhs:like:comment:{id}`；收藏：`myxhs:favorite:{uid}`（ZSet）
- 计数：点赞 `myxhs:counter:1:{id}:1`（笔记）/`myxhs:counter:3:{id}:1`（评论）；收藏 `myxhs:counter:1:{id}:2`
