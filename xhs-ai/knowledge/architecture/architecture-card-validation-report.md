# Architecture Cards 组合回答验证报告（2026-08-20）

> 目标：不再停留在 schema 设计，而是用当前 9 张 architecture cards 真试一轮组合回答，验证这层知识到底能不能回答关键系统问题。

---

## 一、验证范围

当前用于试跑的卡：

1. `service-topology`
2. `gateway-role`
3. `bff-role`
4. `order-orchestration-role`
5. `sync-async-boundary`

选取 3 个代表问题做首轮验证：

1. `my-xhs 的整体架构是什么？`
2. `为什么 home 是 BFF，而不是事务中心？`
3. `哪些链路是同步 Feign，哪些链路是异步 RocketMQ？`

---

## 二、问题 1：`my-xhs 的整体架构是什么？`

### 期望命中
- 主卡：`service-topology`
- 辅卡：`gateway-role`
- 辅卡：`order-orchestration-role`
- 辅卡：`sync-async-boundary`
- 不应主出场：`bff-role`

### 实际组合回答（试跑版）

`my-xhs` 不是“16 个并排摆开的 Spring Boot 服务”，而是一个由统一入口、业务服务分层分组、同步编排链和异步扩散网叠加出来的系统。

先看最上层：
- 所有请求先进入 **gateway**，由它做统一路由、鉴权、限流和超时治理。

再看业务服务层：
- 用户、内容、商品、购物车、订单、支付、库存、搜索、首页、通知、IM 等服务按职责分组存在；
- 其中 **order** 是交易主链的同步编排中心，会在下单时通过 Feign 拉齐地址、商品、库存、优惠券、支付等分散事实；
- **home** 更像 BFF 聚合层，负责把商品、用户、通知等数据拼成页面视图，而不是承担交易事务。

再看协作方式：
- 在交易或动作成立前，系统更依赖 **同步 Feign** 把必要事实拉齐；
- 在动作成立后，再通过 **RocketMQ** 把通知、推荐、Feed、补偿等影响异步扩散到下游。

最底层则是数据与中间件：
- MySQL、Redis、Elasticsearch、RocketMQ、Nacos、XXL-Job 等共同承担事实存储、缓存、消息、配置和任务调度。

所以这个系统真正的复杂度，不在“服务数量多”，而在：**不同服务承担的角色完全不同——有的是入口层，有的是事务中心，有的是读聚合层，有的是异步扩散层。**

### 评估
#### 成功的地方
- `service-topology` 作为主卡是对的
- `gateway-role` 和 `order-orchestration-role` 的补充非常自然
- `sync-async-boundary` 的加入让“为什么复杂”这个问题真正站住了

#### 暂时不足
- `home` 的角色目前只能通过 `bff-role` 隐性补充，如果问题进一步追问首页/聚合层，会需要它显式出场
- 中间件层还缺一张专门的 `middleware-role` 卡，当前只能靠 `service-topology` 的总图式描述

### 结论
> 当前 4 张卡已经足够回答“整体架构是什么”这类总图问题，且回答质量已经明显高于单纯全文检索。

---

## 三、问题 2：`为什么 home 是 BFF，而不是事务中心？`

### 期望命中
- 主卡：`bff-role`
- 辅卡：`order-orchestration-role`
- 辅卡：`gateway-role`
- 不该主出场：`sync-async-boundary`

### 实际组合回答（试跑版）

`home` 在 `my-xhs` 里更像面向页面和体验层的 BFF，而不是事务中心。

原因在于它的职责不是让一笔交易成立，而是把多个下游服务的结果拼成一个前端可见视图。它依赖 product、user、notification 等下游服务，但这种依赖的目的是聚合展示，而不是在一次请求里完成强状态收束。

与它相对的，`order` 才是真正的交易编排中心：
- 下单时要同步拉齐地址、商品、库存、优惠券、支付等事实
- 下游任何一环失败，都可能让整条交易链不成立或进入补偿

所以两者虽然都“依赖很多服务”，但复杂度来源完全不同：
- **home** 复杂在聚合视图，失败语义更偏展示降级、少一块内容；
- **order** 复杂在交易一致性，失败语义更偏回滚、补偿、状态推进失败。

也因此，home 不能被理解成事务中心，更不能把它和 order 归为同一种“中心节点”。

### 评估
#### 成功的地方
- `bff-role` 作为主卡非常合适
- `order-orchestration-role` 作为对照卡，解释力很强
- `gateway-role` 实际上不是必须卡，只作为弱辅助即可

