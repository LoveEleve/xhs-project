# 时间相关测试全景矩阵（Time Matrix）

> 2026-08-12 | 目的：**测试不依赖真实时间**，所有时间场景的触发方式/数据操纵方法预先定义，避免测试中遇到再处理。
> 原则：① xxl-job 任务一律 admin API 手动触发；② 短周期 @Scheduled 等 1-2 个周期；③ 长窗口用 Redis/DB 数据操纵模拟；④ TTL 验证用 TTL 命令直接查，不等待。

---

## A. xxl-job 任务（20 个启用）— 一律手动触发

**触发方法**（admin API）：
```bash
# 1. 登录拿 cookie（默认 admin/123456）
curl -s -c /tmp/xxl.cookie -X POST "http://21.130.247.89:18080/xxl-job-admin/login" \
  -d "userName=admin&password=123456"
# 2. 手动触发（id 见下表）
curl -s -b /tmp/xxl.cookie -X POST "http://21.130.247.89:18080/xxl-job-admin/jobinfo/trigger" \
  -d "id=<任务ID>&executorParam=&addressList="
# 3. 结果查看：admin 日志页，或查 xxl_job_log 表
mysql -h21.130.247.89 -P3306 -uroot -p'Xhs@2026#MySQL' -N -e \
  "SELECT id, job_id, trigger_code, handle_code, trigger_msg FROM xxl_job.xxl_job_log ORDER BY id DESC LIMIT 3"
```

| 任务ID | handler | cron | 测试场景 | 验证点（L2） |
|:--:|---|---|---|---|
| 10 | orderCloseJob | 每分钟 | 超时关单 | 待付款订单→已取消；库存释放；券退还 |
| 11 | localMessageRetryJob | 每分钟 | 本地消息补发 | t_local_message status 0→1 或送达 MQ |
| 12 | deadLetterScanJob | 每分钟 | 死信扫描 | DLQ 消息被扫描处理 |
| 13 | orderMappingRepairJob | 每分钟 | 订单号映射修复 | t_order_no_mapping 补齐 |
| 6 | paymentTimeoutCheckJob | 每30秒 | 支付超时检查 | 待支付超时单被标记 |
| 7 | paymentNotifyCompensateJob | 每2分钟 | 支付通知补偿 | 通知重发 |
| 8 | refundNotifyCompensateJob | 每3分钟 | 退款通知补偿 | 退款通知重发 |
| 9 | refundTimeoutCheckJob | 每分钟 | 退款超时检查 | 退款单超时处理 |
| 14 | inventoryReconcileJob | 每分钟 | 库存对账 | Redis/MySQL 库存漂移修复 |
| 17 | cartReconcileJob | 每小时 | 购物车对账（P2-7/8）| 构造 Redis/MySQL 不一致→对账修复；纯 Redis 用户补录 |
| 15 | couponReconcileJob | 每分钟 | 券对账 | 券账漂移修复 |
| 16 | couponExpireJob | 每分钟 | 券过期 | 过期券标记（模板 valid_end 已过）|
| 4 | counterReconcileJob | 每天3点 | 计数对账 | 计数 Redis/DB 漂移修复 |
| 3 | followCounterRepairJob | 每小时 | 关注计数修复 | 粉丝数漂移修复 |
| 5 | unreadReconcileJob | 每5分钟 | 未读对账 | 未读数修复 |
| 18 | feedCleanupJob | 每天3点 | feed 清理 | feed 收件箱过期清理 |
| 19/20/21 | recommend×3 | 每小时/10分钟/每天2点 | 推荐计算 | 推荐索引/热池/ItemCF 更新 |
| 2 | demoJobHandler | — | 演示（停用）| 不测 |

> 注意：任务 16/17/18/19/20/21 本轮已启用（trigger_status=1）；demo=2 停用跳过。

---

## B. 进程内 @Scheduled（14 个）— 短周期等待 / 长窗口数据操纵

