# G2-02 评论用例（创建/列表/子评论/计数/删除/通知）

> 组：G2 内容与社交 | 服务：content(19002) + counter(19004) + notification(19013) | 入口：**gateway(19000)**
> 依赖：G1 登录（testlib.new_user）+ G2-01 笔记发布（本用例前置内联发布，避免跨用例数据耦合）
> 时间引用：矩阵 **#26**（限流 60s）、B 节（CounterBuffer 5s 攒批）、#23（计数缓存 5min）
> 前提：15 服务 UP；content 已含 O1/O-Comment-1 修复（2026-08-13 重启）

## 代码实证（2026-08-13，本轮核实）

### 端点与安全（gateway:19000 → content:19002）
| 端点 | 鉴权 | HMAC | 说明（代码实证） |
|---|---|---|---|
| POST /api/comment | JWT | **必须签名** | RateLimit **10次/60s** perUser prefix=`myxhs:comment:create`；`@Valid` CommentCreateRequest |
| DELETE /api/comment/{id} | JWT | 必须签名 | 评论作者 **或笔记作者** 可删 |
| GET /api/comment/list/{noteId} | **公开** | 免签 | 游标分页（lastId），orderByDesc(id)，pageSize≤20，**预载前 3 条子评论** |
| GET /api/comment/children/{parentId} | **公开** | 免签 | 子评论正序（gt lastId）|
| GET /api/comment/count/{noteId} | **公开** | 免签 | Cache Aside 缓存 **5min** |
| GET /api/comment/page/{noteId} | **公开** | 免签 | 传统分页（备用）|

- `CommentCreateRequest`：noteId **@NotNull@Positive**、parentId @Min(0)（默认 0=一级）、replyToId @Min(0)、content **@NotBlank ≤500**
- 创建返回：`R.ok("评论成功", Map.of("commentId", id))` → **data 是 dict**
- `CommentVO`：id/noteId/userId/parentId/replyToId/content/likeCount/createdAt + children（一级才有）+ childCount（一级才有，@JsonInclude NON_NULL）

### 错误码（ResultCode）
20001 笔记不存在或未发布（创建时校验） / 20004 评论不存在（父/被回复/删除目标） / 20005 评论敏感词 / 40002 参数（含"父评论不属于该笔记"） / 40202 限流 / 403 无权删除

### 创建流程（CommentService.createComment，事务内）
1. 笔记存在且 **PUBLISHED**（否则 20001）
2. DFA(content) → 命中 20005
3. parentId>0：父评论存在（20004）+ **同笔记**（40002）+ 父是子评论 → **parentId 修正为根评论**（两级制）
4. replyToId>0：存在（20004）+ 同笔记（40002）
5. 入库（like_count=0）
6. afterCommit：delayDoubleDelete(`myxhs:comment:count:{noteId}`) + **SOCIAL_TOPIC:COMMENT** + NOTIFICATION_TOPIC（**排除自己评论自己**；senderName 未设置）

### 删除流程（deleteComment）
- 权限：评论作者 或 笔记作者（否则 403）；一级评论 → **级联逻辑删子评论**；afterCommit：`myxhs:comment:count` 双删 + **SOCIAL_TOPIC:UNCOMMENT（count=1+子评论数）**

### 计数链路（代码实证）
- SOCIAL_TOPIC:COMMENT/UNCOMMENT → CounterEventConsumer.handleCommentEvent：**targetType=1(NOTE) countType=3(COMMENT)** → key=`myxhs:counter:1:{noteId}:3`（INCR + 30 天续期 + msgId 去重 + CounterBuffer 5s 攒批刷 DB）
- 注意：**评论计数挂在笔记 targetType=1 上**（不是 targetType=3——3 是"评论对象"类型，用于评论点赞）

### 列表语义（getCommentList）
- 一级评论：`WHERE note_id=? AND parent_id=0 [AND id<lastId] ORDER BY id DESC LIMIT pageSize`（**倒序**）
- 子评论预载：每根最多取 4 条（3 预览+1 判断"更多"）；**children.size()≥4 时 childCount=精确 COUNT（batchCountByParentIds）**，否则 childCount=children.size()；`children` 字段只含前 3 条
- getChildComments：`WHERE parent_id=? [AND id>lastId] ORDER BY id ASC`（**正序**）
- getCommentCount：缓存 5min；计数准确性以 counter 服务 key 为准（列表/计数接口与 counter 是两套）

