# D0：缺口补齐实施方案

> 版本：v0.1 | 日期：2026-08-10
> 定位：把 D0 审计的 A1-A6（业务）+ B1-B10（观测）缺口**落实为"谁改什么、怎么验"**的可执行方案。
> 原则：改动现有 16 服务/config → **归属对应服务 Owner，AI 项目只提需求+验收，不代改**；优先 **append-only 事件流水**；每项过 **full-chain-test 回归门禁**。
> 依据：`D0-audit.md` + `D0-distributed-diagnosis.md` + 本日代码实测（精确写点 file:line）。

---

## 0. 关键新发现（探索中证实，修正原判断）
1. **`t_user_behavior.behavior_type` 语义冲突是"数据已错标"的 bug（非二选一）**：代码 `BehaviorRequest.java:20` = `1曝光/2点击/3点赞/4收藏/5评论/6分享/7停留`（`@Max=7`），而 SQL 表注释 = `1浏览…6搜索`。代码按 code 枚举写库 → **现存数据可能已错标，需先对账 + 统一枚举**，否则 A5/A6 建立在错数据上。
2. **浏览/曝光的实际落库点在 `my-xhs-search`（`BehaviorReportConsumer`）**，content/home 均无直写点。
3. **A2 无独立审核写点**：`auditStatus` 在发布时直接置 APPROVED，无异步审核通过路径（`NoteService.publishNote/publishDraft`）。
4. **Follow / Cart 均 Redis 权威**：MySQL t_follow / t_cart_item 是异步兜底（try-catch"不影响结果"）→ **取关/加购事件须记在 Redis 权威点，不能依赖 MySQL 写点**。
5. **A6 曝光有两个表面**：`behavior_type=1=曝光` 只覆盖**推荐流**（RecommendService），`getFollowFeed` 是**关注流**——关注流曝光需自建，不能只"复用"。
6. **A1 failCode 来源未定义**：`handlePayFailInternal` 只收 (orderId,userId,paymentNo)，失败原因需从支付渠道回调(pay())透传。

---

## 1. 实施排序与依赖

### Phase 1 —— 🔴 高（阻塞 A1/A2/B1/B2/B4 场景，D1 前）
| 顺序 | 缺口 | 依赖 | 为什么先做 |
|:--:|------|------|-----------|
| 1 | **A1 支付失败事件** | — | A2 场景核心数据 |
| 2 | **A5 电商漏斗（商品浏览+加购）** | 先统一 behavior_type 枚举 | A1 场景核心数据 |
| 3 | **B1 mysql-exporter（含 B6 复制延迟）** | — | B1/B4 排障数据 |
| 4 | **B2 slow_query_log 管道** | — | B1/B4 排障数据 |
| 5 | **B7 MySQL error log / innodb** | — | 死锁定位 |

### Phase 2 —— 🟡 中（D2 前）
A2 published_at / A3 取关流水 / A4 退款sku / A6 曝光 / B3 redis-exporter / B4 DLQ消费者 / B8 RocketMQ / B9 Canal / B10 线程转储

### Phase 3 —— 🟢 低（D6/D7 前）
B5 VictoriaMetrics remote_write

---

## 2. 业务缺口明细

### A1. 支付失败事件（失败码+failStage+channelResp）
- **现状写点**：`my-xhs-payment/.../service/PaymentService.java:333-358` `handlePayFailInternal`（UPDATE t_payment SET status=2），调用点 `pay()` 285 行；成功/失败均经 `sendPayResultMq`（976 行）。
- **具体改动**：
  - **failCode/failStage/channelResp 来源需透传**：`handlePayFailInternal` 现在只收 (orderId,userId,paymentNo)，须从支付渠道回调 `pay()` 处捕获失败原因，作为入参传入。
  - 在 `if(updated>0)` 块内（336-358）追加失败事件：**新增 append-only 表 `t_payment_fail_event`**（order_id, payment_no, fail_code, fail_stage, channel_resp, created_at）；可同时扩展 `sendPayResultMq` 失败消息携带 failCode。
  - 不改 t_payment 原字段（append-only 优先）。
- **归属**：my-xhs-payment。
- **验证**：模拟支付失败 → 事件表有记录 + failCode 正确；`grep` 确认新字段。
- **回归**：payment 链路 full-chain-test。