| 模块 | 任务 | 周期 | 测试方式 |
|---|---|---|---|
| inventory | PreDeductTimeoutJob 预扣超时释放 | 60s | **等 60-90s**（注释"测试环境快速验证"）|
| inventory | TccTimeoutJob TCC 超时 | 60s | 等 60-90s |
| inventory | InventoryCompensationJob 补偿 | 30s | 等 30-60s（P-D22 修复验证）|
| inventory | InventoryOutboxSenderJob 本地消息发送 | 5s | 等 5-15s |
| coupon | CouponOutboxSenderJob 本地消息发送 | 5s | 等 5-15s |
| content | FeedMessageRetryJob 消息重试 | 30s/60s | 等 60-120s |
| search | IncrementalIndexSyncJob ES 增量补偿 | 60s | 等 60-120s |
| search | HotSearchService 热搜计算 | 60s | 等 60-120s |
| search | IndexRebuildJob 索引重建 | 每天4点(cron 可配) | **手动触发 xxl 无此任务**→ 改配置 `search.rebuild.cron` 或调管理端点（若有）|
| counter | CounterBuffer 攒批刷盘 | 5s | 等 5-15s |
| payment | PayCallbackSimulator 支付回调模拟 | 5s(fixedDelay) | 等 5-15s（P-B1 场景：删 pending key 验证不再闭环）|
| notification | SseEmitterManager 心跳/清理 | 10s | 等 10-30s |

---

## C. 业务时间窗口（不等待，数据操纵模拟）

| 场景 | 窗口 | 涉及代码 | 操纵方法（不等待） |
|---|---|---|---|
| 支付超时 | 30min | PaymentService PAY_TIMEOUT_MS=30min；status key TTL 7 天（P2-4）| ① 删/改 pending key：`DEL myxhs:payment:status:{orderId}` ② 手动触发 paymentTimeoutCheckJob(6) ③ 验证关单/退款 |
| 订单超时关单 | 30min | orderCloseJob | 下单后直接**手动触发 orderCloseJob(10)**（不依赖 payment 超时）|
| 预扣库存超时 | 30min | PreDeductTimeoutJob | 等 60s 即可（任务每 1 分钟扫）|
| TCC 超时 | — | TccTimeoutJob | 等 60s |
| 验证码过期 | 5min | CaptchaService | 不等待：直接新取验证码（或构造过期：Redis 删/改 key）|
| access token 过期 | 30min | user access-token-expire=1800000 | 不等待：验证 refresh 换新即可；过期分支代码审查 |
| refresh token 过期 | 7 天 | refresh-token-expire=604800000 | 同上（不实等）|
| HMAC timestamp 窗口 | — | HmacSignatureFilter | **必须测**：构造过期 timestamp（now-10min）验证 403 |
| HMAC nonce 重放 | — | HmacSignatureFilter | **必须测**：同 nonce 二次请求验证拒绝 |
| 本地消息重试 | next_retry_time | localMessageRetryJob | ① UPDATE t_local_message SET next_retry_time=过去 ② 手动触发任务(11) ③ 验证重试计数/送达 |
| 券过期 | valid_end | couponExpireJob | 构造 valid_end 已过的券 → 手动触发(16) → 验证标记 |
| feed 收件箱过期 | inboxMaxDays | FeedPushConsumer | 不等待：TTL 查证（`TTL myxhs:feed:inbox:*`）+ 代码审查 |
| 支付/通知/退款补偿重试 | 超时重试 | paymentNotifyCompensateJob(7) 等 | 手动触发验证幂等（重发不重复入账）|
| 死信 | — | deadLetterScanJob(12) | 构造 DLQ 消息 → 手动触发 → 验证处理 |
| ID 段缓存过期 | 2 天 | IdGeneratorUtil | 不测（TTL 查证）|
| 缓存空值防穿透 | 2min | CacheHelper NULL_PLACEHOLDER | **必须测**：不存在的 key 查询 → 缓存空值 → 2min 内二次查询仍空值（不穿透 DB）|
| 幂等键过期 | 30s~24h | @Idempotent expireSeconds | 测试幂等生效即可（过期不测，代码审查）|

---

## D. TTL 验证点（P2-4/P2-6 修复回归，直接 TTL 命令查，不等待）