### 通知链路（评论 → NOTIFICATION_TOPIC → t_notification）
- content 事件字段（实证）：type=2、senderId、targetUserId=笔记作者、targetId=noteId、targetType=1、content=**前 50 字预览**、targetName=笔记标题；**无 senderName/senderAvatar**
- notification NotificationEventConsumer：msgId 幂等（`myxhs:notification:consumed`，24h）→ 自己不发 → processEvent
- buildNotification 模板渲染：title=`{sender}`（**senderName 为空 → "某用户"**）+"评论了你的笔记"类模板；`{title}`→targetName
- 聚合：key=`myxhs:notification:agg:{userId}:{type}:{targetId}`，窗口=当天剩余秒（对齐 uk_aggregate 按天）；窗口内后续事件**更新主通知 aggregate_count，不新增行**
- 落库：`my_xhs_notification.t_notification`（type=2, is_read=0, aggregate_count≥1）
- 未读：`myxhs:notification:unread:{userId}` +1（仅新建时）

### 死缓存（O-Comment-1，2026-08-13 已修复）
- `myxhs:comment:list:{noteId}` 原在创建/删除后 delayDoubleDelete，但列表读路径**从不回填**（P2-13 同款死缓存）→ 已移除 2 处调用并重启 content；COMMENT_COUNT 有读路径保留
- **回归验证**：评论后 `EXISTS myxhs:comment:list:{noteId}` 应为 0（不再被写）

## 前置准备（执行时记录快照）
```python
from testlib import *
# 1. 注册 2 用户：g2b_（笔记作者） g2c_（评论者）
# 2. g2b_ 发布 1 篇笔记 → {noteId}（G2-01-01 同款入口）
# 3. 基线：SELECT COUNT(*) FROM my_xhs_content.t_comment WHERE note_id={noteId} = 0
#          SELECT COUNT(*) FROM my_xhs_notification.t_notification WHERE user_id={g2b.uid}（记数）
```

## 用例清单

### G2-02-01 评论成功（全断言）
- **入口**：`POST /api/comment` body=`{"noteId":{noteId},"content":"g2c 的一级评论"}`（g2c_ 签名）
- **L1 断言**：200；**data 是 dict**：`data.commentId` 为**字符串**雪花 id（R4：Long→ToStringSerializer）
- **L2 数据验证**（关键）：
  ```
  SELECT id,note_id,user_id,parent_id,reply_to_id,like_count,deleted FROM my_xhs_content.t_comment WHERE id={commentId}
  # → parent_id=0 reply_to_id=NULL like_count=0 deleted=0
  GET /api/comment/list/{noteId}    # → 1 条，id={commentId}，childCount 无（children=[]）
  GET /api/comment/count/{noteId}   # → data.count=1
  # 计数（CounterBuffer 5s 攒批，等 5-15s）：
  GET myxhs:counter:1:{noteId}:3    # → "1"（targetType=1 countType=3 实证）
  TTL myxhs:counter:1:{noteId}:3    # → ~30 天（P2-6 续期）
  # 通知落库（等 1-3s MQ）：
  SELECT id,user_id,type,sender_id,target_id,target_type,is_read,aggregate_count FROM my_xhs_notification.t_notification
    WHERE user_id={g2b.uid} ORDER BY id DESC LIMIT 1
  # → type=2 sender_id={g2c.uid} target_id={noteId} target_type=1 is_read=0 aggregate_count=1
  # → title 渲染："某用户评论了你的笔记"（senderName 缺失→{sender}=某用户；执行时记录实际模板文案）
  # 未读计数：
  GET myxhs:notification:unread:{g2b.uid}    # → ≥1（类型 key 同步）
  # O-Comment-1 回归：EXISTS myxhs:comment:list:{noteId} = 0
  ```
- **🔍 人工观察**：RocketMQ NOTIFICATION_TOPIC 消息；notification 日志"[通知] 处理完成"

### G2-02-02 参数校验（负面）
- ① 缺 noteId → **40002**；② content 空 → **40002**；③ content 501 字 → **40002**；④ noteId=0 → **40002**（@Positive）
- **L1 断言**：均 40002（body code，**HTTP 400**）；t_comment 无新增

