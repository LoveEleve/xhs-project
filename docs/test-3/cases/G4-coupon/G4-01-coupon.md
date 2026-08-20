# G4-01 优惠券用例（模板管理 + 领券 + 用券/退券 + 定时任务）

> 组：G4 优惠券 | 服务：coupon(19010) + RocketMQ（COUPON_CLAIM_TOPIC）| 入口：**gateway(19000)**
> 依赖：G1 登录（testlib.new_user 得 token/hmacSecret/uid）
> 时间引用：矩阵 **#14**（券过期标记）、**#15**（过期即时校验）、D 节（模板缓存 30min/空值 60s）
> 前提：coupon 服务 UP、xxl 任务 15/16 存在且启用（job_group=10）、my_xhs_coupon 三表存在
> **执行前清理：t_coupon_template/t_user_coupon/t_coupon_outbox 现留 Task2 脏数据（17/22/4 行，2026-08-14 核对）——必须清空**

## 代码实证（2026-08-14，G4 梳理时全量核实）

### 端点与安全（gateway:19000 → coupon:19010）
| 端点 | JWT | HMAC | 额外鉴权 | RateLimit（代码实证） |
|---|---|---|---|---|
| POST /api/coupon/template | 需要 | **免签**（hmac 白名单 371 行）| **X-Admin-Call**（isAdminCall 校验）| 5次/60s `myxhs:coupon:createTpl`（**无 perUser**）|
| PUT /api/coupon/template/{id}/status | 需要 | 免签 | **X-Admin-Call** | **无 @RateLimit** |
| GET /api/coupon/template/{id} | **需要**（不在 JWT 白名单）| 免签 | 无 | 无 |
| GET /api/coupon/template/list | **免 JWT**（JWT 白名单 312 行）| 免签（hmac 白名单）| 无 | 无 |
| POST /api/coupon/claim | 需要 | **必须签名**（无任何白名单）| X-User-Id 必填 | 5次/60s **perUser** `myxhs:coupon:claim` |
| GET /api/coupon/user/list | 需要 | **必须签名** | X-User-Id 必填；status 可选 | 无 |
| GET /api/coupon/user/available | 需要 | **必须签名** | X-User-Id 必填 | 无 |
| GET /api/coupon/discount/{id} | —（内部）| — | **X-Internal-Call** + X-User-Id | 无 |
| POST /api/coupon/use | —（内部）| — | **X-Internal-Call** + X-User-Id | 无 |
| POST /api/coupon/return | —（内部）| — | **X-Internal-Call** + X-User-Id | 无 |

**深度 REVIEW 关键结论**：
1. **管理写端点 = JWT + X-Admin-Call**（对照 product 模式）；**⚠️ 参数校验先于 isAdminCall**（@Valid 在参数解析阶段，isAdminCall 在方法体内——**L2 实测：非法 body 无 X-Admin-Call → 40002（非 403）；合法 body 无 X-Admin-Call → 403**。**纠错：G3 #80-4"参数校验在 admin 鉴权之后"结论不成立**（product createSpu 同结构，同理）
2. **coupon 用户端全部要 HMAC 签名**（claim/user/list/user/available 不在 hmac 白名单）——与 cart 同款（对照 G3-02 模式）；GET 带 query 时签名 query 参与（R6）
3. **内部接口 discount/use/return 经 gateway 需 JWT**（不在 JWT 白名单）**+ X-Internal-Call**（controller 校验）+ X-User-Id（gateway 从 JWT 注入）；**直连 19010** 需手动带 X-User-Id（否则 40002"缺少必要请求头"，L2 已实测）
4. **claim 无 @Idempotent**——重复领靠 Lua claimed 计数兜底（perUserLimit=1 时第二次 → 30013）；**createTemplate 无 @Idempotent**——同参快速重发创建多个模板（登记观察，对照 product 的 10s 幂等）
5. **updateTemplateStatus 无非法 status 校验**（status=2 直接入库，与 product 40002"商品状态无效"不同）——登记观察
6. **getTemplate 不过滤 status=0**（禁用模板详情仍 200；领券中心列表才过滤）——登记观察
7. **getAvailableCoupons 只过滤 validEnd 不检查 validStart**（未来生效券也出现在"可用"里，但 useCoupon 的 ExpireValidator 会拦）——登记观察
8. **claimed/stock key 无 TTL**（永久；reconcileJob 注释"stock Key 应持久"）——登记观察
9. **claim -3（未初始化）分支**：initStockFromDb（setIfAbsent remain_count）→ 重试一次；重试仍非 1（含 -2 限领）→ 一律 30014 SOLD_OUT（错误码语义粗糙）——登记观察
10. **⚠️ 领券中心 validStart 矛盾**：创建 API 强制 @FutureOrPresent（validStart≥now），而 listClaimableTemplates 要求 validStart≤now——**新创建模板不会立即出现在领券中心**（未开始活动，产品语义合理）；G4-01-01 断言"list 含"为**错误**（见用例修正）

### Redis key（全部字面花括号 hash tag——python 访问须 `{{{templateId}}}` 转义，#81-1）
- `myxhs:coupon:{%d}:stock`（String，剩余数，**无 TTL**；createTemplate 时 setIfAbsent=totalCount）
- `myxhs:coupon:{%d}:claimed:%d`（String，已领次数，**无 TTL**）
- `myxhs:coupon:template:%d`（JSON String 模板缓存，**30min**；空值 `"NULL"` 60s 防穿透）
- `myxhs:lock:job:coupon:outbox`（Outbox 补发 Redisson 锁，4s lease）

