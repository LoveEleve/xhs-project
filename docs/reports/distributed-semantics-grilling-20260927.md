# 分布式与业务语义深度拷打（2026-09-27）

> **定位**：本文件是"语义拷打"记录——从**业务/技术/分布式**视角对 xhs-project 的一致性、可靠性与边界做系统提问与核查；
> 与 `per-service-review-20260921.md`（修复台账）互补：台账记"改了什么"，本文件记"语义怎么想、哪里还有空洞、面试怎么讲"。
> **方法**：读链路代码 → 提尖锐问题 → 找现有防线 → 标记风险与待决策。所有结论附代码证据（file:line）。

---

## 1. 全景：项目里到底有几套一致性机制？

| 机制 | 所在链路 | 覆盖窗口 | 残余风险 | 兜底 |
|---|---|---|---|---|
| **RocketMQ 事务消息** | 下单创建 | DB 事务 与 MQ 发送 原子 | 回查耗尽后消息被丢 | 本地消息表补发 |
| **本地消息表 + 补发 Job** | 下单创建 | Broker 丢消息/宕机/回查耗尽 | 补发=新 msgId，幂等标记挡不住 | 事件版本 Lua + 对账 |
| **Outbox（自建表 + Sender Job）** | coupon / content(Feed) / inventory | 本地提交 与 MQ 发布 之间 | Job 停摆期间积压 | 重试表 + 死信 + 对账 |
| **对账 Job** | inventory / payment / order / settlement | 所有"消息已丢/处理失败"的残余 | Redis 为准的前提 = Redis 可靠 | 人工 + 告警（部分） |
| **TCC** | inventory（**空壳，无调用方**） | — | 面试若吹 TCC 会翻车 | — |
| **幂等（多形态）** | 全链路消费者 | 重复投递/补发/重放 | TTL 过后重放；失败时不抛异常=不重试 | 版本号/唯一键/状态机 |
| **分布式锁（多形态）** | order（裸 SETNX）/ inventory（Redisson）/ im（pair lock） | 并发互斥 | TTL 与业务时长不匹配；无看门狗 | 幂等键兜底 |

> **一句话结论**：主链路是"**事务消息/Outbox 保证发送 + 至少一次投递 + 消费者幂等 + 对账做最终仲裁**"的可靠性风格；
> TCC/Seata **没有接入**（无 `io.seata`、无 `@GlobalTransactional`）。

---

## 2. 逐域拷打

### 2.1 订单创建（双通道 + TOCTOU + 补偿取消）

**事实链**
- 幂等键（24h）→ 分布式锁（裸 SETNX，10s TTL，UUID value，Lua 比较释放）→ 前置库存校验（Feign）→ 事务消息 → 本地事务（订单+明细+本地消息同库事务）→ 提交后 `markSuccessByTransactionId` 防止补发 Job 双投。
- 券核销在**本地事务提交后**同步执行；核销失败 → **取消订单**（补偿）；取消时 `returnCoupon`。

**拷打问题**
1. 前置校验是 TOCTOU（校验与预扣之间有窗口），只能降低失败率，**不是保证**——预扣失败怎么收口？（见 2.2，P0）
2. 锁只有 10s TTL、无看门狗。若"前置校验+事务消息+券核销"路径耗时超 10s，同用户并发下单会突破锁，只剩 24h 幂等键兜底（bizIdentifier 相同时挡住；不同时放行）。**锁与幂等键的职责边界需要能讲清**。
3. 补偿取消（券核销失败→取消订单）发生在订单已提交之后：若此时用户已支付，取消与支付的竞争靠状态机兜底吗？（待核实取消路径的状态守卫）

**面试讲法**："下单不是一把梭：24h 幂等键挡重复提交，短锁挡并发，事务消息保证订单与消息原子，本地消息表兜 Broker 异常，券核销失败走补偿取消。"

### 2.2 库存预扣（本轮最尖锐：失败收口缺失）**P0**

**事实链**
- 预扣：`deductStock` 乐观锁 + 消费者内退避重试 3 次（50/100ms）；**失败后只 log warn、不抛异常**（不进 DLQ、不触发 MQ 重试）→ "等 L3 对账修复"。
- CONFIRM / RELEASE / REFUND_RESTORE 失败同样只 log。
- **inventory 不向 order 发任何"预扣失败"事件**。
- 对账：每天凌晨 3 点，**以 Redis 为权威**修正 MySQL；文档化边界：对账时有 MQ 积压则积压消费可能多扣一次 MySQL（"概率极低，下次对账再修"）。

