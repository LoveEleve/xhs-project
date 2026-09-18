# 订单域接入通知系统（发现 → 修复 → 验证）2026-09-18

> 背景：跨域 E2E 实测发现订单全生命周期 0 通知（`docs/interview/专题-业务全链路推演.md` 4.1）。
> 本报告记录修复实现、聚合语义取舍与验证证据。

## 一、缺口（修复前）

- order 模块无任何 `NOTIFICATION_TOPIC` 生产者；支付/发货/收货/退款后 `t_notification`=0。
- 通知管道本身可用：跨用户评论 → 1 条 type=2「评论通知」（实测）。
- 契约早已预留：`NotificationEventDTO`（type 5=订单、targetType 3=订单）、`NotificationType.ORDER`、`t_push_template(type='order')`=「订单通知」。

## 二、实现

| 变更 | 文件 | 说明 |
|---|---|---|
| 新增发布器 | `my-xhs-order/.../service/OrderNotificationPublisher.java` | type=5 事件；MQ `asyncSend` + `MqTraceHelper` 包装 + 失败仅告警（不阻塞交易） |
| 埋点×3 | `OrderService.java` | `onPaymentSuccess`（支付成功）/ `deliverOrder`（已发货，附物流）/ `onRefundSuccess`（退款到账）；仅状态机真实流转成功后发送，天然防重复 |
| 聚合分支 | `NotificationAggregator.java` | type=5 不做"某用户等 N 人"社交化聚合：标题保持模板标题，**内容刷新为最新状态** |
| Mapper | `NotificationMapper.java` | 新增 `updateOrderLatest(id, content, extraData)` |
| 测试构造器 | `OrderServiceTest.java` | 补注入 `OrderNotificationPublisher` mock |
| E2E 断言 | `scripts/test-e2e-business-chain.py` | 第 20 步要求 ≥1 条通知；新增第 29 步断言退款后内容="退款已到账" |

## 三、聚合语义与取舍（面试要点）

- 约束：`t_notification` 有唯一键 `uk_aggregate(user_id, type, target_id, notify_date)` → **同一订单当天只能一条**。
- 选择：同订单当天聚合为一条，`aggregate_count` 记录更新次数（实测 count=3），内容刷新为最新状态。
- 取舍：中间状态（支付成功/已发货）会被后续状态覆盖，用户看到的是"最新进展"；订单详情页是完整状态源。
- 若要"每个状态独立成条"：需扩展唯一键（如增加 biz_key 列），属于表结构变更，本次不做（在报告的"后续可选项"中登记）。

## 四、验证（2026-09-18 23:46-23:50）

| 项 | 结果 |
|---|---|
| E2E 全链路 | **30/30 PASS**（`e2e-business-chain-run-20260918-234618.json`） |
| 第 20 步（发货后） | 1 条 type=5，标题「订单通知」，内容"…已发货，请注意查收（SF SF…）" |
| 第 29 步（退款后） | 1 条 type=5，内容"…退款已到账"，`aggregate_count=3` |
| API 回归 07-14 | **94/94 PASS**（与修复前基线一致） |
| 单测（order+notification） | **59/59 PASS** |
| 发布 | order、notification 各一次全量发布，健康检查通过 |

## 五、变更文件
`my-xhs-order/.../OrderNotificationPublisher.java`（新增）、`OrderService.java`、`OrderServiceTest.java`、`my-xhs-notification/.../NotificationAggregator.java`、`NotificationMapper.java`、`scripts/test-e2e-business-chain.py`、`docs/interview/专题-*`（同步更新）、证据 JSON×2。
