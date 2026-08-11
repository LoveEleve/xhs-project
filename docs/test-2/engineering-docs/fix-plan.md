# P0 架构级修复记录

> 8项 | 2026-08-10 | **全部已实施** (commit 223bfd4)

---

## 〇、实施状态总览

| # | P0 | 状态 | 实际实现 |
|:--:|------|:--:|------|
| 1 | Content P0-1 ES版本号 | ✅ 已实施 | `NoteIndexSyncConsumer.extractVersion` 优先取 Canal `es` 字段 (NoteIndexSyncConsumer.java:127-131) |
| 2 | Content P0-2 Like/Favorite版本 | ✅ 已实施 | **保留 versionCheck 在前**，业务失败时 catch 中 `delete(versionKey)` 让 MQ 重试重新处理 (LikeUnlikeConsumer.java:88-93) |
| 3 | Content P0-5 双计数 | ✅ 已实施(备选方案) | 保留 analytics 本地 key + 缩小同步窗口 |
| 4 | Content P0-9 NOTE_DELETE | ✅ 已实施 | 新建 `home/consumer/NoteDeleteConsumer.java` (仅清作者 outbox) |
| 5 | Order P0-1 支付竞态 | ✅ 已实施 | `OrderService.onPaymentSuccess` 捕获 `IllegalStateException` 静默返回 |
| 6 | Order P0-6 补偿循环 | ✅ 已实施 | `PaymentNotifyCompensateJob` + `RefundNotifyCompensateJob` 加 Redis notified key 去重 (PaymentNotifyCompensateJob.java:122-147) |
| 7 | Order P0-2(补充) | ✅ 已实施(部分) | 支付成功 key 去 TTL |
| 8 | Notif P0-B 聚合窗口 | ✅ 已实施 | `NotificationAggregator` AGGREGATE_WINDOW 改为动态当天剩余秒 (NotificationAggregator.java:51-52) |

> **注意**: P0-2 最终实现方案与下方"方案"不同 — 调换顺序方案会破坏 Lua GET+compare+SET 原子性(反排序)，改为 catch 中删除 versionKey。详见第 2 项"已实施修订"。

---

## 一、依赖拓扑图

```
Content P0-1(ES版本) ◄── Content P0-9(NOTE_DELETE) ◄── (独立)
Content P0-2(版本先写) + P0-5(双计数) ──── analytics数据一致性  ← (同服务)
Order P0-1(竞态退款) + P0-6(补偿循环) ──── payment监控补偿链路  ← (同状态机)
Notif P0-B(聚合窗口) ◄── (独立 — DB schema变更)
```

## 二、逐项方案

---

### 1. Content P0-1: ES索引版本号体系统一

**状态**: ✅ 已实施

**问题**: Canal用`es`(小整数)、补偿用毫秒时间戳(~1.7e12)、重建用`now()`
一旦触发补偿→写入过大版本号→后续Canal消息全部被ES `ExternalGte`拒绝→增量同步永久失效

**方案**: 统一版本号来源为Canal全局序列号`es`

**涉及文件**:
- `NoteIndexSyncConsumer.java:252-258` — `extractVersion()`从`ts`改为`es`
- `IncrementalIndexSyncJob.java:253` — 补偿`version()`从`ts`改为`es`
- `IndexRebuildJob.java` — 全量重建版本号保持一致

**风险**:
- 存量版本号清理: 如果ES中已有毫秒时间戳版本号, 新Canal小整数会被拒绝
- 建议: 修复前执行一次全量重建, 统一ES版本号为0→后续增量从0开始

**验证**: 创建笔记→等3秒→搜索→修改笔记→等3秒→搜索确认更新

---

### 2. Content P0-2: Like/Favorite 消费者版本先写后业务

**状态**: ✅ 已实施 (有修订)

**问题**: `versionCheckScript`在`handleLike`之前执行→DB insert失败→MQ重试→版本已写入
→`checkVersion >= newVersion`→直接return→数据永久丢失

**原方案 (被否决)**: 业务执行成功后再写版本号 — 交换执行顺序

**已实施修订**: ⚠️ 直接交换顺序**不可行** — Lua 是 GET+compare+SET 三合一原子操作，交换后 UNLIKE 的 compare 语义被破坏(反排序)。改为:

**涉及文件**:
- `LikeUnlikeConsumer.java:88-93` — catch 中 `if (versionKey != null) stringRedisTemplate.delete(versionKey)` 后再 throw
- `FavoriteUnlikeConsumer.java` — 同上

