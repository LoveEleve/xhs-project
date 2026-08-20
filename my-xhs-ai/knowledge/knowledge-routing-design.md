# 系统知识问答路由设计（2026-08-20）

> 目标：把已经建设好的 `architecture layer + business layer + code structure layer` 从“知识资产”推进成 `my-xhs-ai` 的正式问答主路径之一。

---

## 一、为什么现在必须加这条路由

当前 `my-xhs-ai` 已经有很强的运行态能力：
- 5xx 排障
- DLQ 查询与重投
- 指标查询
- 日志检索
- HITL 审批

但这条主路径天然更擅长回答：
- 现在出什么问题了
- 哪个指标异常了
- 哪条链跑错了

它并不天然擅长回答：
- `my-xhs` 的整体架构是什么？
- 为什么 `order` 是编排中心？
- 为什么 `inventory` 比 `product` 更复杂？
- 事务消息为什么必须存在？
- 哪个类/哪类组件大致负责这条链？

现在知识层已经有：
- architecture cards
- business cards
- code structure layer

如果还不把这些能力接进 Agent 主路径，整个知识层就会长期停留在“资产存在，但系统不用”。

所以现在最合理的下一步，不是继续补卡，而是：

> **给 Agent 增加一条“系统知识问答”主路径。**

---

## 二、这条路由到底要解决什么问题

它解决的不是“让 Agent 回答更多”，而是：

### 1. 让知识型问题不再误走运行态工具链
例如现在如果问：
- `my-xhs 的整体架构是什么？`
- `为什么 home 是 BFF？`
- `为什么事务消息是第一跳绑定器？`

如果系统还沿用当前运行态诊断路径，它很容易：
- 去查工具
- 去查日志
- 去查 Prometheus

这些动作不是完全没帮助，但会很容易答偏。

### 2. 让系统开始具备“解释系统本体”的能力
现在我们已经有知识底座，路由层必须显式承认：

> 有些问题的正确处理方式，不是先查运行态，而是先查系统知识层。

### 3. 让后续评测和 trace 也能区分问题类型
一旦新路由成立，后面就可以清楚地区分：
- 运行态诊断任务
- 架构解释任务
- 业务规则任务
- 源码导航任务

这对评测体系和 Langfuse trace 都有意义。

---

## 三、推荐的路由分层

建议不要一下子做成很复杂的分类器，而是先把当前系统明确拆成 4 类问题：

### A. Metrics / Deterministic Query
适合：
- 订单量是多少？
- 支付成功率多少？
- 最近有哪些支付失败？

走：
- 指标工具
- 不进复杂 Agent 调查

### B. Runtime Diagnosis
适合：
- 为什么最近有 5xx？
- DLQ 有哪些消息？
- 为什么库存预扣失败？

走：
- 当前主线 Harness + tool 调查路径

### C. System Knowledge
适合：
- `my-xhs` 的整体架构是什么？
- 哪些链路同步、哪些异步？
- 为什么 order 是编排中心？

走：
- architecture cards
- business cards
- 不优先查运行态工具

### D. Code Structure Navigation
适合：
- 哪个类负责事务回查？
- 哪个 consumer 接了 `ORDER_TRANSACTION_TOPIC`？
- 哪个服务负责库存预扣？

走：
- service-map
- feign-map
- mq-map
- call-chain-map

这 4 类已经足够形成第一版清晰路由，不需要一开始再拆更多。

---

## 四、如何判断一个问题应该走知识层还是运行态层

这一步不能靠“感觉”，要有明确规则。

## 4.1 优先用规则判断
### 进入 System Knowledge 的信号词
- 整体架构
- 系统架构
- 服务分层
- 为什么这样设计
- 为什么需要
- 边界是什么
- 主链路怎么走
- 哪些同步、哪些异步
- 为什么复杂

### 进入 Code Structure 的信号词
- 哪个类
- 哪个服务
- 哪个 consumer
- 哪个 job
- 哪个 topic
- 哪个 Feign
- 代码里在哪
- 哪一层负责

### 进入 Runtime Diagnosis 的信号词
- 最近为什么报错
- 5xx
- 超时
- 积压
- DLQ
- 哪条消息
- 哪个指标异常
- 运行时
- 日志

### 进入 Metrics 的信号词
- 多少
- 成功率
- 数量
- 统计
- 趋势
- 对比窗口

---

## 五、知识层检索规则

当前不要做“自由全文问答”，而是做**卡片驱动问答**。

### 5.1 System Knowledge 路径
1. 识别问题类型（architecture / business）
2. 先匹配主卡
3. 再匹配 1~2 张辅卡
4. 根据 `serve_mode` 决定是直接回答还是组合回答
5. 如果卡片不够，再用 `followup_docs` 补深度

### 5.2 Code Structure 路径
1. 识别问题类型（service / feign / mq / call-chain）
2. 先匹配 map 类结构单元
3. 用 map 回答“落在哪层、和谁相连”
4. 如需更细，再引导到源码级深挖，而不是一开始就扫全文

---

## 六、回答编排规则

### 6.1 System Knowledge 问题
采用：
- 1 张主卡
- 1~2 张辅卡
- 最后加边界说明

例如：
问题：`my-xhs 的整体架构是什么？`

回答编排：
- 主卡：`service-topology`
- 辅卡：`gateway-role`
- 辅卡：`order-orchestration-role`
- 辅卡：`sync-async-boundary`

### 6.2 Code Structure 问题
采用：
- 1 张主 map
- 1 张链路 map（如果需要）
- 最后指出代码落点边界

例如：
问题：`订单成立后，库存预扣这条链在代码层怎么走？`

回答编排：
- 主卡：`inventory-pre-deduct-mainline`
- 辅卡：`ORDER_TRANSACTION_TOPIC`
- 辅卡：`inventory` service-map

---

## 七、为什么不能让知识层直接替代运行态工具

这一步必须写清楚。

知识层回答的是：
- 设计
- 规则
- 角色
- 代码结构

运行态工具回答的是：
- 当前事实
- 当前异常
- 当前 backlog
- 当前 5xx
- 当前日志

也就是说：
- 知识层不能替代运行态工具
- 运行态工具也不能替代知识层

最理想的状态是：

> 知识层回答“为什么会这样设计 / 哪一层负责”，
> 运行态工具回答“现在到底发生了什么”。

---

## 八、建议的第一版实现方式

不要一上来改很大。

### 第一阶段
先做一个轻量路由层：
- 在现有 router 基础上增加 `SYSTEM_KNOWLEDGE` 与 `CODE_STRUCTURE`
- 暂时仍用规则优先，不先上 embedding

### 第二阶段
接一个最小知识检索器：
- 先只读 cards / maps
- 根据 validation rules 组装回答

### 第三阶段
补知识问答评测集：
- architecture questions
- business questions
- code structure questions

这比一开始就做复杂 RAG / embedding / rerank 要稳得多。

---

## 九、最重要的结论

当前 `my-xhs-ai` 的下一阶段主线，不是继续盲目补功能，
而是：

> **让已经建设好的知识层真正成为 Agent 的一条正式问答路径。**

只有做到这一点，它才会从：
- 会诊断运行态

升级到：
- **既会诊断运行态，也会讲系统本体。**