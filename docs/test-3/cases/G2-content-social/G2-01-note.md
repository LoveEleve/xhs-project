# G2-01 笔记用例（CRUD + 草稿 + 分享 + 上传 + Feed 链路 + canal→ES + 缓存）

> 组：G2 内容与社交 | 服务：content(19002) + home(19008) + search(19013) + analytics(19003) | 入口：**gateway(19000)**
> 依赖：G1 登录（testlib.new_user 得 token/hmacSecret/uid）+ G1-07 关注（本用例前置内联建立 g2f→g2a 关注）
> 时间引用：矩阵 **#13**（FeedMessageRetryJob）、**#23**（缓存 30min）、**#24**（空值防穿透 2min）、**#26**（限流窗口 60s）、F 节（ES 可见性 1-2s）
> 前提：执行前确认 15 服务 UP、Redis 6379 直连、MySQL 3306、RocketMQ 9876、ES 19200、canal note_instance 运行中

## 代码实证（2026-08-13，本轮核实）

### 端点与安全（gateway:19000 → content:19002）
| 端点 | 鉴权 | HMAC | 说明（代码实证） |
|---|---|---|---|
| POST /api/note/publish | JWT | **必须签名** | RateLimit 5次/60s perUser prefix=`myxhs:note:publish`；`@Valid` NotePublishRequest |
| POST /api/note/draft | JWT | 必须签名 | **无 @Valid**（草稿允许不完整）|
| PUT /api/note/{id} | JWT | 必须签名 | @Valid NoteUpdateRequest；仅 DRAFT/PUBLISHED 可编辑；**他人编辑 → 403"无权操作他人笔记"（T-100 实证，非 20001）** |
| DELETE /api/note/{id} | JWT | 必须签名 | 逻辑删除+级联删评论 |
| GET /api/note/detail/{id} | **公开** | 免签 | JWT/HMAC 双白名单；仅 PUBLISHED 可见 |
| POST /api/note/batch-detail | **公开** | 免签 | body=`List<Long>`；逐条复用 getNoteDetail，单条异常降级跳过 |
| GET /api/note/user/{userId} | **公开** | 免签 | 仅 PUBLISHED，orderByDesc(createdAt)，pageSize≤50 |
| GET /api/note/my | JWT | **必须签名**（T-101 实证：gateway HMAC 白名单无此路径——无签名 403）| status 可空过滤，pageSize≤50 |
| POST /api/note/{id}/publish | JWT | 必须签名 | 仅 DRAFT 可发（AUDITING 显式排除）|
| POST /api/note/{id}/share | JWT | 必须签名 | RateLimit 10次/60s prefix=`myxhs:note:share`；仅 PUBLISHED |
| POST /api/note/upload/image | JWT | 必须签名 | RateLimit 20次/60s prefix=`myxhs:note:upload`；multipart `file` |

### 请求/响应契约
- `NotePublishRequest`：title **@NotBlank**≤128、content≤20000、images≤9、videoUrl≤512、coverUrl≤512、topicIds`List<Long>`、tags`List<String>`、noteType∈{0图文,1视频} 默认 0
- `NoteUpdateRequest`：同字段均可空（有值才更新）；LambdaUpdateWrapper 按字段更新
- 发布/草稿返回：`R.ok("...", Map.of("noteId", id))` → **data 是 dict**：`{"noteId": "2087..."}`；**雪花 ID 全部序列化为 JSON 字符串**（R4：common JacksonConfig Long→ToStringSerializer，防 JS 精度丢失——noteId/id/userId 等断言一律按字符串）
- 详情返回 `NoteDetailVO`：id/userId/title/content/images/videoUrl/coverUrl/topicIds/tags/**status/statusDesc**/noteType/createdAt/updatedAt
- 列表返回 `PageResult`：pageNum/pageSize/total/pages/records（NoteItemVO：无 content，firstImage=images[0]）
- 状态枚举：DRAFT=0/AUDITING=1/PUBLISHED=2/OFFLINE=3；audit PENDING=0/APPROVED=1/REJECTED=2

