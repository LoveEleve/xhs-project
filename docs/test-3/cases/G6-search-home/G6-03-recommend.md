# G6-03 推荐用例（推荐 Feed/相似/行为上报 + 离线计算）

> 组：G6 搜索首页 | 服务：search(19016) recommend 域（复用 search 服务）+ 联动 content（t_user_behavior/t_item_feature）| 入口：**gateway(19000)**
> 依赖：G1 登录 + G2 笔记发布（t_note 有已发布笔记）+ G6-01 索引重建（t_item_feature 依赖 note 数据）
> 时间引用：矩阵 **#19**（recommendFeatureJob xxl 每小时）、**#20**（recommendHotPoolJob xxl 每10分钟）、**#21**（recommendItemCFJob xxl 每天2点）——全部手动触发
> 前提：search UP、xxl 19/20/21 存在启用（组 12）、**t_item_feature 表存在性前置确认（README §风险 1：当前缺失）**

## 代码实证（2026-08-14，G6 梳理全量核实）

### 端点与安全（gateway:19000 → search:19016 /api/recommend/**）
| 端点 | 鉴权 | 说明（代码实证） |
|---|---|---|
| GET /api/recommend/feed | **JWT + HMAC** | 个性化推荐 Feed（X-User-Id）；四层流水线异步执行 |
| GET /api/recommend/similar/{noteId} | **JWT + HMAC** | 相似笔记（ItemCF 矩阵 ZSet，size 上限 20）|
| POST /api/recommend/behavior | **JWT + HMAC** | 行为上报（@Valid BehaviorRequest：noteId/behaviorType 1-7/duration）|
| POST /api/recommend/compute | **JWT + X-Admin-Call**（HMAC 豁免）| 手动触发离线计算（特征→ItemCF→热池顺序）|

### 推荐流水线（RecommendService，代码实证）
`getRecommendFeed(userId)`：
1. **冷启动判断**：`SELECT EXISTS(SELECT 1 FROM t_user_behavior WHERE user_id=?)` → 无行为=冷启动
2. **冷启动召回**：热门 60 条 + 地理 30 条（无 Item-CF/内容/关注）
3. **多路召回**（5 路并行，recallExecutor 10/20/queue100 + 超时 2s）：ITEM_CF / CONTENT / HOT / FOLLOWING / GEO，每路 100 条 → 按 noteId 去重保留最高 recallScore
4. **粗排**：rankScore = recallScore × 来源权重（ITEM_CF 1.0 > FOLLOWING 0.9 > CONTENT 0.8 > HOT 0.6 > GEO 0.5）→ Top 100
5. **精排**：四维加权（来源 0.25 + 用户偏好 0.25 + 质量 0.30 + 时效 0.20）→ Top 50
   - 质量分：批量查 `t_item_feature`（**表缺失 → 降级 0.3**）；用户偏好：恒 0.5（兜底）；时效：e^(-小时/24)
   - 分类：批量查 `t_item_feature`（缺失 → category="unknown"）
6. **重排**：已读过滤（`myxhs:recommend:seen:{uid}` Set SISMEMBER，7 天 TTL）+ **品类打散**（同品类连续 ≤2，跳过第 3 个）+ 记录曝光（SADD 本次结果，7 天 TTL）→ Top 20（recommend.feed.size）
7. **VO**：noteId/score/source/category/reason（来源文案映射）

### 行为上报（RecommendService + BehaviorReportConsumer，代码实证）
- `POST /api/recommend/behavior`：构建事件（id=雪花/idGeneratorUtil）→ **MQ asyncSend RECOMMEND_BEHAVIOR_TOPIC**（失败降级同步 INSERT）→ 正向行为（3/4/5/6 或 7 且 duration>10）同步更新兴趣标签
- 兴趣标签：查 t_item_feature 该 note 的 tags（**表缺失 → 无标签，不更新**）→ Hash increment `myxhs:recommend:user:tags:{uid}` → TTL 12h
- BehaviorReportConsumer（recommend-behavior-consumer-group，maxReconsume 3）：解析 id/userId/noteId/behaviorType/duration → `INSERT INTO t_user_behavior`（**无幂等——重复投递重复插入，观察项**）；缺字段跳过；catch 不抛（允许少量丢失）

