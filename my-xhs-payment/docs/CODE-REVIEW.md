# my-xhs-payment 支付服务 Code Review

## 1. 对标 P8 评分表

| 维度 | 评分(1-10) | 说明 |
|------|:----------:|------|
| **架构设计** | 9 | 独立微服务解耦 + 策略模式 + 状态机 + 异步回调，完整的支付生命周期 |
| **并发安全** | 9 | Redis SETNX 幂等 + 乐观锁状态更新 + Lua 原子超时检查 + 分布式锁定时任务 |
| **幂等性** | 10 | 双重保障：Redis 幂等键 + 乐观锁 WHERE status=当前状态，极端并发也不会重复操作 |
| **分布式** | 9 | 定时任务分布式锁 + MQ 异步解耦 + Feign 服务间调用 + 独立数据源 |
| **可扩展性** | 10 | 策略模式：新增支付渠道只需实现 PayChannelStrategy + 注册到 Map，零侵入 |
| **容错性** | 9 | Feign 降级（FallbackFactory）+ 补偿定时任务 + MQ 失败不影响主流程 + 对账兜底 + 定时任务双保险 |
| **代码规范** | 8 | 注释完整，日志规范，但 JdbcTemplate 手动映射可优化 |
| **综合评分** | **9.2** | |

## 2. 发现的问题及修复记录

### 2.1 ✅ 已修复：PaymentService MQ 消息发送空实现
- **问题**：原始 `sendPayResultMq()` 方法仅打日志，未真正发送 MQ 消息
- **影响**：支付成功/退款成功后订单服务无法感知
- **修复**：注入 `RocketMQTemplate`，使用 `syncSend()` 发送消息到 `PAY_RESULT_TOPIC` / `REFUND_RESULT_TOPIC`

### 2.2 ✅ 已修复：PayCallbackSimulator 直接调用策略层
- **问题**：`PayCallbackSimulator` 绕过 `PaymentService` 直接调用 `PayChannelStrategy.handleCallback()`
- **影响**：回调不会触发支付状态更新、MQ 消息发送等完整链路
- **修复**：改为调用 `PaymentService.handlePayCallback()`，走完整的回调处理流程

### 2.3 ✅ 已修复：OrderController 支付模式切换不完整
- **问题**：订单模块硬编码使用 MockPayService，无法切换到独立支付服务
- **修复**：引入 `@Value("${pay.type:mock}")` 配置，支持 `mock`/`remote` 两种模式

### 2.4 ⚠️ 待优化：JdbcTemplate 手动 ResultSet 映射
- **问题**：`PaymentService` 中大量 `JdbcTemplate.query()` 手动映射 ResultSet 到实体
- **建议**：可改用 MyBatis-Plus 的 Mapper 接口（`PaymentMapper` / `RefundMapper` 已创建），减少样板代码
- **优先级**：P2（功能正确性优先）

### 2.5 ✅ 已修复：Feign 降级 + 补偿任务
- **问题**：`OrderFeignClient` 未配置 fallback/fallbackFactory，订单服务不可用时支付服务会直接报错
- **影响**：支付成功但订单未更新（MQ 丢失 + 无补偿），退款成功但订单未确认
- **修复**：
  1. 新增 `OrderFeignFallbackFactory`：所有降级方法返回失败（R.fail），触发补偿任务重试
  2. 新增 `PaymentNotifyCompensateJob`：扫描支付成功但超过 5 分钟未确认的记录，通过 Feign 重新通知
  3. 新增 `RefundNotifyCompensateJob`：扫描退款成功但超过 5 分钟未确认的记录，通过 Feign 重新通知
- **优先级**：P1 → ✅ 已修复

### 2.6 ✅ 已修复：补偿任务未验证订单状态
- **问题**：`PaymentNotifyCompensateJob` 通过 `getOrderPayAmount` 只验证了订单服务可达性，未判断订单是否仍为"待支付"状态。如果订单已被超时关单，补偿任务仍会通知支付成功，导致订单状态异常
- **修复**：利用 `getOrderPayAmount` 间接判断——返回金额说明仍为待支付（需通知），返回成功但 data 为空说明已不是待支付（无需补偿），返回失败说明订单服务不可达（稍后重试）
- **优先级**：P1 → ✅ 已修复