### A4. 退款商品明细（skuId/itemId）
- **现状写点**：`PaymentService.java:420-442`（`refund()` 内 new Refund() + INSERT t_refund）。
- **具体改动**：
  - **⚠️ 退款可能跨多商品**：单 `sku_id` 列可能不够 → 建议 **`t_refund_item` 子表**（refund_id, sku_id, item_id, quantity）或 item 级退款，而非单列。
  - 来源：`RefundRequest` 透传 skuId(s)（当前 DTO 无此字段，需加）或经 `OrderFeignClient` 查 `t_order_item`。
- **归属**：my-xhs-payment（+order 侧透传）。
- **验证**：退款后有商品级明细；商品退款率可算。
- **回归**：payment+order 退款链路。

### A3. 关注/取关事件流水
- **现状**：Follow 是 **Redis 权威**（`FollowService.unfollow` 141-187：Lua 脚本移除列表+计数），MySQL `t_follow` 删除是 try-catch 兜底（"不影响取关结果"），`FollowMapper:21` @Delete。
- **具体改动**：**在 Redis 权威成功点（unfollow Step A 成功后）追加取关事件**，而非 MySQL delete 处（否则 MySQL 失败丢事件）→ 新增 append-only `t_follow_hist`（user_id, follow_user_id, action(FOLLOW/UNFOLLOW), created_at）。
- **⚠️ 需确认**：`t_follow` 作为"当前关注关系"是否可靠（异步兜底可能缺行）→ 对账 job（FollowService:496/548）需一并评估，关注关系权威是 Redis，Agent 查"净粉丝/流失"须明确数据源。
- **归属**：my-xhs-analytics。
- **验证**：unfollow 后能查取关历史；净粉丝增长可算。
- **回归**：analytics/用户关注链路。

### A5. 电商漏斗（商品浏览 + 加购事件流）★核心
- **前置**：**统一 behavior_type 枚举**（SQL 1浏览 vs 代码 1曝光 冲突）→ 立项对齐。
- **商品浏览事件（sku）**：现状无 sku 维浏览；`t_user_behavior` 的浏览是 note 维（search/recommend）。**新增 sku 维"商品浏览"事件**（append-only：user_id, sku_id, ts）——从商品详情页入口上报（content/product 详情读接口处）。
- **加购事件流**：`CartService.addToCart`（`my-xhs-cart/.../service/CartService.java:117`）是 **Redis 权威**；在成功点（result 校验后、147 行 `sendCartSyncEvent` 附近）追加加购事件（user_id, sku_id, qty, ts）——非 DB insert，随 Redis/MQ 链路。
- **漏斗口径**：商品浏览(sku) → 加购 → 下单(t_order) → 支付(t_payment)。
- **归属**：my-xhs-analytics（事件表）+ my-xhs-cart（加购）+ content/product（商品浏览上报）。
- **验证**：能算四环节量 + 断点。
- **回归**：cart/order 链路 + 行为事件链路。

### A2. published_at / audited_at
- **现状写点**：`NoteService.java:93-97`（publishNote）、`401-406`（publishDraft）；`Note` 实体无此字段；无独立审核写点。
- **具体改动**：`Note` 实体补 `publishedAt/auditedAt`；publishNote 入库前 set；publishDraft update wrapper 加 `.set(publishedAt,now).set(auditedAt,now)`。
- **归属**：my-xhs-content。
- **验证**：发布后两字段有值；A3 发布/审核时点可分析。
- **回归**：content 发布链路。

### A6. 曝光 / Feed 分发
- **现状写点**：写侧 `FeedPushConsumer.java:179`（ZADD FEED_INBOX）+ 大V拉 91/141；读侧 `FeedService.getFollowFeed` 82-107（noteIds 100-102）。
- **具体改动**：在 `getFollowFeed` 拿到 noteIds 后（100-107，aggregateFeed 前）上报**关注流曝光**事件（note_id, user_id, ts）。
- **⚠️ 两套表面**：`behavior_type=1=曝光` 只覆盖**推荐流**（RecommendService，已有）；**关注流（getFollowFeed）曝光需自建**，不能只"复用"。
- **归属**：my-xhs-home（关注流曝光）+ 复用 search 推荐流（已有）。
- **验证**：能区分"关注流/推荐流曝光减少" vs "互动质量差"。
- **回归**：home feed 链路。

