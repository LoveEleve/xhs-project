# G5-02 库存+支付用例（预扣/确认/释放/TCC + 支付/回调/退款/超时）

> 组：G5 交易 | 服务：inventory(19009) + payment(19012) + 联动 order | 入口：**gateway(19000)**
> 依赖：G1 登录 + G3 商品（SKU 存在）
> 时间引用：矩阵 **#3**（支付超时 30min ③）、**#5/6**（预扣/TCC 超时 ②）、**#14**（库存对账 ①）、**#18/19/20**（支付/退款通知补偿 ①）
> 前提：inventory/payment 服务 UP、xxl 任务 6-9/14 存在启用

## 代码实证（2026-08-14，全量核实）

### inventory 端点与安全（InventoryController）
| 端点 | 鉴权 | @RateLimit |
|---|---|---|
| POST /api/inventory/init | **X-Admin-Call**（无 JWT/HMAC）| 5次/60s `myxhs:inventory:init` |
| POST /api/inventory/preDeduct | **X-Internal-Call** | 无 |
| POST /api/inventory/confirm | **X-Internal-Call** | 无 |
| POST /api/inventory/release | **X-Internal-Call** | 无 |
| POST /api/inventory/reinit | **X-Admin-Call** | 2次/60s |
| GET /api/inventory/stock/{skuId} | **公开**（无鉴权）| 无 |
| POST /api/inventory/internal/reconcile | **X-Admin-Call** | 2次/60s |
| POST /api/inventory/tcc/try | **X-Internal-Call** | 无 |
| POST /api/inventory/tcc/confirm | **X-Internal-Call** | 无 |
| POST /api/inventory/tcc/cancel | **X-Internal-Call** | 无 |

**注意**：inventory **无 JWT/HMAC 依赖**（gateway 白名单含 `/api/inventory/stock/**`、`init`、`reinit`）；预扣等内部接口 token 校验在 Header；403 业务码 = HTTP 200 + body code

### Redis key（字面花括号 hash tag——python 转义 `{{{skuId}}}`）
- `inventory:{%d}:total`（String 无 TTL）、`inventory:{%d}:bucket:{n}`（分桶）、`inventory:bucket:count:{%d}`（桶数）
- `inventory:prededuct:{pseudoOrderId}`（Hash：skuId→qty + `skuId:bucket` 辅助；**TTL 1800s**）
- `inventory:prededuct:index`（ZSet：orderId→过期时间戳）
- `inventory:paused:{skuId}`（扩容暂停 30s）、`inventory:resize:{skuId}`（扩容锁 10s）
- `inventory:hot:window:{skuId}`（ZSet 10s 窗口/阈值 100/TTL 30s）

