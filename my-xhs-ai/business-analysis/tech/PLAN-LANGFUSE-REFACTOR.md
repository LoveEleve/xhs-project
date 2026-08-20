# Langfuse 重构规划（代码质量提升）

> 2026-08-19 | 不动配置/密钥安全，只改代码质量

---

## 一、当前问题

| 问题 | 影响 |
|------|------|
| 自定义 `LangfuseOtlpExporter` 绕过标准路径 | 脆弱，升级 OTel SDK 可能 break |
| userId 硬编码 `unknown` | Langfuse 无法按用户筛选 trace |
| 没有 token 用量/model name/cost | Langfuse 最核心的价值没用上 |
| span 手动创建，parent-child 关系不完整 | trace 树不清晰 |
| `callModel()` 没有 generation span | Langfuse 无法识别 LLM 调用 |

## 二、重构方案

### 2.1 去掉自定义 OTLP Exporter，用 Langfuse REST API 直接调用

当前：`LangfuseOtlpExporter` 自己拼 OTLP JSON → 发到 Langfuse
问题：绕过标准 OTel SDK，trace context 传播不完整

改为：`LangfuseRestClient` 直接调 Langfuse ingestion API
- `POST /api/public/ingestion` → 批量发送 trace/span/generation
- 不走 OTel SDK，完全自控
- 更容易添加 Langfuse 特有字段（cost/model/tokens）

### 2.2 重构 LangfuseTracingListener

当前：每个事件独立建 span，parent 关系靠 `rootSpans` Map 维护
问题：异步边界 trace context 丢失

改为：
- `onStart()` → 创建 trace + root span
- `onThink()` → 创建 generation span（记录 model/tokens/cost）
- `onTool()` → 创建 tool span（记录 input/output）
- `onAnswer()` → 创建 span（记录 output）
- `onTerminal()` → 关闭 trace（记录 final status）

### 2.3 补充 AgentHarness 数据

当前 `HarnessEvent` 缺少：
- `userId`（需要从 RunManager 传入）
- `modelName`（需要从 Harness 传入）
- `tokenUsage`（需要从 `callModel()` 返回值提取）

方案：扩展 `HarnessEvent` 或创建新的 `TraceContext` 对象。

## 三、实施步骤

| # | 任务 | 产出 |
|---|------|------|
| 1 | 写 `LangfuseRestClient`（直接调 ingestion API） | 新类 |
| 2 | 重构 `LangfuseTracingListener`（用 RestClient 替代 OTel） | 重写 |
| 3 | 在 `AgentHarness.callModel()` 里记录 token usage | 修改 |
| 4 | 在 `RunManager` 里传 userId 到 trace | 修改 |
| 5 | 删掉 `LangfuseOtlpExporter` 和 `OtelConfig` | 清理 |
| 6 | 验证：提交诊断任务，Langfuse 上看到完整 trace | 测试 |

## 四、Langfuse 数据模型映射

| Langfuse 概念 | 实现 |
|---------------|------|
| Trace | 一次 `run()` 调用 |
| Span | think / tool / answer 步骤 |
| Generation | `callModel()` 调用（含 model/tokens/cost） |
| User | `userId`（从 X-User-Id header） |
| Session | `convId`（会话 ID） |
| Metadata | runId / profile / budget |

## 五、成功标准

- [ ] Langfuse trace 显示完整 span 树（think → tool → answer）
- [ ] 每个 generation span 有 model name / token usage / cost
- [ ] userId 正确显示（不是 unknown）
- [ ] 点击 trace 能看到每步的工具输入输出