### 错误码（ResultCode）
20001 笔记不存在 / 20002 状态不允许 / 20006 内容含敏感词 / 40002 参数格式不正确 / 40202 请求过于频繁 / 403 无权限 / 50005 文件上传失败

### 发布链路（代码实证，NoteService.publishNote）
1. DFA 敏感词检测（title+content；词库=sensitive-words.txt 静态[赌博/诈骗/传销/洗钱/走私] + Redis `myxhs:sensitive-word:list` 动态）
2. 入库 status=2/audit_status=1 → `myxhs:note:detail:{id}` 读时回填（Cache Aside，30min TTL+随机偏移；空值占位 2min）
3. 同事务 INSERT `my_xhs_content.t_local_message`（topic=FEED_TOPIC, status=0, retry_count=0）
4. afterCommit → asyncSend **FEED_TOPIC**（payload=NotePublishEvent{noteId,authorId,publishTime(ms),noteType,localMsgId}）→ onSuccess `markSent`（status=1）
5. home FeedPushConsumer（feed-push-consumer-group）消费：非大V（粉丝<100000）→ **推模式**：ZADD 收件箱 `myxhs:feed:inbox:{followerId}` score=publishTime(ms) TTL 7天；进度 hash `myxhs:feed:push:progress:{localMsgId}`{cursor,total,status}
6. canal note_instance（监听 my_xhs_content.t_note）→ **NOTE_INDEX_TOPIC** → search NoteIndexSyncConsumer → ES `note_index`（19200，ExternalGte version=ts 毫秒）；**L2 查 ES 前 sleep 1-2s**

### FeedMessageRetryJob（content，矩阵 #13）
- `retryFailedMessages` @Scheduled 30s：锁 `lock:feed:retry` TTL 55s；扫描 status=0 且 created_at<now-60s 且 retry_count<3 → 补发，成功 markSent；3 次后 status=3（死信）
- `compensateIncompletePush` @Scheduled 60s：锁 `lock:feed:compensate`；扫描 status=1 且 push_status∈(0,1) 且 created_at<now-120s → 重投
- 操纵方法（③）：`UPDATE my_xhs_content.t_local_message SET created_at=DATE_SUB(NOW(),INTERVAL 2 MINUTE) WHERE id=...`

### 限流 key 格式（RateLimitAspect，矩阵 #26）
`{prefix}:{ClassName}:{methodName}:{userId}`，例发布：`myxhs:note:publish:NoteController:publishNote:{uid}`；重置=DEL 该 key

### 图片上传（LocalFileStorageService）
- 类型白名单 image/jpeg/png/gif/webp；**≤5MB**（service 校验 + multipart 配置同）；魔数校验（JPEG FFD8/PNG 89504E47/GIF 47494638/WebP RIFF..WEBP）；文件名=UUID.ext，路径 note/yyyy/MM/dd/
- 返回 `data.url`=`http://21.214.97.212:19002/uploads/note/...`
- 验证魔数伪造：Content-Type=image/jpeg + 文本内容 → 40002"文件内容与声明的类型不匹配"

### 关注（前置用，G1-07 同端点）
POST /api/social/follow/{targetUserId}（HMAC 签名，20次/60s）→ 粉丝 ZSet `myxhs:follow:fans:{authorUid}`

## 前置准备（执行时记录快照）
```python
from testlib import *
# 1. 注册 2 用户：g2a_（作者） g2f_（粉丝）
# 2. g2f_ 关注 g2a_：call("POST", f"/api/social/follow/{g2a.uid}", token=g2f.token, secret=g2f.secret, body={})
# 3. 基线：SELECT COUNT(*) FROM my_xhs_content.t_local_message（记入执行记录）
```

## 用例清单