### 2.7 ✅ 已修复：退款补偿任务缺少数据一致性校验
- **问题**：`RefundNotifyCompensateJob` 直接调用 `notifyRefundSuccess` 而未先验证订单是否仍为"已支付"状态。如果订单已被手动退款或关闭，重复通知退款成功可能导致状态异常
- **修复**：退款补偿场景下，只要订单服务可达就尝试通知（订单服务内部用乐观锁 `WHERE status=1` 保证幂等）。但如果 `getOrderPayAmount` 返回 data 非空（说明订单仍为待支付），说明数据不一致——记录告警日志
- **优先级**：P1 → ✅ 已修复

### 2.8 ✅ 已修复：InventoryCacheEvictConsumer SCAN cursor 泄漏风险
- **问题**：`doEvictCache` 使用 `cursor.stream().toList()` 后通过 `try-with-resources` 关闭 cursor，但如果 `toList()` 抛异常，cursor 可能未关闭导致 Redis 连接泄漏
- **修复**：改为手动 `while(cursor.hasNext())` 遍历 + `finally` 块手动关闭 cursor，确保异常安全
- **优先级**：P1 → ✅ 已修复

### 2.9 ✅ 已修复：checkRefundTimeout 使用 ResultSetExtractor 遍历
- **问题**：`checkRefundTimeout` 使用 `JdbcTemplate.query(sql, ResultSetExtractor)` + `while(rs.next())` 遍历，虽然 ResultSetExtractor 回调期间不会关闭 ResultSet，但这种写法容易导致误解且不利于维护
- **修复**：改为 `RowMapper` 返回 `List<RefundTimeoutRecord>`，然后在 List 上迭代处理
- **优先级**：P2 → ✅ 已修复

### 2.10 ✅ 已修复：reconcile 对账逻辑不完整
- **问题**：`reconcile()` 方法只遍历支付成功的记录并打印日志，没有真正调用订单服务查询订单状态进行比对
- **修复**：通过 Feign 调用 `getOrderPayAmount` 间接判断订单状态——如果返回金额说明订单仍为待支付（不一致），触发补偿通知；如果 data 为空说明订单已更新（一致）
- **优先级**：P1 → ✅ 已修复

## 3. 技术亮点和面试价值评估

### 3.1 策略模式 + Map 注册表
```java
Map<Integer, PayChannelStrategy> payChannelStrategyMap
```
- **面试价值**：★★★★★
- **亮点**：`PayStrategyConfig` 通过 Spring 自动注入所有策略实现，按 payType 注册到 Map。新增支付渠道零侵入。
- **面试话术**：「支付渠道扩展不需要改 PaymentService 一行代码，只需新增 Strategy 实现类，Spring 自动注入到 Map」

### 3.2 双重幂等保障
```java
// 第一层：Redis SETNX 防重复请求
Boolean setSuccess = redisTemplate.opsForValue()
    .setIfAbsent(payingKey, String.valueOf(userId), PAYING_KEY_TTL);

// 第二层：乐观锁 WHERE status=0 防并发更新
int updated = paymentJdbcTemplate.update(
    "UPDATE t_payment SET status = ? ... WHERE order_id = ? AND status = ?", ...);
```
- **面试价值**：★★★★★
- **亮点**：Redis 幂等键是快速失败层（99% 重复请求在此拦截），乐观锁是最终保障层（极端情况下 Redis 键过期但数据库状态不变）
- **面试话术**：「幂等设计两层保障：Redis SETNX 拦截 99% 重复请求，乐观锁兜底极端场景。两层缺一不可：只有 Redis 键可能过期丢失，只有乐观锁并发压力全到 DB」

### 3.3 支付状态机
```
待支付(0) ──支付成功──→ 支付成功(1) ──退款──→ 已退款(3)
   │                                       
   └──超时/失败──→ 支付失败(2)
```
- **面试价值**：★★★★
- **退款状态机**：退款中(0) → 退款成功(1) / 退款失败(2) / 退款关闭(3)

### 3.4 异步回调模拟器
- **面试价值**：★★★★
- **亮点**：`PayCallbackSimulator` 完整模拟第三方支付的异步回调行为（延迟 1~3s + 随机成功率），使开发环境可以测试异步回调的完整链路
- **面试话术**：「Mock 环境下不仅要测正常流程，还要测异步回调的时序问题。回调模拟器让我们能测试支付成功但订单还没更新时的并发场景」

### 3.5 独立数据源
- **面试价值**：★★★★
- **亮点**：`PaymentDataSourceConfig` 创建独立 JdbcTemplate，绕过 ShardingSphere 分片路由
- **面试话术**：「支付表在独立库中，不走分片路由。原因是支付记录需要支持非分片键查询（按订单号查支付状态），且乐观锁状态更新必须精确路由」