**拷打问题**
1. 订单已提交（用户可见）→ 库存真实不足（并发窗口）→ 预扣失败 → **用户 30 分钟内支付成功** → CONFIRM 也失败 → 只剩对账"以 Redis 为准拉平 MySQL"。**这笔"付了钱但库存从未锁住"的订单，业务收口（自动退款/履约拦截/人工）在代码里没有显式路径**。
2. "以 Redis 为准"的前提是 Redis 数据可靠（AOF/RDB 配置、丢 6 秒窗口的历史讨论）——若 Redis 丢数据，对账会把损失**放大同步**到 MySQL。**truth source 的裁决规则需要文档化**。
3. 预扣 TTL 缓冲（+600s）+ 只回退真到期：预扣超时释放与"用户支付确认"的竞争窗口有多窄？释放后支付成功会怎样走？（现有：CONFIRM 失败→log→对账）

**建议**：① 补"库存失败反馈"（inventory→order 失败事件或订单侧发货前守卫）；② 或者明确成文："支付成功但未锁库的单，由对账+人工在 X 小时内处理"，并加指标；③ 对账执行失败/连续失败的监控（待核实）。

### 2.3 支付（fire-and-forget + 对账兜底）

**事实链**：`syncSend` 发 PAY_RESULT/REFUND_RESULT **不检查 SendResult**（注释："由对账兜底"）；`PaymentReconcileJob` 每天 3 点扫支付成功单核对订单状态、补偿通知（**该逻辑曾是死代码**，后补 XXL 入口）；order 侧处理**迟到成功翻转**。

**拷打问题**
1. PAY_RESULT 丢失窗口 = 最长 24h（对账周期），期间用户"付了钱但订单待付款"——**体验缺口**要能主动讲（面试官必问"消息丢了怎么办"）。
2. 对账 Job 扫的是"支付成功单"；"支付失败但订单侧状态错误"由谁扫？（order 侧有 `OrderCalibrationJob` 干跑+指标，覆盖吗？待核实两者边界）
3. 迟到成功（订单已关单后支付成功）的翻转规则 = 资金正确性关键设计，建议补一条"自动退款"预案说明。

### 2.4 订单超时关闭（延迟消息 + Job 双保险）

**事实链**：delay level 16（30min）同步发 ORDER_CLOSE_TOPIC，检查 SEND_OK；失败 warn"定时任务兜底"；`OrderCloseJob` 扫描兜底；关单与支付并发用状态机 `ALLOWED_FROM` 对抗。

**拷打问题**
1. RocketMQ 延迟只有固定 18 档（做不到任意时刻），业务上能接受吗？"30 分钟关单"若要求精确（如 29 分 59 秒），当前设计不满足。
2. delay 消息丢失 → Job 兜底周期内订单滞留（用户看到"待付款"超时未关）；Job 周期 vs 30min 的误差要能说清。
3. 关单消息重复投递靠状态机幂等 ✓（已有 t_order_event 序号唯一键）。

### 2.5 购物车（ORDERLY + 双同步）

**事实链**：`asyncSendOrderly(key=userId)` + 双 ORDERLY 消费者（事件落库 + 同步）+ **时间戳 CAS 作跨分区乱序兜底**；`t_cart_event` + 对账 Job；`t_cart_event` 30 天保留。

**拷打问题**
1. ORDERLY 的代价：队列级锁 + 重试 suspend + **热用户把 6 队列之一打热**（大 V/活动用户）。有没有分片键/热点评估？
2. 为什么购物车用 ORDERLY 而库存用版本号？（面试好题：前者"末态收敛"类，后者"资源安全"类）

### 2.6 营销/券（单 Lua + Outbox + 退券语义）

**事实链**：`claim_coupon.lua` 单脚本原子（扣减+记录）；`CouponOutboxSenderJob` 补发；`useCoupon` 乐观锁 `WHERE status=0` 天然幂等；**退券语义争议 RV30**（"回滚不作废"复现实验）；旧"退券 Redis 补偿链"已整链删除（与 RV30 冲突的地雷）。

**拷打问题**
1. 用券在"订单提交后同步执行"：券核销成功但订单后续失败（如支付超时关单）→ `returnCoupon` 补偿；**补偿本身失败**的回退是什么？（告警/人工/对账）
2. 退券不退库存（RV30）的语义选择，面试要能论证。

### 2.7 内容/Feed（Outbox + 删除竞态）