| key 模式 | 预期 TTL | 验证命令 |
|---|---|---|
| `myxhs:payment:status:{orderId}`（成功支付后）| **7 天**（P2-4）| `redis-cli -a Xhs@2026#Redis TTL myxhs:payment:status:{id}` |
| 计数 key（increment 后）| **30 天**（P2-6）| `TTL myxhs:counter:*`（活跃 key 持续续期）|
| 购物车 key | 30 天 | `TTL myxhs:cart:{userId}:items` |
| 本地消息补偿锁 | 55s | `TTL myxhs:lock:feed:retry:*` |
| feed 收件箱 | ≤ inboxMaxDays | `TTL myxhs:feed:inbox:*` |
| 支付 pending | 30min | `TTL myxhs:payment:pending:*` |

---

## E. 时间相关测试通用方法速查

1. **xxl 手动触发**：见 A 节（登录 cookie + trigger?id=）
2. **Redis 操纵**（模拟过期/清状态）：
   ```bash
   redis-cli -a 'Xhs@2026#Redis' DEL myxhs:payment:status:{orderId}
   redis-cli -a 'Xhs@2026#Redis' TTL myxhs:counter:{type}:{targetId}:{countType}
   ```
3. **DB 操纵**（模拟过期时间）：
   ```sql
   UPDATE my_xhs_order_1.t_local_message_1 SET next_retry_time = DATE_SUB(NOW(), INTERVAL 10 MINUTE) WHERE id = ?;
   ```
4. **等周期**：短任务（≤60s）sleep 后验证；长任务一律手动触发，**禁止 sleep 超 120s**
5. **验证点原则**：每个时间场景必须回答"任务跑了吗（xxl_job_log trigger_code=200）+ 数据变了吗（L2 查 Redis/MySQL）"

---

## F. 深度 REVIEW 补充（2026-08-12 第二轮，代码查证）

### C 节遗漏场景（补入）

| 场景 | 窗口（代码实证）| 操纵/验证方法 |
|---|---|---|
| **RocketMQ 延时关单链路**（重要）| `order.close.delay-level:16` = **30 分钟**（RocketMQ 延时级别 16）| 不等 30min：① 下单后查 ORDER_CLOSE_TOPIC 消息**已投递**（RocketMQ dashboard/Admin API，delayLevel=16）② 消费逻辑用 **orderCloseJob(10) 手动触发**覆盖（与延时消费同一处理链路）|
| **登录锁定（P2-9）** | 账号锁/IP 锁 **15 分钟**（LOCK_MINUTES=15）| ① 错密码 5 次（同 IP）→ 账号锁定 40106 ② 单 IP 20 次 → IP 锁 40203 ③ **解锁操纵**：`DEL myxhs:user:login:lock:*`（不实等 15min）|
| **HMAC per-session secret** | **7 天**（与 refresh token 同生命周期，TokenService:81）| ① 登录返回 hmacSecret ② 写操作签名成功 ③ **重新登录后旧 secret 失效**（新签名必须用新 secret）|
| **logout 黑名单** | TTL = token **剩余有效期**（blacklistByClaims）| 注销后旧 token 立即 401（刷新也用不了）|
| **SSE ticket（通知）** | **30 秒**过期（SseTicketService TICKET_TTL）| SSE 建立链路要在 30s 内完成；ticket 过期再连 → 拒绝 |
| **缓存一致性（普通缓存）** | 30 分钟（CacheHelper）| ① 查数据 → 改 DB → 立即查仍旧值（缓存未失效）② 删缓存 key → 查新值 ③ 空值缓存 2min 防穿透：不存在 id 查两次，第二次不落 DB |
| **过期券下单即时校验** | 模板 valid_end | 构造 valid_end 已过的券 → 下单 → 被拒（业务码），列表不显示 |
| **ES 索引可见性** | refresh 默认 1s | L2 查 ES 前 sleep 1-2s（canal→MQ→consumer→ES 全链路秒级）|
| **大V 标记 / 分析版本域 / IM 会话 / feed 进度** | 10min / 24h / 7 天 / 1h | 仅 TTL 查证（不等待不操纵）：`TTL` 命令核对 |

### L3 审查项（非等待类，代码审查确认）

