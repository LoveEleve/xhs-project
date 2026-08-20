# Code Structure Layer 验证报告（2026-08-20）

> 目标：验证当前 `service-map + feign-map + mq-map` 是否已经足够支撑 `my-xhs-ai` 回答“这些逻辑在源码结构里大致落在哪里”这类问题。

---

## 一、验证范围

当前纳入验证的结构单元：

### 1. service-map
- `gateway`
- `home`
- `order`
- `inventory`
- `payment`
- `coupon`

### 2. feign-map
- `order-feign-map`
- `order-to-user`
- `order-to-product`
- `order-to-inventory`
- `order-to-coupon`
- `order-to-payment`

### 3. mq-map
- `core-topics`
- `ORDER_TRANSACTION_TOPIC`
- `NOTIFICATION_TOPIC`
- `FEED_TOPIC`
- `RECOMMEND_BEHAVIOR_TOPIC`

---

## 二、问题 1：`order 为什么会成为编排中心？`

### 期望命中
- 主单元：`order` (service-map)
- 辅单元：`order-feign-map`
- 辅单元：`order-to-user`
- 辅单元：`order-to-product`
- 辅单元：`order-to-inventory`
- 辅单元：`order-to-coupon`
- 辅单元：`order-to-payment`

### 实际组合回答（试跑版）

从代码结构上看，`order` 之所以成为编排中心，不是因为它有一张订单表，而是因为它在源码层同时持有：

- 订单入口（Controller）
- 核心编排服务（`OrderService` / `OrderTransactionService`）
- 多个 Feign 依赖（user / product / inventory / coupon / payment）
- 补偿消费者
- 本地消息补发 / 关单等任务

也就是说，在代码结构里，`order` 不是一个孤立的领域服务，而是天然位于同步收束链的中心位置。

### 评估
#### 成功的地方
- `service-map + feign-map` 已经足够稳定地证明 order 是代码层的编排中心
- 这个回答不再需要直接回源码 grep，也能先给出有层次的定位答案

#### 暂时不足
- 还没有更细的 `call-chain-map`，因此“controller -> service -> feign -> message” 的完整调用路径还不能精确展开

### 结论
> 当前 code structure layer 已经能稳定回答“order 为什么在代码层也表现为编排中心”。

---

## 三、问题 2：`如果订单链路出问题，大概会落在哪些代码层？`

### 期望命中
- 主单元：`order` (service-map)
- 辅单元：`inventory` (service-map)
- 辅单元：`payment` (service-map)
- 辅单元：`coupon` (service-map)
- 辅单元：`order-feign-map`
- 辅单元：`ORDER_TRANSACTION_TOPIC`

### 实际组合回答（试跑版）

如果订单链路出问题，当前结构层已经能把问题先分到几类组件上：

1. **入口与编排层**
   - `OrderController`
   - `OrderService`
   - `OrderTransactionService`

2. **同步下游依赖层**
   - `UserFeignClient`
   - `ProductFeignClient`
   - `InventoryFeignClient`
   - `CouponFeignClient`
   - `PaymentFeignClient`

3. **异步推进层**
   - `ORDER_TRANSACTION_TOPIC`
   - `OrderTransactionConsumer`

4. **补偿与任务层**
   - `LocalMessageRetryJob`
   - `OrderCloseJob`
   - 补偿消费者

这意味着当前知识层已经能回答：
> 这类问题更像是 controller/service/feign/mq/job 的哪一层责任。 

### 评估
#### 成功的地方
- 当前结构层已经具备“按组件层次定位问题”的能力
- 对于运行态排障来说，这一层已经非常有用

#### 暂时不足
- 仍然不能精确回答“哪一个方法、哪一段调用”负责这次问题
- 这需要下一步的 `call-chain-map`

### 结论
> 当前 code structure layer 已经能做粗粒度源码责任定位，但还不是细粒度调用链追踪层。

---

## 四、问题 3：`哪个 topic 负责把订单成立推进到库存世界？`

### 期望命中
- 主单元：`ORDER_TRANSACTION_TOPIC`
- 辅单元：`core-topics`
- 辅单元：`order` (service-map)
- 辅单元：`inventory` (service-map)

### 实际组合回答（试跑版）

从当前 `mq-map` 可以直接回答：
- `ORDER_TRANSACTION_TOPIC` 是订单成立后推进库存预扣的第一跳绑定器
- producer 是 `my-xhs-order`
- consumer 是 `inventory-order-transaction-consumer-group`
- payload 里关键字段包括 `orderNo` / `userId` / `skuItems`

这已经足够支撑解释：
- 订单世界是怎样把“订单已成立”这件事交给库存世界的

### 评估
#### 成功的地方
- 这类“topic 语义定位”问题，当前 `mq-map` 已经能直接回答
- 相比全文检索源码，稳定性明显更高