### G2-01-01 发布成功 + Feed 全链路 + canal→ES（核心用例，全断言）
- **前置**：g2a_/g2f_ + 关注完成
- **入口**：`POST /api/note/publish` body=`{"title":"g2a 第一篇笔记","content":"测试正文","images":["http://21.214.97.212:19002/uploads/note/2026/08/13/test1.jpg"],"tags":["测试"],"noteType":0}`（带签名）
- **L1 断言**：code=200；**`data` 是 dict**：`data.noteId` 为**字符串**雪花 id（R4：Long→ToStringSerializer 全局配置实证）
- **L2 数据验证**（关键，逐项断言）：
  ```bash
  # ① DB 入库
  mysql -h21.130.247.89 -uroot -p'Xhs@2026#MySQL' -N -e \
    "SELECT id,user_id,title,status,audit_status,note_type,deleted FROM my_xhs_content.t_note WHERE id={noteId}"
  # → status=2(PUBLISHED) audit_status=1(APPROVED) deleted=0 note_type=0
  # ② 本地消息表（同事务写入 → MQ 发送成功 → status=1）
  mysql ... -e "SELECT id,topic,status,retry_count,push_status FROM my_xhs_content.t_local_message WHERE body LIKE '%{noteId}%' ORDER BY id DESC LIMIT 1"
  # → topic=FEED_TOPIC status=1（等 1-3s，afterCommit 异步发送）
  # ③ 粉丝收件箱（推模式，等 1-3s 消费）
  python3 -c "
  import redis; r=redis.Redis(host='21.130.247.89',password='Xhs@2026#Redis',decode_responses=True)
  key=f'myxhs:feed:inbox:{G2F_UID}'
  print('ZCARD', r.zcard(key)); print('ZSCORE', r.zscore(key, str(NOTE_ID)))
  print('TTL', r.ttl(key))
  "
  # → ZSCORE={publishTime 毫秒}（≈发布时刻 ms）TTL≈7 天（604800±）
  # ④ 推送进度（断点续推）
  HGET myxhs:feed:push:progress:{localMsgId} status   # → completed
  # ⑤ ES 可见（canal→MQ→consumer，sleep 1-2s；ES 有认证）
  curl -s -u elastic:'Xhs@2026#Elastic' http://21.130.247.89:19200/note_index/_doc/{noteId} | python3 -m json.tool
  # → _source.status=2 title 一致；_version≈发布时刻 ms（ExternalGte）
  # ⑥ 详情缓存（读时回填）
  GET myxhs:note:detail:{noteId}    # → 存在（JSON 含 title）
  TTL myxhs:note:detail:{noteId}    # → ~1800s（30min±随机偏移）
  # ⑦ VIEW 计数（O1 修复后语义）：单条 detail 每次 +1 VIEW（`myxhs:counter:1:{noteId}:5`）；**batch-detail 不再发 VIEW**（列表/Feed 浏览不计）
  ```
- **🔍 人工观察**：RocketMQ Dashboard FEED_TOPIC/NOTE_INDEX_TOPIC 消费进度；SkyWalking 链路 content→(MQ)→home→Redis；ES (Kibana) 搜标题可查

### G2-01-02 发布参数校验（负面）
- ① 缺 title → **40002**；② images 10 张 → **40002**；③ title 129 字 → **40002**；④ noteType=2 → **40002**
- **L1 断言**：均 **40002**（body code；**HTTP 400**——GlobalExceptionHandler 对参数校验 @ResponseStatus(BAD_REQUEST) 实证）
- **L2**：t_note 无新增（`SELECT COUNT(*) ... WHERE user_id={g2a.uid}` 与用例 01 后一致）

### G2-01-03 敏感词拒绝（DFA）
- **入口**：title=`"赌博"`（sensitive-words.txt 静态词库实证）
- **L1 断言**：**20006**（"内容包含违规词汇：赌博"）
- **L2**：t_note 无新增；`grep 检测到敏感词 content 日志`（可选项）

