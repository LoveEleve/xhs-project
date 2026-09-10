# my-xhs-order 测试用例矩阵（L1-L4）

## L1 业务

| ID | 用例 | 请求/数据 | 预期 | 状态 |
|---|---|---|---|---|
| O-L1-01 | 下单创建 | POST /api/order/create | 200 订单0待付款 | ✅ |
| O-L1-02 | 参数校验 | quantity=0/缺bizIdentifier | 40002拒绝 | ✅ |
| O-L1-03 | 无效地址 | addressId=9999 | 10006收货地址不存在 | ✅ 修复 |
| O-L1-04 | 幂等 | 同bizIdentifier重复 | 40201请勿重复下单 | ✅ |
| O-L1-05 | 支付成功 | pay/create payType=99 | 订单0→1 | ✅ |
| O-L1-06 | 支付失败回调 | /pay-fail | 订单0→4 | ✅ |
| O-L1-07 | 发货/收货 | deliver/confirm | 1→2→3 | ✅ |
| O-L1-08 | 取消 | /cancel | 0→4+库存回补 | ✅ |
| O-L1-09 | 超时关单 | orderCloseJob | 0→4+库存回补 | ✅ |
| O-L1-10 | 订单详情/列表/反查 | get/list/by-order-no | 正确返回 | ✅ |
| O-L1-11 | 退款 | refund | 3→5+库存回补 | ✅ 含已完成 |

## L2 数据

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| O-L2-01 | 分片路由 | user_id=10001→order_1.t_order_0 | ✅ |
| O-L2-02 | 映射表反查 | t_order_no_mapping | ✅ |
| O-L2-03 | 本地消息表 | status/重试 | ✅ |
| O-L2-04 | 事务消息补发 | LocalMessageRetryJob | ✅ |
| O-L2-05 | 事件流水 | t_order_event | ✅ |
| O-L2-06 | 下游故障fail-closed | 停inventory→30004/停product→50002 | ✅ 拒绝下单不跳过校验 |

## L3 质量

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| O-L3-01 | 状态机乐观锁 | 支付重复回调30009不改状态 | ✅ |
| O-L3-02 | 事务消息回查/半消息 | broker回查 | ✅ 半消息→本地事务→COMMIT→预扣 |
| O-L3-03 | 关单双通道 | 延时+Job | ✅ Job |
| O-L3-04 | 补偿重放 | 本地消息补发(status2→1) | ✅ 补发成功=1 |
| O-L3-05 | 券折扣 | 满减核销/退券 | ✅ |
| O-L3-06 | 库存预扣/回补 | 下单/取消/退款 | ✅ |
| O-L3-07 | 分片非分片键查询 | 反查/状态 | ✅ |

## L4 可观测

| ID | 验证点 | 状态 |
|---|---|---|
| O-L4-01 | actuator health | ✅ |
| O-L4-02 | 限流/死信/重放 | ✅ @RateLimit 5/60s触发40202 |
| O-L4-03 | TraceId 跨 Feign/MQ | ✅ 注入X-Trace-Id跨order→MQ→inventory一致 |

## 已实测
- L1 全 11 项、L2 全 5 项、L3 多数、L4 部分 ✅
- 事务消息回查/补偿造场景/DLQ/TraceId 待专项