**事实链**：发布 → Outbox/asyncSend + `FeedMessageRetryJob` 每 60s 扫"未完成推送"补偿；`NOTE_DELETE` 墓碑标记（5min）防"删除后推送复活"；DFA 预处理；评论通知 asyncSend 失败**仅 log**（非关键）。

**拷打问题**
1. 删除与推送的乱序/重复竞态已有墓碑防线 ✓——这是"业务语义防乱序"的好故事。
2. 评论通知发送失败仅 log：可接受（非关键），但要能解释"为什么不是关键消息"（丢了不影响资金/履约）。

### 2.8 搜索/推荐（同步延迟与可见性）

**事实链**：NOTE_INDEX/PRODUCT_INDEX 同步消费者；行为上报 `INSERT IGNORE` + eventId 复用；ItemCF 槽位键 `{item:noteId}`（CROSSSLOT 修复）；重建断点/外部版本/冲突跳过/死链清理；热搜快照 30 天。

**拷打问题**
1. 索引同步延迟期间：商品改动"数据库已改、搜索还是旧值"——**业务可接受窗口**是多少？有没有告知/兜底（详情页走 DB）？
2. 重建双通道期间新旧索引切换的用户可见性（别名/双写）策略——能讲清吗？

### 2.9 IM / 通知（未读与长连接）

**事实链**：IM 未读 Redis Hash `HINCRBY` + DB 定向更新（修过"整行覆盖"）；Redis 抖动未读不清零请求不 500（靠下次读写收敛）；DB 降级查询；conversationId 双人锁；通知未读 reconcile + 残留清理 + 保留 180 天。

**拷打问题**
1. 未读"Redis 与 DB 双写不原子"的收敛路径：以哪边为准？跨实例 KICK/未读乱序的场景演练做过吗？
2. SSE 50k 连接上限下，超限行为（拒绝新连接 vs 踢旧）与用户感知。

### 2.10 对账体系（谁是 truth？）

**事实链**
- inventory：每天 3 点，**Redis 为 truth** 修 MySQL（分桶 Lua 原子求和；Redisson 锁防并发重叠）。
- payment：每天 3 点，扫支付成功单修订单状态（补偿通知）。
- order：`OrderCalibrationJob` 干跑+指标（`orders.calibration.total`）。
- settlement：三方对账差异三态收敛。

**拷打问题**
1. **对账的元问题**：对账本身失败/连续失败谁监控？（XXL 失败重试策略？）对账修复动作是否有审计与二次确认？
2. 两两对账的方向不一致风险：inventory 以 Redis 为准、order 以事件表为准、payment 以支付单为准——**跨域的"对账套娃"会不会互相打架**？需要一张"真相源裁决表"（建议补文档）。

### 2.11 锁与缓存（形态不统一）

**事实链**：order 裸 SETNX+UUID+Lua；inventory 用 Redisson（对账锁）；im 用 pair lock；product **延迟双删**（1s 后二次删，防并发重建回填）；user **三层缓存失效**（本地重试 3 → MQ 兜底消费者）；Sentinel 降级规则 JSON（cart/payment/home/content）+ 网关流控规则。

**拷打问题**
1. 锁形态不统一（裸 SETNX vs Redisson）：**什么时候用哪个**要有说法；10s TTL 的裸锁在慢路径下的失效风险（2.1）。
2. 缓存一致性：延迟双删的 1s 是经验值——高并发下第二次删之前若又有旧值回填呢？（理论窗口）能讲清概率与兜底（TTL）。
3. 缓存击穿/热 key 的防护面（本地缓存 Caffeine？互斥重建？）——目前主要靠 Redis 单 Lua + TTL，是否有热点重建保护待核实。

---

## 3. 发现的真问题清单（按优先级）