#### 暂时不足
- 当前没有一张专门的 `read-aggregation-vs-transaction` 卡，某些“为什么两个中心复杂度不同”的问题仍然要靠手工拼接

### 结论
> `bff-role + order-orchestration-role` 这组卡已经足以回答“为什么 home 是 BFF，而不是事务中心”这种对照型问题。

---

## 四、问题 3：`哪些链路是同步 Feign，哪些链路是异步 RocketMQ？`

### 期望命中
- 主卡：`sync-async-boundary`
- 辅卡：`order-orchestration-role`
- 辅卡：`service-topology`
- 不该主出场：`bff-role`

### 实际组合回答（试跑版）

`my-xhs` 不是“全同步”也不是“全异步”，而是同步编排链和异步扩散网并存。

同步侧主要发生在动作成立之前，尤其是交易主链：
- `order` 会通过 Feign 同步依赖 `user`、`product`、`inventory`、`coupon`、`payment` 等服务，
- 目的是在下单成立前，把地址、商品、库存、优惠券、支付等必要事实拉齐。

异步侧主要发生在动作成立之后：
- 内容发布、通知、推荐、Feed、补偿等，会通过 RocketMQ 向下游扩散，
- 目的是让已经成立的业务动作继续把影响传播到其他视图和其他体验层。

所以最关键的边界是：
- **同步链负责让动作成立**
- **异步链负责让影响扩散并最终收敛**

这也是为什么 RocketMQ 不是 Feign 的替代品，事务消息也不是“全异步系统”——它们只是把最关键的一跳从“本地事务成功”绑定到“消息对下游可见”。

### 评估
#### 成功的地方
- `sync-async-boundary` 完全能承担主卡角色
- `order-orchestration-role` 的辅助非常必要，因为同步 Feign 的意义要靠 order 才能讲透
- 这类问题目前回答质量已经比较稳定

#### 暂时不足
- 如果问题继续追问“具体有哪些 topic / consumer”，当前这张卡还不够，需要未来补 `async-event-map`

### 结论
> `sync-async-boundary` 这张卡已经足以承担“同步 vs 异步边界”问题的主回答单元。

---

## 五、问题 4：`系统里的 MySQL、Redis、RocketMQ、Nacos、XXL-Job、Elasticsearch 分别承担什么职责？`

### 期望命中
- 主卡：`middleware-role`
- 辅卡：`service-topology`
- 辅卡：`sync-async-boundary`
- 不该主出场：`bff-role`

### 实际组合回答（试跑版）

在 `my-xhs` 里，这些中间件不是“部署附件”，而是系统职责的一部分：

- **MySQL**：持有核心业务事实，例如订单、支付、库存等主数据
- **Redis**：承载缓存、热点状态、原子扣减、会话类状态
- **RocketMQ**：负责异步扩散与最终一致性推进，不是 Feign 的替代品
- **Nacos**：负责配置与服务发现
- **XXL-Job**：负责补偿、对账、周期任务执行
- **Elasticsearch**：负责搜索与检索视图，不是业务真相源

它们同时存在的原因，不是“微服务都这么配”，而是不同系统职责根本不能由一层存储或一种同步调用方式统一解决。

### 评估
#### 成功的地方
- `middleware-role` 已经可以独立承担“中间件职责总览”问题
- 与 `service-topology` 拼接后，能把中间件层放回总图，而不是孤立介绍基础设施

#### 暂时不足
- 这张卡适合讲“职责”，但还不足以回答“某个具体中间件在某个业务链里怎样发挥作用”
- 如果用户追问“为什么 RocketMQ 对 order 链这么关键”，仍然需要跳到 `transaction-message` 相关卡

### 结论
> `middleware-role` 的加入明显增强了“整体架构是什么”类问题的完整度，但它仍然是总述卡，不是链路细节卡。

---

## 六、问题 5：`为什么系统复杂度不是来自服务数量，而是来自职责差异？`

### 期望命中
- 主卡：`service-topology`
- 辅卡：`gateway-role`
- 辅卡：`bff-role`
- 辅卡：`order-orchestration-role`
- 辅卡：`middleware-role`
- 不该主出场：`sync-async-boundary`

### 实际组合回答（试跑版）

`my-xhs` 的复杂度不是“服务有 16 个”这件事本身，而是这些服务承担的角色完全不同：

