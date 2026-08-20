# G7-03 计数用例（查询/批量/Buffer 刷盘/dedup/LikeSet/对账/TTL）

> 组：G7 通知IM计数 | 服务：counter(19004) + 联动 analytics/content（SOCIAL_TOPIC 事件源）| 入口：**gateway(19000)**
> 依赖：G1 登录 + G2 社交（点赞/收藏/评论/关注产生计数事件）+ G3 商品（VIEW）
> 时间引用：矩阵 **#2**（CounterBuffer 5s 刷盘）、**#4**（counterReconcileJob xxl 每天3点）、**#26**（get 1s/50 唯一秒级限流）、D 节（计数 30 天 TTL）
> 前提：counter UP、xxl 任务 4 存在启用（组 3，每天3点）、t_counter 表存在

## 代码实证（2026-08-14，G7 梳理全量核实）

### 端点与安全（gateway:19000 → counter:19004）
| 端点 | 鉴权 | 说明（代码实证） |
|---|---|---|
| GET /api/counter/get | **公开**（JWT+HMAC 双白名单）| targetType/targetId/countType；两级缓存（Redis→MySQL 回填）；**@RateLimit 1s/50（唯一秒级）** |
| POST /api/counter/batch-get | **公开** | body=CounterBatchRequest（queries[]: targetType/targetId/countTypes[]）；Pipeline + MySQL 批量兜底；返回 `{"1:20001": {"like":42,...}}`（英文 key）|
| POST /api/counter/reconcile | **JWT + X-Admin-Call**（HMAC 豁免）| 手动对账；@RateLimit 2/60s；返回修复条数 |

### 写入链路（MQ 事件驱动，代码实证——第一轮 REVIEW 精确化）
- **CounterEventConsumer**（SOCIAL_TOPIC，selector=LIKE||UNLIKE||FAVORITE||UNFAVORITE||COMMENT||UNCOMMENT||SHARE||VIEW||FOLLOW||UNFOLLOW，counter-consumer-group）→ CounterService **事件→计数映射**：
  | 事件 | 字段 | 映射 | 路径 |
  |---|---|---|---|
  | LIKE/UNLIKE | bizType/bizId/userId | **bizType=1→笔记**（targetType=1,countType=1）；**bizType=2→评论**（targetType=3,countType=1，H4）；缺 userId 跳过 | LikeSet（SADD/SREM+SCARD）|
  | FAVORITE/UNFAVORITE | noteId | 笔记收藏（1,2）| dedup INCR/DECR |
  | COMMENT/UNCOMMENT | noteId（**UNCOMMENT 带 count 级联删除 1+N**）| 笔记评论（1,3）| dedup INCR/DECR（count delta）|
  | SHARE | noteId | 笔记分享（1,4）只增 | dedup INCR |
  | VIEW | noteId | 笔记浏览（1,5）只增 | dedup INCR |
  | FOLLOW/UNFOLLOW | **followerUserId/followeeUserId** | **双向**：follower 关注数（2,7）+ followee 粉丝数（2,6）；**msgId+"_2" 避免 dedup 冲突**；followee 失败回滚 follower 并抛异常重试 | dedup INCR/DECR |
- **Lua 幂等**：`myxhs:counter:dedup:{msgId}`（2h TTL）——重复消息跳过（返回 0）；归零保护 -1；30 天续期
- **Lua 幂等**：`myxhs:counter:dedup:{msgId}`（2h TTL）——重复消息跳过（返回 0）
- **Buffer 刷盘**：counterBuffer.add → 5s 定时刷盘 / 满 100 触发 / **双 Buffer 交换 + 合并同 Key** / tryLock 非阻塞 / 失败重试 3 次 / **优雅停机强制刷盘** → t_counter batchUpsert（按唯一索引排序防死锁）

### 查询链路（代码实证）
- getCount：Redis GET → 未命中 MySQL selectByTarget → **回填 Redis（30 天 TTL）**
- batchGetCounts：Pipeline GET 全部 → 未命中批量 MySQL（selectByTargets 一次）→ 回填；响应英文 key（like/collect/comment/share/view/follower/following）

