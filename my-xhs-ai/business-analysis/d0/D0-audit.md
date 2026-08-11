# D0 数据与观测缺口审计

> 依据：逐项去 my-xhs 现有源码/配置核对（2026-08-10 实测），非照抄 PLAN §8。
> 目的：把 §8 的缺口核实为"确认缺失 / 修正 / 已有"，并给出可执行清单。
> 说明：审计发现 §8 原 10 项外还缺 A6（曝光数据），现共 12 项。
> 每项标注证据、影响场景（§1.5）、方案、归属、优先级。

---

## A. 业务数据缺口（5 项）

### A1. 支付失败原因 —— ✅ 确认缺失（关键）
- **证据**：`my-xhs-payment/.../entity/Payment.java` 仅 `status(0/1/2/3)` + `payType`，无 failCode/failStage/channelResp；`grep failReason|failCode` 为空。
- **影响场景**：A2 支付成功率下降归因（只能答"哪个渠道降"，答不了"为什么"）。
- **方案**：补支付失败事件（append-only：失败码 + 失败阶段 + 渠道响应摘要），事件流水而非改字段。
- **归属**：my-xhs-payment | **优先级：🔴 高**

### A2. note.published_at / audited_at —— ✅ 确认缺失
- **证据**：`my-xhs-content` 无 publishedAt/auditedAt 字段（grep 为空），仅 created_at。
- **影响场景**：A3 内容互动骤降、发布/审核时点分析。
- **方案**：补 `published_at` / `audited_at` 字段（或发布/审核事件流水）。
- **归属**：my-xhs-content | **优先级：🟡 中**

### A3. 关注事件流水 —— ⚠️ 修正（比原判断更乐观，但缺口仍在）
- **证据**：`my-xhs-analytics/entity/Follow.java` **已持久化**到 MySQL `t_follow`（含 createdAt），`FollowService` 处理 follow/unfollow。→ 原"关注仅在 Redis"不准确，**当前关系有 MySQL 持久化**。
- **缺口本质**：`unfollow` 走 `followMapper.deleteByUserIdAndFollowUserId` **DELETE 删行** → **无取关历史**，无法算"净粉丝增长/流失/取关分析"。
- **影响场景**：内容增长诊断、粉丝流失归因。
- **方案**：关注/取关改**事件流水**（append-only，保留 unfollow 记录），复用现有 t_follow 结构加事件表。
- **归属**：my-xhs-analytics | **优先级：🟡 中**

### A4. 退款商品级明细 —— ✅ 确认缺失
- **证据**：`my-xhs-payment/entity/Refund.java` 有 orderId/paymentId/reason/refundType/refundChannel，**无 skuId/itemId**。
- **影响场景**：商品退款率算不准、退款归因。
- **方案**：t_refund 补商品明细（或关联 t_order_item）。
- **归属**：my-xhs-payment | **优先级：🟡 中**

### A5. 转化漏斗粒度 —— ⚠️ 修正（表存在，但缺电商漏斗环节 + 领域错配）
- **证据**：`sql/mysql-user-init.sql` **存在 `t_user_behavior`**（behavior_type: 1浏览/2点赞/3收藏/4评论/5分享/6搜索 + 代码中 type7 停留时长，带 created_at/note_id）。→ 原"无行为表"**不准确**。
- **缺口本质（深度修正）**：
  1. **领域错配**：`t_user_behavior` 的"浏览"是 **note_id 维度（内容域）**；电商漏斗（加购/下单/支付）是 **sku 维度（电商域）**——两条不同用户旅程，**笔记浏览不可用于电商漏斗**。
  2. **缺商品浏览事件**：现状无 sku 维度的"商品浏览"事件，漏斗第一环缺失。
  3. **缺加购事件流**：`t_cart_item` 只有当前状态（updated_at 为最后变更），无历史加购事件，无法还原时段加购量。
  4. 下单(t_order)/支付(t_payment)可直接聚合，无需行为事件。
- **影响场景**：A1 订单下降归因（漏斗断点）。
- **方案**：补 **sku 维度商品浏览事件** + **加购事件流**（append-only，与内容行为分开的电商漏斗事件表）。
- **归属**：my-xhs-analytics / home | **优先级：🔴 高**（A1 是核心场景）
- **附带发现**：代码 `BehaviorRequest` 提到 type7（停留时长），与 SQL 建表 1-6 不一致，需一并核对。

### A6. 曝光 / Feed 分发数据 —— ⚠️ 覆盖缺口（审计补入）
- **证据**：全仓库无 impression/exposure 表（grep 无结果）；Feed 收件箱(t_user_feed_inbox/feed:timeline) 只记录投递，无"曝光→互动"记录。
- **影响场景**：A3 内容互动骤降——无法区分"内容质量差" vs "分发/曝光减少"。
- **方案**：补曝光事件（note 曝光 + userId + 时间）或基于 feed 投递+阅读行为估算。
- **归属**：my-xhs-home / content | **优先级：🟡 中**

---

## B. 观测缺口（5 项）

### B1. mysql-exporter —— ✅ 确认缺失
- **证据**：config/deploy/docker-compose 无 exporter。
- **影响**：死锁/慢查询/锁等待不可采集（B1 排障无数据）。
- **方案**：部署 mysqld_exporter，接入 Prometheus。
- **优先级：🔴 高**

### B2. slow_query_log 管道 —— ✅ 确认缺失
- **证据**：无 slow_query / long_query_time 配置。
- **影响**：慢查询无法进 Kibana（B1 排障无数据）。
- **方案**：开启 slow_query_log(≥0.5s) → Filebeat → Logstash → ES。
- **优先级：🔴 高**