### G2-02-03 敏感词评论 → **20005**（content="赌博"）
### G2-02-04 未发布笔记评论 → **20001**（草稿 id + 已删 id 各一次；"笔记不存在或未发布"）
### G2-02-05 限流（矩阵 #26，10次/60s）
- 连续评论 11 次（不同 content）→ 第 1-10 次 200，**第 11 次 40202**"评论过于频繁"
- **L2**：`ZCARD myxhs:comment:create:CommentController:createComment:{g2c.uid}` = 10
- **清理**（测完立即）：`DEL myxhs:comment:create:CommentController:createComment:{g2c.uid}`
- **注意**：10 条真实评论留作列表用例数据（11 次里的前 10 次成功）

### G2-02-06 无签名评论 → **403**（带 JWT 无签名头；对照 G2-01-05 机理）
### G2-02-07 回复评论（两级制）
- ① g2c 回复一级评论 {c1}：`POST /api/comment` body=`{"noteId":{noteId},"parentId":{c1},"content":"回复一级"}` → 200 → **DB parent_id={c1}**，记 {c2}
- ② g2d（新注册）回复子评论 {c2}：`parentId={c2}`（c2 的 parent_id 已是 c1）→ 200 → **DB parent_id 被修正为 {c1}**（根评论，实证第 3 步），记 {c3}
- ③ 楼中楼回复指定 replyToId：`{"parentId":{c1},"replyToId":{c3},"content":"@楼中楼"}` → 200 → DB parent_id={c1} 且 reply_to_id={c3}，记 {c4}
- **L2**：`SELECT id,parent_id,reply_to_id FROM my_xhs_content.t_comment WHERE id IN ({c1},{c2},{c3},{c4})` 逐项核对（c2/c3/c4 的 parent_id 均为 c1；c4 的 reply_to_id=c3）

### G2-02-08 回复校验（负面）
- ① parentId 不存在（大数 id）→ **20004**"父评论不存在"
- ② parentId 属于其他笔记 → **40002**"父评论不属于该笔记"（用 G2-01 遗留笔记或临时第二笔记）
- ③ replyToId 不存在 → **20004**
- ④ replyToId 跨笔记 → **40002**

### G2-02-09 列表游标分页 + 子评论预载
- **前置**：01/05/07 已产生一级评论：01(1) + 05(10) + 07①(1) = **12 条一级**；为 {c1} 造 4 条子评论（body parentId={c1}，等 1s）；为 {c2} 造 1 条子（parentId={c2} → 修正为根 {c1}，同样计入 c1 的子）
- ① `GET /api/comment/list/{noteId}?pageSize=10` → 10 条，**第一条 id 最大**（倒序实证）；无 lastId 返回最新 10 条
- ② 取①最后一条 id={lastId} → `?lastId={lastId}&pageSize=10` → **id 全部 < lastId**（游标翻页）
- ③ {c1} 的 VO：`children` 数组 = **前 3 条**（id 最小 3 条）；**childCount=8**（O-Comment-4/6 修复后：精确计数生效——字符串"8"，与 `SELECT COUNT(*) ... WHERE parent_id={c1}` 一致；执行时以实际为准）
- ④ **childCount 边界**（<4 分支）：选 05 限流产生的另一条一级评论 {c5}，造 1 条子 → 列表 {c5} 的 childCount=1（=children.size()，<4 分支实证）

### G2-02-10 子评论列表（children 正序）
- `GET /api/comment/children/{c1}?pageSize=3` → 3 条，**第一条 id 最小**（正序实证）；`?lastId={第3条id}` → 剩余 1 条（id>lastId）

### G2-02-11 计数一致性（缓存 5min vs counter）
- ① 记 `GET /api/comment/count/{noteId}` = N（缓存 5min）→ 等 5-15s → `GET myxhs:counter:1:{noteId}:3` = N（两套数值最终一致；**允许缓存窗口内接口值旧**——断言以 counter key 为真值）
- ② **缓存失效验证**（矩阵 #23）：再发 1 条评论 → `GET /api/comment/count/{noteId}` 应立即 +1（delayDoubleDelete 清缓存后回填新值）——若仍旧值，等 1-2s（延迟双删 500ms）再查