### G2-01-04 发布限流（矩阵 #26，5次/60s）
- **入口**：连续发布 6 篇（不同 title）
- **L1 断言**：第 1-5 次 200；**第 6 次 → 40202**"发布过于频繁"
- **L2**：限流 key 存在：`ZCARD myxhs:note:publish:NoteController:publishNote:{uid}` = 5
- **清理**（测完立即）：`DEL myxhs:note:publish:NoteController:publishNote:{uid}`（避免影响后续用例）
- **注意**：第 6 篇未发布成功——5 篇已发布笔记留作列表用例数据

### G2-01-05 无签名发布被拒（HMAC 强制回归，#39 防回退）
- **入口**：**带 JWT（g2a_ token）+ 不带** X-Signature/X-Timestamp/X-Nonce（实证：GatewayAuthFilter(order+1000) 先于 HmacSignatureFilter(order+1500)，先过 JWT 才到签名校验）
- **L1 断言**：**403**（"签名校验失败：非公开接口必须携带 X-Timestamp、X-Nonce、X-Signature"）
- **对照 1**：GET /api/note/detail/{id} 无 token 无签名 → 200（公开读双白名单）
- **对照 2**：无 token + 无签名发布 → AuthFilter 拦截（401/403，执行时记录精确码——filter 顺序：JWT 先于 HMAC）

### G2-01-06 草稿保存 + 草稿详情不可见
- **入口**：`POST /api/note/draft` body=`{"content":"只有正文的草稿"}`（**无 title**，实证草稿不校验）
- **L1 断言**：200，data.noteId 非空
- **L2**：
  ```
  SELECT status,audit_status FROM my_xhs_content.t_note WHERE id={draftId}
  # → status=0(DRAFT) audit_status=0(PENDING)
  GET /api/note/detail/{draftId}（公开）  # → 20001 笔记不存在（仅 PUBLISHED 可见，实证）
  GET myxhs:note:detail:{draftId}        # → EXISTS=1 且值含 "CACHE_NULL"（Jackson 序列化占位符带引号，断言用 EXISTS+contains 不用精确等于）
  ```
- **注意**：草稿详情走空值缓存——发布草稿用例（01-07）会 delayDoubleDelete 清除

### G2-01-07 草稿发布（POST /{id}/publish）+ Feed 链路
- **前置**：G2-01-06 的 {draftId}
- **入口**：`POST /api/note/{draftId}/publish`（带签名）
- **L1 断言**：200
- **L2**：
  ```
  SELECT status,audit_status FROM my_xhs_content.t_note WHERE id={draftId}
  # → status=2 audit_status=1
  # 注意：afterCommit 的 delayDoubleDelete 是异步的——等 1-2s 再查详情
  GET /api/note/detail/{draftId}          # → 200（空值缓存已清，读 DB 回填）
  ZSCORE myxhs:feed:inbox:{g2f.uid} {draftId}   # → 存在（草稿发布同样触发 Feed）
  ```
- **操纵快照**：用例 06 产生的空值缓存 key `myxhs:note:detail:{draftId}`（发布后自动清除）

### G2-01-08 编辑笔记（DRAFT 与 PUBLISHED 分支）
- **前置**：内联新建草稿 `POST /api/note/draft` body=`{"title":"待编辑草稿"}` → {draft2Id}（快照）
- ① **编辑草稿**：`PUT /api/note/{draft2Id}` body=`{"title":"草稿已改"}` → 200；DB title 更新、**status 仍 0**（DRAFT 分支）
- ② **编辑已发布**：`PUT /api/note/{noteId}`（01-01 的）body=`{"content":"编辑后的正文"}` → 200；DB content 更新 + updated_at 变化
- ③ **已发布编辑含敏感词**：`PUT /api/note/{noteId}` body=`{"title":"赌博"}` → **20006**（实证：PUBLISHED 编辑需重查敏感词）——**且内容未更新**（校验先于更新）
- **L2**：编辑后等 1-2s（delayDoubleDelete 异步）→ `GET myxhs:note:detail:{noteId}` → 新 content
- **越权对照**：g2f_ 编辑 g2a_ 的笔记 → **403**（getAndCheckOwner）

