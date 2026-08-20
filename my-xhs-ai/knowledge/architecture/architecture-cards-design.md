# Architecture Cards 第一批结构设计（修订版，2026-08-20）

> 目标：把 `my-xhs` 的全局架构问题拆成 Agent 可消费的知识单元，而不是直接把 `vol-xhs` 全文塞进 RAG。

---

## 一、为什么先做 Architecture cards

当前 `my-xhs-ai` 最大的知识型暴露点，不是“不会查数据”，而是它不一定能稳稳回答：
- `my-xhs` 的整体架构是什么？
- 服务是怎么分组和分层的？
- 哪些链路是同步、哪些是异步？
- 为什么 `order` 是编排中心，而 `home` 只是 BFF？

这些问题的共同特点是：
- 不是查运行态工具就能得到
- 不是单个类、单个方法能解释完
- 需要把 `vol-xhs` 这种面向人的长文档重组为 Agent 可检索知识单元

所以第一批最应该做的，不是 business/failure，而是 architecture。

---

## 二、卡片建设不是“缩短版 vol-xhs”

必须先把关系说死：

> `vol-xhs` 是面向人类的深度解剖正文，
> Architecture cards 是从正文中提炼出来的**稳定知识单元**，
> cards 不能脱离正文自行长成另一套真相。

也就是说：
- 正文负责解释机制、失败方案、取舍、桥接
- cards 负责提供短而稳的可检索答案

二者职责不同，不能混写。

---

## 三、设计原则

### 1. 一张卡只回答一个问题
不要做“全局架构大全卡”。
每张卡只回答一个清晰问题，例如：
- gateway 的角色是什么？
- home 为什么是 BFF？
- order 为什么是编排中心？

### 2. 先答案，后证据
卡片不是长文，要先给稳定回答，再给来源和边界。

### 3. 必须显式写清边界
每张卡除了 `anti_confusion` 外，还必须有：
- `scope`：这张卡回答到哪里为止
- `boundary`：它不回答什么问题

否则同一张卡会越写越大，最后重新退化成长文。

### 4. 证据层级必须可见
每张卡必须带：
- `evidence_level`

用于说明当前回答主要依赖：
- L0 代码静态证据
- L1 框架/语义证据
- L2 运行态证据

这样后面 RAG 检索时，Agent 才能知道：
- 这是静态设计答案
- 还是已经被运行态验证过的答案

### 5. 回答粒度必须预先定义
每张卡必须带：
- `answer_shape`

枚举建议：
- `short`
- `medium`
- `long`

因为架构类问题并不都应该答成长文。

---

## 四、建议的卡片结构（修订版）

```yaml
id: gateway-role
category: architecture
priority: P0
question: gateway 的角色是什么？
answer_shape: medium
scope: entry-boundary
boundary:
  - 不负责业务真相
  - 不负责事务编排
answer: >
  gateway 是统一入口层，负责路由、鉴权、限流、超时策略和入口治理；
  它不持有业务事实，也不承担交易编排。
why_it_matters: >
  这个边界决定了后续所有业务流的真正起点，也决定了很多 5xx / timeout
  问题属于入口治理，而不是领域 bug。
anti_confusion:
  - gateway 不是 BFF
  - gateway 不是业务核心服务
  - gateway 不是订单事务中心
sources:
  - vol-xhs/00-overview-architecture/01-service-topology.md
  - my-xhs-gateway/src/main/resources/application.yml
evidence_level:
  - L0
  - L1
confidence: high
```

---

## 五、为什么要先做问题清单，而不是先做卡片

这一步看起来慢，但非常必要。

原因是：
- 问题决定知识单元边界
- 不是文档长度决定卡片切法

如果先做卡片，很容易：
- 想到什么就切什么
- 结果没有办法对应用户真实问题
- 最后卡片变成“文档摘要碎片”而不是“回答单元”

所以现在先有：
- `architecture-questions.yaml`

再反推：
- 哪些 cards 必须先存在

这就是正确顺序。

---

## 六、Architecture questions 还要再分成两层

这是这次修订里最关键的一点。

并不是所有“架构问题”都是一类问题。

### 第一层：系统知识问答
例如：
- `my-xhs` 的整体架构是什么？
- 哪些服务是核心，哪些是聚合？
- 为什么 `home` 是 BFF？

这层更依赖：
- `vol-xhs`
- 架构文档
- handoff

### 第二层：源码结构问答
例如：
- 哪个类消费了 `ORDER_TRANSACTION_TOPIC`？
- 事务消息回查逻辑在哪？
- 哪个模块负责库存三级扣减？

这层更依赖：
- code-map
- service-map
- consumer/feign/config 索引

也就是说：
> Architecture cards 先做系统知识层，
> Code-map 再补源码结构层。

这两层不能混成一层一起做。

---

## 七、第一批建议卡片（按优先级排序）

### P0（先做）
1. `service-topology`
2. `gateway-role`
3. `bff-role`
4. `order-orchestration-role`
5. `sync-async-boundary`

这 5 张已经足够支撑：
- `my-xhs` 的整体架构是什么？
- 为什么不是 16 个并排服务？
- 入口、聚合、事务中心和异步扩散到底怎么分层？

### P1（第二批）
6. `inventory-state-role`
7. `product-catalog-role`
8. `transaction-chain`
9. `middleware-role`
10. `role-difference`

### P2（第三批）
11. `read-aggregation-vs-transaction`
12. `stateful-vs-catalog`
13. `consistency-burden`
14. `async-event-map`
15. `feign-dependency-map`

---

## 八、第一批不要一口气全做 5 张

这次最应该克制的点是：

> 不要一上来就把第一批 5 张全部铺开。

最合理的顺序是先做 **2 张样板卡**：

1. `service-topology`
2. `gateway-role`

原因：
- 一张回答“总图”
- 一张回答“入口角色”
- 两张足够检验：
  - 卡片格式是否合适
  - 证据层级是否清楚
  - 粒度是否适合 Agent
  - 和 `vol-xhs` 正文的关系是否自然

如果这两张卡写顺了，再铺第三到第五张才有意义。

---

## 九、这一步真正的产出应该是什么

### 不是
- 一堆 chunk
- 一批 embedding 向量
- 一个全文索引库

### 而是
- 一套问题清单
- 一个可执行的卡片 schema
- 两张能落地的样板卡
- 以及“文档知识层”与“源码结构层”的边界

这是下一阶段能不能做好知识层的前提。

---

## 十、最重要的结论

当前 `my-xhs-ai` 不适合直接把 `vol-xhs` 整卷塞进 RAG。

它真正需要的，是先建立：

> **问题驱动、边界清晰、证据分级明确的 Architecture cards 体系。**

只有这样，后面补系统知识层时，Agent 才不会只是“搜文档”，而是开始真正具备：
- 架构解释
- 模块边界理解
- 主链路讲解
- 设计取舍问答

这才是这一步该做的事。