### Lua 语义
- **prededuct.lua**：KEYS=[total, prededuct, bucket0..N-1, index]；ARGV=[skuId, orderId, qty, bucketCount, userId, expireSeconds]；返回 **1**成功/**0**不足/**-1**重复预扣（幂等）/**-2**未初始化；路由 `userId % bucketCount`，不够遍历其余桶
- **release.lua**：KEYS=[total, prededuct, **来源桶**, index]；返回 >0 释放数量 / 0 无记录
- **confirm.lua**：KEYS=[prededuct, index]；只 HDEL（不动库存）；>0 确认 / 0 无记录
- **reconcile_buckets.lua**：求和修正 total；1 修正 / 0 一致

### inventory 关键流程
- **initStock**：SETNX total 幂等（已存在 → 40002"库存已初始化"）；分桶 total/N 余数给桶 0
- **preDeduct 顺序**：暂停检查 → **初始化检查（bucket:count 缺失 → 40002"库存未初始化"）** → **HotSku recordAndCheck（L235，阈值 100 → 异步扩容桶）** → Lua → MQ 同步 INVENTORY_TOPIC:PRE_DEDUCT（失败回滚）
- **confirmDeduct**：HGETALL 预扣记录，空→幂等 return；confirm.lua；MQ CONFIRM 失败仅日志
- **releaseStock**：来源桶 HGET 缺失默认 0；release.lua；MQ RELEASE
- **getStock**：Redis total 命中 → availableStock=total；未命中 → MySQL（无记录 30002）；**Redis 分桶缺失不回填 MySQL（需 reinit）**
- **reinitStock**：total=available+locked；SCAN 删桶重建；**不清理 prededuct/index**

### inventory 定时任务
| Job | 触发 | 锁 | 逻辑 |
|---|---|---|---|
| PreDeductTimeoutJob | @Scheduled 60s | tryLock(0,40s) | ZRANGEBYSCORE index 0..now+60s → release.lua 逐单回退 + MQ RELEASE；result=0 清陈旧 member |
| TccTimeoutJob | @Scheduled 60s | tryLock(0,50s) | t_tcc_freeze_detail status=1 且 created<10min → cancelFence 1→3 成功才解冻 |
| InventoryCompensationJob | @Scheduled 30s | tryLock(0,25s) | t_inventory_compensation status=0 retry<3 → release.lua（**只传 3 个 KEYS 无 index——HLEN=0 时 ZREM nil 会脚本错**）→ 成功 resolved / 失败 retry+1（≥3 → 2 死信）|
| InventoryReconcileJob | **xxl#14** 每分钟 | 无（xxl 单实例）| **以 Redis 为准修 MySQL**（Redis total ≠ available_stock → 只改 available_stock）；Redis 未初始化跳过；reconcile_buckets 修正 total；可经 /internal/reconcile 手动触发 |
| InventoryOutboxSenderJob | @Scheduled 5s | tryLock(0,4s) | t_inventory_outbox status=0 created<now-3s → syncSend INVENTORY_TOPIC:{action} → SEND_OK 才 markSent |

### TCC（t_tcc_fence + t_tcc_freeze_detail）
- try：tryFence（1-Try）+ 逐 SKU tryFreeze（乐观锁 available_stock>=qty，不足抛异常）；SUSPENDED→false、DUPLICATE→跳过返回 true
- confirm：confirmFence 1→2 + 明细 1→2（SKIP_DUPLICATE/REJECTED 跳过）
- cancel：cancelFence（空回滚/幂等 SKIP/已确认 REJECTED_CONFIRMED）+ 明细 1→3
- **注意**：TCC 用 MySQL available/freezing，与 Redis 桶预扣是**两套并行机制**

### payment 端点与安全（PaymentController）
| 端点 | 鉴权 | @RateLimit |
|---|---|---|
| POST /api/payment/pay | X-Internal-Call + X-User-Id | 10次/60s perUser |
| POST /api/payment/callback/{payType} | X-Internal-Call（无 → 字符串"fail"）| 无 |
| POST /api/payment/refund | **仅 X-User-Id（无 X-Internal-Call！）** | 5次/60s perUser |
| POST /api/payment/refund-callback/{payType} | X-Internal-Call | 无 |
| GET /api/payment/status/{orderId} | **无鉴权** | 无 |

### payment 关键流程
- **pay()**：Redisson 锁 `myxhs:lock:payment:pay:{orderId}`（3s）→ 幂等（status key 已存在 → 40201）→ **订单回查（P1-1）**（null → 30008 / 非 0 → 30009）→ INSERT t_payment(0) → **isMockMode = payType==99**：同步成功；否则 `callbackSimulator.registerCallback`（payType 1/2）
- **PayCallbackSimulator**：@Scheduled 5s；pending key `myxhs:payment:callback:pending:{payType}:{paymentNo}`（5min）；**SUCCESS_RATE=0.9**；delay 1-3s；回调 → handlePayCallback
- **handlePaySuccessInternal**：乐观锁 0→1 → statusKey="1"（**7 天 TTL**）→ PAY_RESULT_TOPIC:PAY_SUCCESS → Feign notifyPaySuccess → **业务失败（非 503）→ 自动退款（P1-1 #57）**
- **handlePayFailInternal**：0→2 → statusKey="2" → PAY_FAIL MQ + notifyPayFail
- **refund()**：锁 → refundingKey SETNX（7d）→ 支付单校验（不存在/非本人 → 30017；状态非 1 → 30009）→ 金额校验（>可退 → 30017）→ INSERT t_refund(0) → 策略退款（99 同步成功）
- **checkPaymentTimeout**（xxl#6 每 30s）：**T-062 已修（2026-08-14）**——DB 扫描 status=0 AND created_at<now-30min（LIMIT 100 分批）→ 乐观锁置 2 + Redis status 同步 + TIMEOUT 事件（原 SCAN+Lua 恒真误标+只改 Redis 已废弃）
- **checkRefundTimeout**（xxl#9 每 60s）：t_refund status=0 created<15 天 → 0→3（关闭）+ REFUND_FAIL MQ

### payment 定时任务（xxl 组 6，全部 trigger_status=1）
| id | 任务 | cron | 逻辑 |
|---|---|---|---|
| 6 | paymentTimeoutCheckJob | 每 30s | checkPaymentTimeout（Redis 置 2）|
| 7 | paymentNotifyCompensateJob | 每 2min | 扫 status=1 且 paid_at<5min 前 100 条 → 订单仍待支付则重发 notifyPaySuccess；notified key 1h 去重；计数 7d |
| 8 | refundNotifyCompensateJob | 每 3min | 扫 status=1 success_at<5min → 重发 notifyRefundSuccess |
| 9 | refundTimeoutCheckJob | 每 60s | checkRefundTimeout（15 天关闭）|

### 错误码
30004 库存不足 / 30002 SKU 不存在 / 30008 订单不存在 / 30009 订单状态不允许 / 30017 支付失败 / 40201 幂等 / 40202 限流 / 403 鉴权 / 40002 未初始化（inventory 复用）

## 前置准备（执行时记录快照）
```python
from testlib import *
# 1. g5a_ 登录 + sku1/sku2（G3 商品）
# 2. inventory init（sku1 total=100, bucketCount 默认）
# 3. 清理：t_inventory_compensation/t_inventory_outbox/t_tcc_* + Redis inventory:* 测试残留
```

## 用例清单

### 库存域

#### G5-02-01 库存初始化（init 幂等 + 分桶）
- init（X-Admin-Call）→ 200；L2：Redis total=100、bucket:count 存在、MySQL t_inventory available_stock=100
- 重复 init → **40002"库存已初始化"**（SETNX 幂等）
- getStock → availableStock=100/initialized=true；**未初始化 SKU**（新 sku 无 init）→ availableStock=?/initialized=false

#### G5-02-02 预扣（preDeduct 三态 + 幂等）
- **请求**：PreDeductRequest `{"orderId":{pseudo},"skuId":{sku1},"quantity":2,"userId":{uid}}`（**userId 必填 @NotNull；quantity @Max 999**；X-Internal-Call）
- preDeduct → 200；L2：`inventory:prededuct:{orderId}` Hash 有 sku1→qty、total 100→98、**路由桶对应扣减**（userId%bucketCount）、index ZSet 有该单、TTL≈1800
- **重复预扣**（同 orderId）→ **幂等返回 200（Lua -1 忽略）**；total 不再减
- **库存不足**：预扣 qty=200 → **30004 库存不足**
- **未初始化**：新 sku2 未 init → preDeduct → **40002"库存未初始化"**
- **鉴权**：无 X-Internal-Call → 403；经 gateway 无头 → 403（对照 sku/batch 模式）

#### G5-02-03 确认扣减（confirm）
- 预扣后 confirm → 200；L2：prededuct Hash 消失、total 保持 98（不动库存）、MySQL locked 归 0
- **重复 confirm** → 幂等 200（无记录 return）
- **未预扣直接 confirm** → 200 无副作用（幂等）

#### G5-02-04 释放（release）
- 预扣后 release → 200；L2：total 100→回满、prededuct 消失
- **重复 release** → 200 幂等（无记录）
- **无来源桶**（Hash 无 skuId:bucket 字段）→ 默认桶 0 释放

#### G5-02-05 预扣超时释放（PreDeductTimeoutJob 60s）
- 预扣 → 改 index score 为过去（ZADD 操纵）→ 等 60-90s → total 回满、prededuct 消失、MQ RELEASE 事件
- **矩阵 #5 主方法**：改 ZSet score=过去（③+②）

#### G5-02-06 TCC 三态（try/confirm/cancel）
- **请求格式**：TccDeductRequest `{"xid":"...","branchId":1,"skuItems":[{"skuId":{sku1},"quantity":2}]}`（X-Internal-Call）
- tcc/try → 200 true；L2：t_tcc_fence status=1、t_tcc_freeze_detail status=1、MySQL freezing_stock 增加
- **库存不足**：try 超量 → 400"库存不足"（R.fail(400)）
- **重复 try**（同 xid）→ 幂等 true（DUPLICATE）
- tcc/confirm → fence 1→2、明细 1→2、freezing 减少
- tcc/cancel → fence 1→3、明细 1→3、freezing 回退
- **已确认后 cancel** → REJECTED_CONFIRMED（不误回退）

#### G5-02-07 TCC 超时（TccTimeoutJob 60s）
- try 后改明细 created_at 过去（③）→ 等 60-90s → fence 1→3、明细 1→3、冻结回退

#### G5-02-08 库存对账（inventoryReconcileJob xxl#14）
- **Redis 权威**：SET Redis total=95（③，快照 MySQL=100）→ 手动触发 id=14 → **MySQL available_stock=95**（只改 available_stock）
- **Redis 缺失**：DEL total/bucket → 触发 → **跳过**（Redis 未初始化不对账）
- **桶修正**：直接 SET bucket:0=30（sum≠total）→ 触发 → reconcile_buckets 修正 total=sum
- 作用域：未初始化 SKU 不参与

#### G5-02-09 库存补偿 + Outbox 补发（T-063/064/065 修复后）
- **补偿链路（30s 周期，T-064/065 修复回归）**：构造预扣记录（total=98）+ INSERT t_inventory_compensation（status=0, created 1min 前）→ 等 40s → **status=1/resolved、total 回退 100（单次）、预扣 Hash 清除、index ZREM 无残留**（修复前：total 已回退但标记失败 → 下周期重复回退超发）
- **死信**：置 retry_count=2 → 等周期 → status=2（重试超限转人工）
- **Outbox（5s）**：INSERT t_inventory_outbox（status=0 created 过去）→ 等 10-15s → status=1（已补发）+ MQ 消费
- **reinit**：改桶数 reinit → total 不变、桶重建；**prededuct 不清**（代码事实）

### 支付域

#### G5-02-10 支付创建（payType=99 同步成功）
- **前置**：订单（G5-01 下单 status=0）
- 直连 19012：`POST /api/payment/pay`（X-Internal-Call + X-User-Id）body={orderId, amount, payType:99} → 200 paymentNo
- L2：t_payment status=1、paid_at；**订单 status=1**（回调链路）；Redis status key="1" TTL≈7 天；paying key 删除
- **幂等**：重复 pay 同订单 → **40201"请勿重复支付"**（status key 存在）
- **非待付款**：已支付订单再 pay → 30009（订单回查）；已取消订单 pay → 30009
- **限流**：连打 11 次 → 第 11 次 40202（10次/60s perUser）

#### G5-02-11 支付回调（模拟器 90% 链路，payType=1）
- pay（payType=1 支付宝模拟）→ 200；pending key 存在（5min TTL）
- 等 5-15s（模拟器 5s 周期 + 1-3s 延迟）→ 回调触发：
  - **90% 成功**：t_payment 0→1、订单 0→1、statusKey=1
  - **10% 失败**：t_payment 0→2、订单自动取消（4）+ 库存释放
- **重复回调**：handlePayCallback 状态非 0 → 幂等 return
- **直接回调 API**：`POST /api/payment/callback/1` body 含 success=true（X-Internal-Call）→ "success"；无头 → 字符串 "fail"

#### G5-02-12 支付失败 + 竞态自动退款（P1-1 #57 回归）
- **竞态构造（必须 payType=1/2 异步模式）**：payType=99 同步成功无竞态窗口（回调立即执行）——用 payType=1：
  1. 下单 → pay（payType=1，pending 等待模拟器）
  2. **回调前** SQL 置订单 status=4（已取消，③ 操纵）
  3. 模拟器回调成功（90%）→ handlePaySuccessInternal → 订单 notifyPaySuccess 返回 **30009**（非 503）→ **payment 自动退款**
- **判定**：t_refund 创建 status=1（全额）、reason="订单已取消/状态不允许支付，自动退款"、t_payment status=3（已退款）——#57 验证
- **先拦路径对照**：订单已取消时直接 pay → 订单回查 30009（G5-02-10 已覆盖）

#### G5-02-13 退款（全流程 + 幂等）
- 前置：已支付订单（payType=99）
- `POST /api/payment/refund`（X-User-Id，**无 X-Internal-Call 也能调——鉴权缺口记录**）body={paymentId, refundAmount, reason} → 200
- L2：t_refund status=1（99 同步）、t_payment status=3、**订单 status=5**（refund-success 回调链路）、refunding key 删除、Redis status="3"
- **重复退款** → 40201（refundingKey 7d）
- **金额超限**：refundAmount > 剩余可退 → 30017；**已全额退款后再退** → 30009（支付单状态不允许退款——状态校验先于金额校验，T-076 修复后语义）
- **非本人退款** → 30017；**未支付单退款** → 30009
- **部分退款（T-076/T-077 修复回归）**：分两次（50+49.90）→ 第一次后支付单保持 **1**、订单保持 **1**（不置 5）；第二次（累计=全额）→ 支付单 **3**、订单 **5**（Feign 直调 notifyRefundSuccess，T-077）；第三次 → 30009

#### G5-02-14 支付超时（paymentTimeoutCheckJob xxl#6，T-062 修复后行为）
- 构造：payType=1 支付（pending 未回调）→ Redis status key="0"（30min TTL）
- **触发**：xxl#6 手动触发 → **DB t_payment status=2 + TIMEOUT 事件（error_code=PAY_TIMEOUT）+ Redis status="2"**（T-062 修复：DB 扫描模式，不再只改 Redis）
- **不误标验证**：新鲜支付单（created 1min 内）→ 触发后 status 仍 0
- 或等待 30s 自动周期（每 30s 扫描）

#### G5-02-15 支付通知补偿（paymentNotifyCompensateJob xxl#7）
- 构造：SQL 置 t_payment status=1 + paid_at=now-10min + **订单仍 status=0**（不一致）→ 触发 id=7 → 订单被补通知 status=1（notifyPaySuccess 重发）
- **notified 幂等**：已通知订单（notified key 1h）→ 跳过

#### G5-02-16 退款通知补偿（refundNotifyCompensateJob xxl#8）
- 构造：t_refund status=1 + success_at=now-10min + **订单仍 status=1** → 触发 id=8 → 订单补退款（status=5）

#### G5-02-17 退款超时关闭（refundTimeoutCheckJob xxl#9）
- 构造：t_refund status=0 + created_at=now-16 天（③）→ 触发 id=9 → status=3（关闭）+ REFUND_FAIL MQ + refunding key 删除

#### G5-02-18 支付鉴权/限流/事件流水
- ① pay 无 X-Internal-Call（直连）→ **40002"缺少必要请求头: X-User-Id"**（P1-3 trust filter 剥离伪造头，先于接口 403 校验——双重拦截）；带 Internal 但**错误 token** → 403"支付请通过订单服务发起"（实测：错 token 时 trust 不信任剥头 → 40002；正确 Internal 才到接口）
- ② **refund/status 需 X-Internal-Call（T-061 已修）**：带合法 JWT 直连 refund/status → 403；带 X-Internal-Call → 业务校验（30017 等）
- ③ **事件流水回归**：一次 pay 成功 → t_payment_event 有 CREATE+PAY_SUCCESS；退款 → REFUND（error_msg=reason）；超时 → TIMEOUT（T-062）

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G5-02-01~18 | 2026-08-15 14:57~15:10 | ✅ 18/18 | 全量回归（Task9 后）；T-071 修复确认（restoreStockOnRefund）；补偿三动作 MQ 投递实证；详见 README 回归记录 |

## 断言关键词速查
- 200 / 30002 / 30004 库存不足 / 30008 / 30009 / 30017 支付失败 / 40002 未初始化 / 40201 / 40202 / 403
- 关键 L2：inventory:prededuct 三态、total 分桶、t_tcc_*、t_inventory_compensation/outbox、t_payment/t_refund 状态机、t_payment_event

## 深度 REVIEW 补充（2026-08-14 第一轮，全量代码核对）
### L0/L1 已核
- ✅ inventory 端点矩阵（10 端点：init/reinit/reconcile=X-Admin-Call；preDeduct/confirm/release/tcc=X-Internal-Call；stock 公开）
- ✅ preDeduct 顺序（暂停→初始化→HotSku→Lua→MQ）+ Lua 四态返回
- ✅ TCC 双表状态机（fence 1/2/3 + detail 1/2/3）+ try 乐观锁 available_stock
- ✅ 5 个 inventory 任务（xxl#14 每分钟组4 + 4 个 @Scheduled）
- ✅ payment 端点矩阵 + **鉴权缺口**：refund 无 X-Internal-Call、status 无鉴权（登记观察）
- ✅ pay 流程（锁/幂等/回查/99 同步/1、2 模拟器）
- ✅ PayCallbackSimulator（5s/90%/1-3s/5min pending）
- ✅ 自动退款触发条件（notifyPaySuccess 业务失败非 503）
- ✅ 4 个 payment xxl 任务（组 6，6-9 全部启用）
- ✅ **checkPaymentTimeout 已修复（T-062）**：DB 扫描+乐观锁+事件（不再恒真误标/只改 Redis）——用例按修复后行为断言
- ✅ refund/status 鉴权已修复（T-061）——JWT 直连越权路径封死，Feign 链路带令牌不受影响
- ✅ Redis key/TTL 全表 + 表结构（t_payment/t_refund/t_tcc_*/t_inventory_*）