### Lua 语义（claim_coupon.lua / return_coupon.lua）
- **claim 返回**：`1`=成功（DECR stock + INCR claimed）/ `-1`=售罄（30014）/ `-2`=达限领上限（30013）/ `-3`=库存未初始化（→ initStockFromDb 重试一次）
- **return 返回**：`1`=成功（INCR stock + DECR claimed）/ `0`=领取记录不存在（不操作）

### 领券全链路（原子性设计，代码实证）
`claimCoupon`：模板缓存校验（不存在→30012 / status!=1→30016"优惠券已下线" / validStart>now→30016"活动尚未开始" / validEnd<now→30015）→ **Lua 原子扣库存+限领** → 成功则 `sendClaimEventSync`：
1. **Outbox 先写**：INSERT t_coupon_outbox(claimNo=UUID)
2. **MQ 同步发送** COUPON_CLAIM_TOPIC（MqTraceHelper 带 traceId，timeout 3s）
3. 成功 → markOutboxSent；**失败 → deleteByClaimNo + rollbackRedisStock（return_coupon.lua 回退）**→ 返回"领券失败，请重试"（500）

### 消费端（CouponClaimConsumer，coupon-claim-consumer-group）
- **幂等双重**：① MessageIdempotentHelper `msg:idempotent:coupon:claim:{msgId}` 24h（首次 SET NX）；② t_user_coupon **uk_claim_no 唯一索引**兜底（DuplicateKeyException → 忽略）
- 消费动作：insert t_user_coupon(status=0) → decrementRemainCount（WHERE remain_count>0 AND deleted=0）
- 失败 → removeMark（允许 MQ 重试，maxReconsumeTimes=5）
- **claim 后 MySQL 落库 = MQ 消费 + 主从(3307 slave 读) 延迟**——等 1-3s 再断言（Task2 链4 实证）

### 定时任务
| 任务 | 触发 | 逻辑（代码实证） |
|---|---|---|
| couponExpireJob（xxl#16，每分钟）| 手动触发（矩阵 A 节）| `batchExpire` LIMIT 1000 循环：JOIN t_coupon_template ct ON ct.deleted=0 且 ct.valid_end<NOW() → uc.status 0→2；每批 sleep 100ms |
| couponReconcileJob（xxl#15，每分钟）| 手动触发 | 只处理 status=1 + valid_end>now + deleted=0 的模板：Redis stock 无 → **从 MySQL 补**；不一致 → **以 Redis 为准修 MySQL remain_count**（无防误删保护，与 cart 场景 3 不同——登记观察）|
| CouponOutboxSenderJob（@Scheduled 5s）| 等 5-15s | Redisson 锁 tryLock(0,4s)；cutoff=now-3s；BATCH 200；补发成功 → markOutboxSent |

### 用券/退券（内部接口，order Feign 调用）
- **getCouponDiscount**（不核销）：归属校验（券不存在/不属于用户→30016"优惠券不属于当前用户"）→ status!=0 → 30016"优惠券不可用" → 责任链 → calculateDiscount
- **useCoupon**：券不存在/不属于用户 → **30012**；status!=0 → 30016"优惠券状态异常"；责任链校验 → 计算折扣 → **markUsed 乐观锁（WHERE status=0）**→ affected=0 → 30016"优惠券已被使用"
- **returnCoupon**（@Transactional）：券不存在 → 30012；**returnCoupon 乐观锁 WHERE status=1 AND used_order_id=orderId，affected=0 → warn 返回 200（幂等）**；成功 → incrementRemainCount（SQL 原子 +1）→ **afterCommit 才执行 Redis 回退（return_coupon.lua）+ evict 模板缓存**（防事务回滚与 Redis 不一致）
- **责任链校验器顺序（@Order）**：1 AmountValidator（门槛）→ 2 ExpireValidator（有效期：未生效/已过期）→ 3 StatusValidator（下线）
- **折扣计算**：type1=discountValue（满减）/ type2=`order - order×value/10`（HALF_UP 2 位）/ type3=discountValue（无门槛）；**`discount.min(orderAmount)` 防 0 元购**（折扣不能超过订单金额）

### 错误码（ResultCode）
30012 优惠券不存在 / 30013 优惠券已领取（限领上限）/ 30014 优惠券已领完 / 30015 优惠券已过期 / 30016 优惠券不满足使用条件 / 40002 参数 / 40202 限流 / 403 内部或管理鉴权

### 模板校验（CreateTemplateRequest，@Valid）
name @NotBlank+@Size(128) / type @NotNull 1-3 / discountValue @NotNull @DecimalMin(0.01) / minAmount @DecimalMin(0)（可空，默认 0）/ totalCount @NotNull @Min(1) / perUserLimit @NotNull 1-10 / validStart、validEnd @NotNull **@FutureOrPresent**（过去时间 → 40002）
Service 层：validEnd<validStart → 40002"有效期结束时间必须晚于开始时间"；type=2 折扣值超出 0.1~9.9 → 40002

