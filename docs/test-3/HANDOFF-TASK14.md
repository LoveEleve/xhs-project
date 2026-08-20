# my-xhs 交接文档 — Task14（AI Agent 真实 E2E 首次跑通 `mqDlqQuery → dlq.redeliver → CR_SUCCESS`）

> 2026-08-19 | 承接 Task13（DLQ 手动验证 + AI Agent profile bug 发现）→ 本阶段：**AI Agent 真实 E2E 首次跑通** + **5 项代码修复** + **深度项目评估**

---

## 零、核心成果

```
AI Agent 诊断台（my-xhs-ai-app:19020）首次完成真实外部 E2E：
  Agent → mqDlqQuery → 拿到 ORIGIN_MESSAGE_ID → dlq.redeliver → HITL 审批 → approve → CR_SUCCESS

修复了 5 项代码问题：
  1. MQ_DLQ_QUERY 未加入 Agent Profile 工具集（AgentProfiles.java）
  2. dlq-redeliver.url 配置缺失（application.yml）
  3. toolResultMaxLen=400 太小导致 DLQ 结果截断（application.yml）
  4. mqDlqQuery 返回结果不含 messageBody，agent 无法匹配业务字段（DlqRedeliverTool.java）
  5. logSearch 白名单只有 my-xhs-nacos（my-xhs-ai-mcp application.yml）
```

---

## 一、修复明细

### 1.1 `MQ_DLQ_QUERY` 未加入 Agent Profile（P0 bug）

**文件**：`my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/profile/AgentProfiles.java:24`

**问题**：`OPS_TOOLS` 包含 `MQ_DLQ_BACKLOG` 和 `L3_DLQ_REDELIVER`，但缺少 `MQ_DLQ_QUERY`。工具在 `AgentToolCatalog` 和 `AgentToolBinder` 中注册了，但没有 Agent Profile 包含它 → 模型看不到 → "当前环境没有 mq.dlq_query 工具"。

**修复**：在 `OPS_TOOLS` 中加入 `AgentToolNames.MQ_DLQ_QUERY`。

### 1.2 `dlq-redeliver.url` 配置缺失（P0）

**文件**：`my-xhs-ai-app/src/main/resources/application.yml`

**问题**：`myxhs.ai.hitl.dlq-redeliver.url` 未配置 → `DlqRedeliverTool.baseUrl=null` → 所有 DLQ 工具返回"管理通道未配置"。

**修复**：添加配置：
```yaml
myxhs:
  ai:
    hitl:
      dlq-redeliver:
        url: ${MYXHS_AI_DLQ_REDIVER_URL:http://21.130.247.89:18081}
```

### 1.3 `toolResultMaxLen` 太小（P1）

**文件**：`my-xhs-ai-app/src/main/resources/application.yml`

**问题**：默认 400 字符，DLQ 查询返回 100 条消息的 JSON 被截断，agent 无法看到完整结果。

**修复**：添加配置 `tool-result-max-len: 2000`。

### 1.4 `mqDlqQuery` 返回不含 `messageBody`（P1）

**文件**：`my-xhs-ai-tools/src/main/java/com/myxhs/ai/tools/DlqRedeliverTool.java:198`

**问题**：返回的消息只有 MQ 级字段（originMsgId/msgId/retryTopic），不含 `messageBody` → agent 无法用 `orderNo` 等业务字段匹配目标消息。

**修复**：始终返回 `body` 字段（截断到 200 字符）。

### 1.5 `logSearch` 白名单只有 `my-xhs-nacos`（P1）

**文件**：`my-xhs-ai-mcp/src/main/resources/application.yml`

**问题**：白名单只有 `my-xhs-nacos`，agent 无法查业务服务日志。

**修复**：加入 `my-xhs-order`/`my-xhs-inventory`/`my-xhs-payment`/`my-xhs-gateway`/`my-xhs-content`/`my-xhs-user`。

---

## 二、真实 E2E 完整链路

### 2.1 数据准备

1. 造坏消息：改 `t_local_message_0` 的 `payload` 为合法 JSON 但 `quantity=9999`（库存不足）
2. 清库存：`t_inventory.available_stock=0` + Redis `inventory:{skuId}:total=0`
3. 清幂等：`t_inventory_prededuct_idem` 删除对应记录
4. 触发 `localMessageRetryJob` → 补发到 `ORDER_TRANSACTION_TOPIC`
5. consumer 重试 5 次（`BizException("库存不足")`）→ 进 `%DLQ%inventory-order-transaction-consumer-group`