| 项 | 要求（方法论 §4.1）| 现状（已核对）|
|---|---|---|
| 定时任务周期 ≤1min | 测试环境 | 全部 ≤60s（IndexRebuildJob cron 4 点除外——手动触发/改配置）|
| Redisson 锁 leaseTime < fixedRate | 防锁重叠 | FeedMessageRetryJob 锁 55s vs fixedRate 60s/30s——**30s 任务锁 55s > 30s 需确认**（L3 审查点）|
| xxl 任务执行超时（executor_timeout）| 防任务悬挂 | 任务表 executor_timeout 需核对（默认 0=不限？）|
| RocketMQ 消费重试 | maxReconsumeTimes=3 | 消费失败重试 3 次（间隔 10s~2min 递增）——构造失败验证可选项 |

### 查证值速查（代码实证，测试直接用）

| 值 | 出处 |
|---|---|
| 支付超时 30min、status key TTL 7 天（P2-4）| PaymentService PAY_TIMEOUT_MS / Duration.ofDays(7) |
| 计数 key 30 天续期（P2-6）| CounterService expire 30d |
| 购物车 30 天 | CartService CART_TTL_DAYS=30 |
| 验证码 5min | CaptchaService EXPIRE_MINUTES=5 |
| access 30min / refresh 7 天 | user yml 1800000/604800000 |
| HMAC secret 7 天 | TokenService refreshTokenExpire |
| 登录锁 15min | UserService LOCK_MINUTES=15 |
| 缓存 30min / 空值 2min | CacheHelper |
| 下单幂等键 24h | OrderService idempotentKey |
| @Idempotent 默认 60s | common Idempotent |
| 本地消息重试锁 55s | FeedMessageRetryJob |
| 通知 SSE ticket 30s | SseTicketService |
| IM 会话 7 天 | ChatService |
| 分析版本域 24h | Analytics VERSION_TTL_HOURS=24 |
| 大V 标记 10min | FeedPushConsumer |
| 延时关单 30min（delayLevel=16）| order.close.delay-level |
| 死信/补偿/通知重试 | xxl 任务 1-3min |
| 黑名单=剩余有效期 | blacklistByClaims |

---

## G. 第三轮深度探索补充（2026-08-12，代码实证）

### 新发现的时间机制

| 机制 | 窗口（代码实证）| 测试方法 |
|---|---|---|
| **@RateLimit 限流窗口**（重要）| **windowSeconds=60**（39 处）+1 处=1s；maxRequests 2~120 | 触发限流：60s 窗口内连打 N+1 次 → 429/业务码拒绝（如退款 5 次/60s）；**窗口恢复**：等 60s 或 `DEL myxhs:rate:*` 计数 key |
| **IM 在线状态** | 心跳间隔 **30s**，在线 key TTL **90s**（3 倍心跳，OnlineRouteService:75）| WS 建立 → im:online 存在；断开后 90s 过期 → 离线（等 90s 或 TTL 操纵）|
| **RocketMQ 事务消息回查** | 下单事务消息（OrderTransactionListener），Broker 回查本地事务 | L2 验证：下单 → 事务消息被消费（库存预扣成功）；**回查分支**代码审查（构造半消息需 broker 侧操纵，标注不实做）|
| **HotSkuDetector 热点窗口** | **30s**（WINDOW_TTL_SECONDS=30）| 构造高频访问 → 30s 窗口内 hot key 生效；TTL 查证 |
| **xxl 执行器注册心跳** | 注册 90s 过期（registry 每分钟更新，当前 10 执行器在线）| 观察性验证：`SELECT COUNT(*) FROM xxl_job_registry`（非测试动作）|
| **连接/发送超时（L3 审查）** | Tomcat connect 30s/keep-alive 60s；Feign 3000/5000；RocketMQ send 3s；锁 tryLock 3s/10s（9 处）| 代码审查确认即可，不构造慢调用 |

### 补充说明
- **事务消息**：下单链 = 事务消息（原子）而非本地消息表（订单侧）；本地消息表用于 feed 推送等**下游**可靠性（P2-8 相关）。L2 验证分开：下单看事务消息消费；feed 看本地消息表流转。
- **限流测试归属**：各 G 组文档按端点带限流用例（如 G5 交易组退款 5 次/60s）。
- **IM 心跳测试**：30s 心跳间隔意味着 WS 连接保持 60-90s 可观察到在线/离线流转（可等）。

