# `my-xhs` 知识层阶段性总评估（2026-08-20）

> 目标：判断当前基于 `vol-xhs` / handoff / review / 源码抽取出来的知识层，是否已经足够支撑 `my-xhs-ai` 从“运行态诊断 Agent”往“系统知识 + 运行诊断一体化 Agent”推进。

---

## 一、当前已经建设完成的两层知识

### 1. Architecture Layer
已沉淀卡片：
- `service-topology`
- `gateway-role`
- `bff-role`
- `order-orchestration-role`
- `sync-async-boundary`
- `middleware-role`
- `transaction-chain`
- `order-inventory-payment-flow`
- `transaction-message-anchor`

已验证：
- 整体架构
- 角色边界
- 同步/异步协作
- 中间件职责
- 主交易链
- 第一跳一致性绑定器

### 2. Business Layer
已沉淀卡片：
- `inventory-three-level`
- `order-create`
- `payment-flow`
- `coupon-lifecycle`
- `refund-flow`
- `close-order`
- `compensation-path`

已验证：
- 主交易规则链如何成立
- 库存、订单、支付、券的角色与时间语义
- 退款、关单、补偿等尾部收敛链

---

## 二、从“有没有卡”升级到“能不能回答”之后，当前到底到了哪一步

### 2.1 已经跨过的阶段
当前知识层已经不是：
- 纯文档堆积
- 纯全文 RAG 设想
- 纯 schema 草图
- 只有一两张样板卡

它已经至少跨过了 4 个阶段：

1. **问题定义阶段**
   - 已经明确知道当前 Agent 答不好的系统级问题是什么

2. **卡片 schema 阶段**
   - 已经建立 cards 结构、serve_mode、boundary、evidence_level、followup_docs 等规范

3. **样板卡阶段**
   - architecture / business 两层都已经有第一批可用卡片

4. **回答试跑阶段**
   - 已经不是“看起来合理”，而是拿真实问题去做组合回答验证

这意味着：

> 当前知识层已经从“设计物”进入了“可工作物”。

### 2.2 还没跨过的阶段
它还没有完全进入：

- **Agent 真接入阶段**
  - 现在 cards 还没有正式接进 `my-xhs-ai` 的路由主线

- **知识层大规模扩张阶段**
  - 还没有扩到 marketing / content / search / recommendation / notification 等外围域

- **release 级知识问答评测阶段**
  - 还没有形成 system-knowledge 的专门评测集

所以当前状态不是“知识层全做完”，而是：
> **知识层核心骨架已成立，但仍处于可集成前的收束阶段。**

---

## 三、当前这套知识层最强的地方

### 1. 它已经能解释“系统是什么”，不是只解释“系统出了什么错”
这是最大的升级。

当前 `my-xhs-ai` 原本最强的是：
- 5xx 排障
- DLQ 重投
- 运行态 trace

这些都属于“出了问题之后怎么办”。

知识层建立之后，它第一次开始具备回答：
- `my-xhs` 整体架构是什么
- 为什么 `home` 是 BFF
- 为什么 `order` 是编排中心
- 为什么 `inventory` 复杂
- 为什么事务消息必须存在

也就是说，它开始能回答：
> **这个系统为什么会这样长。**

### 2. 它已经能解释“为什么必须这样设计”，不只是“功能怎么用”
这在 `transaction-message-anchor` 和 `inventory-three-level` 里尤其明显。

现在它不只是说：
- 有事务消息
- 有本地消息表
- 有三级扣减

而是在回答：
- 为什么不能先写订单再发普通消息
- 为什么库存不能简单单层扣减
- 为什么券不是简单减钱

这说明知识层已经进入“设计取舍解释”阶段。

### 3. 它和 `vol-xhs` 的关系现在比较健康
当前关系已经比较清晰：
- `vol-xhs`：面向人类的长文深度解剖
- knowledge cards：面向 Agent 的稳定知识单元

也就是说，它没有在做“复制一份 vol-xhs 缩写版”，而是在做：
> **从长文中提炼可检索、可组合、可回答的问题单元。**

这条路是对的。

---

## 四、当前最主要的薄弱点

### 1. 还没有真正接进 Agent 主路径
这是当前最大的现实边界。

虽然 architecture/business 两层已经有了卡、schema、validation，
但它们现在还没有真正变成：
- Intent Router 的一个新分支
- 系统知识问答的主路径

也就是说：
> 知识层已经能“被人验证”，但还没有“被系统使用”。

### 2. 还没有 code-structure layer
当前仍然缺：
- code-map
- service-map
- mq-map
- feign-map
- state-map

这意味着目前知识层更偏：
- 文档知识
- 设计知识
- 业务规则知识

但还不够回答：
- 哪个类处理这个逻辑
- 哪个 consumer 接了哪个 topic
- 哪个配置决定了当前行为

所以它还不是“源码级系统知识层”。

### 3. 外围业务域还没展开
当前最强的是：
- 交易主链

还没进入：
- 营销规则
- 内容互动
- 搜索/推荐
- 通知/IM

这不是问题，但说明：
- 现在知识层有明显主线偏向
- 不是完整全域覆盖

### 4. 还没有知识问答评测集
现在有：
- cards
- validation report

但还没有：
- 面向 Agent 的 architecture/business knowledge eval set

所以后面一旦真正接入 Agent，还需要单独做一层知识问答评测。

---

## 五、现在到底够不够进入下一阶段？

### 我的判断：**够。**

原因有三个：

#### 1. 核心知识主线已经立住
architecture + business 两层已经覆盖了：
- 系统骨架
- 主交易链
- 第一跳绑定器
- 尾部收敛

这已经不是零碎知识，而是一条有主线的系统知识骨架。

#### 2. 继续补更多 cards 的边际收益开始下降
如果现在继续立刻补：
- content
- search
- recommendation
- notification
- marketing

不是不行，
但在当前阶段，它们的边际收益不如把现有知识层真正接进 Agent 更大。

#### 3. 现在最缺的不是“更多卡”，而是“让卡进入系统”
这意味着下一阶段的重点应该从：
- **继续造卡**

切到：
- **知识层接入 Agent 主路径**

---

## 六、下一阶段最合理的方向

### Phase 1：补 Code Structure Layer
优先抽：
- service-map
- feign-map
- mq-map
- state-map

因为它会让系统从“会讲设计”升级到“会讲设计 + 能导航源码”。

### Phase 2：新增系统知识问答路由
让 Agent 能明确区分：
- 运行态诊断问题
- 系统知识问题
- 架构解释问题
- 代码结构问题

### Phase 3：补知识问答评测集
做一套：
- architecture questions
- business questions
- failure questions
- code-structure questions

### Phase 4：再决定是否扩外围业务域 cards
也就是说，先让“骨架知识”真的能被 Agent 用起来，再决定要不要铺更多业务域。

---

## 七、当前阶段的最终判断

最准确的说法不是：
- 知识层已经做完了

也不是：
- 现在还只是初稿

而是：

> **`my-xhs-ai` 的知识层已经完成了第一阶段：系统骨架与主交易规则链的沉淀。现在最合理的下一步，不是继续横向补卡，而是向“系统知识问答主路径”推进。**

这就是当前最稳的判断。