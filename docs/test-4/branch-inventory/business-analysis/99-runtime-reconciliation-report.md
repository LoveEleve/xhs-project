# 运行态复核报告（2026-09-06）

> 背景：此前各模块均为源码级分析 + Mockito 单测，从未做过真实的业务级端到端运行验证。
> 复核确认：**「中间件进程在跑」≠「链路就绪」**。一次性暴露并修复了 11 类运行态问题。

## 复核方式

- 注入测试数据（用户/商品/库存/地址/推送模板），走 gateway 完整调用真实业务接口
- 验证 SQL、Redis、MQ、XXL-Job 全部中间件产物
- 全链路：下单 → 事务消息 → 库存预扣 → 支付(payment服务) → 库存确认 → 发货 → 收货 → 退款 → 库存回补/退券 → 关单

## 发现并修复的问题（11 类）

| # | 问题 | 影响 | 修复 |
|---|------|------|------|
| 1 | XXL-Job 调度全缺失（xxl_job_info/group 空表，10 executor 在线 0 任务） | 关单/超时/补偿/对账等全部兜底从未运行 | 全量注册 19 任务并验证执行（init-xxljob.sql） |
| 2 | RocketMQ 业务 topic 全缺失（仅 INVENTORY_TOPIC，autoCreateTopicEnable=false） | 所有 MQ 发送报 "No route info" 失败 | 全量创建 18 个 topic（init-rocketmq-topics.sh） |
| 3 | RefundNotifyCompensateJob 部分退款误全额（资损） | 部分退款被补偿任务误判全额 → 释放全部库存+退券+订单置已退款 | 只补偿 t_payment.status=3 的支付单 |
| 4 | payment 测试构造器缺参/死代码/补偿 Job 缩进日志失真 | 测试编译失败、误导日志 | 修复，5 测试通过 |
| 5 | 支付/退款结果 MQ 无消费端（PAY/REFUND_RESULT_TOPIC） | Feign 失败时无即时兜底 | 新增 order 侧两消费者，端到端验证 |
| 6 | t_user 缺 role 列 | 登录 BadSqlGrammar | 补 DDL + init-all.sql |
| 7 | MQ 消息 Long→String 跨模块解析断裂（JacksonConfig 全局序列化） | inventory 下单预扣断链、order/home 消费失败 | 4 个消费端兼容字符串/数字 id |
| 8 | order 已完成(3)订单退款被拒 | 收货后退款无法收敛、库存不回补 | onRefundSuccess 允许 1/3→5 |
| 9 | 4 张事件表缺失（t_cart_event/t_note_event/t_payment_event/t_product_behavior） | 事件落库失败（日志告警） | 补 DDL + init-all.sql（两份） |
| 10 | TransactionConfig 全局事务失效（@ConditionalOnBean 类级时序） | **所有读写分离服务 @Transactional 静默无效**，中间异常产生脏数据 | 移除类级条件，jcmd 确认 15 服务全加载 |
| 11 | product 布隆过滤器启动时序（启动时 DB 空 → 后插数据被拦截） | 商品详情查不到 | 重启重建（测试数据先于启动或重启） |

## 端到端验证结果（全部实测通过）

```
下单(skuId=3,qty=2,398元) → 事务消息 → 库存预扣 120→118/0→2 ✓
支付(order→payment服务) → t_payment=1 / 订单0→1 / 库存确认 locked2→0 ✓
发货1→2 ✓ 确认收货2→3 ✓
退款(payment) → t_refund=1 / t_payment=3 / 订单3→5 / 库存回补118→120 ✓
关单: orderCloseJob 关闭超时订单0→4 + 库存回补180/0 ✓
券: 建券(满300减50) → 领券(COUPON_CLAIM_TOPIC消费) → 下单核销2999-50=2949
    → 退款 → 券回退status1→0/used_order_id→NULL ✓
购物车: 加购 → CART_TOPIC → MySQL同步 + 事件落库(补表后) ✓
发布笔记: 事务原子(笔记+本地消息) → FEED_TOPIC → FeedPushConsumer 推送 ✓
本地消息兜底: LocalMessageRetryJob 补发(20:06 脏数据笔记被补推) ✓
MQ即时兜底: 支付成功Feign+MQUU(幂等共存) ✓
```

## 遗留说明

- 均为**设计权衡/正确终态**项（详见 payment 模块分析文档 P-2~P-5）
- Mock 支付渠道：order→payment 服务链路已真实走通（pay/refund/回调/对账），仅渠道资金入账为模拟
- 测试数据（5 订单/2 退款/2 笔记/1 券）保留，可复用后续联调

## 经验

运行态复核发现了源码分析 + 单测无法覆盖的问题：topic/job/schema/启动时序/跨模块消息契约。
此后模块分析文档的「待运行确认」项应尽快实测闭环，而非依赖「应有兜底」的假设。