### 对账（CounterReconcileJob + CounterService.reconcile，代码实证）
- xxl#4 counterReconcileJob（组 3，`0 0 3 * * ?` 每天3点，trigger_status=1）
- **DB 扫描基准**（游标 lastId 分批 1000）：Redis 有值 DB 有值不一致 → **以 Redis 为准修 DB**（Redis 实时权威）；Redis=0 DB>0 → 以 DB 回填 Redis
- **analytics 权威修正**：scan `myxhs:counter:{1|3}:*:1`（like key）→ 与 `myxhs:like:set:{note|comment}:{id}` SCARD 对比 → 以 analytics Set 为准
- 懒迁移 tryLazyMigrateLikeSet：counter Set 空但 counter>0 → 从 analytics 权威 Set 同步
- 返回修复条数；handleSuccess"对账修复完成，修复 N 条"

### TTL（D 节/P2-6，代码实证）
- 计数 key：**30 天**（INCR 路径 expire 30d；dedup 路径 Lua EXPIRE 30d；LikeSet 路径同）——**活跃 key 持续续期，冷 key 回收**
- dedup key：2h
- **归零保护**：DECR 前 GET≤0 → 拒绝（m11 Lua；dedup 路径 -1 状态）

### 错误码
200 / 40002（batch-get 参数校验）/ 403（reconcile 管理）/ 40202（限流）

## 前置准备（执行时记录快照）
```python
from testlib import *
# 1. 用户 g7c_（操作者）；目标：G2 笔记/评论 or G3 商品（或直接投 MQ 事件——dashboard 403 限制）
# 2. 触发计数事件：点赞/收藏/评论/关注（G2 接口）→ 消费链路自动产生
# 3. 清理：DEL myxhs:counter:* myxhs:counter:dedup:* myxhs:like:set:* + DELETE FROM my_xhs_counter.t_counter（测试行）
```

## 用例清单

### G7-03-01 计数查询（两级缓存）
- **前置**：构造计数（点赞 n 次）
- **入口**：`GET /api/counter/get?targetType=1&targetId={noteId}&countType=1`（**无 token——公开**）
- **L1**：200；data=计数（Long→String R4）
- **L2**：`myxhs:counter:1:{noteId}:1` 存在 TTL≈30 天（2592000）
- **DB 兜底**：DEL Redis key → 再查 → 值从 MySQL 返回并**回填 Redis**（L2：key 重建）
- **countType 英文对照**：1=like 2=collect 3=comment 4=share 5=view 6=follower 7=following

### G7-03-02 批量查询（Pipeline + 兜底）
- **入口**：`POST /api/counter/batch-get`（无 token）body=`{"queries":[{"targetType":1,"targetId":{noteId},"countTypes":[1,2,3]}]}`
- **L1**：200；data=`{"1:{noteId}":{"like":N,"collect":M,"comment":K}}`（英文 key 实证）
- **多查询**：2 个 target 混合（含不存在的 targetId）→ 存在返回计数、不存在返回 0（MySQL 兜底不报错）
- **负面**：queries 空 → data={}；缺 targetType → 40002（@Valid）

### G7-03-03 计数写入链路（MQ → Redis → Buffer 5s 刷盘，核心）
- **前置**：清理 t_counter 目标行 + Redis 计数 key
- **步骤**：通过 G2 接口产生事件（点赞×2 + 取消×1 → 净 +1；或评论 2 条 → comment +2）
- **L2 分阶段**：
  ```
  # ① 立即（1s）：Redis myxhs:counter:1:{noteId}:1 = 净增量（Lua 实时）
  # ② Buffer 合并：5-15s 后 t_counter count_value=净增量（batchUpsert 刷盘实证）
  # ③ dedup：同 msgId 重投（构造）→ 计数不再变（Lua 返回 0）
  ```
- **🔍 人工观察**：Kibana counter 日志"[Buffer-Trigger] 刷盘成功: N 条"（5s 周期）