### 离线计算（RecommendComputeJob，代码实证）
| 任务 | xxl | handler | 逻辑 |
|---|---|---|---|
| 特征提取 | #19（每小时）| recommendFeatureJob | `t_user_behavior` LEFT JOIN `t_item_feature`（**表缺失 INSERT 必失败，catch 降级**）→ 缺失笔记 ≤500 → batchFetchRealFeatures（t_note tags/content 推断 category）→ 质量分 sigmoid（like×1+comment×3+collect×5）→ INSERT ON DUPLICATE |
| 热门池 | #20（每10分钟）| recommendHotPoolJob | 最近 24h 行为加权（2点击=1/3点赞=3/4收藏=5/5评论=4/6分享=6）SUM hot_score → Top 200 → 临时 ZSet RENAME `myxhs:recommend:hot:global`（TTL 1h）|
| Item-CF | #21（每天2点）| recommendItemCFJob | 最近 7 天正向行为（3/4/5/6 或 7 duration>10）→ 用户-物品倒排 → **交互数>5 热门物品** → 共现矩阵 → 余弦相似度（共现/√(a×b)）→ 每物品 Top 20 `myxhs:recommend:itemcf:{noteId}`（TTL 24h）|

- 每任务 handleSuccess/handleFail（XxlJobHelper）；源表不存在 → 跳过（log"data sync not ready"）

### 召回策略（各 RecallStrategy，代码实证——2026-08-14 深度 REVIEW 全量核对）
| 策略 | 数据源 | 逻辑 | 当前环境状态 |
|---|---|---|---|
| HOT | `myxhs:recommend:hot:global` ZSet | reverseRangeWithScores 取 Top N | ✅ 可用（xxl#20 维护）|
| GEO | **`t_item_feature.geo_hash`**（用户 GeoHash 前缀 5 位匹配 + quality_score>0.3）| 无位置/无结果 → fallbackHotRecall（quality>0.5 全站热门）；**表缺失 → SQL 1146 异常 → 空召回（catch）** | ⚠️ 表缺失 → 恒空 |
| CONTENT | 用户兴趣标签 Hash（Top5）→ `t_item_feature.tags LIKE`（转义 %/_）→ score=tagWeight×(1+quality) | **表缺失 → 空召回** | ⚠️ 表缺失 → 恒空 |
| FOLLOWING | **`myxhs:recommend:following:latest:{uid}` ZSet（无任何写入方——全仓库 grep 实证仅本策略读取）** | freshness=max(0, 1-(now-ts)/3天) | ❌ **恒空**（key 永不写入）|
| ITEM_CF | 用户正向交互笔记（**3/4 或 7>10s——不含 5 评论/6 分享**）Top 20 → 相似矩阵 ZSet Top 10/个 → 累计相似度 | 排除已交互 | ✅ 可用（矩阵由 xxl#21 维护）|

> **REVIEW 重大发现（代码实证）**：
> 1. **FOLLOWING 召回恒空**——`recommend:following:latest:{uid}` 无生产者（FeedPushConsumer 只写 inbox/outbox），reason"你关注的人发布了"**永不出现**（登记观察项，T- 系列：修复=FeedPushConsumer 推模式时同步写 following:latest 或删除该策略）
> 2. **GEO 依赖 t_item_feature**——表缺失时 catch 返回空（**非热门降级**；fallbackHotRecall 仅在查询返回空/位置未知时触发）；冷启动"热门 60% + 地理 30%"实际=纯热门
> 3. **ItemCF 召回口径 ≠ 计算口径**——召回正向=3/4/7>10s，计算任务=3/4/5/6/7>10s（5/6 行为进矩阵但不出现在用户召回输入，观察项）
> 4. **时效衰减默认值**——RecallItem.publishTime<=0 → hoursSincePublish=24 → timeDecay=e^-1≈0.368；仅 GEO 设置 publishTime，HOT/ITEM_CF/CONTENT/FOLLOWING 恒走默认（精排时效维度对多数来源恒 0.368，观察项）

