# 系统知识问答路由第一版实现计划（2026-08-20）

> 目标：把已经建设好的 `architecture / business / code structure` 知识层，从“静态知识资产”推进成 `my-xhs-ai` 可真正使用的一条正式问答路径。

---

## 一、当前起点

现在已经有：

### 1. Architecture Layer
- service-topology
- gateway-role
- bff-role
- order-orchestration-role
- sync-async-boundary
- middleware-role
- transaction-chain
- order-inventory-payment-flow
- transaction-message-anchor

### 2. Business Layer
- inventory-three-level
- order-create
- payment-flow
- coupon-lifecycle
- refund-flow
- close-order
- compensation-path

### 3. Code Structure Layer
- service-map
- feign-map
- mq-map
- call-chain-map

### 4. 规则验证资产
- architecture-card-validation-questions.yaml
- architecture-card-validation-report.md
- business-card-validation-report.md
- code-structure-validation-report.md

也就是说：
> 知识资产已经不少，但还没有真正变成系统能力。

---

## 二、第一版实现的目标是什么

第一版不追求“完整智能知识库”，而只追求：

1. Agent 能识别“这是不是系统知识问题”
2. 能把问题路由到正确的知识层
3. 能用 cards/maps 组装一个比现在更稳的答案
4. 不破坏现有运行态诊断主线

换句话说，第一版追求的是：
> **接通能力，不追求一步到位最优。**

---

## 三、第一版只做 3 件事

### 1. 规则识别（先不用 embedding）

在现有 Intent / Router 基础上增加两个新意图：
- `SYSTEM_KNOWLEDGE`
- `CODE_STRUCTURE`

#### A. `SYSTEM_KNOWLEDGE` 触发词
- 整体架构
- 系统架构
- 服务分层
- 为什么这样设计
- 边界是什么
- 主链路怎么走
- 为什么复杂
- 为什么需要

#### B. `CODE_STRUCTURE` 触发词
- 哪个类
- 哪个 consumer
- 哪个 job
- 哪个 topic
- 哪个 Feign
- 代码里在哪
- 哪一层负责

如果都不命中，仍回到当前：
- metrics
- runtime diagnosis
- decline

---

### 2. 知识检索（先文件驱动，不接全文 RAG）

这一步**不做全文语义检索**，先做结构化检索。

#### A. architecture/business cards 检索
做法：
- 读 YAML
- 按 `best_for_questions` + `question` + `priority` 做匹配
- 选出主卡 + 辅卡
- 读 `serve_mode` 决定 direct 还是 composable

#### B. code structure 检索
做法：
- 先读 `service-map`
- 再按问题类型决定是否补 `feign-map` / `mq-map` / `call-chain-map`

#### 为什么先这样做
因为当前最重要的是：
- 控制回答结构
- 保持边界清晰
- 不重新掉进“全文搜索很灵活但很飘”的坑里

---

### 3. 组合回答（先模板化）

#### A. System Knowledge 回答模板
结构：
1. 先直接回答问题
2. 用主卡给核心结论
3. 用 1~2 张辅卡补角色/链路/边界
4. 结尾加边界说明

#### B. Code Structure 回答模板
结构：
1. 先给大致落点（service/controller/service/consumer/job）
2. 再补依赖关系（Feign / MQ）
3. 最后说明“当前只是结构导航，不是方法级源码详解”

这一版先把回答风格做稳，比追求“更聪明”更重要。

---

## 四、最小实现落点

建议先不要大改现有主链，采用最小增量接法。

### 1. 在 router 层增加新意图
建议新增：
- `SYSTEM_KNOWLEDGE`
- `CODE_STRUCTURE`

### 2. 新增一个轻量知识服务
建议新增：
- `KnowledgeRoutingService`
- `KnowledgeCardLoader`
- `KnowledgeAnswerComposer`

### 3. 主流程保持不变
即：
- metrics 问题还走工具
- runtime 诊断还走现有 Harness
- 只有 architecture / business / code 解释类问题走知识路径

---

## 五、最小实现建议目录

```text
my-xhs-ai-app/src/main/java/.../
  service/knowledge/
    KnowledgeCardLoader.java
    KnowledgeRoutingService.java
    KnowledgeAnswerComposer.java
    KnowledgeQuestionClassifier.java
```

如果想更克制，可以先把 loader / classifier / composer 做成 3 个类，不要过早抽象太多层。

---

## 六、第一版不做什么

非常重要：

### 不做全文源码 RAG
当前先不允许模型自由扫全仓源码。

### 不做 cards 的向量检索
当前先走结构化匹配，不上 embedding。

### 不做混合检索平台
不做 BM25 + vector + rerank + cache 一整套。

### 不做自动生成超长答案
先控制回答结构，宁可保守一点。

这些不做，都是为了避免：
- 复杂度过早爆炸
- 重新把边界搞糊
- 知识层第一版还没站稳就变成“另一个复杂 AI 项目”

---

## 七、第一版最应该优先支持的 6 个问题

### Architecture
1. `my-xhs 的整体架构是什么？`
2. `为什么 home 是 BFF，而不是事务中心？`
3. `为什么 order 是编排中心？`

### Business
4. `为什么库存要三级扣减？`
5. `为什么事务消息、本地消息表和补偿任务必须同时存在？`

### Code Structure
6. `哪个 topic 负责把订单推进到库存世界？`

如果这 6 个问题能稳定回答，第一版系统知识问答路由就已经成立了。

---

## 八、验收标准

第一版完成，不看“代码优雅不优雅”，只看下面 4 个标准：

1. 问 architecture / business / code 问题时，不再误走 runtime diagnosis 工具链
2. 回答能稳定使用主卡 + 辅卡
3. 回答比当前“纯模型自由发挥”明显更稳
4. 不污染原有 metrics / diagnosis / approval 主线

只要做到这 4 条，就算第一版成功。

---

## 九、下一步顺序（最稳）

### Step 1
先加：
- `SYSTEM_KNOWLEDGE`
- `CODE_STRUCTURE`
两类规则意图

### Step 2
写：
- `KnowledgeCardLoader`
- 把 YAML 读起来

### Step 3
写：
- `KnowledgeAnswerComposer`
- 先支持 6 个核心问题

### Step 4
做第一轮知识问答验证

---

## 十、最重要的结论

现在最合理的实现方式不是“再做一个复杂 RAG 系统”，而是：

> **先把 cards/maps 这层资产，以最小增量方式接进现有路由，形成第一版系统知识问答路径。**

只要这一步走通，`my-xhs-ai` 就会第一次真正从：
- 会诊断运行态

升级到：
- **会解释系统本体，也会诊断运行态**