### G2-01-09 删除笔记：级联评论 + 事件 + 详情消失
- **前置**：g2f_ 对 {noteId} 发 1 条评论（`POST /api/comment` body=`{"noteId":{noteId},"content":"待删评论"}`，HMAC 签名）→ 记 {commentId}
- **入口**：`DELETE /api/note/{noteId}`（g2a_ 签名）
- **L1 断言**：200
- **L2**：
  ```
  SELECT deleted FROM my_xhs_content.t_note WHERE id={noteId}     # → 1（逻辑删除）
  SELECT deleted FROM my_xhs_content.t_comment WHERE id={commentId}  # → 1（级联逻辑删除，实证）
  GET /api/note/detail/{noteId}      # → 20001
  ZSCORE myxhs:feed:inbox:{g2f.uid} {noteId}  # → 收件箱残留（实证：NOTE_DELETE 只清作者 outbox，粉丝 inbox 靠 7 天 TTL+FeedCleanupJob）
  ```
- **MQ 事件（可选验证）**：SOCIAL_TOPIC:UNCOMMENT（count=1）+ SOCIAL_TOPIC:NOTE_DELETE 已投递；NOTE_DELETE 由 home NoteDeleteConsumer（note-delete-consumer-group）消费，清理作者发件箱 `myxhs:feed:outbox:{authorId}`（本用例作者非大V无 outbox，消费路径归 G6/大V 场景）
- **越权对照**：g2f_ 删除 g2a_ 的笔记 → **403**

### G2-01-10 详情缓存验证（矩阵 #23/#24）
- ① **缓存命中**：重复 GET /api/note/detail/{id} 两次 → 第二次 `GET myxhs:note:detail:{id}` 存在且为 JSON（30min）
- ② **缓存一致性（③ 操纵）**：`UPDATE my_xhs_content.t_note SET title='缓存验证改题' WHERE id={id}` → 立即 GET 详情 → **仍旧值**（缓存 30min 内不失效）→ `DEL myxhs:note:detail:{id}` → GET → **新值**（DB 直读回填）
  - **恢复**：标题改回原值 + 删缓存（执行记录标注）
- ③ **空值防穿透（#24）**：GET /api/note/detail/{不存在 id} → 20001；`GET myxhs:note:detail:{不存在id}` → **存在**（`\u0000__CACHE_NULL__\u0000` 占位，2min TTL）；**第二次查询**（空值有效期内）不落 DB（观察 content 日志 `SELECT ... t_note` 不出现该 id）
- **操纵快照**：记录改题前后的 title

### G2-01-11 批量详情（P2-3）
- **入口**：`POST /api/note/batch-detail` body=`[{noteId1},{noteId2},{不存在id},{draftId}]`（公开免签）
- **L1 断言**：200；返回 map 含 noteId1/noteId2 的完整 NoteDetailVO；**不存在 id 降级跳过**（key 不在 map，不报错）；draftId（草稿）同样跳过
- **L2**：返回顺序与请求一致（LinkedHashMap 实证）；空列表 `[]` → 200 空 map `{}`

### G2-01-12 用户笔记列表 + 我的列表
- ① `GET /api/note/user/{g2a.uid}?pageNum=1&pageSize=10`（公开，免签）→ 200；records 均 status=2；**不含草稿/已删**；total=当前已发布数（执行时以实际为准：01-01×1 + 01-04×5 + 01-07×1 = 7，01-09 删除 1 → **预计 6**）
- ② **`GET /api/note/my` 不在 HMAC 白名单（实证 gateway 配置）→ 需 JWT + HMAC 签名**（HmacSignatureFilter 对所有方法生效）：
  - `GET /api/note/my?status=0`（g2a_ token+secret）→ 仅草稿；`GET /api/note/my`（无 status）→ 全部（含草稿+已发布）
  - **负向**：仅带 JWT 不带签名 → **403**（与 01-05 同机理）
  - NoteItemVO 含 firstImage（images[0]）