| 级 | 问题 | 影响 | 建议动作 |
|---|---|---|---|
| **P0** | 预扣失败（含 CONFIRM 失败）无业务收口路径；inventory 无失败反馈事件 | 支付成功但未锁库的单可能进入履约 | ✅ **v1 已落**（2026-09-27，台账 §28）：失败事件+待付款自动关单+已付款 P1 告警+runbook；v2 留发货守卫/自动退款 |
| **P1** | TCC 空壳（`InventoryTccService` + `/tcc/*` 无调用方；无 Seata） | 简历/面试表述风险 | 删除 or 标注"未接入"；准备"为何不用 TCC"的说法 |
| **P1** | "Redis 为 truth" 的前提未文档化（持久化配置/丢失窗口） | 对账可能放大数据损失 | 写"真相源裁决表"；核对 AOF everysec 配置 |
| **P2** | 订单号生成 Redis 降级 = 时间戳+随机（概率碰撞） | 极端下单失败/重号 | 降级策略补唯一索引兜底验证 |
| **P2** | 支付消息丢失窗口 = 对账周期（≤24h） | 用户"付钱未确认"体验 | 缩短对账周期 or 加主动查询端点说明 |
| **P2** | 本地消息表回查无 userId 时"全库扫描兜底" | 分片下性能 | 校验是否已成热点；规范化分片键携带 |
| **P3** | 锁/幂等/缓存形态不统一（3 种锁、5 种幂等） | 学习与维护成本 | 出"机制选型表"（什么场景用什么） |
| **P3** | 评论通知发送失败仅 log | 通知丢失 | 可接受；补指标即可 |

---

## 4. 面试叙事：能打的 vs 不能吹的

**能打（有代码证据）**
- 下单双通道：事务消息（DB/MQ 原子）+ 本地消息表（Broker 异常补发）+ 双层幂等（msgId 标记 / 事件版本 Lua）
- 库存：分桶 + 单 Lua + 版本预留/恢复（跨 action 乱序防护）+ 预扣超时只回退真到期 + 对账（Redis 为 truth）
- 购物车：orderly 发送（hash key）+ 时间戳 CAS 跨分区兜底 + 事件表对账
- 缓存：延迟双删（异步调度不占请求线程）+ 三层失效链（本地重试→MQ 兜底）
- 关单：延迟消息 + Job 双保险 + 状态机 ALLOWED_FROM
- 删除竞态：NOTE_DELETE 墓碑防"推送复活"

**不能吹（会被追问穿）**
- ❌ "TCC/Seata"——代码里没接入（只有空壳端点）
- ❌ "消息零丢失"——实际是"至少一次 + 对账仲裁"，极端窗口有丢失
- ❌ "库存强一致性"——实际是"Redis 权威 + MySQL 最终一致"，对账有周期
- ⚠️ 讲告警运维可以一句带过，别当亮点（面试官要的是语义与设计取舍）

---

## 5. 待办与待核实

**待决策**：P0 收口方案（三选一：反馈事件 / 发货守卫 / 成文规则）；TCC 处置（删/标注）。
**待核实清单**
- [ ] 券核销失败→取消订单：取消与支付并发时的状态守卫（2.1）
- [ ] payment reconcile 与 order calibration 的扫描边界是否互补（2.3）
- [ ] 对账 Job 执行失败的监控与 XXL 重试策略（2.10）
- [ ] AOF everysec / RDB 配置是否与"Redis 为 truth"匹配（2.2）
- [ ] 本地消息表"全库扫描兜底"在分片下的实际执行路径（3-P2）
- [ ] 热点重建保护（击穿/热 key）现状（2.11）
- [ ] 未覆盖域：配置热更新（Nacos 刷新旧值）、灰度发布、时钟/ID、分片扩容——下一轮拷打对象

---

*审计方法：链路读码 + 语义提问；证据均为当前 main 工作区代码。gateway 归另一 AI，不在本文件范围。*


---

## 6. 第二轮核查记录（2026-09-27 晚，与 `docs/interview/xhs/` 59 篇专题对照）

> **结论先说**：第二轮选定的 5 个话题（配置热更新/灰度/时钟与 ID/分片扩容/超时预算）在专题库中**基本已被覆盖**（44/25/13·49/48·20 等）。
> 本轮价值 = ① 确认 3 个微缺口 ② 提出专题库不覆盖的**跨域综合问题** ③ 补强证据。
> **定位说明**：逐题深度看 `docs/interview/xhs/00-58`；本文件是**跨域语义综合**（机制全景 + 跨域问题），不重复逐题。