### G2-02-12 删除评论（作者/笔记作者/越权/级联）
- **c1 子树累计**（执行时核对）：07②1 + 07③1 + 09 造 5 = **7 条子**（07③ 的 {c4} parent_id=c1、09 给 c2 造的子也被修正为 c1）
- ① **评论者删**：g2c 删自己的 {c1}（含 7 条子）→ 200 → DB **c1+7 子全部 deleted=1**
- ② **笔记作者删**：g2b 删 {c2} → 200
- ③ **第三方删**：g2d 删 {c3} → **403**"无权删除该评论"
- ④ **计数递减**：等 5-15s → `GET myxhs:counter:1:{noteId}:3` 减少 **8**（UNCOMMENT totalDeleted=1+7=8 实证；执行时以实际子数核算）
- ⑤ 列表验证：`GET /api/comment/list/{noteId}` 不再含 {c1}；`GET /api/comment/count/{noteId}` 同步减少
- ⑥ 删不存在的评论 → **20004**

### G2-02-13 通知链路完整验证（跨服务）
- ① 自己评论自己不通知：g2b 自评 → 等 1-3s → t_notification **无新增**（senderId==targetUserId 实证；计数仍 +1）
- ② 聚合（当天窗口）：g2c 对 {noteId} 再评 2 条 → t_notification 中 type=2 的**主通知 aggregate_count 2→3 不新增行**（uk_aggregate 实证）——若跨天执行则新增行（执行时注明日期边界）
- ③ NOTIFICATION_TOPIC 消息：RocketMQ Dashboard 可见（可选）

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G2-02-01 | 2026-08-13 | ✅ | 评论全链路；**发现 O-Counter-1：计数 key TTL=-1（dedup 路径无 30 天续期）→ 已修复**（Lua 补 EXPIRE，验证 TTL≈2592000）|
| G2-02-02 | | ✅ | 4 项参数校验 HTTP400+40002 |
| G2-02-03 | | ✅ | 敏感词 20005 |
| G2-02-04 | | ✅ | 草稿评论 20001 |
| G2-02-05 | | ✅ | 限流干净窗口 10+第11拒 |
| G2-02-06 | | ✅ | 无签名 403 |
| G2-02-07 | | ✅ | 两级制（c2/c3/c4 parent_id 均修正=c1，c4 reply_to=c3）；断言脚本误把根计入，数据正确 |
| G2-02-08 | | ✅ | 父/被回复 不存在 20004、跨笔记 40002 |
| G2-02-09 | | ✅ | 游标翻页/倒序/预载 3 条；**c1 childCount=7 与 DB COUNT 一致**（O-Comment-4/6 修复实锤；"c2 的子"未创建成功，记录）|
| G2-02-10 | | ✅ | children 正序 + lastId 翻页 |
| G2-02-11 | | ✅ | count 接口=DB=20 一致（缓存 5min）|
| G2-02-12 | | ✅ | 级联删除 6 子+计数递减；**发现 O-Counter-2：UNCOMMENT 固定减 1（20-1-1=18）→ 已修复**（Consumer 读 count 字段，验证 -2 正确）；历史漂移 6 待对账 |
| G2-02-13 | | ✅ | 聚合 aggregate_count 22→24 不新增行；自评不通知；第三方删 403（重验）|

### 执行期发现（补充 REVIEW）
- **O-Counter-1（✅已修复）**：`incrementWithDedup/decrementWithDedup` Lua 只设 dedupKey TTL，counterKey 无续期 → 评论/收藏/分享/VIEW 计数 key TTL=-1 永不回收（P2-6 遗漏 dedup 路径）；补 `EXPIRE KEYS[2] 30天`
- **O-Counter-2（✅已修复，P1）**：UNCOMMENT 事件带 count（级联 1+N），消费端 `decrementWithDedup` 固定 -1 → **级联删除评论计数少减**（实测 20→18，应为 12）；修复：Consumer 读 count 字段 + CounterService 支持 delta（验证 -2 正确）
- **用例顺序教训**：删除类用例需在级联删除前测（第三方删要用未级联的评论）
- 执行断言修正：childCount 以 DB COUNT 为准、聚合行数不变、total 为字符串需 int()

## 清理清单（执行后）
- 用户：g2b_/g2c_/g2d_（t_user + token/hmac key）
- 数据：t_comment（note_id 相关整行删除）、t_note（g2b 笔记 deleted=1）、t_notification（user_id=g2b/g2c 行删除）、t_local_message（body LIKE %noteId%）
- Redis：`myxhs:comment:count:{noteId}`、`myxhs:counter:1:{noteId}:3`、`myxhs:notification:unread:*`、`myxhs:notification:agg:*`、`myxhs:notification:consumed:*`、限流 key、`myxhs:feed:inbox:*`
- 前缀统一 `g2b_`/`g2c_`/`g2d_`