### B3. redis-exporter —— ✅ 确认缺失
- **证据**：无 redis_exporter 配置。
- **影响**：Redis 内存/命中/淘汰不可采集。
- **方案**：部署 redis_exporter 接入 Prometheus。
- **优先级：🟡 中**

### B4. DLQ 消费者 —— ⚠️ 部分存在（基础在，无实际消费）
- **证据**：`my-xhs-common/mq/DlqMessageHandler.java` 是**模板基类**（日志 + DLQ 指标），但仅被 `MetricsAutoConfiguration` 注入；**无服务继承并订阅 `%DLQ%` topic 实际消费**。
- **影响**：死信消息只记录/计数，无法自动排查重投（B2 部分）。
- **方案**：基于 DlqMessageHandler 基类建实际 DLQ 消费者（订阅各 `%DLQ%` 组）+ 告警钩子。
- **优先级：🟡 中**

### B5. VictoriaMetrics 未接线 —— ✅ 确认缺失
- **证据**：config/prometheus 无 remote_write 到 8428。
- **影响**：长期趋势/容量分析缺失。
- **方案**：加 remote_write → `http://...:8428/api/v1/write`。
- **优先级：🟢 低**

### B6. 主从复制延迟指标 —— ✅ 确认缺失（新增，B4 场景）
- **证据**：`sql/init-replication-{user,content,order,inventory}.sql` 有主从(GTID)，但 Prometheus 无 mysql-exporter → **无 replication lag 指标**。
- **影响**：B4 主从复制延迟归因无数据。
- **方案**：mysql-exporter 采 `Seconds_Behind_Master` / Io_Running / Sql_Running。
- **优先级：🔴 高**

### B7. MySQL error log / innodb status 采集 —— ✅ 确认缺失（新增）
- **证据**：无 error log 采集管道；未开 innodb_status_output。
- **影响**：MySQL 死锁（ERROR 1213）无法定位。
- **方案**：error log → 采集管道 + 开启 innodb_status_output / performance_schema 锁等待。
- **优先级：🔴 高**

### B8. RocketMQ broker 指标（主从/切换）—— ✅ 确认缺失（新增）
- **证据**：`config/rocketmq/broker.conf`(ASYNC_MASTER) + `broker-slave.conf`(SLAVE)，但无 broker exporter 指标接入。
- **影响**：RocketMQ 主从选举/切换无法诊断。
- **方案**：RocketMQ exporter / Dashboard 指标接入 Prometheus。
- **优先级：🟡 中**

### B9. Canal 监控指标 —— ✅ 确认缺失（新增）
- **证据**：`config/canal/conf/{inventory,note,product}_instance`，无 Canal server metrics 接入。
- **影响**：binlog→MQ 断连/延迟无法诊断（下游同步失效）。
- **方案**：Canal server metrics + 告警。
- **优先级：🟡 中**

### B10. JVM 线程转储 —— ⚠️ 需补（新增）
- **证据**：`SegmentIdGenerator`/`DynamicDataSource` 有 synchronized/锁竞争热点；无自动线程转储。
- **影响**：Java 死锁无法定位。
- **方案**：按需 jstack/Arthas + 死锁检测（arthas 已在 `../arthas`）。
- **优先级：🟡 中**

---

## C. 审计修正汇总（相对 PLAN §8 / 之前分析）
| 项 | 原判断 | 审计后修正 |
|----|-------|-----------|
| 关注持久化 | "仅 Redis 非持久化" | **MySQL t_follow 已持久化当前关系**；真实缺口=无取关历史(DELETE) |
| DLQ 消费者 | "无 DLQ 消费者" | **有基类+指标，但无实际消费者**；需基于基类补消费端 |
| 转化漏斗(A5) | "无行为表" | **t_user_behavior 存在**，但只覆盖内容行为(note 维度)；**电商漏斗缺：sku 商品浏览事件 + 加购事件流**（且笔记浏览不可用于电商漏斗——领域错配）|
| 曝光数据(A6) | 未审计 | **补入**：无曝光/Feed分发数据，A3 需 |

> 其余 8 项与 §8 一致，均为确认缺失。

---

## D. 可执行清单（优先级排序）

### 🔴 高（阻塞 A1/A2/B1/B2/B4，D1 前必须就绪）
- [ ] A1 支付失败事件流水（payment）→ A2 场景数据
- [ ] A5 电商漏斗：补 **sku 商品浏览事件 + 加购事件流**（append-only，与内容行为分开）（analytics/home）→ A1 场景数据
- [ ] B1 部署 mysql-exporter（含 **B6 复制延迟指标**）→ B1/B4 排障数据
- [ ] B2 开启 slow_query_log + Filebeat 管道 → B1/B4 排障数据
- [ ] B7 MySQL error log / innodb status 采集 → 死锁定位

### 🟡 中（D2 前就绪）
- [ ] A2 note.published_at/audited_at（content）
- [ ] A3 关注/取关事件流水（analytics）
- [ ] A4 退款商品明细（payment）
- [ ] A6 曝光/Feed 分发数据（home/content）→ A3 场景
- [ ] B3 redis-exporter
- [ ] B4 基于基类建实际 DLQ 消费者
- [ ] B8 RocketMQ broker 指标（主从/切换）
- [ ] B9 Canal 监控指标
- [ ] B10 JVM 线程转储（jstack/Arthas 死锁检测）

### 🟢 低（D6/D7 前）
- [ ] B5 VictoriaMetrics remote_write

> 附带：核对 t_user_behavior 的 behaviorType 1-6（SQL）vs 7（代码）不一致。

> 每项补齐后需重跑受影响链路 full-chain-test（回归门禁），确保不破坏既有 46 项 P0 修复。补齐后 §1.5 六场景必须都有事实数据 + 人工基线，才允许进入 D1。