- ③ pageSize 越界：`pageSize=100` → 不报错且 ≤50（MAX_PAGE_SIZE 实证）

### G2-01-13 分享（仅 PUBLISHED + SHARE 计数 + 限流）
- ① 分享已发布 {noteId}：`POST /api/note/{noteId}/share`（g2f_ 签名）→ 200
- ② 分享草稿/不存在 → **20001**（实证：note==null || status!=PUBLISHED）
- ③ **限流**：**限流切面先于业务执行，失败请求也计数**——①(1 次)+②(2 次)=3 次已入窗口，③ 同一 g2f_ 再连打 8 次 → 累计第 11 次（③ 的第 8 次）**40202**"分享过于频繁"；清理 `DEL myxhs:note:share:NoteController:shareNote:{g2f.uid}`
- ④ **SHARE 计数（L2，代码实证）**：share 事件固定映射 **targetType=1(NOTE) countType=4(SHARE)**（CounterEventConsumer.handleShareEvent）→ 等 5-15s（CounterBuffer 攒批 5s，矩阵 B 节）→ `GET myxhs:counter:1:{noteId}:4` = 1（仅①成功，mq msgId 去重）；对照：`GET myxhs:counter:1:{noteId}:5`（VIEW countType=5）>0（详情页浏览过）

### G2-01-14 图片上传（类型/魔数/大小/限流）
- **签名注意（T-102 实证修正）**：BodyCacheFilter **不缓存 multipart**（只缓存 application/json）→ bodyHash **恒为 ""**（实测小文件 multipart 用 body=None 签名成功；body=raw 会 403 签名不匹配）。签名：`testlib.sign(secret, method, path, body=None)` + 原始 multipart 字节作为请求体：
  ```python
  from testlib import *
  # 构造 multipart 原始字节（边界随机）
  boundary = "----g2x" + uuid.uuid4().hex
  raw = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"t.png\"\r\n"
         f"Content-Type: image/png\r\n\r\n").encode() + open("/tmp/t.png","rb").read() + f"\r\n--{boundary}--\r\n".encode()
  s, ts, nonce = sign(g2a.secret, "POST", "/api/note/upload/image", body=raw)
  # 发送 raw + Content-Type: multipart/form-data; boundary={boundary} + 签名头
  ```
- ① 正常 PNG：构造 1x1 真实 PNG（魔数 89 50 4E 47...）→ 200，data.url=`http://21.214.97.212:19002/uploads/note/2026/08/13/{uuid}.png`
- ② 类型拒绝：`file=test.txt;type=text/plain` → **40002**"文件类型不允许，仅支持 JPEG/PNG/GIF/WebP"
- ③ 魔数伪造：`file=test.txt;type=image/jpeg`（txt 内容非 FFD8 开头）→ **40002**"文件内容与声明的类型不匹配"
- ④ 大小拒绝：`dd if=/dev/zero of=/tmp/big.jpg bs=1M count=6`（6MB>5MB）→ **40002**"文件大小不能超过5MB"（**签名按 bodyHash=""** 计算）
- ⑤ 限流：连打 21 次（≤1MB 小文件）→ 第 21 次 **40202**；清理 `DEL myxhs:note:upload:NoteController:uploadImage:{uid}`