### 6.1 核查对照表
| 第二轮话题 | 已有专题 | 核查结论（本轮补证） |
|---|---|---|
| 配置热更新 | 44-Nacos（自认"未做运行时热更演练"） | **微缺口**：实际仅 `ChaosProperties` 是 `@RefreshScope`；业务参数全 `@Value` 静态（bucket 数/热点采样率/预扣 TTL/关单 delayLevel）→ "配置改了要重启" |
| 灰度 | 25-灰度与多版本 | 覆盖；服务侧**无灰度逻辑**（灰度集中在网关，归另一 AI）——口径与专题一致 |
| 时钟与 ID | 散见 13/39/07 | **微缺口**：无专项。补证：三种时间/ID 体系并存——雪花（MP `ASSIGN_ID`/`IdWorker`）+ Redis 日序列（orderNo）+ 墙钟（inventory 事件 `eventTime = System.currentTimeMillis()` 用于版本比较） |
| 分片与扩容 | 13/49 + `sharding-hash-migration-20260920.md`（取模→哈希迁移，682 行，倾斜 75%→16 片全活） | 覆盖；补证：映射缺失时 `getOrderByOrderNo` **抛"订单不存在"**（补录 Job 5 分钟窗口）；映射表独立数据源 + `ZoneAwareMappingDataSourceConfig` 双实现（多活开关） |
| 超时预算 | 48-Feign / 20-慢下游 | **微缺口**：无统一预算表。补证各服务 Feign 超时：500ms~5s 不等（order/payment 3s/5s；cart 3s/5s；search 3s/3s；inventory 500ms/3s；notification/im/user/product/analytics/coupon/counter 500ms/2s）；Sentinel 降级规则仅 5 个服务有 JSON |

### 6.2 跨域综合问题（专题库不覆盖的视角）
1. **时间体系三分裂**：一个订单在三处被"排序/去重"——雪花 ID（趋势递增、非严格单调）、Redis 日序列（orderNo）、墙钟（事件版本）。跨节点时钟偏移直接影响事件版本比较（乱序判定）。建议：**事件序号统一由业务态产生**（如 order 事件表序号进 payload），降低对墙钟的依赖。
2. **端到端超时预算缺失（含 MQ 消费链）**：HTTP 侧（Feign 各 2~5s + Sentinel 降级仅 5 服务）与 MQ 侧（重试 3/5 次）× "消费内 Feign 调用"叠加，**单条消息最长处理时间无文档化上限**；下游慢 → 重试 → 积压放大（专题 20 只覆盖 HTTP 侧）。
3. **配置漂移面**：Nacos `my-xhs-common.yaml`（库内两份副本）+ 各服务 `application.yml` + `sharding-config.yaml` = 三个配置源；"哪些必须重启、哪些热更"**无一张生效方式表**。
4. **映射缺位窗口的业务表述**：客服/支付回调按 orderNo 查询，在补录前（≤5 分钟）得到"订单不存在"——fail-closed 是设计选择，专题 49 有路由技术讨论，**缺"业务可见窗口"的表述**。

### 6.3 建议（小的、可选）
- ✅ **已补**（2026-09-27）：`59-配置生效方式表.md`、`60-时钟与ID体系.md`、`61-端到端超时预算.md`（已入专题索引）。
- 本文件与专题库的关系固定为：专题库=逐题深度；本文件=**机制全景 + 跨域问题 + 能打/不能吹**。

### 6.4 发现清单更新（追加到 §3）
| 级 | 项 | 说明 |
|---|---|---|
| P2 | 时间体系三分裂（事件版本依赖墙钟） | 多节点时钟偏移影响乱序判定 |
| P2 | 端到端超时预算缺失（HTTP×MQ 消费链） | 各层数值不一，无上限表 |
| P3 | 配置生效方式表缺失（热更仅 chaos） | 运维/面试口径都需要 |
| P3 | 映射缺位窗口业务表述缺失（≤5min"订单不存在"） | 需业务侧知晓 |


---

## 7. 第三轮：全链路重试放大总账（2026-09-27）

> 视角：把 HTTP / MQ / Job / 补偿 四层重试叠乘算清"最坏尝试数"，找放大点。
> 结论：**HTTP 侧有优秀护栏（全局不重试），但 MQ Producer 默认重试未记账、Outbox 补发策略三种做法不统一、全部退避无抖动**。

### 7.1 各层重试策略盘点（事实）
| 层 | 策略 | 评价 |
|---|---|---|
| HTTP/Feign | **全局 `Retryer.NEVER_RETRY`**（`FeignSafeConfig`，注明"非幂等接口重试=资损"） | ✅ 正确决策，无放大 |
| MQ Producer | **未显式配置 → 客户端默认**：sync 失败默认重试 2 次（最坏 3×3s=9s 阻塞）；async 默认 2 次 | ⚠️ 默认行为**全仓无一处写明** |
| MQ Consumer | `maxReconsumeTimes`：19 组=3、7 组=5 → 总处理 4/6 次后 DLQ（仅告警，无重放路径） | ✅ 有界 |
| 消费内重试 | inventory 3 次(50/100ms)、notification 聚合 3 次(50/100/200ms)、CounterBuffer N 次(100ms×i)、CacheHelper 3 次 | ⚠️ 固定退避、**无抖动** |
| Outbox 补发 | coupon/inventory：`@Scheduled(fixedRate=5000)` **无退避无上限**；order 本地消息表：30s→480s 指数×5→死信+每小时死信扫描×3 | ⚠️ 三种做法不一致 |
| 补偿兜底 | order 补偿消息 syncSend 失败→Redis Set(TTL 24h)+每分钟回放(每轮≤20) | ✅ 有 TTL+cap |
| Feed 补偿 | 每 60s 一轮、上限 100 轮（≈1.7h）→ 终态 | ✅ 有上限（无退避） |
| XXL-Job | 代码无重试（管理端失败告警） | ✅/⚠️ 失败即人工 |