## 前置准备（执行时记录快照）
```python
from testlib import *
# 1. 注册用户：g4a_（管理者）+ g4b_（领券用户）
# 2. 清理：TRUNCATE my_xhs_coupon.t_coupon_template / t_user_coupon / t_coupon_outbox
# 3. Redis：DEL myxhs:coupon:*（SCAN 后删）
# 4. 基线：三表 COUNT=0
```

## 用例清单

### 模板管理

#### G4-01-01 创建模板全链路（核心）
- **前置**：g4a_ 登录 + X-Admin-Call（tokens.env ADMIN_TOKEN）
- **入口**：`POST /api/coupon/template` body=`{"name":"g4满减券","type":1,"discountValue":20,"minAmount":100,"totalCount":100,"perUserLimit":1,"validStart":"{now+2min}","validEnd":"{now+7天}"}`（带 JWT + X-Admin-Call，**免 HMAC**）
- **L1 断言**：200；data.id 为**字符串**雪花 id（R4）；data.status=1（默认启用）；remainCount=totalCount
- **L2 数据验证**：
  ```
  # ① DB 入库：SELECT id,name,type,discount_value,min_amount,total_count,remain_count,per_user_limit,status,deleted FROM my_xhs_coupon.t_coupon_template WHERE id={id}
  #   → 全字段一致、deleted=0
  # ② Redis 库存初始化：GET myxhs:coupon:{id}:stock = "100"（注意 {{{id}}} 转义）；TTL=-1（无 TTL）
  # ③ 模板缓存：GET myxhs:coupon:template:{id} = JSON（含 name/type/...）；TTL≈1800
  # ④ 详情（带 JWT）：GET /api/coupon/template/{id} → 200 字段一致
  # ⑤ 领券中心（免 JWT）：GET /api/coupon/template/list → **不含该模板**（validStart=now+2min 未开始，
  #    list 需 validStart<=now——代码实证；产品语义：未开始活动不进领券中心；含的验证见 G4-01-04）
  ```
- **🔍 人工观察**：SkyWalking gateway→coupon 链路；模板缓存写入
- **注意**：validStart 必须传未来时间（@FutureOrPresent）；时间格式 `yyyy-MM-dd HH:mm:ss`（Task2 链4 教训：ISO "T" 格式 → 40002）；**validStart=now+2min 为 G4-01-08 领券留生效窗口（执行时若未到点先 sleep）**

#### G4-01-02 创建模板参数校验（负面）
- ① 缺 name → 40002；② type=0/4 → 40002（@Min/@Max）；③ 缺 discountValue → 40002；④ discountValue=0 → 40002（@DecimalMin）；⑤ totalCount=0 → 40002；⑥ perUserLimit=0/11 → 40002；⑦ validStart 过去时间 → 40002（@FutureOrPresent）；⑧ validEnd<validStart → 40002"有效期结束时间必须晚于开始时间"；⑨ type=2 折扣值=9.95 → 40002"折扣值必须在0.1~9.9之间"（>9.9）；⑩ type=2 discountValue=0.05 → 40002（<0.1）
- **L2**：t_coupon_template 无新增
- **注意**：⚠️ **参数校验先于 isAdminCall**（L2 实测：非法 body 无 X-Admin-Call → 40002）——**无需先带 X-Admin-Call 即可测参数负面**（G3 #80-4"参数校验在 admin 鉴权之后"为错误结论，本组已纠正）

#### G4-01-03 创建限流（5次/60s）
- createTemplate 连打 6 次（不同 name 避开任何缓存/重复影响）→ 第 6 次 **40202**；`ZCARD myxhs:coupon:createTpl:CouponController:createTemplate`=5（**无 perUser → key 不含 uid**）
- **清理**：DEL 限流 key

#### G4-01-04 模板详情 + 领券中心列表
- `GET /api/coupon/template/{id}`（带 JWT）→ 200
- **不存在**：GET /api/coupon/template/999999999999 → **30012**
- **空值缓存（防穿透）**：Redis `myxhs:coupon:template:999999999999` = "NULL"，**TTL=60s**；二次查询同 30012
- **领券中心（免 JWT 实证）**：`GET /api/coupon/template/list`（无 token）→ 200 含有效模板；条件=status=1+deleted=0+remain>0+validStart≤now+validEnd≥now
- **过滤实证**：① 下架模板（status=0）→ list 不含（但详情仍 200——登记观察）；② SQL 置 remain_count=0 → list 不含；③ validEnd 已过（SQL 改）→ list 不含；④ **SQL 把 valid_start 改过去（如 G4-01-01 模板）→ list 含**（正验证：validStart≤now 后进入领券中心——**list 无缓存，SQL 改立即生效**）
- **列表缓存**：无缓存（每次查 DB，代码实证 selectList 无缓存）——不需要缓存一致性断言