#### 暂时不足
- 如果继续问“这个 topic 为什么不能被普通消息替代”，还需要跳回 architecture/business cards

### 结论
> `mq-map` 已经能支撑关键 topic 的语义级回答。

---

## 五、问题 4：`NOTIFICATION_TOPIC`、`FEED_TOPIC`、`RECOMMEND_BEHAVIOR_TOPIC` 各自是什么角色？`

### 期望命中
- 主单元：`core-topics`
- 辅单元：`NOTIFICATION_TOPIC`
- 辅单元：`FEED_TOPIC`
- 辅单元：`RECOMMEND_BEHAVIOR_TOPIC`

### 实际组合回答（试跑版）

当前 `mq-map` 已经能把这几条异步扩散链区分开：

- `NOTIFICATION_TOPIC`：把评论/点赞/关注/交易等动作推进到通知域
- `FEED_TOPIC`：把内容发布推进到 Feed 分发链
- `RECOMMEND_BEHAVIOR_TOPIC`：把用户行为推进到推荐侧特征链

这说明 code structure layer 已经不只是知道“有哪些 topic”，而是开始知道：
> 每个 topic 在系统里的角色语义是什么。

### 评估
#### 成功的地方
- 主题角色边界已经基本成立
- 回答不再停留在“谁生产谁消费”，而开始有系统语义

#### 暂时不足
- 仍然没有完整 `async-event-map`，对于“多条 topic 如何组成扩散网”回答还不够强

### 结论
> 当前 `mq-map` 已经能回答“某条 topic 是什么角色”，但还没形成完整异步扩散网总图。

---

## 六、问题 5：`gateway、home、order` 在源码结构上的差别到底是什么？`

### 期望命中
- 主单元：`gateway` (service-map)
- 主单元：`home` (service-map)
- 主单元：`order` (service-map)
- 辅单元：`order-feign-map`

### 实际组合回答（试跑版）

从当前 `service-map` 层能看出一个非常清楚的差异：

- **gateway**：没有业务 Controller/Service 主线，而是路由、过滤器、入口治理配置为主
- **home**：以 Controller + Feign 聚合为主，明显偏 BFF 读聚合结构
- **order**：不仅有 Controller / Service，还同时携带多 Feign、Consumer、Job，明显承担交易主链编排责任

也就是说，即使不回到长文，当前结构层已经能在代码组织上区分：
- 入口治理层
- 读聚合层
- 事务编排层

### 评估
#### 成功的地方
- `service-map` 的结构已经足够把三种中心/角色在源码层区分开
- 这使得“为什么复杂度不同”这个问题第一次有了代码结构层答案

#### 暂时不足
- 还没细到具体 filter / controller / service 方法链级别

### 结论
> 当前 service-map 已经能支撑“不同服务在代码结构层为什么看起来不一样”这个问题。

---

## 七、问题 6：`下单主链在代码层是怎样推进的？`

### 期望命中
- 主单元：`order-create-mainline`
- 辅单元：`order` (service-map)
- 辅单元：`order-feign-map`
- 辅单元：`ORDER_TRANSACTION_TOPIC`

### 实际组合回答（试跑版）

从当前 code structure layer 看，下单主链在代码层的推进大致是：

1. `OrderController` 接请求
2. `OrderService` / `OrderTransactionService` 同步收束地址、商品、库存、优惠等前置事实
3. 在本地事务里写订单主表、明细、事件和本地消息锚点
4. 通过 `OrderTransactionListener` / `ORDER_TRANSACTION_TOPIC` 把“订单成立”推进到库存世界
5. 由 `OrderTransactionConsumer` 在 inventory 侧继续接管库存预扣

这说明 code structure layer 已经能把“谁是什么角色”提升成“主链在代码层怎样流动”。

### 评估
#### 成功的地方
- `order-create-mainline` 显著提升了代码层路径解释能力
- 与 `service-map` / `feign-map` / `mq-map` 组合后，主链回答已经有了层次

#### 暂时不足
- 仍然是主路径骨架，不是方法级追踪图
- 若继续追问“具体到哪个方法和哪段状态推进”，还需要更细的 call-chain-map 或 state-map

### 结论
> `order-create-mainline` 已经让 code structure layer 从“静态结构导航”进入“路径级解释”。

---

## 八、问题 7：`订单成立后，库存预扣这条链在代码层怎么走？`

### 期望命中
- 主单元：`inventory-pre-deduct-mainline`
- 辅单元：`ORDER_TRANSACTION_TOPIC`
- 辅单元：`inventory` (service-map)
- 辅单元：`transaction-message-anchor`

### 实际组合回答（试跑版）

订单成立后，库存预扣不是 order 内部一个 if 分支，而是一条独立链路：

