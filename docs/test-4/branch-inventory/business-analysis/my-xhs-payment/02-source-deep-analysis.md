# my-xhs-payment 源码深度分析

## 1. 模块定位与位置

支付服务（19012），资金域。order 通过 Feign（`/api/payment/pay`、`/api/payment/refund`）发起支付/退款，payment 通过 Feign（`/api/order/pay-success|pay-fail|refund-success|pay-amount|status`）回写订单。独立库 `my_xhs_payment`。

依赖链：`order --Feign--> payment --Feign--> order`（互相回调）；策略模式封装 Mock/支付宝/微信三种渠道。

## 2. 关键业务链路与源码流转

### 2.1 创建支付单 `PaymentService.pay()`
```
Redisson锁(3s等待/10s租期) → statusKey 双重检查(防重)
→ Feign getOrderStatus 回查(仅0待付款可支付, P1-1)   [PaymentService.java:179-189]
→ 设 payingKey(TTL30min) → 生成paymentNo/ID → INSERT t_payment(status=0)
→ 事件CREATE(异步) → 设 statusKey="0"(TTL30min)
→ 策略 pay() 返回tradeNo
→ Mock(99): handlePaySuccessInternal 同步成功; 异步渠道: registerCallback(模拟器5s后回调)
```
异常路径：BizException/Exception 均删除 payingKey/statusKey + 删除未成功的支付记录（仅 status=0 AND deleted=0），Redisson 锁 finally 安全释放。

关键决策：P1-1 回查订单状态是资金安全核心——防止对已取消/已支付订单发起支付。锁后双重检查用 statusKey 而非 payingKey（见风险 P-3）。

### 2.2 支付成功 `handlePaySuccessInternal()`
```
乐观锁 UPDATE t_payment SET status=1 WHERE order_id AND status=0  → 冲突则幂等返回
→ Redis statusKey="1"(7天TTL) + 删 payingKey
→ 事件PAY_SUCCESS + metrics
→ Feign notifyPaySuccess(orderId, tradeNo)
    - 返回业务失败(非503): businessRejected=true → 不发MQ → 自动 refund() 全额退回
    - Feign异常/503: 发 PAY_RESULT_TOPIC(预留,无消费端), 兜底=XXL-Job补偿
```

### 2.3 支付失败 `handlePayFailInternal()`
乐观锁置 2 → Redis statusKey="2" → 事件PAY_FAIL → 发MQ(预留) + Feign notifyPayFail（order 自动取消订单）。

### 2.4 退款 `refund()`
```
Redisson锁 → refundingKey SETNX(TTL7天) → findById + 归属校验(防越权) + 状态=1校验
→ 已退金额校验(累计退款≤支付金额, 支持部分退款)
→ INSERT t_refund(status=0) → 策略 refund()
→ Mock同步 handleRefundSuccessInternal; 异步渠道 registerRefundCallback
```

### 2.5 退款成功 `handleRefundSuccessInternal()`
```
乐观锁置1 → 累计退款 vs 支付金额
  - 全额(累计≥支付): t_payment置3 + Redis statusKey="3" + Feign notifyRefundSuccess + MQ
  - 部分: t_payment保持1 + Redis statusKey="1", 不通知订单
→ 删 refundingKey
```
T-077 修复：order 无 REFUND_RESULT_TOPIC 消费者，全额退款补 Feign 直调与支付成功对称。

### 2.6 超时与补偿
- `checkPaymentTimeout()`：T-062 改为 DB 扫描（status=0 AND created_at<30min → 乐观锁置 2），每 30s 由 XXL-Job 触发。Redis Lua 脚本方案已废弃（死代码已删）。
- `checkRefundTimeout()`：DB 扫描 15 天退款中 → 置 3 关闭，发 MQ（预留）。
- `PaymentNotifyCompensateJob`：扫描 status=1 且 paid_at<5min 的支付单，Feign getOrderPayAmount 判断订单是否仍待付款，是则重发 notifyPaySuccess；Redis 计数防无限重试（MAX 10 次）。
- `RefundNotifyCompensateJob`：扫描退款成功超窗记录，重发 notifyRefundSuccess。
- `reconcile()`：游标分页扫描 status=1 支付单，查订单 payAmount，待付款则补偿通知。

### 2.7 回调模拟器 `PayCallbackSimulator`
支付宝/微信异步渠道模拟：注册回调 pending key（TTL 24h/5min），@Scheduled 5s 扫描，SETNX 锁防重复，随机延迟 1-3s，90% 成功率，成功后删除 key。ObjectProvider 懒加载 PaymentService 破循环依赖。

## 3. 数据流转与中间件参与点

| 项 | 内容 |
|---|---|
| 库 | `my_xhs_payment`（独立 Hikari 数据源，不走 ShardingSphere） |
| 表 | t_payment（支付单）、t_refund（退款单）、t_payment_event（事件流水） |
| Redis | paying:30min / statusKey:7天 / refunding:7天 / 锁 key / 回调 pending / 补偿计数 |
| MQ | PAY_RESULT_TOPIC、REFUND_RESULT_TOPIC（预留，无消费端） |
| 定时 | XXL-Job 4 个 + @Scheduled 模拟器 2 个 |
| 事件 | t_payment_event append-only，独立线程池+DiscardPolicy 异步落库 |