### 7.2 最坏尝试数总账（按链路）
1. **下单创建**：producer 半消息(≤3) → inventory 消费(≤6)×消费内 DB(≤3) = **最坏 18 次 DB 尝试**；耗尽后发失败事件(≤3) → order 收口(≤4)。
2. **支付成功**：payment `syncSend` **最坏 9s 阻塞回调线程**（3 次×3s，默认重试）→ order 消费(≤4)＋每轮通知 asyncSend(≤3) → 丢了由对账兜底。
3. **Feed 推送**：producer async(≤3) → home 消费(≤4) → 失败由 content 补偿 Job **≤100 轮**（固定 60s）。**勘误（2026-09-27 核实）**：fan-out 实际走 Redis ZSet 分页（500/批 + Pipeline），**循环内零 Feign**；`AnalyticsFeignClient` 字段为死代码（已删除）。原"400 次×Feign"表述不成立。
4. **券领取**：consumer(≤6)；OutboxSenderJob 每 5s 重发（仅发送失败期持续）。
5. **库存 Outbox**：每 5s 重发（broker 故障期持续捶打，无退避）。

### 7.3 放大热点 Top 3
1. ~~Feed 链 ≈400 次×Feign~~ → **勘误**：无 Feign 放大；真实问题 = 补偿固定 60s 轮询（≤100 轮）→ **已加退避 60s→600s+抖动**（P3，2026-09-27）；
2. **coupon/inventory Outbox 5s 无退避**（broker 故障时每 5s×200 条捶打，恢复瞬间集中涌入）→ 建议改 `next_retry_time` 指数退避+抖动（对齐 order 本地消息表）；
3. **Producer 默认重试未记账**（sync 最坏 9s；"超时≠失败"可能双发，靠消费者幂等/对账兜）→ 显式配置并写进《61-端到端超时预算》。

### 7.4 全局问题：无抖动（jitter）
所有退避均为固定值（50/100ms、30s→480s、5s、60s）——多实例/多服务同时重试会**对齐共振**，下游恢复瞬间被二次打垮。建议统一加 ±20% 抖动。

### 7.5 建议（并入 61 题预算表）
- 预算表补三行：**Producer 默认重试**、**Outbox 补发策略**、**DLQ 终态（人工）**；
- coupon/inventory OutboxSenderJob → next_retry_time 退避+cap（对齐 LocalMessageRetryJob）；
- Feed 补偿加退避+抖动；analytics 调用批量化；
- DLQ 重放 runbook（当前只告警）。

> **✅ 修复记录（2026-09-27）**：① 11 个服务 Producer 重试显式固定为 2（search 补 `send-message-timeout`）；
> ② coupon/inventory Outbox 增 `retry_count`/`next_retry_time` + 30s→480s 指数退避 + ±20% 抖动（迁移 `coupon/V1`、`inventory/V3`；init-all×2 与 migration 双向零差异）；
> ③ 退避抖动统一：`RetryBackoffUtils` + 本地消息表/库存消费/计数 Buffer/通知聚合/CacheHelper 接入；
> ④ 61 题预算表已更新。
> **P3 收口（2026-09-27 晚）**：⑤ Feed 补偿退避（`t_local_message.push_next_retry_time`，迁移 `content/V4`；60s→600s 指数+抖动）+ 死代码清理（`FeedPushConsumer` 删除未用的 AnalyticsFeignClient）；
> ⑥ DLQ 重放 runbook：`docs/reports/dlq-replay-runbook-20260927.md`（原则/定位/限速重放/幂等核对表/演练）。