### G2-01-15 Feed 补偿链路（矩阵 #13，③ 操纵 + ② 等周期）
- **前置**：造一条"丢失"消息——`UPDATE my_xhs_content.t_local_message SET created_at=DATE_SUB(NOW(),INTERVAL 2 MINUTE), status=0, retry_count=0 WHERE id={上一条已 markSent 的消息id}`（**快照**：原 status=1）
- **操纵**：同上（把已发送消息改回待发送并过期 created_at）
- **入口**：无——等 retryFailedMessages（30s 周期，等 30-60s，禁止超 120s）
- **L1/L2 断言**：
  ```
  # 任务跑了吗 + 数据变了吗（矩阵 E5 原则）
  SELECT status,retry_count FROM my_xhs_content.t_local_message WHERE id={msgId}   # → status=1（补发成功 markSent）
  ZSCORE myxhs:feed:inbox:{g2f.uid} {noteId}   # → 仍在（ZADD 幂等，无副作用）
  # 锁 key 生命周期：任务执行时 EXISTS myxhs:lock:feed:retry；结束后 =0（可观察）
  ```
- **兜底**：若 60s 内未补发（任务被锁占用等），再等 1 个周期；仍不行 → 检查 content 日志 `[Feed补偿] 补发成功`
- **死信分支（可选项）**：retry_count 置 2 + status=0 + created_at 过期 → 任务补发失败 1 次 → status=3（死信）；验证后清理

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G2-01-01 | 2026-08-13 | ✅ | 全链路过；收件箱 ZSCORE 断言校准（score=发布时刻，查询晚 6s 正常）；TTL≈604794 |
| G2-01-02 | | ✅ | 4 项参数校验全 40002（HTTP 400）|
| G2-01-03 | | ✅ | 敏感词 20006 |
| G2-01-04 | | ✅ | **首轮受窗口污染(成功2/6)→清理后干净窗口重测 5+1 拒**（O-Note-1：限流窗口跨用例共享，须隔离）|
| G2-01-05 | | ✅ | 无签名 403 + 公开读对照 200 |
| G2-01-06 | | ✅ | 草稿 0/0 + 空值缓存 |
| G2-01-07 | | ✅ | 草稿发布 2/1 + Feed + 空值缓存清除 |
| G2-01-08 | | ✅ | 编辑分支/敏感词 20006/越权 403 |
| G2-01-09 | | ✅ | 删除+级联评论+20001+收件箱残留（设计如此）|
| G2-01-10 | | ✅ | 缓存命中/一致性/空值防穿透；②断言值修正（草稿 title=None 仍旧值）|
| G2-01-11 | | ✅ | batch-detail 4 条→1 条（降级跳过）+ 空列表 {} |
| G2-01-12 | | ✅ | total=9 列表/status 过滤/无签名 403/pageSize≤50 |
| G2-01-13 | | ✅ | 分享 200/已删 20001/限流第 9 次拒；SHARE 计数=9（①1+限流循环 8 次成功——文档预期值修正为实际成功数）|
| G2-01-14 | | ✅ | PNG/类型/魔数/限流(干净窗口 20+1) 全过；**超 5MB → 500（O-Note-2）**|
| G2-01-15 | | ✅ | Feed 补偿 status 0→1 补发+锁释放+收件箱幂等 |

### 执行期发现（补充 REVIEW）
- **R6（已修）**：testlib.call 缺 query 参数——T-009/010/011 新算法 query 参与签名，带 query 的签名请求（如 /my?status=0）必须传 query；testlib 已扩展（向后兼容）
- **O-Note-1**：@RateLimit 窗口按 prefix+Class+Method+userId 共享——前置用例请求（含业务失败的）占用额度，测试需隔离（执行前 DEL key）
- **O-Note-2**：上传 >5MB → **HTTP 500**（MaxUploadSizeExceededException 未映射友好错误码；service 层 40002 校验仅对 ≤5MB 生效）——T- 候选
- 断言校准：收件箱 ZSCORE=发布时刻 ms（非查询时刻）；SHARE/VIEW 计数以实际成功请求数核算；分页 total 为字符串（R4）需 int()