#### G4-01-05 模板状态变更（上/下线 + 非法状态观察）
- ① 下线：`PUT /api/coupon/template/{id}/status?status=0`（JWT+X-Admin-Call）→ 200
- **L2**：SELECT status=0；**缓存 evict**：GET myxhs:coupon:template:{id} → 0（updateTemplateStatus 后 evictTemplateCache）；详情返回 status=0
- ② 领券校验联动：claim 该模板 → **30016"优惠券已下线"**（缓存重建后 status=0 生效）
- ③ 上线：status=1 → 200；claim 恢复成功
- ④ **非法 status=2 → 200（代码无校验，直接入库）——登记观察**（对照 product 40002）；测试后改回 1
- ⑤ **参数负面**：id=0/负数 → **40002**（@Positive @PathVariable，ConstraintViolationException 路径，**L2 已实测**）；缺 status → **40001"缺少参数: status"**（MissingServletRequestParameterException，**L2 已实测**）；status 非数字 → 40003
- **注意**：updateTemplateStatus 无 @RateLimit（代码实证）

#### G4-01-06 管理端点鉴权（X-Admin-Call）
- ① 带 JWT **无 X-Admin-Call** → **403"无权访问管理接口"**
- ② 带 JWT + 错误 X-Admin-Call → 403
- ③ 带 JWT + 正确 → 200
- ④ 无 JWT 经 gateway → 401（AuthFilter 先拦）
- ⑤ **直连 19010** 无 X-Admin-Call：**合法 body → 403"无权访问管理接口"（L2 已实测）**；**非法 body → 40002（参数校验先于 isAdminCall，L2 已实测）**——端口信任模型下仍要 token（对照 G3-01-11⑥）
- ⑥ **免 HMAC 实证**：带 JWT 无签名 POST → 200（hmac 白名单放行管理端点）
- ⑦ updateTemplateStatus 同①②：无 X-Admin-Call → 403
- **注意**：403 业务码响应 **HTTP=200 + body code=403**（R.fail 无 @ResponseStatus）；40002/40001/40003 为 HTTP=400 + body code（@RestControllerAdvice @ResponseStatus）——断言按 **body code** 为准（testlib 语义，与 G1-G3 一致）

#### G4-01-07 模板缓存一致性（30min + evict）
- ① **缓存命中**：创建后 GET 详情两次 → 第二次走缓存（L2：改 DB 后立即查仍旧值）
- ② **操纵验证（③ 类）**：`UPDATE t_coupon_template SET name='缓存改' WHERE id={id}`（绕过缓存）→ 立即 GET 详情 → **旧值**（缓存 30min 未过期）→ `DEL myxhs:coupon:template:{id}` → GET → 新值
- ③ **状态变更 evict**：PUT status 0→1 → Redis key 已删 → 详情重建
- ④ **恢复**：改回原 name + DEL 缓存 key（执行记录标注）

### 领券

#### G4-01-08 领券全链路（核心，Lua+Outbox+MQ+DB）
- **前置**：G4-01-01 模板（total=100, perUserLimit=1）+ g4b_ 登录（签名）；**确认模板 validStart 已过（创建时 now+2min——若未到点先 sleep）**
- **入口**：`POST /api/coupon/claim` body=`{"templateId":{id}}`（g4b_ HMAC 签名）
- **L1 断言**：200
- **L2 数据验证**：
  ```
  # ① Redis 原子扣减：GET myxhs:coupon:{id}:stock = "99"；GET myxhs:coupon:{id}:claimed:{g4b_uid} = "1"
  # ② Outbox（等 MQ 同步发送完成）：SELECT * FROM my_xhs_coupon.t_coupon_outbox ORDER BY id DESC LIMIT 1
  #    → status=1（已发送）、user_id/template_id 匹配（claim_no=UUID 与 t_user_coupon 一致）
  # ③ MQ→DB（等 1-3s）：SELECT id,user_id,coupon_id,claim_no,status FROM my_xhs_coupon.t_user_coupon
  #    WHERE user_id={g4b_uid} → status=0、coupon_id={id}、claim_no 与 Outbox 一致；模板 remain_count=99（decrementRemainCount）
  # ④ 列表：GET /api/coupon/user/list（签名）→ 含该券：name/type/discountValue/minAmount/validEnd/status=0
  # ⑤ 可用：GET /api/coupon/user/available（签名）→ 含该券
  ```
- **🔍 人工观察**：RocketMQ COUPON_CLAIM_TOPIC 消费进度（coupon-claim-consumer-group）；SkyWalking gateway→coupon→MQ→coupon 消费链路
- **注意**：claim 后 user/list 立即查 = 0（MQ+主从延迟）——sleep 1-3s 再断言

#### G4-01-09 限领上限 + 售罄
- **限领（perUserLimit=1）**：g4b_ 再 claim 同模板 → **30013"已达限领上限"**（Lua -2）；L2：stock 仍 99（未扣）、claimed 仍 1
- **perUserLimit>1 场景**：模板 G perUserLimit=2 → g4b_ 领 2 次均 200（claimed=2、t_user_coupon 2 行、remain_count 减 2）→ 第 3 次 → **30013**
- **售罄**：`SET myxhs:coupon:{id}:stock 0`（③ 操纵）→ 新用户 g4c_ claim → **30014 优惠券已领完**（Lua -1）；L2：stock 不变
- **恢复**：`DEL myxhs:coupon:{id}:stock` → 下次 claim 触发 -3 分支（initStockFromDb 从 remain_count 恢复）——与 G4-01-12 联动；或手动 SET 回 remain_count 值