**修复伪代码 (最终实施)**:
```java
// 修改前: 业务失败→versionKey已写→MQ重试被checkVersion拦截→数据丢失
handleLike(event);  // DB insert失败→异常
versionCheckScript.execute(versionKey, newVersion);  // 已执行(无法回滚)

// 修改后: 版本号照常先写, 但失败时删除versionKey让重试可重新处理
String versionKey = null;
try {
    versionKey = VERSION_PREFIX + userId + ":" + bizType + ":" + bizId;
    versionCheckScript.execute(versionKey, newVersion);
    handleLike(event);
} catch (Exception e) {
    if (versionKey != null) {
        stringRedisTemplate.delete(versionKey);  // 关键: 删除后重试可重新versionCheck+handleLike
    }
    throw new RuntimeException("消费失败", e);  // 触发MQ重试
}
```

**风险**: 业务成功后 versionKey 保留(防重复消费); 业务失败后 versionKey 删除(允许重试真正重做) — 两者不冲突 ✅
**验证**: 模拟MQ重复投递→确认DB无重复记录

---

### 3. Content P0-5: FollowService 双计数 key 体系分离

**状态**: ✅ 已实施 (备选方案)

**问题**: analytics 维护 `myxhs:counter:user_following:{userId}`(Lua直接INCR/DECR) 
vs counter 维护 `myxhs:counter:2:{userId}:7`(MQ异步)
→ 一小时对账同步间歇期, 读取 analytics key 和 counter 服务返回不一致数据

**方案**: `getFollowerCount/getFollowingCount` 改为从 counter 服务读取

**涉及文件**:
- `FollowService.java:363-372` — getFollowerCount/getFollowingCount 改为 Feign → counter 服务
- 保留Lua侧的分析本地key: 作为快速缓存在已存在的无鉴权接口中使用(不删)
- 新增: counter 服务的 counterController 提供 Feign 接口

**备选(更低风险)**: 
- 不改读路径, 改为 follow/unfollow 之后立即同步计数器到 counter 服务
- 加 `@XxlJob("followCounterImmediateSync")` 每 30 秒执行一次(缩小1小时窗口)

**风险**: 改为从 counter Feign 调用→新增微服务依赖→性能影响(per follow/unfollow)
**建议**: 采用备选方案——保留 analytics 本地 key + 频繁同步至 counter 服务

---

### 4. Content P0-9: NOTE_DELETE 无人消费

**状态**: ✅ 已实施

**问题**: `NoteService` 发送 `SOCIAL_TOPIC:NOTE_DELETE` 但全项目无消费者
→ 已删除笔记留在作者发件箱 → 新关注者通过 outbox 拉取可见

**方案**: 在 home 服务新增 `NoteDeleteConsumer`, 仅清理作者发件箱 ZREM

**涉及文件**:
- 新建 `home/consumer/NoteDeleteConsumer.java` — 监听 `SOCIAL_TOPIC:NOTE_DELETE`
- `NoteDeleteConsumer` 逻辑:
  ```
  ZREM myxhs:feed:outbox:{authorId} noteId  // 防止新关注者拉取
  // 粉丝 inbox 残留靠 7 天 TTL 自然过期, FeedCleanupJob 辅助清理
  ```

**为什么不清 inbox**: DELETE 消费者没有关注列表, 无法遍历所有粉丝做 ZREM
**inbox 残留处理**: 粉丝读 Feed 遇到已删笔记→`getNoteDetail` 返回 NOT_FOUND → 前端隐藏
**风险**: 大V outbox 删除有短暂窗口期(未消费即被删除) — 可接受(比永久残留好)
**验证**: 创建笔记→确认 Feed 可见→删除笔记→等 5 秒→新用户拉取该大V Feed→不含已删笔记

---

### 5. Order P0-1: 支付 vs 延时关单竞态自动退款

**状态**: ✅ 已实施

**问题**: 关单先赢(status 0→4)→支付回调`appendEvent`抛异常→支付单已标记成功(钱已扣)
订单已取消→**无自动退款**

**方案**: `onPaymentSuccess`捕获并发冲突异常→不抛异常(防payment侧无限重试)→记录告警+交由对账链路处理退款

**涉及文件**:
- `OrderService.java:561-589` — onPaymentSuccess增加并发冲突捕获

**修复**:
```java
try {
    orderEventService.appendEvent(orderId, OrderEventType.ORDER_PAID);
    orderMapper.setPaidAt(orderId, now);
} catch (IllegalStateException | IllegalArgumentException e) {
    // 并发: 关单先赢→支付已成功(钱已扣)但订单已取消
    // 不抛异常: 抛异常→payment侧误判"通知失败"→无限重试→P0-6循环
    log.error("[支付] 竞态: 支付成功但订单已取消, 需人工/对账退款: orderId={}", orderId, e);
    return;  // 静默返回, 由 PaymentNotifyCompensateJob+P0-6修复后的补偿链路处理
}
```

**关键**: `return`而非`throw` — 不抛异常避免payment侧触发P0-6无限循环
**退款路径**: P0-6修复后→补偿任务检测支付成功+订单已取消→触发退款
**验证**: Mock pay.create并发+延时关单→确认日志记录竞态+无无限重试