## 前置准备（执行时记录快照）
```python
from testlib import *
# 1. 注册用户 g6r_（推荐用户）+ g6a_ 作者；g6a_ 发布 3-5 条笔记（带 tags，如 ["美食","旅行"]）
# 2. 行为数据：用户上报/直插 t_user_behavior（id 雪花范围，user_id=g6r_，3-5 条行为）
# 3. 清理：
#    - Redis: DEL myxhs:recommend:seen:{uid} myxhs:recommend:user:tags:{uid}
#      + SCAN myxhs:recommend:itemcf:* myxhs:recommend:hot:global 后 DEL（避免旧矩阵/热池污染）
#    - MySQL: DELETE FROM my_xhs_content.t_user_behavior WHERE user_id={g6r.uid}（清理旧行为）；测试行用独立 id 段
```

## 用例清单

### G6-03-01 冷启动推荐（无行为记录）
- **前置**：g6r_ 无任何行为（t_user_behavior 无该用户行）+ 热门池已构造（G6-03-09 触发过或手工 ZADD `myxhs:recommend:hot:global`）
- **入口**：`GET /api/recommend/feed`（JWT+HMAC）
- **L1**：200；data 非空；每条含 noteId/score/source/category/reason
- **⚠️ REVIEW 修正（代码实证）**：冷启动 = 热门 60 + 地理 30——**GEO 依赖 t_item_feature（表缺失 → 空）→ 实际结果纯 source=HOT**；断言改为 `source=HOT`（若表修复后可为 HOT/GEO 混合）；不走 ITEM_CF/CONTENT/FOLLOWING（冷启动分支实证）
- **L2**：① `myxhs:recommend:seen:{uid}` Set 已记录本次曝光（SADD 实证，TTL≈604800）；② 二次请求 → 已曝光笔记被过滤（SISMEMBER → 不在结果）——与 G6-03-03 联动
- **清理**：DEL seen key（否则后续用例被已读过滤干扰）

### G6-03-02 个性化推荐（有行为 → 5 路召回）
- **前置**：g6r_ 有行为记录（G6-03-07 造数或直插 t_user_behavior）+ ItemCF 矩阵已算（G6-03-09）
- **入口**：`GET /api/recommend/feed`
- **L1**：200；data 非空；isColdStartUser=false（有行为实证）；reason 与 source 匹配（"猜你喜欢"/"大家都在看"等）
- **⚠️ REVIEW 修正**：source 断言 ∈ {ITEM_CF, HOT, CONTENT}（**FOLLOWING 恒空**——following:latest 无写入方；GEO 表缺失恒空）；CONTENT 也依赖 t_item_feature（表缺失恒空）→ **实际可观测 source ∈ {ITEM_CF, HOT}**
- **L2**：排序合理性（rankScore 降序——粗排×来源权重后精排四维）；category 非 unknown（若 t_item_feature 可用——**表缺失时 category=unknown，登记**）
- **清理**：DEL seen key

### G6-03-03 已读过滤 + 曝光记录（重排层）
- **前置**：G6-03-01/02 已产生 seen Set
- **步骤**：① 记录本次返回 noteIds；② **再次请求** → 返回不含首次 noteIds（重排已读过滤实证）；③ L2：SISMEMBER `myxhs:recommend:seen:{uid}` 全部=1；TTL≈604800（7 天）
- **恢复**：DEL seen key → 再次请求 → 已曝光笔记重新出现
- **清理**：DEL seen key

### G6-03-04 品类打散（可选——依赖 t_item_feature）
- **前置**：t_item_feature 可用（修复 DDL 后）且构造 3 条同 category 笔记
- **步骤**：请求 feed → 结果中同 category 连续出现 ≤2（打散实证：第 3 个同品类被跳过）
- **⚠️ 前置风险**：t_item_feature 缺失 → category=unknown 全相同 → 打散退化为每 2 个一组（观察断言：连续相同 category ≤2 仍成立，但语义失真）——**执行前确认表状态**

### G6-03-05 相似笔记推荐（similar/{noteId}）
- **前置**：ItemCF 矩阵已算（G6-03-09）——`myxhs:recommend:itemcf:{noteId}` ZSet 有相似项
- **入口**：`GET /api/recommend/similar/{noteId}?size=5`（JWT+HMAC）
- **L1**：200；data 为相似 noteId 列表（source=SIMILAR、reason="相似内容推荐"）；size>20 → 截断 20（Math.min 实证）
- **L2**：ZREVRANGE `myxhs:recommend:itemcf:{noteId}` 0 4 → 与响应一致（相似度降序）
- **负面**：无矩阵（DEL itemcf key）→ data=[]（非 500）