### 时间相关测试覆盖检查表（最终版）
- [x] xxl-job 20 任务（A 节）——手动触发
- [x] @Scheduled 13 任务（B 节）——等待/操纵
- [x] 业务时间窗口 15 场景（C 节 + F 节 8 项）
- [x] TTL 验证 7 组（D 节）
- [x] 限流窗口（G 节新增）
- [x] IM 在线/心跳（G 节新增）
- [x] 事务消息/回查（G 节新增）
- [x] 热点检测窗口（G 节新增）
- [x] 锁超时/连接超时（G 节 L3 审查）
- [x] 幂等窗口（C 节 + 代码审查）
- [x] 缓存失效/空值（F 节）

---

## H. 触发方式矩阵（修正版：并非全部可手动触发）+ 第三轮新发现

### 触发方式分类（诚实版）

| 方式 | 覆盖范围 | 说明 |
|---|---|---|
| **① xxl admin API 手动触发** | 20 个 xxl 任务 | 已验证可用（登录 cookie + trigger?id=）|
| **② 等周期** | 13 个 @Scheduled（全部 ≤60s）| 短周期可等 1-2 个周期；**@Scheduled 无 API 可手动触发** |
| **③ 数据操纵**（删 key/改 TTL/改时间戳/改 ZSet score）| 业务时间窗口（支付超时/预扣超时/本地消息重试/券过期/feed 清理）| 正确方法：先把"时间证据"改成过去，再等短周期任务或手动触发 xxl |
| **④ 无法手动触发 → 替代验证/代码审查** | 见下表 | **明确标注，禁止假装测过** |

### ④ 修正版：通过"投测试消息/构造凭证"直测（原"无法触发"表述不准确）

| 机制 | 正确测试方法（直测）|
|---|---|
| RocketMQ **延时消息消费路径**（30min 关单）| **console/dashboard 投 delayLevel=1（1 秒）延时消息**到 ORDER_CLOSE_TOPIC（payload=测试订单）→ 1s 后消费端真实触发关单 → 直测消费路径 |
| RocketMQ **消费重试**（maxReconsumeTimes=3）| **投畸形消息**（payload 格式错误）→ 消费者抛异常 → 自动重试 3 次 → DLQ 出现 → 验证重试日志+DLQ |
| RocketMQ 事务消息**回查**（checkLocalTransaction）| 运行态半消息状态难构造 → **单测** checkLocalTransaction（查本地订单表分支）+ 代码审查（唯一保留"单测级"项）|
| token **过期** | 用 JWT secret **自签过期 access token** → 服务返回 401（不需要等 30min）|
| HMAC secret **过期** | `DEL myxhs:user:hmac:secret:{uid}` → 写操作 403"密钥已过期" |
| 验证码**过期** | 删 Redis 验证码 key → 登录被拒 |
| **锁看门狗续期**（leaseTime=-1 → 30s/10s 续期）| L3 代码审查（DistributedLockAspect）|
| **Nacos 配置刷新**（refresh: true）| 长轮询 ~30s → **等 30-60s** 验证动态配置生效 |
| **xxl misfire**（DO_NOTHING）| 不测（配置事实记录）|
| feed 收件箱 7 天自然过期 | 构造 8 天前时间戳数据 → 手动触发 feedCleanupJob(18) 验证清理 |

> 修正说明：**"不能手动触发"几乎不存在**——RocketMQ 消息投递本身就是测试触发器（console 发消息），过期类可自签/删 key 构造。唯一运行态不可直测的是**事务消息回查**（需半消息状态），降级为单测+审查并标注。

### 第三轮新发现（配置实证）

