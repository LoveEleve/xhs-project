# vol-ai 交接与阅读入口（2026-08-19）

> 目标：把 `vol-ai` 从“已经写了几篇文章”收口成“读者知道该从哪里开始、看到哪里停、不同场景应该怎么读”的一卷文档入口。

---

## 一、这一卷现在写到了哪里

当前 `vol-ai` 已经完成的正文主链包括：

### 00-overview-architecture
- `01-project-positioning.md`
- `02-system-boundaries.md`
- `03-why-harness-is-the-center.md`

### 02-tool-mcp-policy
- `01-tool-registry-boundary.md`

### 03-memory-conversation-rag
- `01-memory-is-for-dev-not-end-user.md`

### 04-hitl-dlq-observability
- `01-hitl-and-dlq.md`

### 05-eval-quality-release
- `01-what-has-really-been-verified.md`
- `02-how-to-close-the-project-honestly.md`
- `03-final-closing-position.md`

此外，卷外还有这些强关联资产：
- `my-xhs-ai/SHOWCASE.md`
- `my-xhs-ai/FINAL-SUMMARY.md`
- `my-xhs-ai/business-analysis/tech/pitch-3tier-v1.md`
- `my-xhs-ai/docs/reports/e2e-eval-report-20260819.md`
- `docs/test-3/HANDOFF-TASK14.md`

---

## 二、这一卷现在最适合谁读

### 1. 想快速判断项目值不值得看的读者
推荐只看：
1. `my-xhs-ai/FINAL-SUMMARY.md`
2. `my-xhs-ai/SHOWCASE.md`
3. `vol-ai/00-overview-architecture/03-why-harness-is-the-center.md`

### 2. 想系统读懂架构的人
推荐顺序：
1. `vol-ai/METHODOLOGY.md`
2. `vol-ai/00-overview-architecture/01-project-positioning.md`
3. `vol-ai/00-overview-architecture/02-system-boundaries.md`
4. `vol-ai/00-overview-architecture/03-why-harness-is-the-center.md`
5. `vol-ai/02-tool-mcp-policy/01-tool-registry-boundary.md`
6. `vol-ai/03-memory-conversation-rag/01-memory-is-for-dev-not-end-user.md`
7. `vol-ai/04-hitl-dlq-observability/01-hitl-and-dlq.md`
8. `vol-ai/05-eval-quality-release/01-what-has-really-been-verified.md`
9. `vol-ai/05-eval-quality-release/02-how-to-close-the-project-honestly.md`
10. `vol-ai/05-eval-quality-release/03-final-closing-position.md`

### 3. 想面试讲项目的人
推荐顺序：
1. `my-xhs-ai/business-analysis/tech/pitch-3tier-v1.md`
2. `my-xhs-ai/SHOWCASE.md`
3. `my-xhs-ai/FINAL-SUMMARY.md`
4. `vol-ai/00-overview-architecture/03-why-harness-is-the-center.md`
5. `vol-ai/04-hitl-dlq-observability/01-hitl-and-dlq.md`
6. `vol-ai/05-eval-quality-release/03-final-closing-position.md`

### 4. 想看真实证据的人
推荐顺序：
1. `my-xhs-ai/docs/reports/e2e-eval-report-20260819.md`
2. `docs/test-3/HANDOFF-TASK14.md`
3. Langfuse 3 条 trace
4. `my-xhs-ai/demo-dlq.sh`
5. `my-xhs-ai/demo-order-decline.sh`
6. `my-xhs-ai/demo-5xx.sh`
7. `my-xhs-ai/demo-temporal-restart.sh`

---

## 三、这一卷的真正主线是什么

这一卷不是要证明“我会多少 AI 名词”，而是要把下面这条线讲透：

```text
它不是聊天机器人
  ↓
它是一个受限诊断 Agent 控制面
  ↓
中心不是模型，而是 Harness
  ↓
工具系统本身是边界
  ↓
Memory 服务的是研发诊断用户，而不是消费者画像
  ↓
高危动作必须停下来进入审批
  ↓
关键能力已经有真实证据，但边界仍需诚实标注
  ↓
因此它现在可以体面收官
```

这条主线就是 `vol-ai` 的骨架。

---

## 四、哪些东西要故意不往下写了

为了收官，下面这些方向目前不再继续扩正文：

- 真正多 Agent 协作体系
- A2A
- MemoryOS 深度复现展开
- Temporal 全量主线迁移
- Docker/K8s/灰度/回滚
- 300+ release 级评测集

原因不是它们不重要，而是：

> 对当前项目来说，这些已经属于下一阶段增强，而不是当前收官前置项。

`vol-ai` 现在的目标不是把所有未来都讲完，而是把**当前已成立的系统层级**讲透。

---

## 五、如果还要继续写，下一步该写什么

现在最适合补的，不再是能力正文，而是卷内的编排辅助页。推荐优先级如下：

### P1：`00-overview-architecture/04-reading-path.md`
解释：
- 为什么这卷的阅读顺序是这样
- 先看哪篇，再看哪篇
- 不同读者该跳过哪些篇章

### P2：`vol-ai/INDEX.md`
把已完成篇章、强关联篇章、demo、trace、报告串成统一索引

### P3：`vol-ai/06-temporal-durable-poc/01-why-temporal-is-only-a-poc.md`
如果还想补一篇能力正文，这是最值得补的一篇，因为它能把“PoC 边界”讲透。

---

## 六、当前最推荐的对外使用方式

如果你现在要拿这个项目去讲，不建议让对方自己在几十篇文档里乱翻。

最稳的方式是：

1. 先发 `my-xhs-ai/FINAL-SUMMARY.md`
2. 再发 `my-xhs-ai/SHOWCASE.md`
3. 再按对方兴趣给：
   - 架构看 `03-why-harness-is-the-center.md`
   - 边界看 `01-tool-registry-boundary.md`
   - 记忆看 `01-memory-is-for-dev-not-end-user.md`
   - 企业味最强的看 `01-hitl-and-dlq.md`
   - 收官口径看 `03-final-closing-position.md`

这比把整卷一次性甩给别人更有效。

---

## 七、当前这一卷最准确的状态

最准确的描述不是：
- “还只是草稿”

也不是：
- “已经是完整大卷终稿”

而是：

> **主线正文已经成立，关键篇章已具备卷内标杆质量，当前进入收口与编排阶段。**

这就是当前 `vol-ai` 最真实的状态。