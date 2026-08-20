# my-xhs-ai 展示页（2026-08-19）

> 这是当前项目最适合对外展示的一页：4 个 demo、3 条 Langfuse trace、1 个 Temporal PoC、1 套 Memory、1 份 E2E 报告。

---

## 一、项目一句话

`my-xhs-ai` 是一个面向电商运营和运维场景的**受限诊断 Agent**：
- 确定性数字优先走工具
- 复杂归因进入有预算/权限/证据约束的 Agent
- 高危动作进入 HITL 审批
- 每一步都可追溯到真实证据和 Langfuse trace

---

## 二、三大可现场演示 Demo

### Demo 1：DLQ 死信诊断 + 重投
- 脚本：`my-xhs-ai/demo-dlq.sh`
- 亮点：Agent 自动查 DLQ → 找到 `ORIGIN_MESSAGE_ID` → 进入 HITL 审批 → 重投 → `CR_SUCCESS`
- Langfuse：`https://cloud.langfuse.com/project/cmszt286u009oad0jpm40exh0/traces/8a487e394887d9b70c17549dcc005618`

### Demo 2：订单量下降归因
- 脚本：`my-xhs-ai/demo-order-decline.sh`
- 亮点：Agent 通过订单量 + 基线 + 漏斗，发现**加购→下单转化率从44%暴跌到3%**
- Langfuse：`https://cloud.langfuse.com/project/cmszt286u009oad0jpm40exh0/traces/9c4a3cf82c2ea631a98cab1bba49a526`

### Demo 3：5xx 排障
- 脚本：`my-xhs-ai/demo-5xx.sh`
- 亮点：Agent 自动定位两个真实 bug：
  1. `CouponFeignClient` 缺 `X-User-Id`
  2. `comment/count` 的 `ClassCastException`
- Langfuse：`https://cloud.langfuse.com/project/cmszt286u009oad0jpm40exh0/traces/299302066f6cd66bbbafa94df7f1e019`

### Demo 4：Temporal 审批型长任务 restart 恢复
- 脚本：`my-xhs-ai/demo-temporal-restart.sh`
- 亮点：workflow 进入 `WAITING_APPROVAL` 后，独立 worker 被 kill，再重启 worker，approve 后仍能 `COMPLETED`
- 价值：证明不仅能做 Agent，还理解 durable execution 的边界与取舍

---

## 三、Langfuse 可视化能力

当前 Langfuse trace 已展示：
- 主 trace 名：`diagnosis-run_<runId>`
- `userId` / `sessionId` 正确挂钩
- span 树：`agent.run` → `GENERATION` → `TOOL` → `agent.answer`
- generation metadata：
  - `modelName = mimo-v2.5-pro`
  - `inputTokens`
  - `outputTokens`
  - `cost`
- tool output：结构化 JSON 结果

代表性 trace：
- `https://cloud.langfuse.com/project/cmszt286u009oad0jpm40exh0/traces/80e214f98f322c1c2f1c733878e2462d`

---

## 四、Memory 能力

### 4.1 技术方案
- 向量模型：豆包 `doubao-embedding-vision-large`（2048 维）
- 检索方式：余弦相似度语义检索
- 存储：`my_xhs_ai.ai_memory`
- 隔离：按研发用户 `userId` 分库（逻辑隔离）

### 4.2 已验证
- `dev-zhangsan`：订单/支付相关记忆
- `dev-lisi`：服务/5xx/MQ 相关记忆
- 不同研发的记忆完全隔离
- 不同措辞能语义命中（例："服务端报错情况怎么样？" 命中 "HTTP 5xx 错误" 记忆）

---

## 五、E2E 评测结果

报告：`my-xhs-ai/docs/reports/e2e-eval-report-20260819.md`

| 指标 | 数值 |
|------|------|
| E2E case 数 | 5 |
| 完成率 | 100% |
| 通过率 | 80% |
| 幻觉误报 | 1（评测器把错误消息里的数字当成幻觉） |

这说明项目已经进入真实 E2E 阶段，而不是停留在 fake/mock 契约测试。

---

## 六、最适合面试时怎么说

> 我这个项目的价值不在于“接入了一个大模型”，而在于把大模型约束成一个能在真实业务边界内工作的诊断 Agent。它不会直接写 SQL，也不会执行任意代码；所有数字都来自固定工具，所有高危动作都进入 HITL 审批，所有过程都能在 Langfuse 上追 trace。现在我已经有三个可以现场演示的真实场景：DLQ 死信重投、订单漏斗归因、5xx 排障，这些都不是 mock，而是直接对真实的 RocketMQ、MySQL、Prometheus 和日志文件做调查。