| 项 | 值 | 说明 |
|---|---|---|
| **xxl executor_timeout 全部 = 0** | 不限时 | **L3 审查缺陷**：任务无执行超时上限，可能悬挂；建议设 60s |
| misfire_strategy | 全部 DO_NOTHING | 停机错过不补跑（对账类任务靠下次周期兜底）|
| 购物车对账锁 | 600s（10min）| setIfAbsent 锁 TTL |
| Redisson 看门狗 | leaseTime=-1 → 30s 锁 + 10s 续期 | L3 审查：与 @Scheduled 周期配合 |
| Nacos 动态配置 | refresh: true 长轮询 | 配置变更测试需等 30-60s |
| SegmentIdGenerator | 无定时预取（懒加载）| 无时间依赖 |

### 修正原则（写入各组测试文档）
1. 每个时间用例必须标注**触发方式**（①API/②等待/③操纵/④替代+审查）
2. ④类用例（仅剩事务回查）**禁止写"通过"**，只能写"单测级/代码审查结论"；其余时间机制均有直测手段
3. ③类操纵必须记录**操纵前快照**（原值）与操纵动作

---

## I. 时间机制测试规划总表（主方法 + 兜底方法，测试规划直接引用）

> 触发方式：①xxl API ②等周期 ③数据操纵 ④投测试消息/构造凭证。每项含**主方法**与**兜底方法**（主方法不可行时的备选）。

