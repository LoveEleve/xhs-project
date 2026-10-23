---
id: C-017
slug: 优惠券规模化
status: done
created: 2026-10-23
updated: 2026-10-23
iteration: ITER-017
relations:
  relates_to:
    - slug: docs/学习/优惠券-讲解/06-规模化改造规划（C-017）
      reason: 规划来源（P1 分片 / P2 延时过期 / P3 推送设计）
  depends_on:
    - slug: C-007
      reason: 优惠券 Design B/Outbox/DLQ 基线
---

# C-017 优惠券规模化

> 目标：补齐"32 分片 + 延时消息过期"（P3 推送先设计），保持默认单库与 726 基线不变。
> 决策（2026-10-23 用户"开始吧"）：P1 2 库 × 16 表；默认 profile 单库、`coupon-sharding` 显式启用；P2 必做；P3 先设计。

## 范围

- **P1**：`t_user_coupon` 按 `user_id` 分片（2 库 × 16 表）；模板/Outbox 全局表；Mapper 按 id 读写补 `user_id`；路由单测。
- **P2**：模板结束时发 RocketMQ 定时消息（`setDeliverTimeMs`）→ 消费端批量 0→2；XXL-Job 保留兜底。
- **P3**：推送引擎设计文档（不实现）。

## 非目标

- 不做运行时/压测验证；不声称 5 万 QPS/10 万 msg/s；不改订单/支付/库存主链路。

## 验收

- 默认 profile：`mvn -o -pl my-xhs-coupon -am test` 绿，15 模块 726 基线不变；
- `coupon-sharding` profile 编译通过 + 路由单测通过；
- P2 producer/consumer 单测通过；过期 Job 兜底不变。

## 影响面

- `my-xhs-coupon`（主体）、`sql/init-all.sql`、`sql/migration/coupon/`、`sql/mysql-content-init.sql`。

## 执行结果（2026-10-23）

### P1 分片（已完成，默认不启用）

- 新增 `my-xhs-coupon/src/main/resources/sharding-config.yaml`（2 库 × 16 表，`user_id % 2` / `(user_id DIV 2) % 16`）；
- 新增 `config/ShardingSphereDataSourceConfig`（`@Profile("coupon-sharding")`，默认 profile 不加载）；
- 新增 `application-coupon-sharding.yml`（关闭读写分离/自动数据源）；
- 新增 `sql/mysql-coupon-sharding-init.sql`（DDL + 32 分片 + 存量迁移）；
- Mapper 适配：`selectByIdAndUserId`、`markUsed/returnCoupon` 补 `user_id`、过期改为"按模板单表 UPDATE"（避免跨分片 JOIN）；
- 新增 `CouponShardingRouteTest`（路由规格）。

### P2 延时过期（已完成）

- `CouponService.sendExpireDelay`：模板创建/上线时 `syncSendDeliverTimeMills(valid_end)`；
- `CouponExpireDelayConsumer`：到点批量 0→2；提前/模板缺失忽略；
- `CouponExpireJob` 保留兜底（改为按模板循环）；
- 新增 `CouponExpireDelayConsumerTest` + 创建模板发送断言。

### P3 推送引擎（最小实现完成）

- `push/`：`CouponPushTarget`、`UserBucketSplitter`（条数+800KB 字节双限）、`SharedThreadPool`（ThreadPoolExecutor + Semaphore 背压）、`CouponPushSender`（发 `NOTIFICATION_TOPIC`，不直接依赖 notification）；
- `CouponExpiryPushJob`（XXL-Job `couponExpiryPushJob`）：扫"明天到期"未使用券 → 幂等记录 → 分桶 → 批任务并发推送；
- `t_coupon_push_record`（uk(template_id,user_id,push_date)）：已发送跳过、失败重试；
- 画像：最小版用 user_id 分桶；行为标签轻量聚合保留在 `07-推送引擎设计`（未实现，明确标注）。

### 验证

- coupon 测试：31 全绿（24 基线 + 7 新增）；
- 全仓测试：747 全绿（15 模块基线 733 + user 14）；
- 运行时（分片路由/延时消息/推送）按项目口径后置，不在本次验证范围。

## Review（2026-10-23）

### 已修复

| 级别 | 问题 | 修复 |
|---|---|---|
| **高** | `CouponClaimConsumer` 的"插 user_coupon（分片）+ 扣 template.remain（ds_0）"在分片后是**跨库本地事务**，ShardingSphere 本地事务不保证跨 DS 原子性 | 改为"插券为准、remain 派生"：扣减失败只记 `claim_stock_lag`，由 `CouponReconcileJob`（以 Redis 为准）修复；不再抛异常回滚 |
| **中** | 模板/Outbox 依赖"未配置表走默认数据源"的隐式行为 | 显式 `!SINGLE`（template/outbox/push_record → ds_0） |
| **中** | `SharedThreadPool` 在 CPU 核数 > permits*2 时 `core > max` 抛 IllegalArgumentException（测试暴露） | `core = min(max, max(2, cpus))` |
| **低** | 推送计数 `int[]` 并发自增有竞态 | 改 `AtomicInteger` |
| **低** | 无跳过时也打 `push_skip` 指标；sender 把 targetType 写成"订单" | 仅 skipped>0 记录；移除 targetType |

