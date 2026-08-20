# Call Chain Map 设计（2026-08-20）

> 目标：在 service-map / feign-map / mq-map 已成立后，再补上“这些节点在一次业务动作里如何串起来”的链路层。

---

## 一、为什么要补 call-chain-map

当前 code structure layer 已经能回答：
- 哪个服务是什么角色
- 哪个 Feign 依赖了谁
- 哪个 topic 谁生产谁消费

但还不能稳定回答：
- 一次下单到底先经过哪些类、再经过哪些下游、最后怎么推进到 MQ？
- 某个 5xx 更像是在哪一段调用链里断掉？
- 哪个环节是同步收束，哪个环节是异步接管？

也就是说，当前能做“结构导航”，但还不够做“路径导航”。

---

## 二、call-chain-map 要回答什么

call-chain-map 不是所有方法调用的全量图，而是要回答：

1. 一次典型业务动作从哪里进
2. 它在主链上经过哪些关键代码层
3. 哪一步进入下游同步依赖
4. 哪一步进入异步消息
5. 哪一步是状态锚点

所以它关注的是：
- 关键入口
- 关键转折点
- 关键 handoff
- 关键失败点

而不是所有方法细节。

---

## 三、推荐结构

```yaml
id: order-create-mainline
category: call-chain
question: 下单主链在代码层是怎样推进的？
entry:
  controller: OrderController
  service: OrderService
phases:
  - name: validate-and-collect-facts
    components:
      - UserFeignClient
      - ProductFeignClient
      - InventoryFeignClient
      - CouponFeignClient
  - name: local-transaction
    components:
      - OrderTransactionService
      - t_order / t_order_item / t_local_message
  - name: async-handoff
    components:
      - OrderTransactionListener
      - ORDER_TRANSACTION_TOPIC
      - OrderTransactionConsumer
state_anchor: 订单创建成功
sync_boundary: 订单成立前同步收束
async_boundary: 订单成立后异步推进
failure_hotspots:
  - Feign 下游失败
  - 事务消息回查
  - 本地消息补发
```

---

## 四、第一批最值得做的 call-chain-map

1. `order-create-mainline`
2. `inventory-pre-deduct-mainline`
3. `payment-success-mainline`
4. `dlq-redeliver-mainline`

建议先从 `order-create-mainline` 开始，因为它能把：
- service-map
- feign-map
- mq-map
- architecture / business cards

第一次真正串起来。