- `ORDER_TRANSACTION_TOPIC` 承担订单世界到库存世界的第一跳 handoff
- `OrderTransactionConsumer` 消费消息，解析 `orderNo / userId / skuItems`
- 先做 msgId 幂等和 pseudoOrderId 派生
- 再进入 `InventoryService.preDeduct`
- 成功则进入预扣语义，失败则抛异常触发 MQ 重试 / DLQ

### 评估
#### 成功的地方
- 这条链已经能说明“库存预扣是被 inventory 侧接管的一条独立主链”
- 也能把它和事务消息的第一跳绑定器联系起来

#### 暂时不足
- 还没细到 Redis bucket / Lua / MySQL 幂等占位层
- 若继续追问三级扣减实现，需要 state-map 或更细卡片

### 结论
> `inventory-pre-deduct-mainline` 已经能稳定支撑“库存预扣是怎样被接管和推进的”这种代码层问题。

---

## 九、问题 8：`支付成功后，主交易链在代码层怎样继续推进？`

### 期望命中
- 主单元：`payment-success-mainline`
- 辅单元：`payment` (service-map)
- 辅单元：`order-inventory-payment-flow`
- 辅单元：`inventory` (service-map)

### 实际组合回答（试跑版）

支付成功后，交易主链并没有结束，而是继续推进：

- 支付服务确认支付成功并更新支付状态
- order 侧感知支付结果，继续推进订单完成语义
- inventory 从预留态推进到最终消耗态
- 后续通知、补偿、退款等再继续在后面展开

所以 payment-success-mainline 解释的不是“支付成功这个点”，而是“成功之后谁继续接棒”。

### 评估
#### 成功的地方
- 当前 code structure layer 已经能解释“支付成功只是状态推进中点，不是终点”
- 与 business cards 结合后，系统解释开始变得完整

#### 暂时不足
- 还没有细到支付通知、退款链的分叉条件

### 结论
> `payment-success-mainline` 已经让 code structure layer 开始能回答“后半段主链怎么继续走”。

---

## 十、问题 9：`DLQ 重投为什么必须经过审批，这条链在代码层怎么走？`

### 期望命中
- 主单元：`dlq-redeliver-mainline`
- 辅单元：`mqDlqQuery` / `dlq.redeliver` 工具相关实现
- 辅单元：`RunController` / `RunManager`
- 辅单元：`PolicyGuard`

### 实际组合回答（试跑版）

DLQ 重投链在代码层不是“查到 msgId -> 调接口”这么短，而是：

1. Agent 先调 `mqDlqQuery`
2. 获取 `ORIGIN_MESSAGE_ID`
3. 因为 `dlq.redeliver` 是 L3，高危动作先被 `PolicyGuard` 判为 requiresApproval
4. Harness 进入 `WAITING_APPROVAL`
5. `RunController /approve` 触发 resume
6. `DlqRedeliverTool.redeliver` 真正调用 Dashboard 重投端点

这条代码链解释了：
- 为什么这不是普通工具调用
- 为什么审批不是聊天话术，而是系统状态与 API 语义

### 评估
#### 成功的地方
- 这是当前 code structure layer 最能体现“控制面价值”的一条链
- 它成功把 tool / policy / harness / api / execution 串起来了

#### 暂时不足
- 对 trace 侧、Langfuse 观测侧的链接还可以继续补一层 map

### 结论
> `dlq-redeliver-mainline` 已经能稳定解释“为什么高危动作不是工具调用，而是审批型执行链”。

---

## 十一、整体评估

### 11.1 当前 code structure layer 已经能回答什么
它已经能比较稳地回答：
- 哪个服务在代码里承担什么角色
- order 为什么会成为编排中心
- 某类问题更可能落在哪类组件层
- 关键 topic 是谁生产、谁消费、语义是什么
- gateway / home / order 在源码组织上为什么不同
- 下单主链在代码层怎样推进
- 库存预扣是怎样被 inventory 接管的
- 支付成功后主链怎样继续推进
- DLQ 重投为什么必须经过审批并如何执行

也就是说，code structure layer 已经不只是“有几张 map”，而是开始具备：
> **从系统理解落回源码结构导航，并进一步解释关键路径如何在代码层推进的能力。**

### 11.2 还缺什么
下一步最自然还缺：
- `state-map`
- `async-event-map`
- 更细的 `call-chain-map`（尤其是通知/推荐/补偿尾链）

因为当前最弱的是：
- 更细粒度的状态机落点
- 多条异步扩散链如何组成更大的网络
- 从运行态症状反推到更具体方法级责任点

### 11.3 当前阶段的准确判断
> 第一批 code structure layer 已经成立，而且已经从“静态结构导航”推进到“关键路径导航”；它已经足以让 Agent 从“会讲系统”迈向“会解释系统在代码层如何推进”。