### 残余风险（已记录，不修/后置）

1. 过期 Job 与推送 Job 按 `coupon_id` 查询在分片模式下是**广播**（32 表）；量级大时应改"按分片扫描/离线表"。
2. `COUPON_EXPIRE_TOPIC` 需在 Broker 预创建（RocketMQ 生产通常关闭自动建 Topic）。
3. `sql/mysql-coupon-sharding-init.sql` 的存量迁移是**演示级直插**（停机窗口/小数据量）；生产应双写迁移。
4. 运行时（分片路由、延时投递、推送并发）未验证——按项目口径（编译+单测）。

### 最终验证

- coupon：38 全绿（24 基线 + 14 新增：P1 路由 2、P2 消费者 4 + 创建发送 1、P3 7）；
- 全仓：754 全绿（15 模块 740 + user 14）。

## 第二轮 Review（2026-10-23）

| 级别 | 问题 | 修复 |
|---|---|---|
| **高** | `sharding-config.yaml` 的 `shardingAlgorithms` 被缩进到 `!SINGLE` 下（YAML 结构错误，启动会找不到算法） | 重写 rules：`!SHARDING`（含 algorithms）与 `!SINGLE` 两个独立列表项 |
| **高** | `CouponClaimConsumer` 用 `isFirstProcess` **前置置位**：标记后、insert 前崩溃 → 重投被拦截 → 用户丢券 | 改用 `isProcessed`（只读）+ `markProcessed`（成功后置位）；失败不置位，重投可重处理；DB `uk_claim_no` 兜底 |
| **高** | RocketMQ 开源版延时消息窗口 **默认 24h**，超过会被**立即投递**；7 天有效期的券排 7 天 = 立即消费后忽略，等于没有延时 | `CouponExpireDelayProducer` 分段：每次最多排 `now+23h`；消费端未到期则续排下一段；Job 兜底。**不再声称"任意延时"** |
| **中** | 推送 Job 用 `LIMIT 5000` 无游标：单模板 >5000 张券时永远只推前 5000（饥饿） | 改 `id > lastId` 游标循环（单库正确；分片模式广播查询的残余风险已记录） |
| **低** | `mysql-content-init.sql` 缺 `t_coupon_push_record` | 已补 |

### 第二轮验证

- coupon：41 全绿（新增 3 个延时生产器测试：23h 分段/窗口内精确/已过期不排）；
- 全仓：757 全绿（15 模块 743 + user 14）。

### 简历口径提醒

可写：32 分片（user_id 路由、模板/Outbox 单表）、Redis Lua、RocketMQ 异步落账 + Outbox、**23h 分段延时 + Job 兜底**、分桶/批分割/共享线程池推送、跨库事务拆分（remain 派生 + 对账）。
不可写：千万级/5 万 QPS/10 万 msg/s、完整用户画像、template_id 分片映射、20% 空分片/影子库/onlineDDL、生产验证。

## 第三轮：JD 对齐补齐（2026-10-23）

| JD 能力 | 实现 |
|---|---|
| 责任链模式实现券创建 + 规则配置 | 新增 `TemplateCreateValidator` 责任链（`@Order`）：有效期 → 优惠值 → 库存/限领 → 类型规则；`createTemplate` 不再内联 if |
| 双层过期检测·预检层 | 新增 `couponExpirePrecheckJob`：每日扫未来 24h 到期模板，补排/续排延时消息（与延时消费、过期 Job 三层） |
| 推送·每分片 1000 用户 | `coupon.push.bucket-size`（默认 1000）+ `coupon.push.scan-limit`（默认 5000）可配置；`UserBucketSplitter` 仍受 800KB 限制 |
| 三级库存 | Redis Lua ✅ / RocketMQ 异步落账 ✅ / 32 分片 ✅ |
| 亿级分表 | 32 分片 ✅；**未做**：20% 空分片、影子库、onlineDDL、template_id 映射（模板为全局单表） |
| 画像分片 | **未做**完整画像；最小版按 user_id 分桶，行为标签分桶仍是设计 |

### 第三轮验证

- coupon：43 全绿（新增创建责任链拒绝 1 + 预检 Job 1）；
- 全仓：759 全绿（15 模块 745 + user 14）。

### 仍不能声称（非代码可补）

- 5 万 QPS / 10 万 msg/s / 千万 MAU（需压测/容量验证）；
- 20% 空分片/影子库/onlineDDL（基础设施/运维方案）；
- 完整用户画像/实时特征平台；
- 生产验证（项目口径：编译 + 单测）。
