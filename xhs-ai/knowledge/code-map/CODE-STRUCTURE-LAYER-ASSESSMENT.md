# Code Structure Layer 阶段性收束评估（2026-08-20）

> 目标：判断当前已经沉淀出来的 `service-map + feign-map + mq-map + call-chain-map + state-map + async-event-map`，是否已经足够构成 `my-xhs-ai` 第一版源码结构知识层。

---

## 一、当前已经具备的六类结构单元

### 1. service-map
已覆盖：
- gateway
- home
- order
- inventory
- payment
- coupon

回答：
- 每个服务是什么角色
- 核心 controller / service / consumer / job / config 落在哪

### 2. feign-map
已覆盖：
- order → user
- order → product
- order → inventory
- order → coupon
- order → payment

回答：
- order 为什么会成为同步编排中心
- 哪些前置事实必须在订单成立前被同步拉齐

### 3. mq-map
已覆盖：
- ORDER_TRANSACTION_TOPIC
- NOTIFICATION_TOPIC
- FEED_TOPIC
- RECOMMEND_BEHAVIOR_TOPIC
- core-topics 总述

回答：
- 哪条消息网在系统里分别承担什么语义
- 哪个 topic 是第一跳绑定器，哪些是成立后的扩散链

### 4. call-chain-map
已覆盖：
- order-create-mainline
- inventory-pre-deduct-mainline
- payment-success-mainline
- dlq-redeliver-mainline

回答：
- 一条关键业务链在代码层怎样推进
- 哪一跳是状态锚点，哪一跳交给下游接管

### 5. state-map
已覆盖：
- order-state
- inventory-state
- payment-state

回答：
- 哪个域持有哪条状态机
- 哪些类负责状态推进

### 6. async-event-map
已覆盖：
- notification-event-map
- feed-event-map
- recommend-event-map
- compensation-event-map
- async-event-network 总述

回答：
- 哪些事件网属于通知/Feed/推荐/补偿
- 它们在系统里分别怎样扩散和收敛

---

## 二、这层知识现在已经能回答什么

### A. 角色落点类问题
- gateway 在代码结构里是什么角色？
- home 在代码结构里是什么角色？
- order 为什么在代码层像编排中心？

### B. 同步依赖类问题
- order 为什么依赖这么多 Feign client？
- 哪些事实必须在订单成立前同步拉齐？

### C. 消息语义类问题
- 哪个 topic 负责把订单推进到库存世界？
- 哪条消息负责通知扩散？
- 哪条消息负责推荐行为推进？

### D. 主链路径类问题
- 下单主链在代码层怎么推进？
- 库存预扣在代码层怎么被 inventory 接管？
- 支付成功后哪一层继续推进？
- DLQ 重投链为什么必须带审批？

### E. 状态机落点类问题
- 订单状态机大致落在哪些类里？
- 库存状态机大致落在哪些类里？
- 支付状态机大致落在哪些类里？

### F. 异步网络类问题
- 通知相关事件怎样扩散？
- Feed 发布链怎样推进？
- 推荐行为怎样进入推荐侧？
- 补偿链到底在收什么残局？

也就是说，当前 code structure layer 已经不只是“会告诉你文件在哪”，而是开始能回答：
> **这个系统在代码层大致是怎么被组织起来的。**

---

## 三、这层知识真正最值钱的地方

### 1. 它把“系统理解”落回了“代码结构”
Architecture layer 解决的是：
- 系统骨架是什么

Business layer 解决的是：
- 为什么这样运转

而 code structure layer 解决的是：
- 这些骨架和规则在代码里大致落在哪里

这一步一旦成立，`my-xhs-ai` 就不再只是“会讲系统”，而开始具备：
- 解释结构
- 导航代码
- 连接运行态异常与实现位置

### 2. 它已经比全文源码检索更高级
如果没有这层，后面一旦要回答：
- 哪个类
- 哪个 consumer
- 哪个 topic
- 哪条链

最常见做法就是：
- 让模型自由扫全仓源码

那会立刻变成：
- 粒度失控
- 答案飘
- 成本高
- 稳定性差

当前这层的价值，就在于它已经把最关键的结构骨架先抽出来了。

### 3. 它开始能回答“代码责任落点”问题
例如：
- 这是 controller 问题，还是 job 问题？
- 这是 order 编排链问题，还是 inventory 接管链问题？
- 这是通知扩散层问题，还是主交易链问题？

这比单纯知道“类名在哪”更有系统价值。

---

## 四、当前仍然存在的缺口

### 1. 还没有方法级别链路
当前最强的是：
- service / consumer / job / topic / mainline 级

但还没有：
- 关键方法级细化图
- 例如某个具体状态推进在方法层如何交接

所以它现在是：
- **结构导航层**
不是：
- **方法级执行图谱层**

### 2. 还没有跨域全链路 code-map
当前重点仍然集中在主交易链：
- order
- inventory
- payment
- coupon

而外围域：
- content
- search
- recommendation
- notification
- analytics
- counter

虽然已经有异步网概念，但还没有像主交易链那样细。

### 3. 还没有正式形成“源码问答评测集”
虽然已经做过一轮问题验证，
但还没有形成：
- code structure eval set
- 规则化测试集
- 主卡 / 辅卡命中质量衡量

这意味着当前这层已经可用，
但还没有进入稳定评测治理阶段。

---

## 五、现在是否足够构成第一版源码结构知识层？

### 我的判断：**足够。**

原因不是“已经无缺口”，而是：

#### 1. 核心主链已经立住
主交易链相关的：
- 服务角色
- Feign 依赖
- MQ handoff
- 状态机
- 补偿网络

都已经有了第一版结构答案。

#### 2. 关键运行态问题已经有代码结构映射能力
当前已经能比较稳地回答：
- 哪个 topic 负责推进库存
- 哪个 consumer 接管预扣
- 哪个类负责事务回查
- 哪个 job 负责本地消息补发与死信扫描
- 哪个核心类负责预扣主逻辑

这说明它已经不是“文件索引”，而是真正开始有代码问答能力了。

#### 3. 继续横向扩张的收益已经开始下降
如果现在继续把很多外围服务都一口气拉进来，
收益不如先把这层真正接进 Agent 问答主路径大。

---

## 六、这层知识现在最适合进入什么阶段

不是继续大规模补 map，
而是进入：

> **接入与验证阶段**

也就是说：
1. 接入 `CODE_STRUCTURE` 路由
2. 让 cards/maps 真被 Agent 使用
3. 做一轮 code-structure question eval

如果这一步跑通，
这一层就真正从“知识资产”升级成“系统能力”。

---

## 七、最终判断

当前 `code structure layer` 的最准确定位是：

> **第一版源码结构知识层已经成立。**

它已经足够让 `my-xhs-ai` 从：
- 会讲系统
- 会讲业务

进一步迈向：
- **会把系统和业务落回代码结构来解释**

这就是当前最重要的阶段性结论。