- **gateway** 是统一入口和流量治理层
- **home** 是面向页面的 BFF 聚合层
- **order** 是交易主链的同步编排中心
- **inventory / payment / coupon** 是强状态链上的关键协作方
- **notification / analytics / counter** 更偏动作扩散和视图支撑
- **MySQL / Redis / RocketMQ / ES / XXL-Job / Nacos** 又在底层承担完全不同的中间件职责

如果只是“服务多”，系统仍可能只是并排部署的 CRUD；真正把复杂度抬起来的，是这些服务在同一张结构图里处于完全不同的职责位置。

### 评估
#### 成功的地方
- 当前 7 张辅助卡已经足够把“复杂度来自职责差异”讲得比较稳
- 这类问题不再需要直接回到 `vol-xhs` 长文，就能先给出结构化回答

#### 暂时不足
- `notification / analytics / counter` 这一组目前在卡片层还没有独立角色卡，解释扩散层角色时仍然偏概括

### 结论
> 这组卡已经能稳定支撑“复杂度来源于职责差异”这种系统级问题，但异步扩散层的角色解释还值得再补一张卡。

---

## 七、问题 6：`为什么不能把所有事情都放进 MQ？`

### 期望命中
- 主卡：`sync-async-boundary`
- 辅卡：`order-orchestration-role`
- 辅卡：`middleware-role`
- 不该主出场：`gateway-role`

### 实际组合回答（试跑版）

不能把所有事情都放进 MQ，因为 `my-xhs` 里有两种根本不同的职责：

- **动作成立前的必要事实拉齐**：例如下单前要确认地址、商品、库存、优惠券、支付条件，这一层更适合同步编排
- **动作成立后的影响扩散**：例如通知、推荐、Feed、补偿等，这一层更适合异步传播

如果把所有事情都丢进 MQ，就会把“成立条件”也异步化，导致：
- 订单是否能合法成立变得模糊
- 失败边界不清
- 补偿复杂度上升

所以 MQ 在这个系统里不是 Feign 的替代品，而是对“成立后扩散”这一层复杂度的承载。

### 评估
#### 成功的地方
- `sync-async-boundary` 已经能回答这类设计取舍问题
- `order-orchestration-role` 的辅助使“为什么同步不能被全部异步化”讲得更有抓手

#### 暂时不足
- 如果继续追问“事务消息为什么是第一跳绑定器”，需要专门的 `transaction-chain` / `transaction-message` 卡

### 结论
> 当前 7 张卡已经能比较稳地回答“为什么不是全同步，也不是全异步”，并且已经开始触到主交易链设计层；但更细的消息一致性与补偿细节仍要靠后续卡继续补。

---

## 八、问题 7：`my-xhs 的主交易链是怎样流动的？`

### 期望命中
- 主卡：`transaction-chain`
- 辅卡：`order-orchestration-role`
- 辅卡：`sync-async-boundary`
- 辅卡：`middleware-role`
- 不该主出场：`gateway-role`

### 实际组合回答（试跑版）

`my-xhs` 的主交易链不是“用户下单 -> 订单表插入 -> 支付”这么简单，而是一条从商品与购物车起步、在 order 服务处同步收束、再向库存 / 支付 / 通知等方向推进的复合链路。

更具体地说：
- 商品与购物车、优惠券属于交易动作成立前的准备层；
- order 在创建订单前，会同步拉齐地址、商品、库存、优惠、支付等必要事实；
- 订单创建是这条链的状态锚点，后续库存预扣、支付推进、补偿与通知都围绕它展开；
- 所以这条链不是单次本地事务，也不是单次消息投递，而是同步收束和异步推进叠在一起的一条主链。

### 评估
#### 成功的地方
- `transaction-chain` 明显补上了此前 architecture layer 缺少的“主链总述卡”
- `order-orchestration-role` 和 `sync-async-boundary` 的辅助非常自然，能把“为什么 order 复杂”与“为什么这条链不能只靠同步或只靠异步”同时讲清

#### 暂时不足
- 当前仍然缺少 `order-inventory-payment-flow` 这种更细颗粒度链路卡，因此当问题继续追问“支付和库存哪个先推进”“事务消息在哪一跳接管”时，还需要回到 `vol-xhs` 长文或继续补卡

### 结论
> `transaction-chain` 的加入，让当前这批 architecture cards 第一次具备了回答“主交易链怎么走”这一类核心业务结构问题的能力。

---