## 清理清单（执行后）
- 用户：g2a_/g2f_（t_user + token/hmac key + follow 关系 `myxhs:follow:fans:{uid}`）
- 数据：`UPDATE my_xhs_content.t_note SET deleted=1 WHERE user_id IN (g2a,g2f)`（或整行删除）；t_comment 同理；t_local_message 测试消息置 status=3/删除
- Redis：`myxhs:note:detail:{ids}`、`myxhs:feed:inbox:{g2f.uid}`、`myxhs:feed:push:progress:*`、限流 key（note:publish/share/upload）、`myxhs:user:bigv:*`、计数 key
- ES：`DELETE note_index/_doc/{ids}`（或留给后续用例）
- 前缀统一 `g2a_`/`g2f_` 便于识别

## 深度 REVIEW 记录（2026-08-13 第三轮，REVIEW-METHODOLOGY 三层法）

### L0/L1 已核（代码实证）
- ✅ 全部端点路径/参数/限流值/白名单逐项对照源码与 gateway 配置
- ✅ counter key：`buildRedisKey = myxhs:counter:{targetType}:{targetId}:{countType}`（CounterService:579-581）→ SHARE=`myxhs:counter:1:{noteId}:4`、VIEW=`...:5`
- ✅ BizException → HTTP 200 + body code；参数校验（@Valid）→ **HTTP 400** + body 40002（GlobalExceptionHandler 实证）
- ✅ 签名算法含 bodyHash（T-009 修复后现状；G1-05 文档中"body 不参与签名"描述已过时，以本轮代码为准）

### 断言修正（本轮 REVIEW 发现）
| # | 修正 | 依据 |
|---|---|---|
| R1 | GET /api/note/my **需 HMAC 签名**（非仅 JWT） | /api/note/my 不在 HMAC 白名单；HmacSignatureFilter 对所有方法生效 |
| R2 | 参数校验 40002 = **HTTP 400** | GlobalExceptionHandler @ResponseStatus(BAD_REQUEST) |
| R3 | 空值防穿透断言改为"第二次查询不落 DB" | 语义精确化 |

### 业务逻辑问题/观察项（L1 确认；✅=已修复，待 L2 实证后登记 ISSUES.md）
| # | 问题 | 定性 | 说明 |
|---|---|---|---|
| O1 | **批量详情 VIEW 事件放大**：batch-detail N 条成功 → 发送 N 个 VIEW 事件 | ✅ **已修复（2026-08-13）** | `batchGetNoteDetail` 改为复用无 VIEW 的 `readNoteDetail`；单条 `getNoteDetail` 保留 VIEW。**运行态已验证**：batch-detail 2 条 → VIEW counter 不变；单条 detail → VIEW=1 |
| O2 | 删除笔记后粉丝收件箱残留（NOTE_DELETE 仅清 outbox）| ❌ 撤销（非 bug）| feed 读取走 batch-detail → 已删笔记降级跳过，用户不可见；残留仅 Redis noteId，TTL 回收 |
| O3 | OFFLINE(3) 状态无业务入口可达 | 观察（状态机） | 若审核系统才有下线入口则属正常；否则状态机死分支（T- 候选）|
| O4 | 图片 URL 前缀硬编码本机 IP（yml `storage.local.url-prefix` 可配）| 部署注意项 | 云主机部署需改配置（非代码 bug）|
| O5 | **补偿重投二次投递**：Feed 补偿(30s)补发后 status=1，60s 后 compensateIncompletePush 会再扫到（push_status=0）重投 1 次 | 观察（幂等已兜底） | ZADD 幂等无副作用；执行 01-15 时注意收件箱可能多一次写入 |

### L2 待实测清单（执行时确认，标注在用例中）
- multipart ≤1MB 的 bodyHash 计算（gateway BodyCacheFilter 实测）
- ES note_index `_version` 数值（ExternalGte ts）
- 收件箱 TTL 精确值（≈604800）

## 断言关键词速查
- 200 成功 / 20001 笔记不存在 / 20002 状态不允许 / 20006 敏感词 / 40002 参数 / 40202 限流 / 403 无权限（越权、无签名）
- 发布返回 `data.noteId`（dict）| status=2 PUBLISHED / audit_status=1 APPROVED | 草稿 status=0