### G6-03-06 行为上报（1-7 类型 + 校验）
- **入口**：`POST /api/recommend/behavior` body=`{"noteId":{n1},"behaviorType":{t},"duration":{d}}`（JWT+HMAC）
- **① 类型全覆盖**：t=1..7 各报一次 → 200
- **L2**（等 1-3s MQ 消费）：`my_xhs_content.t_user_behavior` 有 7 行（user_id/note_id/behavior_type/duration 正确）；RECOMMEND_BEHAVIOR_TOPIC 已消费（Kibana 或 MQ 进度）
- **② 正向行为兴趣标签**：t=3（点赞）+ 已有 t_item_feature（表缺失则跳过）→ `HGETALL myxhs:recommend:user:tags:{uid}` 含 note 标签、权重递增（increment 实证）、TTL≈43200（12h）
- **③ 负面**：缺 noteId → **40002**；behaviorType=0/8 → **40002**（@Min/@Max）；behaviorType=null → 40002（@NotNull）
- **④ 停留**：type=7 duration=5（≤10）→ 非正向（不更新标签）；duration=15 → 正向
- **注意**：t_user_behavior 无幂等——**重复上报重复插入**（观察项，用例内勿重复）；清理 DELETE WHERE user_id 前缀

### G6-03-07 特征提取（recommendFeatureJob xxl#19）
- **前置**：有行为的笔记且 t_item_feature 无对应行（或 DELETE 该行）——**⚠️ 表不存在时本用例先确认 DDL/登记问题**
- **触发**：xxl admin API 手动触发 id=19（矩阵 #1）
- **L2**：
  ```
  # ① xxl_job_log：trigger_code=200；handle_code 视实现（表缺失时任务仍 handle 200 但 log error"特征提取失败"）
  # ② t_item_feature：INSERT 行（note_id/tags=真实标签 JSON/category 推断/quality_score 0.3~1.0）
  # ③ 幂等：再次触发 → ON DUPLICATE KEY UPDATE（无重复行）
  ```
- **⚠️ 当前环境实证（2026-08-14）**：t_item_feature 表不存在 → 本用例**必须**先与对方确认 DDL（README §风险 1）——缺失则登记问题（T-080+）并按降级行为断言

### G6-03-08 热门池（recommendHotPoolJob xxl#20）
- **前置**：t_user_behavior 最近 24h 有 g6r_ 行为（不同 behavior_type 权重不同——构造 2 条：点赞(3)=3 分 + 分享(6)=6 分）
- **触发**：xxl admin API 手动触发 id=20
- **L2**：① xxl_job_log handle 200"热门池更新完成: N 条"；② `myxhs:recommend:hot:global` ZSet 存在：score=加权和（6 分享 > 3 点赞，SQL CASE WHEN 实证）、TTL≈3600；③ 排序倒序 top=最高分
- **无数据**：清空行为 → 触发 → log"无热门数据"（handle 200）
- **清理**：DEL hot:global key（1h 自动过）

### G6-03-09 Item-CF 矩阵（recommendItemCFJob xxl#21，核心）
- **前置**：构造共现数据——**同一笔记被 ≥2 用户正向行为**（如 n1 被 g6r_ 点赞 + g6x_ 点赞；n2 同样被两人行为）→ 满足交互数>5 才进热门物品集？——**注意：交互数>5 门槛高，小数据构造难满足**
- **⚠️ 代码实证**：`itemCount >= 5` 才计算——测试环境少量用户行为**通常不会触发矩阵计算**（log"无热门物品，跳过计算"）——**降级验证**：SQL 直插 ≥5 用户对同一组笔记的行为（5 用户 × 2 笔记），或标注"构造数据满足门槛"
- **⚠️ REVIEW 补充（口径）**：计算任务的正向行为=3/4/5/6 或 7>10s（含评论/分享）；但**召回端（ItemCFRecallStrategy）只认 3/4 或 7>10s**——行为 5/6 会进矩阵但不会成为用户召回输入（观察项，G6-03-06 造数时两者都覆盖）
- **触发**：xxl admin API 手动触发 id=21
- **L2**：① handle 200"Item-CF 相似矩阵计算完成: 热门物品=N, 相似对=M"；② `myxhs:recommend:itemcf:{noteId}` ZSet 存在（相似 noteId + 余弦相似度 0~1、Top 20、TTL≈86400）
- **相似度核对**：共现 2 次 / sqrt(交互数a × 交互数b)——按构造数据手算对照
- **清理**：DEL itemcf:* key（24h 自动过）