## 九、问题 8：`订单、库存、支付三者在主交易链里是怎样衔接的？`

### 期望命中
- 主卡：`order-inventory-payment-flow`
- 辅卡：`transaction-chain`
- 辅卡：`order-orchestration-role`
- 辅卡：`sync-async-boundary`
- 不该主出场：`gateway-role`

### 实际组合回答（试跑版）

在 `my-xhs` 的主交易链里，`order`、`inventory`、`payment` 不是三个平行 CRUD 服务，而是一条连续推进链：

- `order` 先同步收束地址、商品、库存、优惠等事实，创建“合法成立”的订单；
- 订单成立之后，通过事务消息把“库存应该看见这笔订单”推进给 `inventory`，由库存侧完成预扣这一跳；
- 之后再由 `payment` 链接管资金状态推进；
- 订单成立后的通知、补偿等动作，再继续向下游扩散。

所以这里最关键的，不是“谁先被调用”，而是：
- 订单成立前谁负责收束条件
- 订单成立后谁接管后续状态推进

### 评估
#### 成功的地方
- `order-inventory-payment-flow` 显著提升了主交易链回答的精度
- `transaction-chain` 提供总图，`order-orchestration-role` 提供中心角色，`sync-async-boundary` 提供协作边界，组合关系很顺

#### 暂时不足
- 如果继续追问“库存预扣为什么要三级扣减”，仍需要未来补 `inventory-state-role`
- 如果继续追问“支付成功后通知和补偿怎么分叉”，仍需要补 `async-event-map`

### 结论
> 加入 `order-inventory-payment-flow` 后，architecture layer 已经不只能讲“主交易链存在”，而开始能解释三大核心域是如何衔接的。

---

## 十、问题 9：`为什么事务消息、本地消息表和补偿任务必须同时存在？`

### 期望命中
- 主卡：`transaction-message-anchor`
- 辅卡：`transaction-chain`
- 辅卡：`order-inventory-payment-flow`
- 辅卡：`sync-async-boundary`
- 不该主出场：`bff-role`

### 实际组合回答（试跑版）

事务消息、本地消息表和补偿任务之所以必须同时存在，不是因为系统“喜欢复杂”，而是因为它们在主交易链里解决的是三种完全不同的问题：

- **事务消息**：把“订单本地提交成功”和“库存预扣消息对下游可见”绑进同一条推进链，解决最危险的第一跳断裂
- **本地消息表**：为事务回查和补发提供订单域内部的证据锚点，保证系统知道“这笔订单到底是否已提交”
- **补偿任务/补偿消息**：在订单已经成立后，某条后续链路（例如释放库存、退券）失败时，继续收残局

所以这三者不是一个机制的不同写法，而是：
- 一个解决“订单如何安全成立”
- 一个解决“消息如何不丢”
- 一个解决“成立后的后续失败如何继续收敛”

### 评估
#### 成功的地方
- `transaction-message-anchor` 把“为什么不能先写订单再发普通消息”这件事讲得非常清楚
- 这张卡使 architecture layer 第一次具备回答“为什么必须这样设计”的能力，而不只是描述链路

#### 暂时不足
- 对“补偿任务”这一部分，目前仍偏概念解释；如果要继续追问具体补偿链和死信策略，需要 failure / async cards 继续补

### 结论
> `transaction-message-anchor` 的加入，让这层知识开始从“架构总述”真正进入“设计取舍解释”层。

---

## 十一、整体评估（扩展版）
### 5.1 当前 9 张卡已经能回答什么
已经能比较稳地回答：
- `my-xhs` 整体架构是什么
- gateway 的角色是什么
- home 为什么是 BFF
- order 为什么是编排中心
- 哪些链路是同步，哪些是异步

也就是说，第一批 architecture cards 已经不只是样板，而是开始具备实际回答能力，尤其在“总图 + 角色边界 + 主交易链 + 第一跳一致性绑定器”这一层已经能够形成较稳定的组合回答。

### 5.2 还缺什么
最明显还缺：
- `middleware-role`
- `transaction-chain`
- `read-aggregation-vs-transaction`
- `async-event-map`

其中最值钱的下一张是：
- `middleware-role`

因为它能把“底层中间件职责”这一层补上，让整体架构问答更闭环。

### 5.3 当前阶段的准确判断
> 第一批 architecture cards 已经成立，可以进入“继续补卡”的阶段；但仍应保持克制，不要一下扩成几十张，优先围绕当前验证问题继续长。