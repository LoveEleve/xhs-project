# vol-ai 索引页（2026-08-19）

> 目标：把 `vol-ai` 的正文、展示资产、trace、demo、评测报告和收官文档串成一张统一地图，让读者不用在几十个文件里自己摸索阅读顺序。

---

## 一、如果你只想最快看懂这个项目

### 最短阅读路径（15~20 分钟）
1. `my-xhs-ai/FINAL-SUMMARY.md`
2. `my-xhs-ai/SHOWCASE.md`
3. `my-xhs-ai/business-analysis/tech/pitch-3tier-v1.md`
4. `vol-ai/00-overview-architecture/03-why-harness-is-the-center.md`
5. `vol-ai/04-hitl-dlq-observability/01-hitl-and-dlq.md`
6. `vol-ai/05-eval-quality-release/03-final-closing-position.md`

这 6 个文件已经足够让读者知道：
- 项目是什么
- 强点在哪里
- 真实证据是什么
- 为什么现在可以收官

---

## 二、如果你想系统读完整卷

### 先读入口
1. `vol-ai/HANDOFF-AI-VOL.md`
2. `vol-ai/METHODOLOGY.md`
3. `vol-ai/README.md`

### 再读主线正文

#### 00-overview-architecture
- `01-project-positioning.md`
- `02-system-boundaries.md`
- `03-why-harness-is-the-center.md`

#### 02-tool-mcp-policy
- `01-tool-registry-boundary.md`

#### 03-memory-conversation-rag
- `01-memory-is-for-dev-not-end-user.md`

#### 04-hitl-dlq-observability
- `01-hitl-and-dlq.md`

#### 05-eval-quality-release
- `01-what-has-really-been-verified.md`
- `02-how-to-close-the-project-honestly.md`
- `03-final-closing-position.md`

这就是当前卷内主线。

---

## 三、按问题来找文章

### 1. “这个项目到底是什么？”
- `00-overview-architecture/01-project-positioning.md`
- `00-overview-architecture/02-system-boundaries.md`

### 2. “为什么系统中心不是模型，而是 Harness？”
- `00-overview-architecture/03-why-harness-is-the-center.md`

### 3. “为什么这里的工具不是随便给模型调的？”
- `02-tool-mcp-policy/01-tool-registry-boundary.md`

### 4. “为什么这里的 Memory 不是终端用户画像？”
- `03-memory-conversation-rag/01-memory-is-for-dev-not-end-user.md`

### 5. “为什么 HITL / DLQ / trace 让它更像企业系统？”
- `04-hitl-dlq-observability/01-hitl-and-dlq.md`

### 6. “这些能力到底被验证到了什么程度？”
- `05-eval-quality-release/01-what-has-really-been-verified.md`

### 7. “为什么现在就可以收官？”
- `05-eval-quality-release/02-how-to-close-the-project-honestly.md`
- `05-eval-quality-release/03-final-closing-position.md`

---

## 四、真实证据入口

### 1. Langfuse Trace
- DLQ：`https://cloud.langfuse.com/project/cmszt286u009oad0jpm40exh0/traces/8a487e394887d9b70c17549dcc005618`
- 订单归因：`https://cloud.langfuse.com/project/cmszt286u009oad0jpm40exh0/traces/9c4a3cf82c2ea631a98cab1bba49a526`
- 5xx 排障：`https://cloud.langfuse.com/project/cmszt286u009oad0jpm40exh0/traces/299302066f6cd66bbbafa94df7f1e019`

### 2. Demo 脚本
- `my-xhs-ai/demo-dlq.sh`
- `my-xhs-ai/demo-order-decline.sh`
- `my-xhs-ai/demo-5xx.sh`
- `my-xhs-ai/demo-temporal-restart.sh`

### 3. 评测报告
- `my-xhs-ai/docs/reports/e2e-eval-report-20260819.md`
- `my-xhs-ai-app/target/e2e-eval-report.json`

### 4. Temporal PoC
- `my-xhs-ai/business-analysis/tech/temporal-poc-review.md`

### 5. 测试矩阵
- `my-xhs-ai/business-analysis/tech/TEST-COVERAGE-MATRIX.md`

---

## 五、如果你是不同角色，该怎么读

### 1. 面试官 / 招聘方
推荐看：
1. `my-xhs-ai/FINAL-SUMMARY.md`
2. `my-xhs-ai/SHOWCASE.md`
3. `pitch-3tier-v1.md`
4. 一条 Langfuse trace
5. 一个 demo 脚本

### 2. 架构评审 / 技术负责人
推荐看：
1. `00-overview-architecture/03-why-harness-is-the-center.md`
2. `02-tool-mcp-policy/01-tool-registry-boundary.md`
3. `04-hitl-dlq-observability/01-hitl-and-dlq.md`
4. `temporal-poc-review.md`

### 3. 接手维护的人
推荐看：
1. `HANDOFF-AI-VOL.md`
2. `METHODOLOGY.md`
3. `HANDOFF-TASK14.md`
4. `TEST-COVERAGE-MATRIX.md`

### 4. 想快速看证据的人
推荐看：
1. `SHOWCASE.md`
2. Langfuse trace
3. demo 脚本
4. E2E 报告

---

## 六、当前卷的真实状态

### 已经完成
- 主线正文骨架已成立
- 关键篇章已写出
- 方法论已对齐
- 展示资产已可用
- 收官口径已稳定

### 还没做的
- `01-intent-router-harness/` 目录正文还没展开
- `06-temporal-durable-poc/` 卷内正式正文还没写
- 仍可继续补更多辅助索引页

### 当前最准确的判断

> `vol-ai` 当前已经不是零散分析文档，而是一卷**主线已成立、可继续补完但已经具备收口能力**的 AI 系统深度解剖文档。

---

## 七、如果要继续往下写，下一步最合理的两个方向

### 方向 A：补缺的正文
- `01-intent-router-harness/01-why-router-must-decide-before-agent.md`
- `06-temporal-durable-poc/01-why-temporal-is-only-a-poc.md`

### 方向 B：正式收口
- 不再继续写正文
- 直接把 `FINAL-SUMMARY.md + SHOWCASE.md + pitch-3tier-v1.md + vol-ai/INDEX.md` 作为最终对外包

当前最稳的建议是：
> **已经可以收口，不必再为了“完整感”而机械补篇。**