### G7-03-04 Buffer 合并与满量触发
- **合并**：同一 key +1+1-1 → 刷盘后 count_value 净 +1（合并 3 次写 → 1 次 SQL）
- **满量触发**：构造 >100 次不同 key 写入（循环点赞不同笔记）→ 观察立即刷盘（bufferSize≥100 → flush 实证——Kibana 日志）
- **优雅停机刷盘**：标注（@PreDestroy——不实测，代码实证）

### G7-03-05 dedup 幂等（MQ 重复消费）
- **入口**：直接投 SOCIAL_TOPIC 消息（构造 msgId）或经 G2 接口产生事件后**重放同 msgId**——dashboard 403 → **用 SQL/日志核对**：`myxhs:counter:dedup:{msgId}` 存在 TTL≈7200；重复消费被 Lua 拦截（计数不变）
- **验证**：两次相同 msgId 的 incrementWithDedup 调用（间接：投 2 次同消息）→ 计数仅 +1

### G7-03-06 LikeSet 幂等计数（like/unlike）
- **前置**：G2 点赞链路（analytics + counter 双 Set）
- **步骤**：g7c_ 点赞 note → `myxhs:like:set:1:{noteId}` SADD；**重复点赞** → changed=0（SADD 幂等）→ 计数不变
- **取消**：unlike → SREM + SCARD 覆盖（**乱序安全**：LIKE 后到 UNLIKE → 最终 SCARD=0，非 delta 计数）
- **L2**：`myxhs:counter:1:{noteId}:1` = SCARD（Set 值覆盖）
- **懒迁移**：构造 counter>0 但 like:set 空 → 下次 like 操作触发 tryLazyMigrateLikeSet（从 analytics 权威 Set 同步）

### G7-03-07 归零保护
- **构造**：计数 key 值=0（或 DEL 后）→ 投 UNLIKE/UNFAVORITE 事件（dec）→ **拒绝 -1**（m11 Lua：GET≤0 返回 0；dedup 路径 -1 状态）
- **L2**：计数保持 0 不为负；Kibana"[计数] 归零保护触发"

### G7-03-08 对账修复（counterReconcileJob xxl#4 / 手动 reconcile）
- **构造漂移**（③ 操纵）：
  ```
  # ① 点赞构造计数 Redis=5 → Buffer 刷盘 t_counter=5
  # ② 漂移 A：SQL 改 t_counter count_value=99（DB 高）→ 触发 → Redis 权威 → DB 修正回 5
  # ③ 漂移 B：DEL Redis key（Redis=0）→ t_counter=5 → 触发 → Redis 回填 5（以 DB 为准分支）
  # ④ 漂移 C：analytics 权威 Set 与 counter like 不一致 → 以 analytics 修正
  ```
- **触发**：`POST /api/counter/reconcile`（JWT + X-Admin-Call）→ 返回修复条数（≥1）
- **xxl#4**：admin API 手动触发 id=4 → xxl_job_log handle 200"对账修复完成，修复 N 条"
- **幂等**：无漂移 → 修复 0
- **限流**：reconcile 连打 3 次 → 第 3 次 40202（2/60s）

### G7-03-09 计数 TTL 30 天（P2-6 回归）
- **入口**：任意计数 key → `TTL myxhs:counter:{t}:{id}:{c}` ≈ 2592000（30 天）
- **续期**：再触发 +1 → TTL 复位 30 天（活跃续期实证）
- **dedup key**：`TTL myxhs:counter:dedup:{msgId}` ≈ 7200（2h）

