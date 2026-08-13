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
- **附带发现（已解决 2026-08-13）**：`BehaviorRequest` 用 type7（停留时长）+ 7 值枚举，与 SQL 建表注释 1-6 不一致 → **业务方拍板：统一 API 7 值枚举，仅修表注释**（写读一致实证）；且 **P1 修复**：表原建在 my_xhs_analytics、search 数据源=content → 写入 1146 全丢 → 已迁 content 库 + 删空表 + init-all 修正 + 验证落库。

### A6. 曝光 / Feed 分发数据 —— ⚠️ 覆盖缺口（审计补入）
- **证据**：全仓库无 impression/exposure 表（grep 无结果）；Feed 收件箱(t_user_feed_inbox/feed:timeline) 只记录投递，无"曝光→互动"记录。
- **影响场景**：A3 内容互动骤降——无法区分"内容质量差" vs "分发/曝光减少"。
- **方案**：补曝光事件（note 曝光 + userId + 时间）或基于 feed 投递+阅读行为估算。
- **归属**：my-xhs-home / content | **优先级：🟡 中**

---

## B. 观测缺口（基于部署包 `my-xhs-deploy-package.zip` 复核，2026-08-13）

> ⚠️ **重大更正**：本 B 段原按**本地 `config/`** 判断为"缺失"，但**实际部署包（330 文件）已实现绝大多数**。以下按部署包实测更新。证据：`config/mysqld-exporter/*`、`config/alertmanager/`、`config/deploy-cloud/`、`DEPLOY-NOTES.md`、`README-METRICS.md`、`docker-compose.yml`。

### B1. mysql-exporter —— ✅ 已部署（更正）
- **证据**：compose 有 `mysqld-exporter`（9104，`config/mysqld-exporter/my.cnf`）；Prometheus job `mysql` → 9104。
- **影响**：死锁/慢查询/锁等待可采集。
- **优先级：🔴 高 → 已满足**

### B2. slow_query_log 管道 —— ⚠️ 半就绪（更正）
- **证据**：MySQL command 含 `--slow-query-log=1 --long-query-time=0.5`（**已开启**）；但**慢查询 → ES/Kibana 管道未在部署包确认**（Logstash 15044 是微服务 TCP 直连；filebeat 已按基线移除）。
- **待确认**：慢查询日志如何进 ES？若无管道，需补采集（或日志读源）。
- **优先级：🔴 高（管道未确认）**

### B3. redis-exporter —— ✅ 已部署（更正）
- **证据**：compose 有 `redis-exporter`（9151，oliver006）；Prometheus job `redis` → 9151。
- **优先级：🟡 中 → 已满足**

### B4. DLQ 消费者 —— ⚠️ 仍缺（代码侧，未变）
- **证据**：`my-xhs-common/mq/DlqMessageHandler.java` 基类+指标；**无服务继承订阅 `%DLQ%` 实际消费**（部署包不涉及）。
- **方案**：基于基类建实际 DLQ 消费者 + 告警钩子。
- **优先级：🟡 中（仍开放）**

### B5. VictoriaMetrics remote_write —— ✅ 已部署（更正）
- **证据**：prometheus.yml 有 `remote_write → http://127.0.0.1:8428/api/v1/write`；compose 有 `victoria-metrics`。
- **优先级：🟢 低 → 已满足**

### B6. 主从复制延迟指标 —— ✅ 已部署（更正）
- **证据**：compose 有 `mysqld-exporter-slave`（**9105，连 3307，collect.slave_status**，`my-slave.cnf`）；Prometheus job `mysql-slave` → 9105。DEPLOY-NOTES §三.10。
- **优先级：🔴 高 → 已满足**（B4 复制延迟场景数据就绪）

### B7. MySQL error log / innodb status —— ⚠️ 半就绪（更正）
- **证据**：`--innodb-print-all-deadlocks=ON`（死锁写 error log）+ `mysql-deadlock-metrics.sh`（cron，对比 LATEST DETECTED DEADLOCK 时间戳 → `mysql_innodb_deadlock_total`，已实测造死锁验证）。**error log → 采集管道未确认**。
- **待确认**：error log 如何进 ES/观测？若只在容器内，死锁定位需按需抓取。
- **优先级：🔴 高（管道未确认）**

### B8. RocketMQ broker 指标 —— ✅ 已部署（textfile 方案，更正）
- **证据**：官方 5.1.4 无内置 metrics + exporter 客户端不兼容 → 用 `rocketmq-metrics.sh`（cron，textfile collector）+ node-exporter 挂载 `/data/rocketmq-textfile`；指标 `rocketmq_broker_*`/`rocketmq_consumer_*`。README-METRICS §1。
- **优先级：🟡 中 → 已满足**