### 2.2 AI Agent 诊断

1. 提交诊断任务：`POST /api/runs {"message": "帮我查一下...死信消息...重投它"}`
2. Agent 调 `mqDlqQuery` → 拿到 DLQ 消息列表 + `ORIGIN_MESSAGE_ID`
3. Agent 调 `dlq.redeliver` → HITL 审批门触发 → `WAITING_APPROVAL`
4. 人工 approve → `APPROVED_RUNNING` → 执行重投
5. Dashboard 返回 `consumeResult: CR_SUCCESS`

### 2.3 关键参数

| 参数 | 值 |
|------|---|
| 订单号 | `ORD2026081910092814599380003` |
| userId | `2089723488945319938` |
| skuId | `2089725027437015042` |
| 分片 | db=`uid%4=2`, tb=`(uid/4)%4=0` → `my_xhs_order_2.t_local_message_0` |
| ORIGIN_MESSAGE_ID | `1582F75900002E87000000009171E637` |
| DLQ topic | `%DLQ%inventory-order-transaction-consumer-group` |
| consumer group | `inventory-order-transaction-consumer-group` |
| retryTopic | `ORDER_TRANSACTION_TOPIC` |

---

## 三、深度项目评估结论

### 3.1 代码质量：Harness 是真的好

- `AgentHarness.java` 859 行，从零写的受限 Agent 引擎
- 循环控制（`LoopDetector`）、预算管理（`LoopCtrl`）、证据链（`EvidenceChain`）、策略守卫（`PolicyGuard`）、HITL 审批、SSE 流式推送、会话多轮——全部自研
- 比 LangChain 套壳项目强一个量级

### 3.2 工具系统：17 个工具，15 个有真实执行器

| 级别 | 数量 | 状态 |
|------|------|------|
| L1 业务指标 | 7 | ✅ 全部真实 DB 查询 |
| L2 观测 | 8 | ✅ 全部真实（MCP → Prometheus/MySQL） |
| L3 高危 | 2 | ✅ dlq.redeliver 真实 Dashboard；service.restart/order.refund 空壳 |

### 3.3 评测：通过率是"契约通过率"，不是"E2E 通过率"

- `EvalRunner` 用真模型跑评测集，但断言是模式匹配
- 外部依赖用 fake HTTP 响应（契约测试）
- "100% 通过"="模型按预期格式回答"+"工具返回正确结构"

### 3.4 多 Agent：是 Profile 切换，不是多智能体

- `BUSINESS`/`OPS`/`FULL` = 同一 harness + 不同工具子集 + 不同 prompt
- 没有 agent 间通信、任务分解、协调

### 3.5 Memory：会话级已做，用户级未做

- ✅ `ConversationService`：多轮上下文注入、摘要、消息持久化
- ❌ 用户级长期记忆（没有 `ai_memory` 表、没有偏好学习）

---

## 四、启动命令

```bash
# AI MCP
export MYXHS_DB_PASSWORD=9c63e8a12920706e855c63e5669a7d45
export MYXHS_AI_DB_PASSWORD=9c20307695d6cb3ed6f392c88a3772c3
nohup java -jar my-xhs-ai-mcp/target/my-xhs-ai-mcp-1.0-SNAPSHOT.jar > /tmp/ai-mcp.log 2>&1 &

# AI App
export MYXHS_LLM_API_KEY=sk-mUC3NsNqoWC0YH6HkXfNXotPSiFgkpFaQ8wxNSs6o1AtAMl7xgI7WZNBX0XMNcTt
export MYXHS_AI_DB_PASSWORD=9c20307695d6cb3ed6f392c88a3772c3
export MYXHS_DB_PASSWORD=9c63e8a12920706e855c63e5669a7d45
nohup java -jar my-xhs-ai-app/target/my-xhs-ai-app-1.0-SNAPSHOT.jar > /tmp/ai-app.log 2>&1 &

# 验证
curl http://localhost:19020/actuator/health
curl http://localhost:19021/actuator/health
```

---

## 五、下一步