| # | 机制 | 窗口 | 触发 | 主方法 | 兜底方法 | 验证点 |
|:--:|---|---|:--:|---|---|---|
| 1 | xxl 任务（20 个）| 按 cron | ① | admin API 手动触发（trigger?id=）| 等 cron 到点（不推荐）；改 cron 为每分钟（需 admin 操作）| xxl_job_log trigger/handle=200 + L2 数据变化 |
| 2 | @Scheduled 短周期任务（13 个）| 5s~60s | ② | 等 1-2 个周期（sleep ≤120s）| 数据操纵让任务必命中（改时间戳/ZSet score）；重启服务 | 任务日志 + L2 数据变化 |
| 3 | 支付超时 | 30min | ③ | 删/改 `myxhs:payment:status:{id}` → 手动触发 paymentTimeoutCheckJob(6) | 等真实 30min（不现实）；代码审查 | 超时单标记/关单/退款 |
| 4 | 订单超时关单 | 30min | ③+① | 下单后手动触发 orderCloseJob(10) | ④投 delayLevel=1 延时消息直测消费路径；改 `order.close.delay-level` 配置重启（测试环境配 1s）| 订单关闭+库存/券释放 |
| 5 | 预扣库存超时 | 30min | ③+② | 改预扣索引 ZSet score=过去 → 等 PreDeductTimeoutJob 60s | 等真实 30min；直接等 60s 周期（任务每 1min 扫）| 预扣释放回滚 |
| 6 | TCC 超时 | — | ② | 等 TccTimeoutJob 60s | 改 t_tcc_fence gmt_create=过去 → 等周期 | 分支事务取消 |
| 7 | 验证码 | 5min | ③ | `DEL myxhs:user:captcha:{captchaKey}` → 登录被拒（GETDEL 消费：同码二次使用也被拒）| 新取验证码验证正常路径；等 5min（不现实）| 过期码拒绝+正常码通过+防并发消费 |
| 8 | access token | 30min | ③ | **自签过期 JWT** → 401 | 用 refresh 换新验证续期；等 30min（不现实）| 过期拒+refresh 续期 |
| 9 | refresh token | 7 天 | ③ | 自签过期 refresh → 刷新被拒 | 审查；等 7 天（不现实）| 刷新拒绝 |
| 10 | HMAC timestamp 窗口 | **5 分钟**（TIMESTAMP_TOLERANCE_MS，HmacSignatureFilter:79）| ③ | 构造过期 timestamp（now-10min）→ 403 | 构造超前 timestamp（now+10min）→ 403；正常 timestamp 通过 | 过期/超前/正常三分支 |
| 11 | HMAC nonce 重放 | — | ③ | 同 nonce 二次请求 → 拒绝 | 审查 nonce 存储/过期 | 重放拒绝 |
| 12 | HMAC secret | 7 天 | ③ | `DEL myxhs:user:hmac:secret:{uid}` → 403 | 重新登录换新 secret 验证旧失效 | 过期拒绝+重登生效 |
| 13 | 本地消息重试 | next_retry_time | ③+① | UPDATE next_retry_time=过去 → 手动触发 localMessageRetryJob(11) | 等任务周期（每分钟）；投畸形消息验证重试计数 | 消息补发+retry_count 递增 |
| 14 | 券过期标记 | valid_end | ③+① | 构造 valid_end 过去 → 手动触发 couponExpireJob(16) | 等周期（每分钟）；过期券下单验证即时校验 | 券标记+下单被拒 |
| 15 | 券即时校验（下单）| valid_end | ③ | 持有过期券下单 → 被拒 | 审查校验分支 | 业务码拒绝 |
| 16 | feed 收件箱清理 | inboxMaxDays（默认 7 天）| ③+① | **ZSet score 操纵**：测试用户收件箱 ZSet 某条 score 改为 8 天前（FeedCleanupJob 以 score<cutoff 清理）→ 手动触发 feedCleanupJob(18) | 改 home.feed.inbox-max-days 配置重启；TTL 查证 | 过期成员被清理 |
| 17 | feed 大V/进度 | 10min/1h | — | 仅 TTL 查证 | 审查 | TTL 值符合 |
| 18 | 支付通知补偿 | 超时重试 | ① | 构造未通知支付单 → 手动触发 paymentNotifyCompensateJob(7) | 等周期（每 2min）；代码审查幂等 | 通知重发不重复入账 |
| 19 | 退款通知补偿 | 同上 | ① | 同 18（任务 8）| 同上 | 同上 |
| 20 | 退款超时检查 | 超时 | ① | 构造超时退款单 → 手动触发 refundTimeoutCheckJob(9) | 等周期（每 1min）| 退款超时处理 |
| 21 | 死信扫描 | — | ① | 构造 DLQ 消息 → 手动触发 deadLetterScanJob(12) | ④投畸形消息触发消费重试 3 次后自然进 DLQ | DLQ 被扫描处理 |
| 22 | ID 段缓存 | 2 天 | — | 仅 TTL 查证 | 审查（号段懒加载无定时）| TTL 值 |
| 23 | 缓存普通 | 30min | ③ | 改 DB → 查仍旧值 → 删缓存 key → 查新值 | 等 30min（不现实）；审查 CacheHelper | 缓存一致性 |
| 24 | 缓存空值防穿透 | 2min | ③ | 不存在 id 查询两次 → 二次不落 DB | 审查 | 空值缓存生效 |
| 25 | 幂等键（下单 24h/@Idempotent 30-60s）| 24h/60s | ③ | 同参重复请求 → 幂等拒绝 | 等窗口过期后同参可再提交；审查 | 重复请求拒绝 |
| 26 | 限流窗口 | 60s（1 处 1s）| ③ | 连打 N+1 次 → 429/拒绝 | 重置：`SCAN {prefix}:*` 后 DEL（key 格式 `{prefix}:{Class}:{method}[:userId]`，RateLimitAspect:106）；等 60s | 限流生效+窗口恢复 |
| 27 | IM 在线/心跳 | 30s/90s | ② | WS 连接观察在线；断开等 90s 离线 | 删 `im:online:{uid}` 立即离线 | 在线流转 |
| 28 | SSE ticket | 30s | ③ | 拿 ticket 后 30s 内建连成功；过期再连被拒 | 删 ticket key 立即失效 | 过期拒绝 |
| 29 | 延时消息消费（关单）| 30min | ④ | **console 投 delayLevel=1 消息** → 1s 后消费关单 | xxl orderCloseJob(10) 替代；改 delay-level 重启 | 消费路径直测 |
| 30 | 消费重试 | 3 次 | ④ | **投畸形消息** → 抛错重试 3 次 → DLQ | 审查重试配置 | 重试日志+DLQ |
| 31 | 事务消息回查 | broker | ④ | **单测** checkLocalTransaction | 代码审查（运行态半消息难构造）| 回查分支逻辑（单测级）|
| 32 | HotSkuDetector | 30s | ② | 高频访问 → hot key 生效；TTL 查证 | 审查窗口逻辑 | 热点标记 |
| 33 | xxl registry 心跳 | 90s | ② | 观察 registry 在线（10 执行器）| 审查 | 在线状态 |
| 34 | Nacos 配置刷新 | ~30s | ② | 改配置 → 等 30-60s → 验证生效 | 重启服务（立即生效）| 动态配置生效 |
| 35 | 登录锁（账号/IP，P2-9 新语义）| 账号 15min / IP 20次 / IPS 集 30min | ③ | **新逻辑（代码实证）**：① 同 IP 错 5 次 → 账号**不**锁（仅累计）；② 单 IP 达 20 次 → IP 锁（40203）；③ 失败 ≥5 次且来源 IP ≥2 个（USER_LOGIN_FAIL_IPS 30min 窗口内）→ 账号锁（40106）| 解锁：`DEL myxhs:user:login:lock:*` + `DEL myxhs:user:login:fail:ips:*`（账号锁）；IP 锁删对应 IP key | 三种锁定分支分别验证 |
| 36 | 锁看门狗 | 30s/10s | — | L3 审查（DistributedLockAspect）| — | leaseTime=-1 行为 |
| 37 | xxl misfire | DO_NOTHING | — | 不测（配置事实）| — | — |
| 38 | executor_timeout=0 | 不限 | — | L3 审查：**建议设 60s**（任务悬挂风险）| — | 配置项 |
| 39 | 连接/发送超时 | 30s/3-5s | — | L3 审查（Tomcat/Feign/RocketMQ）| — | 配置项 |
| 40 | 购物车对账锁 | 600s | — | 审查锁 TTL（对账期间锁 10min）| — | 锁行为 |

