# my-xhs-payment 测试用例矩阵（L1-L4）

## L1 业务

| ID | 用例 | 请求/数据 | 预期 | 状态 |
|---|---|---|---|---|
| P-L1-01 | 创建支付单 | POST /api/payment/pay | 支付单待支付 | ✅ |
| P-L1-02 | 支付前回查订单 | 非待付款订单 | ORDER_STATUS_ERROR 拒绝 | ✅ |
| P-L1-03 | Mock 支付成功 | payType=99 | t_payment=1 + 订单0→1 | ✅ |
| P-L1-04 | 支付失败回调 | 失败回调 | ✅ 幂等不覆盖已支付(Mock无支付中窗口) |
| P-L1-05 | 退款 | POST /api/payment/refund | t_refund=1 + 订单已退款 | ✅ |
| P-L1-06 | 部分退款 | 部分金额 | 支付单保持已支付 | ✅ 50/199保持1 |
| P-L1-07 | 退款金额超限 | 退>支付金额 | ✅ 30017超过可退金额 |
| P-L1-08 | 查询支付状态 | GET /status/{orderId} | 支付单状态 | ✅ |
| P-L1-09 | 重复支付拦截 | 同订单二次 pay | 幂等拒绝 | ✅ |
| P-L1-10 | 支付超时 | paymentTimeoutCheckJob | t_payment=2 | ⬜ |

## L2 数据

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| P-L2-01 | t_payment 状态机 | 0→1→3/0→2 | ✅ |
| P-L2-02 | t_refund 状态机 | 0→1/2/3 | ✅ |
| P-L2-03 | 独立 payment 库 | my_xhs_payment | ✅ |
| P-L2-04 | PAY_RESULT/REFUND_RESULT MQ | 消费/兜底 | ✅ |
| P-L2-05 | 事件流水 | t_payment_event | ✅ CREATE+PAY_SUCCESS |

## L3 质量

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| P-L3-01 | 乐观锁幂等 | 重复回调不改状态 | ✅ |
| P-L3-02 | 支付重复回调 | 30009订单状态不允许 | ✅ |
| P-L3-03 | 订单状态回查 | 已取消订单支付 | ✅ 30009不允许支付(P1-1) |
| P-L3-04 | 补偿 Job | paymentNotifyCompensate | ⬜ |
| P-L3-05 | 部分退款全额判断 | 累计=金额才置3 | ✅ 50+149累计=199置3/5+回补 |
| P-L3-06 | 对账 reconcile | 不一致补偿通知 | ⬜ |

## L4 可观测

| ID | 验证点 | 状态 |
|---|---|---|
| P-L4-01 | 支付事件流水 | ✅ t_payment_event落库 |
| P-L4-02 | 补偿任务日志 | ✅ |
| P-L4-03 | TraceId 跨 payment/order | ✅ |

## 已实测
- P-L1-01/02/03/05/08/09、L2-01/02/03/04、L3-01/02 ✅
- 退款失败/超时/业务拒绝自动退款/补偿/对账 待专项
