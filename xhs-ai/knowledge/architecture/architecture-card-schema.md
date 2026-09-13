# Architecture Cards Schema（2026-08-20）

> 目标：把当前 `service-topology` / `gateway-role` / `bff-role` / `order-orchestration-role` 这批样板卡，收束成一套可复制的稳定 schema，避免后续卡片越写越像自然语言短文。

---

## 一、为什么要单独定 schema

当前样板卡已经证明：
- 方向是对的
- 粒度也大体合适

但如果没有 schema，后面很容易出现：
- 每张卡字段不一致
- `structured_points` 越写越随意
- `boundary` 风格不统一
- 卡片和正文的关系变得混乱

所以现在必须先把 card schema 钉死。

---

## 二、Architecture cards 的定位

Architecture cards 不是：
- `vol-xhs` 正文的缩写版
- 任意文本 chunk
- 全文 embedding 的替代物

Architecture cards 是：

> **从 `vol-xhs` / handoff / 关键源码中提炼出来的、面向系统级问题问答的稳定知识单元。**

它们回答的是：
- 整体架构
- 服务分组
- 服务边界
- 同步/异步协作
- 中间件职责
- 事务中心 / 聚合中心的区别

---

## 三、字段规范

每张 architecture card 必须包含：

### 1. 基础字段
- `id`
- `category`
- `priority`
- `question`
- `answer_shape`
- `serve_mode`

### 2. 范围字段
- `scope`
- `boundary`
- `anti_confusion`

### 3. 内容字段
- `answer`
- `structured_points`
- `why_it_matters`
- `best_for_questions`

### 4. 证据字段
- `sources`
- `evidence_level`
- `confidence`
- `source_version`
- `last_verified_at`

### 5. 导航字段
- `related_cards`
- `followup_docs`
- `notes`

---

## 四、`answer_shape` 的硬规则

### `short`
- 1 句结论
- 最多 2 条要点
- 用于中间件职责、单点角色说明

### `medium`
- 1 段结论
- 3~4 条结构点
- 用于 gateway / home / inventory / product 这类角色解释

### `long`
- 1 段结论
- 4~6 条结构点
- 允许加一段边界或设计取舍
- 用于 service-topology / transaction-chain / order-orchestration-role 这类大问题

规则：
> card 不是正文，`long` 也不能写成迷你文章。

---

## 五、`serve_mode` 的硬规则

### `direct`
适用：
- 单张卡就足够回答
- 不需要拼接别的卡
- 典型如：`gateway-role`、`bff-role`

### `composable`
适用：
- 这是总图的一部分
- 需要和其他卡一起拼装回答
- 典型如：`service-topology`、`transaction-chain`

规则：
- `direct` 卡可以直接给用户
- `composable` 卡优先被上层编排，不建议单独裸答超长问题

---

## 六、卡组分类与 `structured_points` 槽位规范

后续不要把所有 architecture cards 当成同一种卡。当前至少分成三类：

### A. 总图类卡（Global Topology Cards）
典型：
- `service-topology`
- `domain-groups`

职责：
- 回答整体结构问题
- 给读者建立总图
- 通常不直接深入局部实现

推荐配置：
- `answer_shape: medium | long`
- `serve_mode: composable`

推荐槽位：
- `entry_layer`
- `core_domains`
- `transaction_centers`
- `read_aggregation`
- `async_fanout`
- `middleware_layer`

### B. 角色类卡（Role Boundary Cards）
典型：
- `gateway-role`
- `bff-role`
- `order-orchestration-role`
- `inventory-state-role`
- `product-catalog-role`

职责：
- 回答某个服务/模块到底承担什么角色
- 强调“它是什么”和“它不是什么”

推荐配置：
- `answer_shape: medium`
- `serve_mode: direct`

推荐槽位：
- `position`
- `depends_on`
- `responsibility`
- `failure_mode`
- `non_responsibility`
- `output`