## 4. 跨模块与分布式行为

- **与 order 双向 Feign**：pay 前回查、支付/退款结果回写。`InternalCallFeignConfig` 按路径注入 X-Internal-Call（pay-success/pay-fail/refund-success/refund-fail/pay-amount/status）。
- **幂等三重保障**：Redisson 锁 + Redis SETNX 幂等键 + DB 乐观锁（WHERE status=当前）。
- **退款部分/全额语义**：累计退款达到支付金额才置 3 并通知订单，避免部分退款后订单误置已退款。
- **补偿一致性**：支付成功但订单未收敛 → XXL-Job 补偿；退款成功但订单未收敛 → XXL-Job 补偿。

## 5. 性能与工程质量

- 支付单 ID/流水号用雪花 + Redis 自增，趋势递增利于索引
- 事件落库独立线程池（1/2 线程 + 队列 500 + DiscardPolicy），绝不影响资金主流程
- Hikari 连接池参数完备（max=20、keepalive、validation）
- Tomcat 线程数 100（等待回调场景），HTTP/2、压缩、优雅停机配置齐全
- 潜在：`checkRefundTimeout`/`reconcile` 单批次 100/200 游标分页，量大时多次往返

## 6. 鉴权基础检查

- `/api/payment/pay`、`/refund`、`/status/{orderId}` 均要求 X-Internal-Call（fail-closed）
- 回调端点要求 X-Internal-Call；order 侧对称校验
- 退款归属校验（payment.userId == 发起用户），防水平越权
- 回调体日志脱敏（P2-5）

## 7. 本轮修复与待评估

### 已修复（代码）
1. **PaymentServiceTest 构造器缺 `paymentEventMapper`**：PaymentService 有 12 个 final 字段，测试只传 11 个，编译失败 → 补 mock
2. **测试未 mock P1-1 的 `getOrderStatus` 回查**：5 个测试全部 error（"订单不存在或不可支付"）→ setUp 补 `R.ok(0)`
3. **删除死代码 `paymentTimeoutScript`**：PaymentConfig 的 Lua 脚本 Bean + PaymentService 字段在 T-062 改为 DB 扫描后从未使用 → 删除 PaymentConfig 类与字段、测试 mock
4. **补偿任务缩进错误 + 日志失真**：PaymentNotifyCompensateJob/RefundNotifyCompensateJob 的 incrementRetryCount 缩进错误；"扫描=%d" 用 compensated 填充失真 → 修正
5. **注释失真**：支付成功删幂等键注释（退款后不会再次支付）、MQ"订单消费者会兜底"（实际无消费端）→ 修正为真实兜底链
6. **死变量 feignFailed**：声明并赋值但从未读取 → 删除

### 运行态修复（全局）
7. **XXL-Job 调度全缺失**：xxl_job_group/xxl_job_info 均为空，10 个 executor 在线但无任务 → 生成全量初始化脚本（19 个任务）执行并验证，orderCloseJob/paymentTimeoutCheckJob/localMessageRetryJob/paymentNotifyCompensateJob 等已按 cron 正常执行（handle_code=200）

### 待评估（有兜底/设计权衡）
- P-1 MQ 兜底断链：Feign 失败时发 PAY_RESULT_TOPIC 无消费端。真实兜底为 XXL-Job paymentNotifyCompensateJob（已恢复运行），MQ 消息滞留无害。可选：启用预留消费者或删发送。
- P-2 超时支付单不通知订单：checkPaymentTimeout 只置 t_payment=2，不通知 order；订单靠自身关单兜底（orderCloseJob 每分钟）。两方超时窗口 30min 基本同步，可接受。
- P-3 双重检查用 statusKey：已退款(3)订单无法再次支付，与 P1-1 回查（非待付款拒绝）语义一致，属设计一致而非 bug。
- P-4 模拟器回调无解锁：SETNX 锁 30s 自然过期，失败保留 pending key 下轮重试，可接受。

## 8. 风险与测试重点

| 类别 | 风险 |
|---|---|
| 代码 | 死代码已清；JdbcTemplate 手写 SQL 字段映射需防漂移 |
| 业务 | 部分退款 → 全额退款链路；退款金额校验并发（锁内已保证） |
| 分布式 | 支付成功 Feign 失败依赖补偿 Job；回调重复（幂等已保证） |
| 性能 | 事件线程池 DiscardPolicy 高并发下事件可能丢弃（可容忍） |
| 可观测 | paymentNotifyCompensateJob 日志需跟踪；reconcile 依赖人工关注 |

测试重点：支付/退款幂等、部分退款、P1-1 订单状态拒绝、回调重复、补偿 Job 计数。