#### G4-01-10 领券时间窗口（未开始/已过期/已下线）
- **未开始**：创建模板 validStart=now+10min → g4b_ claim → **30016"活动尚未开始"**（claim 校验用缓存模板——注意缓存 30min：validStart 在创建时已缓存，行为一致）
- **已过期**：模板 B validEnd=**now+10min** → 领券成功（1 张）→ **SQL UPDATE valid_end=now-1min** → `DEL myxhs:coupon:template:{B}`（清缓存重载）→ g4b_ claim → **30015 优惠券已过期**
- **已下线**：模板 C → PUT status=0 → claim → **30016"优惠券已下线"**
- **L2**：三模板 Redis stock 均未扣（30015/30016 在 Lua 前拦截）
- **注意**：claim 模板校验用 getTemplateWithCache（Redis→MySQL 二级）——SQL 改 valid_end 后必须 DEL 缓存 key 才生效

#### G4-01-11 领券参数 + 限流 + 签名
- ① 缺 templateId → 40002；templateId=0 → 40002（@Positive）
- ② **无 HMAC 签名**（带 JWT）→ **403**（claim 不在 hmac 白名单——与模板管理免签对照）
- ③ **限流**：连打 6 次（不同模板或同一）→ 第 6 次 **40202**；`ZCARD myxhs:coupon:claim:CouponController:claimCoupon:{uid}`=5（**perUser → key 含 uid**）
- **注意**：限流计数含业务失败请求（#79-3）；执行前 DEL 限流 key；用例后清理

#### G4-01-12 幽灵模板 + 库存未初始化兜底（Lua -3）
- **幽灵模板**：claim templateId=999999999999 → **30012**（模板缓存空值 60s 后重建——L2：Redis = "NULL"）
- **未初始化兜底（-3 分支）**：`DEL myxhs:coupon:{id}:stock` → claim → Lua -3 → **initStockFromDb（setIfAbsent remain_count）→ 重试成功 200**；L2：stock=remain_count 恢复、t_user_coupon 新增
- **注意**：initStockFromDb 从 MySQL remain_count 初始化——若 remain_count 已减（MQ 消费过），会以当前值初始化

#### G4-01-13 消费幂等 + Outbox 补发
- **DB 唯一索引兜底**：向 COUPON_CLAIM_TOPIC 投递同 claimNo 消息（**console/dashboard 投递，payload=`{"userId":{g4b_uid},"templateId":{id},"claimNo":"dup-claim-no-1"}`**）→ 第一次消费 insert 成功；再投同 claimNo → **uk_claim_no 冲突 → 忽略**；L2：t_user_coupon 该 claim_no 仅 1 行、remain_count 只减 1
  - **兜底**：console 不可用时降级为 SQL 构造验证唯一索引：`INSERT t_user_coupon` 同 claim_no 第二行 → DuplicateKeyException 被拒（索引存在性实证）；消费者重复消费路径标注"代码审查级"（uk_claim_no 兜底逻辑已代码实证）
- **Outbox 补发（CouponOutboxSenderJob 5s 周期）**：
  ```
  # 构造 pending 事件：INSERT INTO t_coupon_outbox(id,user_id,template_id,claim_no,status,created_at)
  #   VALUES({雪花}, {g4b_uid}, {id}, 'outbox-test-1', 0, DATE_SUB(NOW(), INTERVAL 1 MINUTE))
  # 等 10-15s（5s 周期 + 3s cutoff + 锁 4s）→ SELECT status FROM t_coupon_outbox WHERE claim_no='outbox-test-1'
  #   → 1（补发成功）；L2：t_user_coupon 新增 claim_no='outbox-test-1' 的行（消费成功）
  ```
- **幂等标记**：L2 检查 `GET msg:idempotent:coupon:claim:{msgId}`（消费后存在，TTL≈86400）
- **注意**：Outbox 补发使用 event.claimNo 而非 msgId（consumer 逻辑：claimNo 优先）——所以 outbox-test-1 会落库为 claim_no='outbox-test-1'

### 我的券

#### G4-01-14 user/list 状态过滤
- 前置：g4b_ 有 3 张券（1 未使用 + 1 已使用[SQL 构造 status=1] + 1 已过期[SQL 构造 status=2]）
- `GET /api/coupon/user/list`（签名）→ 3 条，**orderByDesc(receivedAt)**；`?status=0` → 仅未使用；`?status=1` → 仅已使用
- **L2 结构**：UserCouponVO 含 id/couponId/name/type/discountValue/minAmount/status/validEnd/receivedAt（name 等来自模板批量查询 batchToVO——无模板关联时 name=null，可构造孤儿券观察）
- **孤儿券（观察）**：INSERT t_user_coupon 带不存在的 coupon_id → list 返回该行但 name=null（batchToVO template==null 跳过填充）——登记观察

#### G4-01-15 user/available 实时过滤
- `GET /api/coupon/user/available`（签名）→ 仅 status=0 且 validEnd>now 的券
- **过滤实证**：SQL 把某未使用券的模板 valid_end 改过去 → available **不含**（validEnd 来自 batchToVO 实时查模板，**不依赖模板缓存**——SQL 改立即生效）
- **不检查 validStart**：SQL 把模板 valid_start 改未来 → available 仍含（登记观察——使用端 ExpireValidator 会拦）

### 用券/退券（内部接口）