| # | 任务 | 优先级 | 状态 |
|---|------|--------|------|
| 1 | 更新 `final-status-v1.md` / `HANDOFF-AI-v6.md` / `retrospective-v1.md` 口径（CR_SUCCESS） | P0 | ✅ 已完成 |
| 2 | 写 3-5 个真实 E2E 评测 case（`cases-e2e.yaml`） | P1 | ✅ 已完成（5 case，80% 通过） |
| 3 | 实现用户级长期记忆（`ai_memory` 表 + 豆包 embedding 语义检索） | P2 | ✅ 已完成 |
| 4 | `service.restart`/`order.refund` 从 prompt 标注未实现 | P2 | ✅ 已完成 |
| 5 | 文档收敛索引（`DOC-INDEX.md`） | P2 | ✅ 已完成 |
| 6 | 3 个 demo 脚本（DLQ 重投 / 订单归因 / 5xx 排障） | P1 | ✅ 已完成并验证 |
| 7 | 面试话术更新（pitch-3tier-v1.md，含 E2E 证据） | P1 | ✅ 已完成 |

---

## 六、Demo 脚本验证结果

| Demo | 结果 | 亮点 | Langfuse Trace |
|------|------|------|----------------|
| `demo-dlq.sh` | ✅ | Agent 找到 DLQ 消息，识别 quantity=9999 异常，谨慎询问确认 | `https://cloud.langfuse.com/project/cmszt286u009oad0jpm40exh0/traces/8a487e394887d9b70c17549dcc005618` |
| `demo-order-decline.sh` | ✅ | 漏斗分析：加购→下单转化率从44%暴跌到3%，8条证据链 | `https://cloud.langfuse.com/project/cmszt286u009oad0jpm40exh0/traces/9c4a3cf82c2ea631a98cab1bba49a526` |
| `demo-5xx.sh` | ✅ | **自动发现两个真实代码 bug**：CouponFeignClient 缺 X-User-Id、comment ClassCastException，13条证据 | `https://cloud.langfuse.com/project/cmszt286u009oad0jpm40exh0/traces/299302066f6cd66bbbafa94df7f1e019` |

---

## 七、Memory 系统验证

| 验证项 | 结果 |
|--------|------|
| 向量化（豆包 doubao-embedding-vision-large） | ✅ 2048维，embedding_json 存储 |
| 语义检索（余弦相似度） | ✅ "服务端报错" 匹配到 "5xx 错误" |
| 多用户隔离 | ✅ dev-zhangsan / dev-lisi 记忆完全隔离 |
| 自动分类 | ✅ order/payment/service/mq/inventory/content/general |
| 记忆注入 | ✅ 运行前注入相关记忆到上下文 |
| 记忆提取 | ✅ 运行后自动从结论提取记忆 |

---

## 八、一句话交接

本阶段完成：AI Agent 真实 E2E 首次跑通（CR_SUCCESS）+ 5 项代码修复 + E2E 评测（5 case 80% 通过）+ 向量语义记忆（豆包 embedding + 多用户隔离）+ 3 个可现场演示的 demo 脚本 + 面试话术更新 + Langfuse trace 接通（已可展示）+ Temporal PoC 跑通审批型长任务的 worker kill / restart / approve / complete 闭环。项目现在有一条可重复跑通的真实端到端验证链路，面试时可现场演示 DLQ 诊断/订单归因/5xx 排障，并能讲清 durable execution 取舍。

---

## 九、Langfuse 接入状态

| 组件 | 状态 | 文件 |
|------|------|------|
| Maven 依赖 | ✅ 已添加 | `my-xhs-ai-app/pom.xml` |
| OTel/自定义 Exporter 配置 | ✅ 已配置 | `application.yml` / `OtelConfig.java` |
| TracingListener | ✅ 已实现 | `LangfuseTracingListener.java` |
| Exporter | ✅ 已实现并连通 | `LangfuseOtlpExporter.java` |
| RunManager 注入 | ✅ 复合 listener + run metadata | `RunManager.java` |
| Langfuse API Key | ✅ 已配置并验证 | `cloud.langfuse.com` |

当前效果：
- 主 trace 名称：`diagnosis-run_<runId>`
- `userId` / `sessionId` 正确显示
- span 树：`agent.run` → `GENERATION` / `TOOL` / `agent.answer`
- generation metadata 已展示：`modelName` / `inputTokens` / `outputTokens` / `cost`

代表性 trace：
- DLQ：`https://cloud.langfuse.com/project/cmszt286u009oad0jpm40exh0/traces/8a487e394887d9b70c17549dcc005618`
- 订单归因：`https://cloud.langfuse.com/project/cmszt286u009oad0jpm40exh0/traces/9c4a3cf82c2ea631a98cab1bba49a526`
- 5xx 排障：`https://cloud.langfuse.com/project/cmszt286u009oad0jpm40exh0/traces/299302066f6cd66bbbafa94df7f1e019`