### G7-03-10 鉴权 + 限流（安全）
- ① `GET /api/counter/get`、`POST /api/counter/batch-get` 无 token → **200**（公开）
- ② `POST /api/counter/reconcile` 无 token → 401；带 token 无 X-Admin-Call → 403；带错值 → 403
- ③ **get 秒级限流**：1s 内连打 51 次 → 第 51 次 **40202"查询过于频繁"**（windowSeconds=1 唯一实证——#26 唯一 1s 窗口）；等 1s 恢复
- ④ 未知路径 → 404

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G7-03-01 查询 | 19:41 | ✅ | 两级缓存/兜底回填/TTL30天 |
| G7-03-02 批量 | 19:42 | ✅ | Pipeline/英文 key/40002 |
| G7-03-03 写入链路 | 19:43 | ✅ | 真实事件→LikeSet→Buffer 5s 刷盘（O-Like-3 目标校验实证）|
| G7-03-04 Buffer 合并 | 19:46 | ✅ | 3 用户 3 写→净+1；同用户净 0（幂等）|
| G7-03-05 dedup | 19:47 | ✅ | dedup key TTL 2h；xxl#4 handle 200 |
| G7-03-06 LikeSet | 19:45 | ✅ | SADD 幂等/SCARD 一致 |
| G7-03-07 归零保护 | 19:46 | ✅ | 不为负 |
| G7-03-08 对账 | 19:48 | ⚠️（T-095） | 双向修复 ✅；**reconcile 全局限流优先于鉴权=缺陷行为（T-095 待修）** |
| G7-03-09 TTL | 19:44 | ✅ | 30 天/续期 |
| G7-03-10 鉴权+秒级限流 | 19:49 | ✅ | 公开/51 连打 40202/1s 恢复 |

## 断言关键词速查
- 200 / 40002（batch 参数）/ 403 / 40202（限流）
- 关键 L2：t_counter 刷盘（5-15s）、dedup key 2h、计数 key 30 天、LikeSet SCARD 覆盖、归零保护、对账双向修复、reconcile 修复条数

## 深度 REVIEW 补充（2026-08-14 第一轮，代码实证核对）
### L0/L1 已核
- ✅ 端点安全矩阵（2 公开 + reconcile 管理；**get 1s/50 唯一秒级限流**）
- ✅ 写入链路（10 事件 selector、LikeSet/dedup 双 Lua、归零保护、30 天续期）
- ✅ Buffer（5s/100 满量/双交换/合并/tryLock/重试 3/停机刷盘/排序防死锁）
- ✅ 查询两级缓存（回填 30 天）+ Pipeline 批量 + MySQL 批量兜底
- ✅ 对账三路（Redis 权威修 DB / Redis=0 回填 / analytics 权威修 like）+ 懒迁移
- ✅ xxl#4 运行库确认（组 3、每天3点、trigger_status=1）

### 第二轮深度 REVIEW（2026-08-14，代码+运行库实证）
- ✅ **事件映射精确化**（上表）：LIKE 分 bizType 1/2（笔记/评论 LikeSet）；UNCOMMENT 带 count（级联 1+N）；FOLLOW 双向（follower/followee 字段名 + msgId2 分 dedup）+ 部分失败回滚
- ✅ 运行态：三服务 UP、notification=dev profile（N09 可用）、xxl#4（组3 每天3点）/xxl#5（组5 每5分钟）trigger_status=1、Redis G7 域全 0
- ⚠️ **执行前清理表残留（G7 前置）**：t_notification=4（g6 回归）、t_chat_message=15（历史 Task 残留）、t_counter=278（Buffer 刷盘累积）——全为测试数据，G7 开始前 DELETE 全表/按前缀清理

### 风险/观察项
- [ ] Buffer 刷盘失败重试 3 次后仅日志（对账兜底——设计取舍）
- [ ] DB 无记录但 Redis 有值（对账不覆盖——等 Buffer 自然刷盘）
- [ ] LikeSet 懒迁移只覆盖 targetType 1/3（笔记/评论）
- [ ] 事件 msgId 由上游生成（G2 验证过幂等——G7 补充 counter 侧断言）

### 待 L2 确认
- [ ] Buffer 满 100 立即刷盘实测
- [ ] dedup 重投构造方式（MQ dashboard 403——用 SOCIAL_TOPIC 直接投或 G2 接口 + 记录 msgId）
- [ ] 对账 analytics 修正路径实测
- [ ] get 秒级限流实测（51 连打）