### B9. Canal 监控指标 —— ✅ 已部署（更正）
- **证据**：Prometheus job `canal` → **11112**（Canal server metrics）。
- **优先级：🟡 中 → 已满足**

### B10. JVM 线程转储 —— ⚠️ 需补（未变）
- **证据**：`SegmentIdGenerator`/`DynamicDataSource` 有 synchronized/锁竞争热点；部署包无自动线程转储。
- **方案**：按需 jstack/Arthas（`/data/workspace/arthas` 已有源码）+ 死锁检测。
- **优先级：🟡 中（仍开放）**

### 附：告警与看板（部署包已有）
- `alertmanager`（19093，webhook 占位需配渠道）+ Prometheus alert_rules（`myxhs_rules.yml`）+ **Grafana 9 个看板**（JVM/MQ/MySQL含复制+锁/ES/node/redis/api/biz/tomcat）+ node-exporter(9100) + ES exporter(9114) + SkyWalking OAP telemetry(1234)。

---

## C. 审计修正汇总（相对 PLAN §8 / 之前分析）
| 项 | 原判断 | 审计后修正 |
|----|-------|-----------|
| 关注持久化 | "仅 Redis 非持久化" | **MySQL t_follow 已持久化当前关系**；真实缺口=无取关历史(DELETE) |
| DLQ 消费者 | "无 DLQ 消费者" | **有基类+指标，但无实际消费者**；需基于基类补消费端 |
| 转化漏斗(A5) | "无行为表" | **t_user_behavior 存在**，但只覆盖内容行为(note 维度)；**电商漏斗缺：sku 商品浏览事件 + 加购事件流**（且笔记浏览不可用于电商漏斗——领域错配）|
| 曝光数据(A6) | 未审计 | **补入**：无曝光/Feed分发数据，A3 需 |
| **观测 B 面（2026-08-13 部署包复核）** | "全部缺失" | **多数已部署**：mysql-exporter(9104)+slave(9105)、redis-exporter(9151)、VM remote_write、Canal(11112)、RocketMQ textfile、innodb_print_all_deadlocks+deadlock脚本、alertmanager、Grafana 9 看板；**仍缺**：DLQ 消费者、JVM 线程转储、slow_query→ES 管道与 error log→ES 管道未确认 |

> 其余 A 面 8 项与 §8 一致，均为确认缺失（业务数据侧）。

---

## D. 可执行清单（优先级排序，按部署包复核后）

### 🔴 高（阻塞 A1/A2 场景，D1 前必须就绪——业务侧）
- [ ] A1 支付失败事件流水（payment）→ A2 场景数据
- [ ] A5 电商漏斗：补 **sku 商品浏览事件 + 加购事件流**（append-only，与内容行为分开）（analytics/home）→ A1 场景数据
- [ ] ⚠️ 确认 B2 slow_query_log → ES 管道是否接通（已开启未确认管道）
- [ ] ⚠️ 确认 B7 MySQL error log → 观测（innodb_print 已开，管道未确认）

### 🟡 中（D2 前就绪）
- [ ] A2 note.published_at/audited_at（content）
- [ ] A3 关注/取关事件流水（analytics）
- [ ] A4 退款商品明细（payment）
- [ ] A6 曝光/Feed 分发数据（home/content）→ A3 场景
- [ ] B4 基于基类建实际 DLQ 消费者
- [ ] B10 JVM 线程转储（jstack/Arthas 死锁检测）

### ✅ 已部署（无需动作，部署包已含）
- [x] B1 mysql-exporter(9104) | B3 redis-exporter(9151) | B5 VM remote_write | B6 复制延迟(9105) | B8 RocketMQ textfile | B9 Canal(11112) | alertmanager | Grafana 9 看板 | innodb_print_all_deadlocks + deadlock 脚本

> ✅ 已解决（2026-08-13 业务方拍板）：统一 API 7 值枚举，仅修表注释；P1 表迁 content 库。

> ✅ 已解决（2026-08-13 业务方拍板）：统一 API 7 值枚举，仅修表注释；P1 表迁 content 库。

> 每项补齐后需重跑受影响链路 full-chain-test（回归门禁），确保不破坏既有 46 项 P0 修复。补齐后 §1.5 六场景必须都有事实数据 + 人工基线，才允许进入 D1。