### G6-03-10 推荐离线计算管理端点（/api/recommend/compute）
- **入口**：`POST /api/recommend/compute`（JWT + X-Admin-Call）
- **L1**：200"推荐离线计算已触发"（依次执行特征→ItemCF→热池——与 G6-03-07/08/09 同一代码路径，可作三任务聚合验证）
- **负面**：无 X-Admin-Call → 403"无权访问管理接口"；带错 token → 403；无 token → 401
- **L2**：三任务执行日志（Kibana search 服务日志含"特征提取完成/Item-CF 相似矩阵计算完成/热门池更新完成"）

### G6-03-11 推荐鉴权矩阵（安全）
- ① `/api/recommend/feed|similar/**|behavior` 无 token → **401**；带 token 无 HMAC → **403**
- ② `/api/recommend/compute` 无 token → 401；带 token 无 X-Admin-Call → 403
- ③ behavior 无签名 body 篡改 → 403（HMAC 防篡改实证）

### G6-03-12 行为链路降级（MQ 不可用——标注不实测）
- **L1 代码实证**：reportBehavior catch → 降级同步 INSERT t_user_behavior（MQ 发送失败不丢数据）；BehaviorReportConsumer catch 不抛（消费失败不无限重试）
- 运行态不构造（停 MQ 影响面大）——登记 L1 结论

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G6-03-01 冷启动 | 16:44 | ✅ | **纯 HOT（GEO 表缺失恒空——REVIEW 预测实证）**；seen TTL 7 天/已读过滤 |
| G6-03-02 个性化 | 16:45 | ⚠️（T-082） | 非冷启动 ✅；**结果被打散过严误伤（同品类仅 1 条，T-082 待修）** |
| G6-03-03 已读过滤 | 16:46 | ✅ | 曝光记录/过滤轮换/DEL 恢复 |
| G6-03-04 品类打散 | 16:47 | ⚠️（T-082） | **3 条同品类仅返回 1 条=缺陷行为（第 2 条即跳过，过严，T-082 待修）** |
| G6-03-05 相似推荐 | 16:48 | ✅ | SIMILAR/n2/score=1.0/size 截断/无矩阵空 |
| G6-03-06 行为上报 | 16:49 | ✅ | 7 类型全落库/40002 负面/兴趣标签空（表缺失观察项） |
| G6-03-07 特征提取 | 16:50 | ⚠️（T-083） | **表缺失仍 handle 200"特征提取完成"=缺陷行为（误导性成功，T-083 待修）** |
| G6-03-08 热门池 | 16:51 | ✅ | 加权 19（1+3+5+4+6 权重 SQL 实证）、TTL 1h、xxl#20 handle 200 |
| G6-03-09 ItemCF | 16:52 | ✅ | **交互数≥5 门槛实测（n2 补到 5 用户后共现）**；余弦=1.0 手算对照、TTL 24h |
| G6-03-10 compute | 16:53 | ✅ | 管理鉴权 403/触发三任务 |
| G6-03-11 鉴权 | 16:54 | ✅ | 401/403 全矩阵 |
| G6-03-12 降级 | — | L1 | 代码实证标注，不实测 |

## 断言关键词速查
- 200 / 40002（behavior 参数）/ 401 / 403（HMAC/管理）
- 关键 L2：t_user_behavior 7 类型落库、recommend:seen 已读过滤 7 天、itemcf ZSet Top20 24h、hot:global 加权分 1h、user:tags 12h、xxl 19/20/21 handle 200、t_item_feature（前置确认）