#### G4-01-16 内部接口鉴权（discount/use/return）
- ① **经 gateway 带 JWT 无 X-Internal-Call**：`GET /api/coupon/discount/{userCouponId}?orderAmount=200` → **403"仅限内部服务调用"**；POST /api/coupon/use → 同 403；POST /api/coupon/return → 同 403
- ② **经 gateway** 带 JWT + HMAC 签名 + X-Internal-Call（+X-User-Id 由 gateway 注入）→ 200
- ③ **直连 19010** + X-Internal-Call + 手动 X-User-Id → 200（端口信任模型）
- ④ **直连无 X-User-Id** → **40002"缺少必要请求头: X-User-Id"**（MissingRequestHeaderException → PARAM_INVALID，**L2 已实测**）
- ⑤ use/return 参数校验：缺 userCouponId/orderId/orderAmount → 40002（@NotNull）；orderAmount=0 → 40002（@DecimalMin 0.01）
- ⑥ discount 的 orderAmount 非数字 → **40003"参数类型错误: orderAmount"**（MethodArgumentTypeMismatchException → PARAM_TYPE_ERROR，**L2 已实测**）
- **注意**：内部接口经 gateway 需 JWT（不在 JWT 白名单）+ **HMAC 签名**（discount/use/return 不在 hmac 白名单——对照 G3 sku/batch 因 `/api/product/sku/**` 在 hmac 白名单而免签，coupon 无此豁免）；**直连 19010 不走 gateway 无需签名**（端口信任模型，order Feign 即此路径）
- **注意**：参数绑定/校验先于 isInternalCall/isAdminCall（L2 实测：discount 参数错误时未到内部鉴权即返回 40003）——负面断言顺序：400xx 优先于 403

#### G4-01-17 折扣计算（三类型 + 0 元购兜底）
- 前置：建 3 模板（满减/折扣/无门槛）各领 1 张
- **满减 type1**：discountValue=20, minAmount=100 → `GET /api/coupon/discount/{uc1}?orderAmount=199`（X-Internal-Call）→ **20**（199≥100 过门槛）
- **折扣 type2**：discountValue=8.5（85 折）→ order=100 → **15.00**（100-100×8.5/10，HALF_UP）
- **无门槛 type3**：discountValue=5, minAmount=0 → order=10 → **5**
- **0 元购兜底**：type3 discountValue=30, order=10 → **10**（discount.min(orderAmount)）
- **不核销实证**：discount 调用后 t_user_coupon status 仍 0（getCouponDiscount 无 markUsed——#53 修复前死代码，现为下单前展示用）
- **归属校验**：g4a_（非本人）调 discount 用 g4b_ 的券 → **30016"优惠券不属于当前用户"**

#### G4-01-18 责任链校验（门槛/未生效/过期/下线/状态）
- **门槛**：满减券（min=100）order=50 → use → **30016"订单金额50.00未达使用门槛100.00"**
- **未生效**：领券后 `UPDATE t_coupon_template SET valid_start=now+1h` + DEL 模板缓存 → use → **30016"优惠券尚未生效"**（**注意：不能创建时就设未来 validStart——claim 也会拦"活动尚未开始"，券领不到**）
- **已过期**：领券后 `UPDATE t_coupon_template SET valid_end=now-1h` + DEL 模板缓存 → use → **30015"优惠券已过期"**（同样不能创建时就设过期 validEnd——@FutureOrPresent 拒绝）
- **已下线**：模板 status=0（PUT）→ use → **30016"优惠券已下线"**
- **券状态异常**：同一张券 use 两次（第一次成功）→ 第二次 → **30016"优惠券已被使用"**（markUsed 乐观锁）
- **券不存在/非本人**：use 他人券 → **30012**；use id=999999999999 → 30012
- **L2**：失败路径 t_user_coupon status 不变、used_order_id 为空

#### G4-01-19 用券乐观锁（并发双用一券）
- 并发 2 个请求同时 use 同一张券（不同 orderId）→ **恰一成功**（200+折扣）、另一 → **30016"优惠券已被使用"**；L2：status=1、used_order_id=成功方 orderId、只绑定一个 order
- **失败方不扣券**：无副作用（乐观锁 UPDATE 0 行即拒绝）

#### G4-01-20 退券（状态流转 + Redis 回退 + remain_count 回退）
- 前置：领券 → use 成功（status=1, used_order_id=O1）
- **入口**：`POST /api/coupon/return` body=`{"userCouponId":{uc},"orderId":O1}`（X-Internal-Call + X-User-Id）
- **L1**：200
- **L2**：
  ```
  # ① MySQL：SELECT status,used_order_id,used_at FROM t_user_coupon → 0/NULL/NULL（returnCoupon 乐观锁）
  # ② remain_count +1（SQL 原子）：SELECT remain_count → 退前值+1
  # ③ Redis 回退（afterCommit）：GET myxhs:coupon:{id}:stock → 回退前+1；GET claimed:{uid} → 回退前-1
  # ④ 模板缓存 evict：GET myxhs:coupon:template:{id} → 0（重建后 remain_count 新值）
  # ⑤ 可再次使用：user/available 含该券（status=0 恢复）
  ```
- **注意**：Redis 回退在事务 afterCommit 执行——断言前等 0.5s

