# my-xhs-order 测试重点

## 当前状态

- order 非 `target` 文件基线：56 个
- 本轮已修复测试编译与断言，order 58 个测试全部通过；common 53、coupon 13 个通过
- RocketMQ broker 当前运行中；order 服务（19011）未启动，运行级验证待启动后执行
- AI 排除；鉴权仅基础检查

## L1 业务

- ✅ 下单创建
- ✅ 参数校验（quantity=0/缺幂等键拒绝；**addressId无效拒绝【实测发现修复】**）
- ✅ 幂等（同 bizIdentifier 40201 请勿重复下单）
- ✅ 支付成功/失败/退款回调（pay-success/pay-fail/refund-success 全实测）
- ✅ 发货、确认收货、取消、关单（0→1→2→3、取消0→4、超时关单4）
- ✅ 订单详情、列表、订单号反查
- ✅ 优惠券折扣计算与核销（满300减50 2999-50=2949）

## L2 数据

- ✅ 分片路由（user_id=10001 → my_xhs_order_1.t_order_0 一致）
- ✅ 映射表 t_order_no_mapping 反查（order_id/user_id/order_no 正确）
- ✅ 独立 payment 库
- ✅ ORDER_TRANSACTION/CLOSE/COMPENSATION topic（已创建+消费验证）
- ✅ 本地消息表补发（status改0→LocalMessageRetryJob 补发成功=1）
- ⚠️ DLQ（maxReconsumeTimes 耗尽进 DLQ 未造场景实测）

## L3 质量

- ✅ 下单幂等与用户锁（幂等✅；用户锁并发未专门压测）
- ✅ 状态机乐观锁（支付重复回调 30009 不改状态）
- ⚠️ 事务消息回查/半消息（broker 回查机制未直接触发验证）
- ✅ 关单双通道（orderCloseJob ✅；RocketMQ 延时30min 未等真实触发）
- ⚠️ 补偿 MQ/Redis set/Job 重放（本地消息补发✅；compensation DLQ 兜底未造场景）
- ✅ 券折扣静默降级（已修复验证）
- ✅ 库存预扣与释放/退款回补（120→118/2→0，退款回120）
- ✅ 支付重复回调（乐观锁幂等）
- ✅ 分片路由与非分片键查询

## L4 可观测性

- ⚠️ 指标（prometheus 端点待验证）
- ⚠️ 本地消息积压/死信（DLQ 未造场景）
- ⚠️ TraceId 跨链路（日志有 traceId，未端到端断言）
- ✅ actuator health

## 已修复（本轮）
- 死代码 Mapper 方法删除（markPaid/markCompleted/markRefunded/selectPendingMessages/markFailed/markDead）
- 注释失真修正（映射表为同步写入）
- InternalCallFeignConfig 删除硬编码公开令牌，改为空默认 + fail-closed 拒绝启动
- 券折扣静默降级修复：携带券下单时折扣查询失败拒绝下单，避免券核销但全价
- 退款双重回补修复：退款前同步确认库存扣减清理预扣，仅走 refundRestore
- 补偿 DLQ 无重放修复：重试上限前写入 Redis 兜底，由 OrderCloseJob 重放
- 支付回调时间戳非原子修复：DataAccessException 捕获，避免误触发已支付订单退款
- order 58 个测试通过；order 已启动 19011 UP

## 当前未修改/待确认
- 本地消息重复投递（N-2）、事件全分片广播（N-3）、useCoupon 折扣丢弃（N-7）
- 预扣竞态（D1，inventory 超时恢复兜底）
- 映射修复全表扫描（有意设计，注释已声明）、列表无分页、广播 LIMIT pushdown
- 运行级验证已按 L1-L4 矩阵执行（见上），✅ 项为实测通过，⚠️ 项待专门场景