## 深度 REVIEW 补充（2026-08-14 第一轮 + 第二轮深度 REVIEW + 第三轮运行态 REVIEW，全量代码核对）
### L0/L1 已核
- ✅ 端点安全矩阵（3 用户端 JWT+HMAC + 1 管理端 JWT+X-Admin-Call）
- ✅ 四层流水线（5 路召回 2s 超时 → 粗排来源权重 → 精排四维 0.25/0.25/0.30/0.20 → 重排已读+打散+曝光记录）
- ✅ 冷启动语义（EXISTS 判断 → 热门 60%+地理 30%）
- ✅ 行为上报（MQ 异步 + 降级同步 + 正向判定 3-6/7>10s + 兴趣标签 12h）
- ✅ 离线三任务（特征/热池/ItemCF 全量代码核对：SQL、权重 CASE、余弦公式、TTL）
- ✅ **t_item_feature 表缺失（运行环境实测 SHOW TABLES 确认）**——最大前置风险
- ✅ ItemCF 交互数>5 门槛——测试构造需 ≥5 用户（或用 SQL 直插满足门槛）

### 第二轮深度 REVIEW 修正（2026-08-14，5 路召回策略全量代码核对）
- ✅ **FOLLOWING 召回恒空**（重大发现）：`myxhs:recommend:following:latest:{uid}` 全仓库无写入方（grep 实证仅 FollowingRecallStrategy 读取 + 常量定义；FeedPushConsumer 只写 inbox/outbox）→ reason"你关注的人发布了"永不出现——**登记观察项（修复=FeedPushConsumer 同步写 following:latest 或移除策略）**
- ✅ **GEO 召回依赖 t_item_feature.geo_hash**：表缺失 → SQL 1146 异常 → catch 空召回（**非热门降级**——fallbackHotRecall 仅位置未知/结果空时触发）；冷启动实际=纯热门
- ✅ **ItemCF 召回/计算口径不一致**：召回正向=3/4 或 7>10s；计算=3/4/5/6 或 7>10s（观察项）
- ✅ **时效衰减默认值**：RecallItem.publishTime<=0 → hoursSincePublish=24 → timeDecay≈0.368；仅 GEO 设置 publishTime（观察项）
- ✅ **G6-03-01/02 断言修正**：source 实际可观测 ∈ {HOT}（冷启动）/ {ITEM_CF, HOT}（个性化）——按表修复前后分两档断言
- ✅ CONTENT 召回细节：tags LIKE 模糊匹配（转义 %/_）、score=tagWeight×(1+quality_score)（补充实证）

### 风险/观察项
- [ ] **t_item_feature 缺失**：特征提取 INSERT 失败（任务 handle 仍 200）、精排质量分降级 0.3、category=unknown、兴趣标签不更新、CONTENT/GEO 召回空——**执行前必须与对方确认 DDL**（README §风险 1）
- [ ] **FOLLOWING 召回恒空**（无生产者）——见上
- [ ] t_user_behavior 无幂等（重复投递重复插入——观察项）
- [ ] 用户偏好分恒 0.5（getUserPreferenceScore 兜底——实现不完整，登记观察）
- [ ] 热门池 SQL 权重与行为枚举对齐（2点击/3点赞/4收藏/5评论/6分享——注释 vs SQL 一致）
- [ ] recommend:seen 注释写 HyperLogLog 但实现为 Set（代码注释与实现不一致——已按实现 Set 断言）
- [ ] ItemCF 召回/计算口径不一致 + 时效衰减默认 24h（见上）

### 待 L2 确认
- [ ] 冷启动 60/30 比例（热门 60 + 地理 30——GEO 修复后验证）
- [ ] ItemCF 余弦值手算对照
- [ ] 特征提取 ON DUPLICATE 幂等
- [ ] compute 端点三任务顺序与日志
- [ ] FOLLOWING 恒空运行态确认（推荐结果无 source=FOLLOWING）
- [ ] GEO 修复后同城召回（geo_hash 前缀匹配）

### 第三轮运行态 REVIEW（2026-08-14，环境实测）
- ✅ **search 服务 UP**；xxl 组 12=my-xhs-search 确认（recommend×3 挂 search 执行器，id 19/20/21 trigger_status=1）
- ✅ **Redis recommend 域 key 全空**（itemcf/hot:global/seen/user:tags SCAN 实测）——无旧矩阵/热池污染
- ⚠️ **t_item_feature 不存在**（SHOW TABLES 实测，README §风险 1 已登记）——本组最大前置风险，执行前确认 DDL
- ⚠️ **t_user_behavior=0**（当前无行为数据）——G6-03 造数需从零构造（前置准备已列，id 用独立段便于清理）
- ⚠️ **t_note=0**——recommendFeatureJob 的 batchFetchRealFeatures 需 t_note 数据，执行前 G2 造数