#### G4-01-21 退券幂等（重复退/状态不匹配）
- ① **重复退**：对 G4-01-20 已退的券再 return（同 orderId）→ **200**（returnCoupon affected=0 → warn 日志"[优惠券] 退券失败(状态不匹配)"，不报错——幂等）；L2：status 仍 0、stock 不重复 +1
- ② **退未使用券**（status=0）：return → **200**（同幂等路径）；L2：无变化
- ③ **退券归属**：他人 return 我的券 → **30012**（selectById + userId 校验）
- ④ **退券 Redis 回退失败容错**（观察）：仅代码审查（rollbackRedisStock 场景由 reconcileJob 兜底）

### 定时任务

#### G4-01-22 couponExpireJob（xxl#16，矩阵 #14）
- **前置**：模板 D（validEnd 未来）+ g4b_ 领 2 张（status=0）
- **构造**：`UPDATE t_coupon_template SET valid_end=DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id={D}` + `DEL myxhs:coupon:template:{D}`
- **触发**：xxl admin 手动触发 id=16（jobGroup=10；矩阵 A 节：login cookie → jobinfo/trigger）
- **L2**：
  ```
  # SELECT id,status FROM t_user_coupon WHERE coupon_id={D} → status=2（全部标记过期）
  # xxl_job_log trigger_code=200 + handle_code=200（"标记 N 张券过期"）
  # 即时校验联动（#15）：useCoupon 该券 → 30015（过期即时拦截，不依赖任务）
  # user/available → 不含（validEnd 过滤）
  ```
- **未过期券不受影响**：模板 E（validEnd 未来）的券 status 仍 0

#### G4-01-23 couponReconcileJob（xxl#15，券对账）
- **前置**：模板 F（status=1、validEnd 未来、remain_count=R）
- **场景 1（Redis 缺失 → MySQL 补）**：`DEL myxhs:coupon:{F}:stock` → 手动触发 id=15 → **Redis stock=R**（从 MySQL 补全，修复计数+1）
- **场景 2（不一致 → 以 Redis 为准修 MySQL）**：`SET myxhs:coupon:{F}:stock 5`（③ 操纵，快照 MySQL remain=R）→ 触发 → **MySQL remain_count=5**（Redis 权威）；L2 日志"[券对账] 修复: templateId=..., redis: 5 → mysql: R"
- **场景 3（反向）**：`SET myxhs:coupon:{F}:stock {R+3}` → 触发 → MySQL=**R+3**（Redis 更大也以 Redis 为准——与 cart 双向一致）
- **作用域**：status=0（下线）模板不参与对账（构造一个 status=0 模板 Redis 缺失 → 触发 → 不补全）
- **注意**：避免与每分钟 cron 竞争（构造后立即手动触发；对账后恢复 stock/remain 一致）；对账以 Redis 为准无防误删（登记观察）

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G4-01-01~23 | 2026-08-15 14:02~14:25 | ✅ 23/23 | 全量回归（Task9 后）；无新增缺陷；详见 README 回归记录 |

## 断言关键词速查
- 200 成功 / 30012 不存在 / 30013 已领取 / 30014 已领完 / 30015 已过期 / 30016 不满足条件 / 40002 参数 / 40202 限流 / 403 鉴权
- 关键 L2：stock/claimed key、t_coupon_template/t_user_coupon/t_coupon_outbox、模板缓存 30min/空值 60s、uk_claim_no 幂等、remain_count 双路径