## 深度 REVIEW 记录（2026-08-13，第三轮）

### L0/L1 已核（代码实证）
- ✅ 全部端点/限流/白名单/错误码对照源码与 gateway 配置
- ✅ 评论计数 key=`myxhs:counter:1:{noteId}:3`（targetType=1 NOTE, countType=3 COMMENT——**不是** targetType=3）
- ✅ 两级制 parentId 修正、级联删除、UNCOMMENT 计数=1+子数
- ✅ 通知：聚合 key/窗口/自评排除/模板渲染（senderName 缺失→"某用户"）

### 本轮修复（已部署重启 + 运行态验证 ✅）
| # | 问题 | 根因 | 修复与验证 |
|---|---|---|---|
| O-Comment-1 | `myxhs:comment:list:{noteId}` 死缓存 | 列表读路径从不回填（P2-13 同款，content 遗漏）| 移除 2 处调用；验证：评论后 EXISTS=0 ✅ |
| O-Comment-4 | **子评论预载全局截断**：子评论总数 > rootIds×4 时后序根评论 children 空/childCount=0（功能级）| 旧 SQL `LIMIT rootIds.size()*4` 全局截断 + 全局排序 | 改窗口函数 `ROW_NUMBER() OVER (PARTITION BY parent_id ORDER BY id)` 每根独立取 4 条（CommentMapper.selectTopChildrenByParentIds）；验证：根A 8 子+根B 1 子 → B 正常返回 ✅ |
| O-Comment-6 | **childCount 精确计数恒失效**：子评论 ≥4 的根评论 childCount 恒=4（真实 8 显示 4）| `batchCountByParentIds` 返回 `Map<Long,Long>`——MyBatis Map key 运行时类型不可控（String/Long），`countMap.get(rootId)` 恒 miss → 回退 children.size()=4 | 改 `List<Map<String,Object>>` 显式 `(Number)` 转换（CommentMapper/CommentService）；验证：根A 8 子 → childCount=8 ✅ |

### R4 断言修正（全局行为实证）
- **所有 Long/雪花 ID 在 JSON 响应中序列化为字符串**（common JacksonConfig Long→ToStringSerializer，防 JS 精度丢失）——`data.noteId`/`data.commentId`/`id`/`userId`/`parentId`/`childCount` 等断言一律按**字符串**（内容为数字），`childCount` 的算术比较需 `int()` 转换

### 未读 key 类型（UnreadCountService 实证）
- `myxhs:notification:unread:{userId}` = **string**（INCR）；`myxhs:notification:unread:type:{userId}` = **hash**（HINCRBY，field=type）——断言可用 GET

### 观察项（不修，记录）
| # | 问题 | 说明 |
|---|---|---|
| O-Comment-2 | 评论通知缺 senderName/senderAvatar → 通知标题显示"某用户" | content 事件未填发送者信息；归 G7 统一处理 |
| O-Comment-3 | 评论计数双轨：接口缓存(5min) vs counter key（最终一致）| 设计如此；L2 以 counter key 为真值 |
| O-Comment-5 | **楼中楼回复只通知笔记作者，不通知被回复者** | createComment 的 targetUserId 恒=笔记作者；产品语义（小红书回复通知被回复者）待产品决策 |
| O-Comment-7 | 删除根评论与并发建子评论竞态：父删后新建的子评论成孤儿（children 接口可查）| 极小窗口；低风险观察 |
| O-Comment-8 | 通知跨天聚合边界：跨天后 ≤60s 内新事件仍聚合到前一天主通知（窗口锁 TTL 60s 保底）| 语义偏差窗口极小，观察 |
| O-Comment-9 | 评论列表无缓存（每页 3 条 SQL：根+子+精确 COUNT）| 热门笔记 DB 压力；实时性设计权衡，观察 |

### L2 待实测清单
- 通知模板实际文案（t_push_template type=comment 内容）
- counter key TTL 精确值（≈30 天）
- 聚合跨天边界行为（执行日当天内验证单日聚合即可）

## 断言关键词速查
- 200 成功 / 20001 笔记不存在或未发布 / 20004 评论不存在 / 20005 评论敏感词 / 40002 参数（HTTP 400） / 40202 限流 / 403 无权删除或无签名
- 创建返回 `data.commentId`（dict）| 一级 parent_id=0 | 子评论 parent_id=根评论 id