### C. 链路类卡（Flow / Boundary Cards）
典型：
- `transaction-chain`
- `sync-async-boundary`
- `order-inventory-payment-flow`
- `async-event-map`

职责：
- 回答一条业务/消息/协作链是怎么流动的
- 强调收束点、扩散点和失败语义

推荐配置：
- `answer_shape: long`
- `serve_mode: composable`

推荐槽位：
- `entry`
- `sync_chain`
- `async_chain`
- `state_anchor`
- `failure_split`
- `boundary`

规则：
> 后面新卡必须先决定自己属于哪一类，再决定 `structured_points` 怎么写，禁止每张卡都重新发明一套自然语言标题。

---

## 七、`serve_mode` 的编排规则

有了 `direct / composable` 还不够，必须写清怎么编排。

### `direct`
适用：
- 问题本身很聚焦
- 一张卡就能回答清楚
- 典型如：`gateway-role`、`bff-role`

编排规则：
- 可以直接返回给用户
- 如果用户问题更大，允许作为辅卡插入组合回答

### `composable`
适用：
- 问题本身是总图或长链路问题
- 单张卡只能回答其中一部分
- 典型如：`service-topology`、`sync-async-boundary`

编排规则：
- 不建议单独裸答复杂问题
- 优先作为主卡/辅卡参与组合回答
- 当主问题是全局问题时，通常至少要搭配 1~2 张 `direct` 卡

### 一个推荐的组合示例
问题：`my-xhs 的整体架构是什么？`

推荐组合：
1. 主卡：`service-topology`
2. 辅卡：`gateway-role`
3. 辅卡：`order-orchestration-role`
4. 视情况补：`sync-async-boundary`

不推荐直接出场：
- `bff-role`（除非问题进一步追问 home）
- 未来的 `middleware-role`（除非问题专门问中间件职责）

这说明：
> 卡片不是简单检索命中就全拼，而要按问题层级做编排。

---

## 八、`boundary` 的标准句式

每张卡的 `boundary` 建议至少覆盖 2~3 类：

1. **不展开什么细节**
   - 例如：不展开库存三级扣减实现细节

2. **不替代哪张更大的卡**
   - 例如：不替代 `transaction-chain`

3. **不回答什么相邻问题**
   - 例如：不回答页面聚合接口的具体实现

建议句式：
- 不展开 ... 的实现细节
- 不替代 ... 相关长链路分析
- 不回答 ... 这一层问题

这样后续卡片才不会越写越大。

---

## 八、`sources.role` 命名规范

统一前缀，不要混用“类型”和“用途”。

### 文档类
- `doc-primary`
- `doc-architecture`
- `doc-routing`
- `doc-auth`
- `doc-transaction`
- `doc-failure`

### 代码类
- `code-entry`
- `code-route`
- `code-bff`
- `code-orchestration`
- `code-feign`
- `code-async`
- `code-state`
- `code-config`

规则：
> role 用来说明“这条 source 在卡里的证明作用是什么”，而不是简单说明文件类型。

---

## 九、`followup_docs` 的必要性

卡片不应假装自己能解释一切。

所以每张卡最好再补：
- `followup_docs`

例如：
```yaml
followup_docs:
  - /data/workspace/source-code/openjdk-book/docs/openjdk/vol-xhs/00-overview-architecture/01-service-topology.md
  - /data/workspace/source-code/openjdk-book/docs/openjdk/vol-xhs/05-inventory-order-payment/03-transaction-message.md
```

这样 Agent 或人类读者都知道：
- 当前卡片够不够
- 不够的话，应该继续跳到哪里

---

## 十、这套 schema 的目标

这套 schema 最终不是为了“卡片好看”，而是为了让后面可以稳定做到：

1. 问题清单驱动卡片建设
2. 卡片可直接用于 Agent 检索与组装回答
3. 卡片与 `vol-xhs` 正文保持单向一致
4. 后续新增卡片不需要重新摸索格式

换句话说：

> 这不是在做文档装饰，而是在给 `my-xhs-ai` 的系统知识层立接口。