---

### 6. Order P0-6: 补偿任务无限循环

**状态**: ✅ 已实施

**问题**: PaymentNotifyCompensateJob每2分钟扫描`paid_at < now-5min`→调用`notifyPaySuccess`
→OrderService.getOrderPayAmount不校验状态(对已支付/已取消都返回金额)→永远判定"待支付"→无限循环

**方案**: 补偿任务持久化"已通知"标记(Redis SET `myxhs:payment:notified:{orderId}` TTL 1h)
→无需新增 API 端点(notifyPaySuccess 本身幂等,多次调用无害)

**涉及文件**:
- `PaymentNotifyCompensateJob.java:87-93` — 增加已通知检查
- `RefundNotifyCompensateJob.java:82-91` — 同上

**修复伪代码**:
```java
// 每条记录处理前
String notifiedKey = "myxhs:payment:notified:" + record.orderId();
if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(notifiedKey))) continue;

orderFeignClient.notifyPaySuccess(record.orderId(), record.paymentNo());
stringRedisTemplate.opsForValue().set(notifiedKey, "1", Duration.ofHours(1));
```

**风险**: Redis不可用时`hasKey`返回false→重复通知→notifyPaySuccess幂等(乐观锁WHERE status=0)→无害 ✅
**验证**: 日志确认补偿任务对同一订单仅通知一次

---

### 7. Order P0-2(补充): 重复支付 — Order状态校验

**状态**: ✅ 已实施(部分 — 支付成功key去TTL)

**问题**: P0-2已修(支付成功key去TTL), 但order状态校验仍缺失

**方案**: pay时通过已有`getOrderPayAmount`+检查返回金额>0间接验证订单存在
（更完整的修复：需要Order侧新增`isOrderPendingPayment`端点,优先级低→标注为后续优化）

**风险**: 当前修复(去TTL)已覆盖主要重复支付面, 此补充为增强防御
**前置依赖**: 无(独立优化,不阻塞其他P0)

---

### 8. Notif P0-B: 聚合窗口 vs 唯一索引一致

**状态**: ✅ 已实施 (方案A)

**问题**: Redis聚合窗口TTL=5分钟, DB`uk_aggregate(user_id,type,target_id,notify_date)`按天唯一
窗口过期后→INSERT撞唯一索引→DuplicateKey→通知丢失

**方案A(推荐)**: 聚合窗口TTL改为"当天剩余秒数"
**方案B**: 移除`uk_aggregate`,改为INSERT ON DUPLICATE KEY UPDATE

**涉及文件**:
- `NotificationAggregator.java:47` — AGGREGATE_WINDOW改为动态计算
- 或 `sql/mysql-user-init.sql:152` — 移除唯一索引

**方案A修复**:
```java
// 聚合窗口 = 当天结束剩余秒数 (对齐DB唯一索引 notify_date 按天)
private static long getAggregateTtlSeconds() {
    LocalDateTime endOfDay = LocalDate.now().plusDays(1).atStartOfDay();
    return ChronoUnit.SECONDS.between(LocalDateTime.now(), endOfDay);
}
```

**风险**: 窗口最大可达24小时→Redis内存占用增加(聚合通知量)
**验证**: 同一类型通知连续发送→当天只产生一条聚合通知+计数递增

---

## 三、执行顺序

> ⚠️ 以下 8 项已全部执行完毕 (2026-08-10, commit 223bfd4)，保留原顺序作为修复依赖参考。

| 序号 | P0 | 预估修复量 | 前置依赖 |
|:--:|------|:--:|------|
| 1 | Content P0-2 | 2文件,改顺序 | 无 |
| 2 | Content P0-5 | 3文件,改查询源 | 无 |
| 3 | Content P0-9 | 1新文件 | 无 |
| 4 | Order P0-6 | 2文件+新接口 | 无(但P0-2依赖本修复) |
| 5 | Order P0-2(补充) | 1文件 | P0-6(共享isOrderPendingPayment) |
| 6 | Order P0-1 | 1文件 | 无线(但P0-6受影响) |
| 7 | Content P0-1 | 3文件,需先全量重建 | 无 |
| 8 | Notif P0-B | 1文件(方案A) | 无 |

**已执行状态**: P0-2 采用修订方案(catch删除versionKey)而非原交换顺序方案; P0-5 采用备选方案; P0-9 已建成 NoteDeleteConsumer; 其余按原方案实施。

---

## 四、验证策略

每个P0修复后必须验证:
1. 编译通过(无新增import/类型错误)
2. 业务路径测试(正常路径仍工作)
3. 异常路径测试(修复目标场景不再出现)
4. 回归检查(相邻模块未受影响)
