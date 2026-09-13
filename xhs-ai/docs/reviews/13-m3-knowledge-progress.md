# RV13：M3 知识层进展与模型网关稳定性评估

> 日期：2026-09-13 ｜ 范围：M3 知识底座（D12）第一增量 + 外部依赖评估
> 结论：**知识层交付完成并验证**；Agent 端到端问答受 **siyu 网关间歇性不稳定**限制（有实测证据），已加失败显式化与模型重试/降级，下一步优先做 D01 网关韧性。

---

## 1. 本增量交付（已验证）

| 项 | 证据 |
|----|------|
| 知识卡迁移 | 54 张 A 级卡入 `xhs-ai/knowledge/`（architecture 11 / business 7 / code-map 35 / failure 1） |
| 卡片加载 + catalog | 启动日志 `[知识] 卡片加载完成: 54 张`；catalog 输出压缩为每层 Top10（渐进披露） |
| ES BM25 索引 | `xhs_ai_knowledge` 创建并索引 54 文档（`/api/ai/knowledge/stats` → cards=54, indexed=54） |
| 检索质量（直查） | "home BFF 事务中心" → top1 `architecture/bff-role` score 46.5（精确命中） |
| 工具链 | `knowledge_catalog` / `knowledge_search` / `card_read` 注册进 Agent |
| MCP 白名单 | 改用 `McpServerRegistrar + enableTools`：Prometheus 仅 17 个只读工具，**排除 docs_***（修复模型误选 Prometheus 文档工具做架构问答） |
| 模型参数缺陷 | Agent `maxTokens 2048 → 8192`（推理模型长推理耗尽配额导致空答复） |
| 模型韧性（最小） | `maxRetries(2)` + `fallbackModel(deepseek-v4-flash)` |
| 失败显式化 | 网关无内容时 SSE 返回 `error`（不再静默 `done`）；同步接口返回 503 |

## 2. 外部依赖评估：siyu 网关稳定性

| 探测 | 结果 |
|------|------|
| 短请求连通（10 次，max_tokens=1） | **9/10 成功**（1 次连接失败） |
| 长流式（Agent 单轮多模型调用） | 间歇失败：`SocketException: Connection reset` / `HttpConnectTimeoutException`（日志实证多次） |
| 直接复现（同提示+同工具，curl 直连） | 正常返回（说明提示/工具本身无问题） |

结论：短请求 ~90% 可用，但 Agent 单轮需 2~4 次模型调用，叠加后失败概率显著上升；失败时 AgentScope 可能返回空消息，已由"失败显式化"兜底。

## 3. 下一步（优先级）

1. **D01 模型网关韧性**（最高优先）：流式连接重试（含中途断开）、熔断与快速失败、错误语义向上传递；必要时切换备用 provider。
2. 模型选型：为工具循环选择非推理/低推理模型或支持 `reasoning_effort` 的通道（需与网关确认能力），降低单轮时长与失败面。
3. M3 续：`code_locate`（轻量解析）、KB EVAL 30 条、卡片引用校验门禁。
4. 工具预算：当前 Agent 侧 26 个工具（3 DLQ + 3 知识 + ES 4 + Prom 17-1），接近 RV05 阈值，保持白名单纪律。

## 4. 诚实边界

- 知识问答"到答案"的端到端在网关稳定时段可用（早期 DLQ 场景多次成功），但在当前网络条件下未做满 30 条 EVAL；
- 跨实例/超时审批等 M2.x 项不受影响，已挂账。