---

## 3. 观测缺口明细

### B1+B6. mysql-exporter（含复制延迟指标）
- **现状**：Prometheus（`config/prometheus/prometheus.yml`）只 scrape 服务端口，无 mysql-exporter target。
- **改动**：docker-compose 加 mysqld_exporter（**需 MySQL 账号授权：PROCESS / REPLICATION CLIENT / REPLICATION SLAVE / performance_schema**）→ 采集 `mysql_slave_status_seconds_behind_master` / `Io_Running` / `Sql_Running`（B6）；prometheus.yml 加 target。
- **归属**：基建/config。
- **验证**：Prometheus 查到复制延迟指标；B4 场景可查。

### B2. slow_query_log 管道
- **改动**：MySQL 开 `slow_query_log=ON, long_query_time=0.5` → 日志采集 → Logstash（`config/logstash/logstash.conf` 已有，15044 tcp / 15045 beats）→ ES。
- **⚠️ Filebeat 未确认**：compose 仅有 Logstash，未发现 filebeat 服务/配置；慢查询需确认日志采集器（Filebeat 或 微服务直写 Logstash TCP），否则管道缺一环。
- **归属**：基建/config。
- **验证**：慢查询进 Kibana；B1 场景可查。

### B7. MySQL error log / innodb status
- **改动**：error log 采集管道 + 开 `innodb_status_output`（或 performance_schema 锁等待）。
- **归属**：基建/config。
- **验证**：死锁 ERROR 1213 可定位。

### B3. redis-exporter / B8. RocketMQ exporter / B9. Canal metrics / B10. JVM 线程转储
- **B3**：redis_exporter 接入 Prometheus（Redis 内存/命中/淘汰）。
- **B8**：RocketMQ exporter（broker 主从/切换指标）接入 Prometheus。
- **B9**：Canal server metrics + 告警（binlog→MQ 延迟/断连）。
- **B10**：按需 jstack/Arthas（`/data/workspace/arthas` 已有源码）死锁检测；SegmentIdGenerator/DynamicDataSource 热点。
- **归属**：基建/config（B10 各服务）。
- **验证**：各指标在 Prometheus/Grafana 可见。

### B4. DLQ 消费者 / B5. VictoriaMetrics
- **B4**：基于 `my-xhs-common/mq/DlqMessageHandler` 基类建实际 `%DLQ%` 消费者 + 告警钩子。
- **B5**：prometheus.yml 加 remote_write → VM 8428。

---

## 4. 回归与门禁
- 每项补齐后重跑受影响链路 **full-chain-test**（`full-chain-test.sh` / `-v2` / `-v3`），确保不破坏既有 **46 项 P0 修复**。
- 补齐后 §1.5 六场景（含新增 B4）必须**有事实数据 + 人工基线**，才允许进入 D1。
- 数据缺口按 **append-only 事件流水**优先，降低对既有表/逻辑的回归风险。

## 5. 待决策 / 风险
| # | 项 | 说明 | 需拍板 |
|:--:|----|------|:--:|
| 1 | **behavior_type 枚举统一 + 现存数据对账** | 代码 1曝光…7停留 vs SQL 1浏览…6搜索；**现存 t_user_behavior 可能已错标**，非二选一，须先对账 | ✅ 需定+对账 |
| 2 | A5 漏斗"浏览"口径（sku 商品浏览 vs 复用曝光）| 决定漏斗第一环语义 | ✅ 需定 |
| 3 | A6 曝光：关注流自建 + 推荐流复用 | 两套表面分开 | ✅ 需定 |
| 4 | A3 取关事件记 Redis 权威点 + t_follow 可靠性确认 | 关注关系权威是 Redis | ✅ 需定 |
| 5 | A4 refund_item 子表 vs 单列 | 退款可能跨多商品 | ✅ 需定 |
| 6 | 事件表建在哪个服务库（analytics 统一 vs 各服务）| 数据归属 | ✅ 需定 |

> **提醒**：这些改动**动现有 16 服务/config**，需对应服务 Owner 认领；本方案是"需求+验收"清单，非 AI 侧直接实现。
