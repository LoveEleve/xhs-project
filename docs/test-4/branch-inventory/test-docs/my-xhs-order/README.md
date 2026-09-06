# my-xhs-order 测试重点

## 当前状态

- order 非 `target` 文件基线：56 个
- 本轮已修复测试编译与断言，order 58 个测试全部通过；common 53、coupon 13 个通过
- RocketMQ broker 当前运行中；order 服务（19011）未启动，运行级验证待启动后执行
- AI 排除；鉴权仅基础检查

## L1 业务

- 下单创建、参数校验、幂等
- 支付成功/失败/退款回调
- 发货、确认收货、取消、关单
- 订单详情、列表、订单号反查
- 优惠券折扣计算与核销

## L2 数据

- 分片 t_order/order_item/local_message/snapshot/event
- 映射表 t_order_no_mapping 反查
- 独立 payment 库
- ORDER_TRANSACTION/CLOSE/COMPENSATION topic
- 本地消息表补发与重试/DLQ

## L3 质量

- 下单幂等与用户锁
- 状态机并发（乐观锁）
- 事务消息回查/半消息
- 关单双通道（延时+Job）
- 补偿 MQ/Redis set/Job 重放
- 券折扣静默降级
- 库存预扣与释放/退款回补
- 支付重复回调
- 分片路由与非分片键查询

## L4 可观测性

- 订单创建/支付/退款/关单指标
- 本地消息积压、死信、补偿重放
- TraceId 跨 Order 与 6 个 Feign/MQ
- actuator/prometheus、SkyWalking、bucket 待启动验证

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
- 运行级验证可基于已启动的 order 与 broker 执行