## 深度 REVIEW 补充（2026-08-14 三轮完成，全量代码核对）
### 第一轮 L0/L1 已核（代码实证）
- ✅ 端点安全矩阵全表（JWT/HMAC/Admin/Internal 四维）——claim/user/** 必须签名、template 管理免签、template/list 公开、discount/use/return 内部
- ✅ Lua 四态返回（1/-1/-2/-3）与 Controller 错误码映射（30013/30014/30016 语义已核对）
- ✅ claim 无 @Idempotent（Lua claimed 兜底）；createTemplate 无 @Idempotent（观察）
- ✅ updateTemplateStatus 无非法 status 校验、无 RateLimit（观察）
- ✅ Outbox 三态（insert→sent/delete）与回滚链路（deleteByClaimNo + rollbackRedisStock）
- ✅ 消费幂等双保险（msgId SET NX 24h + uk_claim_no 唯一索引）+ decrementRemainCount（remain_count>0 条件）
- ✅ 用券乐观锁 markUsed（WHERE status=0）；退券乐观锁 returnCoupon（WHERE status=1 AND used_order_id）+ afterCommit Redis 回退
- ✅ 责任链 @Order：Amount→Expire→Status
- ✅ 折扣计算三类型 + discount.min(orderAmount) 兜底
- ✅ 模板校验全注解（@FutureOrPresent、type 1-3、perUserLimit 1-10、discountValue 0.1~9.9 仅 type2）
- ✅ xxl#15/#16 job_group=10、cron 每分钟、trigger_status=1（2026-08-14 运行态核对 xxl_job_info）
- ✅ coupon 执行器在线（xxl_job_registry：my-xhs-coupon @ 21.214.97.212:9995）
- ✅ 三表结构（t_coupon_template/t_user_coupon uk_claim_no/t_coupon_outbox）
- ✅ **环境脏数据**：coupon 三表现 17/22/4 行（Task2 遗留）——执行前必须清理

### 第二轮修正（自检）
- ✅ 内部接口经 gateway = JWT + HMAC + X-Internal-Call 三重（coupon 无 sku/** 式 hmac 豁免）；直连免签名（端口信任）
- ✅ G4-01-08 Outbox 断言改"最新一行反查 claim_no"
- ✅ G4-01-09 增 perUserLimit>1 场景；恢复方式明确为 -3 分支联动
- ✅ G4-01-13 投消息标注 console 依赖 + SQL 兜底降级
- ✅ G4-01-15 validEnd 来自 batchToVO 实时查模板（不依赖缓存）——删多余 DEL 步骤
- ✅ G4-01-16/05 补参数负面（orderAmount 非数字、id=0）

### 第三轮修正（L2 最小化探测实证——零数据变更请求仅查响应码）
- ✅ **异常映射四连实测**（2026-08-14 直连 19010）：
  - `PUT /api/coupon/template/0/status?status=1`（@Positive id=0）→ **40002"must be greater than 0"**（ConstraintViolation 路径，非 500——Spring 6.1 HandlerMethodValidationException 风险排除）
  - `PUT /api/coupon/template/1/status`（缺 status）→ **40001"缺少参数: status"**（MissingServletRequestParameterException）
  - `POST /api/coupon/claim`（无 X-User-Id）→ **40002"缺少必要请求头: X-User-Id"**（MissingRequestHeaderException，T-021 路径）
  - `GET /api/coupon/discount/1?orderAmount=abc`（类型错）→ **40003"参数类型错误: orderAmount"**（PARAM_TYPE_ERROR）
- ✅ **参数绑定/校验先于 isInternalCall/isAdminCall**（discount 参数错误未到内部鉴权即返回 40003）——负面断言顺序 400xx 优先于 403
- ✅ **RocketMQ dashboard 端口 18081 OPEN**（compose 实证）——G4-01-13 投消息路径可用
- ✅ gateway X-User-Id 注入机制确认：GatewayAuthFilter 鉴权后 `set()` 覆盖注入（C-07 防伪造）——claim/user/** 经 gateway 自动带 X-User-Id
- ✅ **G4-01-18"未生效"构造修正**：不能创建时设未来 validStart（claim 会先拦"活动尚未开始"，券领不到）——改为领券后 SQL 改 valid_start 未来 + 清缓存
- ✅ **G4-01-10 已过期窗口** 5min→10min（执行时序余量）

### 第三轮核对
- ✅ 端点全景核对：CouponController 10 端点全覆盖（template CRUD+list / claim / user×2 / discount+use+return），无遗漏
- ✅ CouponApplication 无 @Profile 测试端点（对照 notification dev profile）
- ✅ 交接文档 §十一-2 素材全部复核一致（hmac 白名单 371/372 行、xxl#15/#16 job_group=10、写端点 X-Admin-Call）
- ✅ testlib.sign 兼容 GET+query 签名（user/list?status=）

### 第四轮修正（2026-08-14，L2 实测推翻既有结论）
- ✅ **T-056 纠错**：G3 #80-4"参数校验在 admin 鉴权之后"**不成立**——L2 实测 createTemplate 非法 body + 无 X-Admin-Call → **40002**（@Valid 参数解析阶段先于方法体 isAdminCall）；合法 body + 无 X-Admin-Call → **403**；product createSpu 同结构同理。G4-01-02 注意已修正；G3 文档 #80-4 待执行期顺手更正
- ✅ **T-057 纠错**：G4-01-01 原断言"领券中心含该模板"错误——list 需 validStart≤now 而创建强制 ≥now → **新模板不进领券中心**（未开始，语义合理）。已改：01-01 断言"不含"、01-04 SQL 改 past 验证"含"
- ✅ **403 HTTP 语义记录**：业务 403 = HTTP 200 + body code 403（R.fail）；40001/40002/40003 = HTTP 400 + body code（@ResponseStatus）——断言按 body code（testlib）
- ✅ G4-01-16 ② 补全"经 gateway = JWT+HMAC+X-Internal-Call"（非仅 X-Internal-Call）
- ✅ G4-01-06 ⑤ 拆分为合法 body→403 / 非法 body→40002 两分支（L2 实测）
- ✅ G4-01-01 validStart now+5min→**now+2min**（G4-01-08 领券生效窗口）；G4-01-08 前置加"确认 validStart 已过，未到点先 sleep"

### 待 L2 运行态确认（执行时验证）
- [ ] claim -3 分支实际行为（initStockFromDb → 重试成功；重试 -2 报 30014 的语义）
- [ ] Outbox 补发实际周期（5s + 3s cutoff）
- [ ] xxl#16 手动触发日志（handle_msg 格式）
- [ ] 折扣 type2 计算精度（HALF_UP 2 位）
- [ ] 并发用券乐观锁恰一成功
- [ ] template/list 无 JWT 访问 200（gateway 白名单路径）

### 已知风险
- coupon 无 gateway 白名单的用户端点全部需要签名——testlib.sign 的 query 参数要与 URL 一致（#79-5）
- claim 后断言必须等 MQ+主从（1-3s）；Redis 写后 sleep 1-2s
- 定时任务每分钟 cron——对账/过期构造后立即手动触发，避免与自动周期竞争
- 改 coupon 相关代码必须 rm -rf target 重打包（交接纪律 §九-2）