### 第二轮修正（2026-08-14 自检）
- ✅ **G5-02-12 竞态构造改为 payType=1**：99 同步模式无竞态窗口（回调立即执行）——异步模式才能在"pay 后、回调前"构造订单被取消
- ✅ G5-02-06 补 TCC 请求格式（xid/branchId/skuItems）
- ✅ G5-01-15 交叉核对：onRefundSuccess 代码实证 = 状态5 + releaseInventory + returnCouponIfUsed（G5-01 断言正确）

### 待 L2 确认
- [ ] 未初始化 SKU getStock 的 initialized 标志实测
- [ ] InventoryCompensationJob release.lua 缺 index key 的 HLEN=0 边界（ZREM nil 脚本错）
- [ ] TCC try 库存不足的错误码格式（400 + 消息）
- [ ] 自动退款竞态全链路（G5-02-12）
- [ ] 补偿死信路径（retry≥3 → status=2）

### 已知风险
- TCC 与 Redis 桶预扣是两套机制——用例勿混断言（t_tcc 用 MySQL 字段）
- 对账（xxl#14）以 Redis 为准——构造不一致后立即触发
- 补偿 Job 的 release.lua 边界（无 index key）——若触发脚本错属已知代码事实（可登记 T）
- 模拟器 90% 成功率——失败分支用例需重试或构造（pending key 手动触发回调）