## 4. 面试话术（Q&A 格式）

### Q1: 支付服务如何保证幂等性？
**A**: 双重保障。第一层 Redis SETNX：同一订单 30 分钟内不能重复发起支付，同一支付单 7 天内不能重复退款。第二层乐观锁：`UPDATE ... WHERE status = 待支付`，即使 Redis 键意外过期，数据库层也不会重复更新。两层缺一不可：只有 Redis 无法应对键过期后的并发请求，只有乐观锁并发压力全到数据库。

### Q2: 支付超时关单如何实现？
**A**: 双保险机制。第一层：支付创建时发送 RocketMQ 延时消息（30 分钟），消费者调用订单服务的超时关单。第二层：`PaymentTimeoutCheckJob` 每 30 秒扫描 Redis 中的待支付支付单，使用 Lua 脚本原子检查+更新。两层保证即使 MQ 延时消息丢失，定时任务也能兜底。

### Q3: 如何设计支付渠道的扩展性？
**A**: 策略模式。`PayChannelStrategy` 定义支付生命周期（pay/handleCallback/refund/queryPayStatus），每种渠道（Mock/支付宝/微信）实现此接口。`PayStrategyConfig` 通过 Spring 自动注入所有实现，按 payType 注册到 Map。新增渠道只需：1）新建 Strategy 实现类；2）注册到 Map；3）修改配置。PaymentService 零修改。

### Q4: 退款金额如何校验？
**A**: 支持部分退款和多次退款。每次退款前查询该支付单的累计已退金额（`COALESCE(SUM(refund_amount), 0)`），确保本次退款金额 ≤ 支付金额 - 已退金额。这样一笔 100 元的支付可以分两次退 50 元。

### Q5: 支付服务与订单服务如何交互？
**A**: 异步 MQ + 同步 Feign + 补偿任务三层保障。1）支付成功后发 MQ 消息（PAY_RESULT_TOPIC），订单服务消费后更新订单状态；2）Feign 回调接口作为补偿通道，当 MQ 消费失败时支付服务可主动调用订单服务；3）PaymentNotifyCompensateJob / RefundNotifyCompensateJob 每 2~3 分钟扫描支付/退款成功但订单未确认的记录，通过 Feign 重新通知。关键设计：支付补偿任务先通过 `getOrderPayAmount` 间接判断订单状态——返回金额说明仍为待支付（需通知），返回成功但 data 为空说明已不是待支付（无需补偿），返回失败说明不可达（稍后重试）。退款补偿场景下只要订单服务可达就尝试通知，因为订单服务内部用乐观锁保证幂等；但如果 `getOrderPayAmount` 返回 data 非空说明订单仍为待支付（数据不一致），记录告警。FallbackFactory 所有降级方法返回 R.fail（而非静默成功），因为支付/退款是强一致场景，降级后必须触发补偿任务，不能丢弃。

### Q6: 为什么 FallbackFactory 返回失败而不是静默成功？
**A**: 这是支付场景的关键设计决策。如果降级返回成功，调用方认为操作已完成，不会触发补偿任务，导致"支付成功但订单仍为待支付"的数据不一致。返回失败后，补偿任务会扫描到这条记录并重新通知订单服务。这就是"宁可多重试也不丢消息"的原则——在支付场景下，多重试只会多做一次乐观锁更新（幂等），但丢消息会导致资金和订单不一致。

## 5. 修复前后代码对比

### 5.1 MQ 消息发送修复

**修复前**：
```java
private void sendPayResultMq(Long orderId, Long userId, boolean success, String tradeNo) {
    log.info("[支付结果MQ] 发送: topic={}, tag={}, orderId={}", topic, tag, orderId);
}
```

**修复后**：
```java
private void sendPayResultMq(Long orderId, Long userId, boolean success, String tradeNo) {
    String destination = "PAY_RESULT_TOPIC:" + (success ? "PAY_SUCCESS" : "PAY_FAIL");
    String body = String.format("{\"orderId\":%d,\"userId\":%d,\"success\":%b,\"tradeNo\":\"%s\"}",
            orderId, userId, success, tradeNo != null ? tradeNo : "");
    Message<String> message = MessageBuilder.withPayload(body)
            .setHeader("KEYS", "PAY_" + orderId)
            .build();
    rocketMQTemplate.syncSend(destination, message);
}
```

