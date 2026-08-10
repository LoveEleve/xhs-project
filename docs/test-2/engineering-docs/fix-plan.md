# P0 架构级修复规划

> 8项 | 2026-08-10 | 按依赖分组

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

**问题**: `versionCheckScript`在`handleLike`之前执行→DB insert失败→MQ重试→版本已写入
→`checkVersion >= newVersion`→直接return→数据永久丢失

**方案**: 业务执行成功后再写版本号

**涉及文件**:
- `LikeUnlikeConsumer.java:69-88` — 调换versionCheck和handleLike执行顺序
- `FavoriteUnlikeConsumer.java:69-88` — 同上

**修复伪代码**:
```java
// 修改前: 版本先写后业务(重试时版本命中→跳过)
versionCheckScript.execute(versionKey, newVersion);
handleLike(event);

// 修改后: 业务先执行后写版本(重试时DB唯一索引幂等→跳过)
handleLike(event);  // SADD Redis Set, DB依靠唯一索引防重
versionCheckScript.execute(versionKey, newVersion);
```

**风险**: 调换顺序后, 若handleLike成功但versionCheck失败→下一消费同样数据→handleLike重复→DB唯一索引兜底 ✅
**验证**: 模拟MQ重复投递→确认DB无重复记录

---

### 3. Content P0-5: FollowService 双计数key体系统一

**问题**: analytics维护`myxhs:counter:user_following:{userId}`, counter维护`myxhs:counter:2:{userId}:7`
两套key独立更新→UserProfileAgg读analytics、计数器查询读counter→数据不一致(最多1小时)

**方案**: follow/unfollow后不再独立维护analytics侧计数key, 统一从counter服务读取

**涉及文件**:
- `FollowService.java:82,99` — 删除Lua中的counter key更新行
- `FollowService.java:434` — 删除syncCountersToCounterModule调用(或改为trigger counter refresh)
- `UserProfileAggService.java:140` — 粉丝/关注数改为从counter服务查询

**风险**: 需要counter服务提供批量查询接口(当前已有`batch-get`)
**验证**: 关注→立即查两个入口→确认数值一致(不再依赖1小时对账)

---

### 4. Content P0-9: NOTE_DELETE 无人消费

**问题**: `NoteService.publishNote`发送`SOCIAL_TOPIC:NOTE_DELETE`但全项目无消费者
→已删除笔记ID残留inbox/outbox ZSet达7天→Feed分页污染

**方案**: 在home服务新增`NoteDeleteConsumer`

**涉及文件**:
- 新建 `home/consumer/NoteDeleteConsumer.java` — 监听`SOCIAL_TOPIC:NOTE_DELETE`
- `NoteDeleteConsumer`逻辑:
  ```
  for each follower:
    ZREM myxhs:feed:inbox:{followerId} noteId
  ZREM myxhs:feed:outbox:{authorId} noteId
  ```

**风险**: 大V有海量粉丝时ZREM开销大→需异步批量处理+限流
**验证**: 创建笔记→确认Feed可见→删除笔记→等5秒→Feed不再出现

---

### 5. Order P0-1: 支付 vs 延时关单竞态自动退款

**问题**: 关单先赢(status 0→4)→支付回调`appendEvent`抛异常→支付单已标记成功(钱已扣)
订单已取消→**无自动退款**

**方案**: `onPaymentSuccess`捕获并发冲突异常→调支付服务发起自动退款

**涉及文件**:
- `OrderService.java:561-589` — onPaymentSuccess增加并发冲突处理
- `PaymentService.java` — 已有refund方法,可直接调用

**修复伪代码**:
```java
try {
    orderEventService.appendEvent(orderId, OrderEventType.ORDER_PAID);
    orderMapper.setPaidAt(orderId, now);
} catch (IllegalStateException | IllegalArgumentException e) {
    // 并发: 订单已被关单/取消→发起自动退款
    log.warn("[支付] 订单状态已变(关单/取消), 发起自动退款: orderId={}", orderId);
    paymentFeignClient.refund(orderId, payAmount);  // 使用已有退款接口
    return;
}
```

**风险**: 退款Feign失败→需发补偿消息ORDER_COMPENSATION_TOPIC兜底
**验证**: Mock pay.create并发→确认自动退款触发+退款状态正确

---

### 6. Order P0-6: 补偿任务无限循环

**问题**: PaymentNotifyCompensateJob每2分钟扫描`paid_at < now-5min`→调用`notifyPaySuccess`
→OrderService.getOrderPayAmount不校验状态(对已支付/已取消都返回金额)→永远判定"待支付"→无限循环

**方案**:
- 补偿任务持久化"已通知"标记(Redis SET `myxhs:payment:notified:{orderId}` TTL 1h)
- OrderService增加真正的状态查询接口`isOrderPendingPayment(orderId)`

**涉及文件**:
- `PaymentNotifyCompensateJob.java:87-93` — 增加已通知检查
- `RefundNotifyCompensateJob.java:82-91` — 同上
- `OrderService.java` — 新增`isOrderPendingPayment`方法

**修复**: 
```java
// 补偿任务
String notifiedKey = "myxhs:payment:notified:" + record.orderId();
if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(notifiedKey))) continue;

R<Boolean> pending = orderFeignClient.isOrderPendingPayment(record.orderId());
if (pending.isSuccess() && !pending.getData()) continue;

orderFeignClient.notifyPaySuccess(record.orderId(), record.paymentNo());
stringRedisTemplate.opsForValue().set(notifiedKey, "1", Duration.ofHours(1));
```

**风险**: Redis不可用时已通知标记丢失→重复通知→幂等消费端兜底 ✅
**验证**: 监控日志确认补偿任务不再对同一订单重复调用

---

### 7. Order P0-2(补充): 重复支付 — Order状态校验

**问题**: P0-2已修(支付成功key去TTL), 但order状态校验链仍缺失
pay时应Feign调order确认订单仍待支付(status=0)

**涉及文件**:
- `PaymentService.java:135-138` — pay时增加order状态验证

**修复**:
```java
// pay方法中,在statusKey检查之后
R<Boolean> canPay = orderFeignClient.isOrderPendingPayment(orderId);
if (canPay == null || !canPay.isSuccess() || !canPay.getData()) {
    throw new BizException("订单状态不允许支付");
}
```

**风险**: 与P0-6共用新的`isOrderPendingPayment`接口→先修P0-6再修此

---

### 8. Notif P0-B: 聚合窗口 vs 唯一索引一致

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

顺序1→8逐一执行, 每个修复后验证无副作用再继续下一个。

---

## 四、验证策略

每个P0修复后必须验证:
1. 编译通过(无新增import/类型错误)
2. 业务路径测试(正常路径仍工作)
3. 异常路径测试(修复目标场景不再出现)
4. 回归检查(相邻模块未受影响)