### 7.6 发现清单更新
| 级 | 项 |
|---|---|
| P2 | Producer 客户端默认重试未显式配置/未文档化（sync 最坏 3×3s=9s） |
| P2 | coupon/inventory Outbox 补发无退避无上限（固定 5s 捶打） |
| P2 | 全部重试退避无抖动（共振风险） |
| P3 | Feed 补偿链路量级（≈400 次消费×Feign）未写入容量文档 |
| P3 | DLQ 无重放路径（仅告警，人工） |


---

## 8. 第四轮：发布中断与在途请求（2026-09-27）

> 视角：发布（关旧起新）窗口里，在途请求/消息/内存状态/路由缓存各会发生什么。
> 结论：优雅停机体系完整且顺序大多正确，但发现 **1 个真实缺陷**（线程池关闭早于 HTTP 排空）+ 3 个待优化窗口。

### 8.1 事实链（代码/字节码证据）
- **部署方式**：Java 服务**不在 docker-compose**（compose 仅中间件）；由 `scripts/release-service.sh` 以"停旧-起新"发布（版本目录 + `current` 软链 + SIGTERM + 健康校验 + 自动回滚），脚本自注：**"有损发布（约 15-20s 窗口）；真蓝绿未实现"**。
- **停机流程**（`common/shutdown/GracefulShutdownListener`；Spring 6.1.6 `doClose` 字节码核实）：
  SIGTERM → `doClose()` **先发 ContextClosedEvent（偏移 58）→ 后停 SmartLifecycle/web（偏移 89）** → 监听器：① Nacos deregister（ServiceRegistry 反射，失败降级 TTL）② sleep 10s ③ 自定义 ShutdownHook（CounterBuffer 双 Buffer 刷盘等）④ `shutdownExecutors()` 关闭所有 `ExecutorService` bean → 然后 web 停收 + 排空（≤30s）→ destroyBeans（@PreDestroy：库存异步池/号段预加载/IM·SSE 订阅）→ 退出。
- **脚本兜底**：`stop()` 等待端口释放 ≤60s（覆盖 10s+30s 优雅窗口）；`start()` 健康校验 ≤60s + 防"旧进程占端口"假成功 + 失败自动回滚上一版。

### 8.2 拷打结论
1. **摘流与停服的先后**：实际 = **先摘除、10s 后停服**（监听器先于 web 停服执行）→ **顺序正确**；但监听器注释把顺序写反（声称"1 停服 2 排空 3 事件"）→ 需更正注释。
2. **~~🔴 线程池关闭时机（真缺陷）~~ → ✅ 已修（2026-09-27）**：`shutdownExecutors()` 原在 web 排空**之前**执行（窗口内依赖 ExecutorService bean 的请求会 `RejectedExecutionException`）；现移至新增 `common/shutdown/ExecutorShutdownProcessor` 的 `@PreDestroy`（destroyBeans 阶段 = web 排空之后），监听器注释同步更正（实际顺序：先摘流等待 → web 排空 → destroy 关池）。
3. **10s 传播等待是否够**：Spring Cloud LoadBalancer 实例缓存默认 TTL 35s；Nacos 2.x 为推送式刷新（通常秒级）。需**发布演练实测**"摘除→零路由"时长——列入待核实。
4. **单实例硬窗口**：停旧→起新→健康通过之间硬不可用；健康窗口 60s（冷启动可 >40s）→ 实际窗口可能大于注记的 15-20s。改进：双实例发布（脚本已支持 `INSTANCE_ID` 第二实例，缺流量切换）或 K8s 探针方案（L1-7）。
5. **IM 路由 90s 陈旧窗口**：实例停机无批量注销 → 死实例路由最长 90s（消息靠 DB 持久化 + 重连同步兜底）。改进：停机钩子按本实例在线用户批量注销（复用 `UNREGISTER_SCRIPT`）。
6. **在途消息/任务**：MQ 未 ACK 消息重投（幂等兜底 ✓）；XXL 运行中任务中断=失败告警（仅结算类配置 misfire 补跑）；双 Buffer 刷盘/号段/订阅均有钩子 ✓。

### 8.3 修复清单（建议）
| 优先 | 项 | 动作 |
|---|---|---|
| **P1** | ~~shutdownExecutors 时机~~ | ✅ 已修（2026-09-27，ExecutorShutdownProcessor + 注释更正） |
| P2 | 发布演练实测 | 记录"摘除→零路由"与"停→健康通过"实际时长，验证 10s/窗口假设 |
| P2 | IM 实例级路由注销 | shutdown hook 批量 UNREGISTER 本实例在线用户 |
| P3 | 双实例发布切换 | 复用 `INSTANCE_ID` 脚本 + 流量切换；或按 L1-7 K8s 探针方案 |
| P3 | XXL misfire 盘点 | 对账/清理类任务补 misfire 策略评估 |


