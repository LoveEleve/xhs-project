# my-xhs-coupon 测试用例矩阵（L1-L4）

## L1 业务

| ID | 用例 | 请求/数据 | 预期 | 状态 |
|---|---|---|---|---|
| CO-L1-01 | 创建模板 | POST /api/coupon/template | 模板创建，remain=total | ✅ |
| CO-L1-02 | 模板上下架 | PUT /template/{id}/status | 下架后领券30016已下线 | ✅ |
| CO-L1-03 | 领券 | POST /api/coupon/claim | user_coupon + remain-1 + MQ | ✅ |
| CO-L1-04 | 重复领券 | 同模板二次领 | perUserLimit=2 语义正确 | ✅ |
| CO-L1-05 | 用券核销 | POST /use | 券 status→used | ✅ |
| CO-L1-06 | 退券 | POST /return | 券回退可用 | ✅ |
| CO-L1-07 | 可用券列表 | GET /user/available | 200 未用可用券 | ✅ |
| CO-L1-08 | 折扣计算 | GET /discount/{id} | 满减/折扣金额 | ✅ |
| CO-L1-09 | 用户券列表 | GET /user/list | 分页状态 | ✅ |
| CO-L1-10 | 过期券 | valid_end过去 | ✅ 30015优惠券已过期 |

## L2 数据

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| CO-L2-01 | Redis 库存/限领 | `myxhs:coupon:{id}:stock/claimed` | ✅ |
| CO-L2-02 | 模板缓存 | 创建后 getTemplateWithCache | ✅ |
| CO-L2-03 | COUPON_CLAIM_TOPIC 消费 | 领券持久化 | ✅ |
| CO-L2-04 | Outbox 补发 | t_coupon_outbox | ⬜ |
| CO-L2-05 | t_user_coupon 状态 | status/used_order_id | ✅ |

## L3 质量

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| CO-L3-01 | 并发领券超卖 | 10并发 | remain98→96, 限领2张, 30013拦截 | ✅ 不超发 |
| CO-L3-02 | 幽灵券 | MQ超时但broker已投递 | 幂等不重复 | ⬜ |
| CO-L3-03 | 券折扣静默降级 | 折扣查询失败 | 拒绝下单(修复) | ✅ |
| CO-L3-04 | 对账 | reconcile 修正 Redis/MySQL | ⬜ |
| CO-L3-05 | 限领绕过 | 领→用→退→再领 | perUserLimit 语义 | ⬜ |

## L4 可观测

| ID | 验证点 | 状态 |
|---|---|---|
| CO-L4-01 | 领券/核销/退券日志 | ✅ |
| CO-L4-02 | 扣减失败/重试/DLQ 指标 | ✅ Prometheus端点暴露 |
| CO-L4-03 | TraceId 跨 MQ | ⬜ |

## 已实测
- CO-L1-01/03/05/06/08/09、L2-01/02/03/05、L3-03 ✅
- 过期券/并发/幽灵券/对账 待专项
