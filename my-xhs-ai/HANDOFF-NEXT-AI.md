# my-xhs-ai 下一阶段增强入口（给下一位 AI）

> 日期：2026-08-20
> 用途：下一位 AI 不要再围绕“如何收官”打转，而是从这里继续推进下一阶段增强主线。

---

## 一、当前项目一句话状态

`my-xhs-ai` 已经不是单纯的运行态诊断 demo，而是一个：

> **关键真实链路已被证明、系统知识主路径已接通、正在向“研发 / 运维工作台型 Agent 原型”推进的企业级诊断 Agent 系统。**

已经成立的主线：
- 运行态诊断主线
- HITL 审批
- `dlq.redeliver -> CR_SUCCESS`
- Langfuse trace
- 向量语义 Memory
- Temporal PoC（worker kill / restart / approve / complete）
- 系统知识问答主路径（`KnowledgeEvalRunnerTest` 9/9 通过）

所以当前重点不再是“是否收官”，而是：
> **继续增强，让它更像日常可用的研发 / 运维工作台。**

---

## 二、你先看这 5 个文件

### 1. 当前项目最终状态
- `my-xhs-ai/FINAL-SUMMARY.md`

### 2. 当前展示资产
- `my-xhs-ai/SHOWCASE.md`

### 3. 当前知识层总评估
- `my-xhs-ai/knowledge/KNOWLEDGE-LAYER-CLOSING-ASSESSMENT.md`

### 4. 运行与实现交接
- `docs/test-3/HANDOFF-TASK14.md`

### 5. 卷式深度文档入口
- `my-xhs-ai/docs/vol-ai/INDEX.md`

如果时间只够看两份，先看：
1. `FINAL-SUMMARY.md`
2. `KNOWLEDGE-LAYER-CLOSING-ASSESSMENT.md`

---

## 三、当前已经做完的主线

### A. 运行态诊断
- 5xx 排障
- DLQ 查询与重投
- 指标查询
- 日志检索
- HITL 审批

### B. 真实证据
- `dlq.redeliver -> CR_SUCCESS`
- Langfuse trace 已接通
- 4 个 demo 可跑
- E2E 报告已出

### C. Memory
- 豆包 `doubao-embedding-vision-large`
- 多用户隔离
- 语义命中已验证

### D. Temporal
- 审批型长任务 PoC 已跑通：
  - start
  - WAITING_APPROVAL
  - kill worker
  - restart worker
  - approve
  - COMPLETED

### E. 系统知识问答
- architecture / business / code structure 三层知识已建设
- `KnowledgeEvalRunnerTest` 已真实跑出 **9 / 9 通过**
- 说明系统已经从“只会查运行态”升级到“能回答系统本体问题”

---

## 四、当前真正的缺口

现在最重要的缺口，不再是：
- 再补主线能力证明
- 再补收官文档

而是日常高频使用能力不够全：

### P0（最该先补）
1. requestId / traceId 请求流转能力（已进入“最后命中服务优先 + 主类/方法/最近改动解释”阶段）
2. code search / code navigation（已从类级升级到方法级）
3. git history / blame / 变更解释（已从文件级升级到方法附近 blame）
4. 知识问答主路径稳定化（主路径已收敛，剩余是长期规则治理）

已修复的真实阻塞：Gateway `BodyCacheFilter` 的 `switchIfEmpty` 重入导致 POST 请求重复进入、HMAC nonce 重复和 trace 污染。故障卡见 `knowledge/failure/gateway-body-cache-reentry.yaml`。

### P1（系统体验增强）
5. 知识问答 ↔ 运行态诊断双向联动
6. 使用者工作流闭环
7. Langfuse ↔ eval 联动

### P2（治理与平台化）
8. 知识治理
9. 更大规模 eval
10. Temporal adoption boundary
11. 真多 Agent 深化

---

## 五、当前知识层的真实状态

### Architecture Layer
已成立：
- 系统骨架
- 角色边界
- 同步/异步协作
- 中间件职责
- 主交易链轮廓
- 第一跳一致性绑定器

### Business Layer
已成立：
- 订单创建
- 库存三级扣减
- 支付推进
- 券生命周期
- 退款 / 关单 / 补偿收敛

### Code Structure Layer
已成立：
- service-map
- feign-map
- mq-map
- call-chain-map
- state-map
- async-event-map
- 第一批 code cards
- `CodeSearchResult` 结构化返回（topHit / hits）
- 方法级字段：`methodHint` / `diagnosisTriplet` / `methodBlameSummary` / `methodSnippet`

### 知识问答主路径
- `SYSTEM_KNOWLEDGE` / `CODE_STRUCTURE` 已接入
- `KnowledgeEvalRunnerTest` 9/9 通过
- 当前已经能真实回答：
  - 整体架构
  - BFF / 编排中心
  - 三级扣减
  - 事务消息为什么必须存在
  - 哪个 topic / consumer / class / job 负责什么
  - 最近谁改过这个类/方法
  - 应优先检查哪个文件/方法/最近提交

---

## 六、当前最该继续做什么

### 第一优先级（P1）
> **知识问答 ↔ 运行态诊断双向联动**

P0 已基本完成：
- `requestId/traceId` 已能返回 `最后命中服务 -> 主类 -> 方法 -> 最近提交 -> 方法级 blame -> 方法级源码片段`
- `code search / code navigation` 已能返回 `topHit/hits + owner/recentCommits/changeExplanation`
- `git history / blame / 变更解释` 已落到方法附近，不再只是文件级
- 知识问答主路径已收敛到 `CODE_STRUCTURE -> code-map 主路径 -> cards fallback`

真实样本已验：
- trace：`5304dc5a8afb4741b8bc74cee49c3980`
- trace run：`run_f3433b191f2c`（三联建议） / `run_b29ad6e18937`（最后命中服务优先） / `run_95b8a751b45d`（method-level blame）
- code search run：`run_a40fbf029c8f`（`InventoryService.preDeduct()` 方法块）

现在最值钱的不再是继续补单点能力，而是把两条链路接起来：
- 运行态诊断结果里，允许继续追问“这个方法负责什么 / 为什么这样设计”
- 知识问答结果里，允许继续追问“这个类最近在哪条真实请求里最可疑”

---

## 七、下一位 AI 的推荐起点

最推荐从这里开始：

1. 读 `my-xhs-ai/business-analysis/tech/my-xhs-knowledge-gap-analysis.md`
2. 读 `my-xhs-ai/knowledge/knowledge-routing-design.md`
3. 再直接进入：
   - requestId / traceId 请求流转能力规划与实现

当前**不建议**下一位 AI 继续：
- 盲目补更多 cards
- 扩更多卷式正文
- 围绕“收官”继续写文档

因为当前最需要的是：
> **把系统从“会讲、会查、会审批”推进到“日常真会用”。**

---

## 八、最短行动建议

如果你是下一位 AI，不要再问“还能不能收官”。

直接进入：

> **下一阶段增强路线**

第一步先做：
- requestId / traceId 请求流转能力

因为这一步最能直接抬高项目的真实使用价值。