### 使用说明
1. 规划测试时按 # 引用（如"G5-14：券过期标记，主③+①，兜底等周期"）
2. ③ 类必须记录操纵前快照；④ 类记录投递消息内容
3. 兜底方法优先级：**④投消息 > ③操纵 > ①API > ②等待 > 审查**
4. #31（事务回查）与 #36~#40（审查类）为"单测级/审查级"，禁止写"通过"，写结论

---

## J. 深度 REVIEW 修正与补漏（2026-08-12）

### 修正（代码实证，原文档有误）
1. **#35 登录锁**：原"错 5 次锁账号"是**旧逻辑**。P2-9 新语义（代码实证）：同 IP 5 次**不**锁账号；单 IP 20 次 → IP 锁；失败 ≥5 次且来源 IP ≥2 个（30min 窗口）→ 账号锁。三种分支分别测，解锁删对应 key。
2. **#26 限流 key**：原"DEL myxhs:rate:*"错误。实际 key=`{prefix}:{Class}:{method}[:userId]`（RateLimitAspect:106），重置用 `SCAN {prefix}:*`。
3. **#10 HMAC 窗口**：补实证值 **5 分钟**（TIMESTAMP_TOLERANCE_MS），加"超前 timestamp"分支。
4. **#16 feed 清理**：机制实证为 **ZSet score<cutoff** 清理（非 TTL），操纵方法=改 score。
5. **#7 验证码**：key=`myxhs:user:captcha:{key}`，GETDEL 防并发消费（同码二次使用被拒——附加验证点）。

### 补漏（REVIEW 新发现）
| 项 | 值 | 说明 |
|---|---|---|
| **产品 SPU/分类缓存** | 1 小时（CacheWarmupRunner:71/96/115）| 预热缓存 TTL 1h；测试：改 DB → 缓存 1h 内仍旧值 → 删缓存 key 验证新值 |
| **通知聚合窗口** | 当天（自然日，P2-14）| 非等待类：L2 验证聚合 key 按天（notify_date 索引）|
| **登录失败 IPS 集窗口** | 30min（UserService:395）| 账号锁判定依赖的辅助窗口（与 #35 关联）|
| **@RateLimit windowSeconds=1 的 1 处** | 1s | 唯一秒级限流端点（防刷场景），按注解定位测试 |

### REVIEW 结论
- 40 项总表 + 5 处修正 + 3 项补漏后，时间矩阵完整且**每项方法均经代码实证**（窗口值/触发机制/key 格式）
- 仍为"单测级/审查级"的仅：#31 事务回查、#36~#40 审查类
