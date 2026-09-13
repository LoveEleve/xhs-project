# Code Structure 问答验证报告（2026-08-20）

> 目标：真实验证 `CODE_STRUCTURE` 路径是否能从 service-map / feign-map / mq-map / call-chain-map 回答源码结构问题。

---

## 一、验证问题

1. 哪个 topic 负责把订单推进到库存世界？
2. 哪个 consumer 负责库存预扣？
3. 哪个类负责事务消息回查？

---

## 二、结果

### 1. `哪个 topic 负责把订单推进到库存世界？`

结果：**✅ 通过**

回答：
- `ORDER_TRANSACTION_TOPIC`
- producer：`my-xhs-order`
- consumer：`inventory-order-transaction-consumer-group`
- 语义：订单成立后把库存预扣推进到 inventory 世界的第一跳绑定器

说明：
- `order-transaction-topic.yaml` 已经包含 `id/question/answer`
- 当前 loader 能把它当成可回答的结构知识卡加载

### 2. `哪个 consumer 负责库存预扣？`

结果：**✅ 已覆盖**

当前回答：
- `OrderTransactionConsumer`
- 位于 `my-xhs-inventory`
- 消费 `ORDER_TRANSACTION_TOPIC`
- 在订单成立后接管库存预扣链

说明：
- `order-transaction-consumer-card` 已经补上
- CODE_STRUCTURE 路由可以稳定回答 consumer 级源码导航问题

### 3. `哪个类负责事务消息回查？`

结果：**✅ 已覆盖（带合理补充）**

当前回答：
- 主答案：`OrderTransactionListener`
- 关键方法：`checkLocalTransaction`
- 补充：`InventoryService` 是库存预扣主逻辑落点

说明：
- 现在已经不再只讲“事务消息为什么存在”，而是能定位回查逻辑所在类
- 同时还能把相关主逻辑类补出来，形成更完整的源码导航回答

### 4. `哪个 job 负责本地消息补发与死信扫描？`

结果：**✅ 已覆盖**

当前回答：
- `LocalMessageRetryJob`
- 同时解释了它承担本地消息补发与死信扫描两件事

### 5. `哪个核心类负责库存预扣主逻辑？`

结果：**✅ 已覆盖（路由修正后）**

当前回答：
- 先答出 `OrderTransactionConsumer` 是接管预扣链的 consumer
- 再补充 `InventoryService.preDeduct()/doPreDeduct()` 是预扣主逻辑落点

说明：
- 此前这类“核心类 / 主逻辑”问法会误走 out-of-scope
- 修复 `IntentRouter + KnowledgeQuestionClassifier` 之后，已经能稳定进入 `CODE_STRUCTURE` 路径

---

## 三、当前真实结论

`CODE_STRUCTURE` 路由现在已经不再只是部分成立，而是已经具备第一版稳定可用能力：

- topic 级结构回答可用
- consumer 级源码导航可用
- listener / job 级定位可用
- 核心主逻辑类定位可用

也就是说，当前可以准确表述为：

> `CODE_STRUCTURE` 路由已经成立，并能稳定支撑组件入口、topic、consumer、listener、job、核心主逻辑类这几类源码导航问题。

---

## 四、下一步修复建议

### P0：继续把 code-map 扩到 state / async-event / call-chain 细化层
因为当前最弱的已经不是“哪类组件负责什么”，而是：
- 更细的状态机落点
- 更复杂的异步扩散网
- 方法级调用链

### P1：继续补第一批高价值 code cards
后续最值钱的方向：
1. `payment-service-card`
2. `refund-service-card`
3. `coupon-service-card`
4. `inventory-reconcile-job-card`

---

## 五、结论

这轮真实验证证明：

> **当前 code-map 已经从“结构素材层”升级成了“可被 Agent 直接消费的源码导航层”。**

换句话说，`my-xhs-ai` 现在不只是会讲系统和业务规则，也开始会回答“这段关键逻辑在代码里大致落在哪”。