## 6. 深度技术分析

### 6.1 幂等性边界分析

| 操作 | Redis 幂等键 | TTL | 乐观锁 | 边界条件 |
|------|:----------:|:---:|:------:|---------|
| 创建支付 | payment:paying:{orderId} | 30min | WHERE status=0 | 支付成功后删键，允许退款后重新支付 |
| 支付回调 | — | — | WHERE status=0 | 回调到达时订单可能已超时关单 |
| 发起退款 | payment:refunding:{paymentId} | 7d | WHERE status=0(退款中) | 部分退款场景需特殊处理 |
| 退款回调 | — | — | WHERE status=0(退款中) | 退款回调可能比支付回调晚很久 |

### 6.2 一致性保证

```
支付成功链路：
PaymentService.handlePaySuccessInternal()
  → 乐观锁更新 payment.status=1
  → Redis 更新缓存
  → MQ 发送 PAY_SUCCESS 消息
  → 订单服务消费 → 乐观锁更新 order.status=1

退款成功链路：
PaymentService.handleRefundSuccessInternal()
  → 乐观锁更新 refund.status=1
  → 更新支付单 payment.status=3（已退款）
  → MQ 发送 REFUND_SUCCESS 消息
  → 订单服务消费 → 恢复库存 + 退优惠券 + 更新 order.status=3

补偿机制（三层保障）：
  1. MQ 重试（消费失败自动重试 16 次）
  2. Feign 主动回调（MQ 彻底失败时，FallbackFactory 降级返回失败触发补偿）
  3. PaymentNotifyCompensateJob（每 2 分钟扫描支付成功但订单未确认的记录）
     - 通过 Feign 查询订单支付金额间接判断状态
     - 返回金额 → 仍为待支付 → 重新发送 notifyPaySuccess
     - 返回成功但 data 为空 → 已不是待支付 → 无需补偿
     - 返回失败 → 订单服务不可达 → 稍后重试
  4. RefundNotifyCompensateJob（每 3 分钟扫描退款成功但订单未确认的记录）
     - 只要订单服务可达就尝试通知（订单服务内部乐观锁保证幂等）
     - 但如果 getOrderPayAmount 返回 data 非空 → 数据不一致 → 记录告警
  5. 对账定时任务（每天凌晨核对状态）
     - 通过 Feign 查询订单支付金额间接判断订单状态
     - 如果支付成功但订单仍为待支付 → 不一致 → 触发补偿通知
     - 如果一致 → 记录日志
```

### 6.3 支付模块文件清单（26个Java文件）

```
com.myxhs.payment/
├── PaymentApplication.java         # 启动类
├── config/
│   ├── PayStrategyConfig.java      # 策略注册
│   ├── PaymentConfig.java          # Lua 脚本 + 线程池
│   └── PaymentDataSourceConfig.java # 独立数据源
├── consumer/
│   ├── PayResultConsumer.java      # 支付结果消费
│   └── RefundResultConsumer.java   # 退款结果消费
├── controller/
│   └── PaymentController.java      # RESTful API
├── dto/
│   ├── request/
│   │   ├── PayCreateRequest.java   # 支付请求
│   │   └── RefundRequest.java      # 退款请求
│   └── response/
│       └── PaymentVO.java          # 支付响应
├── entity/
│   ├── Payment.java                # 支付实体
│   └── Refund.java                 # 退款实体
├── feign/
│   ├── OrderFeignClient.java       # 订单服务 Feign
│   └── OrderFeignFallbackFactory.java # 订单 Feign 降级工厂
├── job/
│   ├── PaymentTimeoutCheckJob.java # 支付超时定时任务
│   ├── RefundTimeoutCheckJob.java  # 退款超时定时任务
│   ├── PaymentNotifyCompensateJob.java # 支付成功通知补偿
│   └── RefundNotifyCompensateJob.java  # 退款成功通知补偿
├── mapper/
│   ├── PaymentMapper.java          # 支付 Mapper
│   └── RefundMapper.java           # 退款 Mapper
├── service/
│   └── PaymentService.java         # 核心业务逻辑（770行）
├── simulator/
│   └── PayCallbackSimulator.java   # 异步回调模拟器
└── strategy/
    ├── PayChannelStrategy.java     # 策略接口
    └── impl/
        ├── MockPayStrategy.java    # Mock 支付
        ├── AlipayPayStrategy.java  # 支付宝 Mock
        └── WechatPayStrategy.java  # 微信 Mock
```
