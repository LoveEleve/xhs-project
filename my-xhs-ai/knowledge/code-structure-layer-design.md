# Code Structure Layer 设计（2026-08-20）

> 目标：在 architecture / business knowledge 已经立住之后，补上 `my-xhs` 源码结构层，让 `my-xhs-ai` 不只是会讲系统和规则，还能知道“这些逻辑在源码里大致落在哪”。

---

## 一、为什么现在该补 Code Structure Layer

当前知识层已经有两层：

### 1. Architecture Layer
能回答：
- 系统总图
- 服务边界
- 同步 / 异步协作
- 主交易链骨架

### 2. Business Layer
能回答：
- 下单怎么成立
- 库存为什么要三级扣减
- 支付链如何推进
- 券、关单、退款、补偿怎样收尾

这两层已经足够让 Agent 开始“懂系统”。

但还缺一层非常关键的桥：

> **当读者继续追问“这段逻辑在代码哪一层、哪一类组件里”时，系统还没有一层稳定的源码导航能力。**

也就是说，当前它更像：
- 会讲系统
- 会讲规则

但还不够：
- 会从系统解释顺滑地落回源码结构

这就是 Code Structure Layer 要补的东西。

---

## 二、这层要解决什么问题

这层不是为了做“全文源码 RAG”，而是为了回答四类问题：

### 1. 源码导航问题
- 哪个服务处理这个能力？
- 哪一类组件负责这个逻辑？
- Controller / Service / Feign / MQ / Job 各在哪？

### 2. 调用链定位问题
- 某次 5xx 对应哪一层调用链？
- 为什么 `CouponFeignClient` 会在这个位置出问题？
- 事务消息回查走哪条链？

### 3. 状态机落点问题
- 哪个类负责订单状态推进？
- 哪个 consumer 接库存预扣？
- 哪个 job 在做关单 / 补偿？

### 4. 配置边界问题
- 哪个 yml 控制哪个行为？
- 哪个开关影响哪个能力？
- 哪个 topic / route / cron 来自哪层配置？

这些问题的共同特点是：
- 不只是“讲设计”
- 也不是“查运行态”
- 而是要把“系统理解”映射回“源码结构”

---

## 三、为什么不能直接把整个源码仓库做全文检索

这是最直觉、也最 low 的做法。

### 看起来为什么合理
- 直接扫全仓
- 让 embedding / grep 去找
- 问什么搜什么

### 为什么不行

#### 1. 语义粒度不稳定
源码全文对模型来说噪音太大：
- import
- DTO
- 样板代码
- 配置细节
- 测试类

真正有价值的“结构知识”会被淹没。

#### 2. 系统边界会重新变糊
如果不先抽结构层，模型容易把：
- 某个配置片段
- 某个 Feign 接口
- 某个 Consumer

误当成“系统主线答案”。

#### 3. 成本高、稳定性差
全文检索虽然看起来灵活，但：
- 贵
- 不稳
- 很难复现
- 很难保证 Agent 总是先看到最关键的结构节点

所以 Code Structure Layer 的目标不是“让模型自由看仓库”，而是：
> **先把最关键的源码结构抽成可导航、可组合的知识层。**

---

## 四、Code Structure Layer 的推荐结构

我建议先拆成四类 map，而不是直接做一堆散卡。

### A. `service-map`
回答：
- 每个服务的核心角色是什么？
- 这个服务有哪些关键入口类型？

字段建议：
- service
- role
- key_controllers
- key_services
- key_consumers
- key_jobs
- key_configs

### B. `feign-map`
回答：
- 某个服务同步依赖了哪些下游？
- 这些依赖在主链里的角色是什么？

字段建议：
- caller_service
- callee_service
- client_class
- main_endpoints
- role_in_chain

### C. `mq-map`
回答：
- 哪些 topic 被谁生产、被谁消费？
- 这些消息属于“成立前绑定”还是“成立后扩散 / 补偿”？

字段建议：
- topic
- producers
- consumers
- semantic_role
- risk_level

### D. `state-map`
回答：
- 哪些核心状态机在哪个服务里？
- 哪个类在推进这些状态？

字段建议：
- domain
- state_owner
- key_classes
- transitions
- failure_paths

---

## 五、第一批最值得抽的结构单元

不要全量抽。先抓最值钱的主链结构。

### 1. 服务映射
优先：
- `order`
- `inventory`
- `payment`
- `coupon`
- `gateway`
- `home`

### 2. Feign 映射
优先：
- `order -> user`
- `order -> product`
- `order -> inventory`
- `order -> coupon`
- `order -> payment`

### 3. MQ 映射
优先：
- `ORDER_TRANSACTION_TOPIC`
- `NOTIFICATION_TOPIC`
- `FEED_TOPIC`
- `RECOMMEND_BEHAVIOR_TOPIC`

### 4. 状态映射
优先：
- 订单状态
- 库存状态
- 支付状态
- 券状态

这一步不是在“做完整源码地图”，而是在抽：
> **最能支撑架构解释 + 运行态定位的结构骨架。**

---

## 六、建议的数据格式

建议也走和 cards 类似的结构化格式。

### 示例：service-map
```yaml
service: order
role: 交易主链同步编排中心
key_controllers:
  - OrderController
key_services:
  - OrderService
  - OrderTransactionService
key_feign_clients:
  - UserFeignClient
  - ProductFeignClient
  - InventoryFeignClient
  - CouponFeignClient
  - PaymentFeignClient
key_consumers:
  - OrderCompensationConsumer
key_jobs:
  - LocalMessageRetryJob
  - OrderCloseJob
key_configs:
  - application.yml
```

### 示例：mq-map
```yaml
topic: ORDER_TRANSACTION_TOPIC
producers:
  - order-service
consumers:
  - inventory-order-transaction-consumer-group
semantic_role: 订单成立后推进库存预扣的第一跳绑定器
risk_level: high
```

---

## 七、这层和现有 layers 的关系

### 和 Architecture Layer 的关系
Architecture 负责回答：
- 谁是什么角色
- 边界在哪里

Code Structure Layer 负责回答：
- 这个角色在代码里大概落在哪里

### 和 Business Layer 的关系
Business 负责回答：
- 为什么这样运转
- 状态语义是什么

Code Structure Layer 负责回答：
- 哪些类/接口/consumer/job 承担这些语义

### 和未来 Agent 路由的关系
这层完成后，Agent 才能更稳地回答：
- “哪个类负责这个逻辑？”
- “这次 5xx 更像哪一层责任？”
- “为什么这段代码会出现在这里？”

---

## 八、下一步最合理的落地顺序

### Phase 1：先做 `service-map`
先抽 6 个服务：
- gateway
- home
- order
- inventory
- payment
- coupon

### Phase 2：再做 `feign-map`
重点只抽 order 这一组同步编排链。

### Phase 3：再做 `mq-map`
先抽最关键的几个 topic。

### Phase 4：最后补 `state-map`
先做订单 / 库存 / 支付 / 券四条状态线。

---

## 九、最重要的结论

当前 `my-xhs-ai` 的知识层已经完成了：
- 系统骨架层（Architecture）
- 业务规则层（Business）

下一步最自然也最值钱的，不是继续横向补更多业务域卡片，而是：

> **补 Code Structure Layer，让 Agent 具备从“系统理解”落回“源码结构”的能力。**

这一步补上之后，`my-xhs-ai` 才会真正从：
- 会讲系统

升级到：
- **会讲系统，也会定位代码结构。**