---

## 9. 第五轮：缓存失效时序（2026-09-27）

> 视角：写后读旧值/失效失败/重建竞态——全站缓存失效链路盘点。
> 结论：读路径有教科书级实现（product）；**失效可靠性三档不统一**，另有 1 处**幽灵失效代码**与 1 个**兜底单点**。

### 9.1 全站缓存失效台账（现状）
| 模式 | 使用方 | 可靠性 |
|---|---|---|
| **CacheHelper**（30min+随机抖动、空值 2min、分布式锁防击穿、删除重试 3 次、延迟双删、失败发 `CACHE_EVICT_TOPIC` 兜底） | user、content(NoteDetail) | ✅ 最全；TTL 抖动+锁+MQ 兜底齐全 |
| **自研多级缓存**（布隆→逻辑过期 30min+锁异步刷新→DB 锁+双重检查→空值；延迟双删 1s） | product(SPU) | ✅ 读路径最强（SWR）；❌ **失效失败仅 log**（`evictSpuCache` 无重试/MQ）→ 靠逻辑过期/TTL 收敛（最长 30min 旧值） |
| **回声保护型 Canal**（UPDATE/INSERT **跳过**，仅 DELETE 清） | inventory（桶/总量 Key） | ✅ 设计正确：MySQL 是 L2 镜像，删除会导致"未初始化"+滞后快照回填 **超卖**；out-of-band 改动走 `/api/inventory/reinit`；❌ 类 javadoc 仍描述旧行为（UPDATE/INSERT 删缓存）→ 文档漂移 |
| **TTL-only**（无主动失效） | search suggest（1h，`search.suggest.cache-ttl-seconds`）、product 类目树（2h+预热） | ⚠️ 可接受但需台账注明（类目树更新路径未发现删除——待核实是否低频） |
| **幽灵失效** | order `myxhs:order:info:{orderId}`：**9 处删除、全仓无任何写入方** | ❌ 死代码（no-op）；**复核注记**：项目已有登记（T-031/T-066 同族"登记观察"，`docs/test-3/cases/G5-trade/G5-01-order.md`）——维持观察，本轮不清理 |

### 9.2 拷打结论
1. **写后读窗口量化**：product 更新后 1s 二次删；重建 >1s 或删失败 → 逻辑过期 30min 内返回旧值（异步刷新修复）；user/content 靠"延迟双删+重试+MQ"最终收敛；inventory 不删（回声保护）。
2. **兜底通道单点（P2）**：`CACHE_EVICT_TOPIC` 消费者**只有 user 一个消费组**；content 的失效兜底消息实际靠 user 服务消费（RocketMQ 保留可延迟处理，但语义未文档化；user 长期不可用则兜底积压）。
3. **幽灵缓存（已知登记项）**：order:info 9 处删除无写入方——项目早有登记（T-031/T-066 同族"登记观察"）；本轮复核确认，**维持观察不改动**（避免与既有决策冲突）。
4. **失效可靠性不一致（P2）**：product 的 `evictSpuCache` 无兜底 vs user/content 的 CacheHelper 三重保障——同一系统两套标准。

### 9.3 修复清单
| 优先 | 项 | 状态 |
|---|---|---|
| P2 | product 失效兜底 | ✅ 已修（2026-09-27）：`evictSpuCache` 删除失败 → 发 `CACHE_EVICT_TOPIC`；product 补 producer 配置（此前无 MQ） |
| P2 | 兜底消费者单点 | ✅ 已修：product/content 各补 `CacheEvictConsumer`（独立消费组）；user/product/content 三组并存（删除幂等） |
| P3 | order:info 幽灵删除 | ⏸️ **维持观察**（已有登记 T-031/T-066 同族，不改动） |
| P3 | inventory javadoc 漂移 | ✅ 已修（改为"回声保护：UPDATE/INSERT 跳过，仅 DELETE 清"，含 T-066 溯源） |
| P3 | 缓存台账成文 | ✅ 已建：`docs/reports/cache-invalidation-ledger-20260927.md`（含幽灵键与兜底通道消费组清单） |

> 交叉引用：专题库 `02-缓存一致性.md` / `27-商品多级缓存.md` 已覆盖读路径设计；本轮新增增量 = 幽灵缓存/兜底单点/失效可靠性不一致/回声保护文档漂移。
