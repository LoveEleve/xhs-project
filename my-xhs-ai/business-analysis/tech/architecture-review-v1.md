# 深度 Review：重构后核心链路（架构-review-v1）

> 日期：2026-08-15 | 范围：AgentHarness（拆分后）、RunManager（并发）、IntentRouter（三阶）、McpClient（会话）、resume（M5-4）、ToolJson/工具 record（类型化）
> 方法：逐行读核心链路 + 并发路径推演 + 行为回归对照（192 测试 + 真实 E2E 为安全网）
> 结论：**1 个 P0 已修**；3 个 P1 记录待办；若干 P2 改进项

---

## P0（已修复）

### P0-1 RunManager 意图预检与 AiQueryController 不一致
- **问题**：`RunManager` 3 参构造器（@Autowired）内部 `new IntentRouter()`——纯规则模式，无 LLM 分类器。而 `AiQueryController` 注入的是 RouterConfig 装配的（含 LLM 分类，默认开启）。
- **后果**：UI 走 `/api/runs`（前端唯一入口），"帮我找下昨天的告警"这类**无规则信号但语义是诊断**的消息：`/api/ai/query` 会 LLM 分类 → AGENT；`/api/runs` 预检 → GREETING 引导语打发。**同一系统两种行为**。
- **修复**：@Autowired 移到 4 参构造器，注入与 AiQueryController 共用的 IntentRouter bean。测试全绿（RunManagerTest 用 3/2 参构造器保持纯规则行为不变）。

---

## P1（记录待办）

### P1-1 cancelStream 窗口竞态（低危）
- streamTo：`compareAndSet(streaming)` 成功 → `streamTokens.put(runId, cancel)` → `executor.submit(泵)`。
- 若客户端在 compareAndSet 与 put 之间断开：cancelStream 找不到 token/thread → 取消丢失；泵 poll(5s) 阻塞，新订阅 409 最长 5s（EventSource 自动重试）后恢复。
- **影响**：毫秒级窗口，5s 最坏 409，前端自动重试可自愈。修复成本低但收益小，暂记录。

### P1-2 resumeEntry 指标失真（低危）
- `resumeEntry` 调 `metrics.onRunSubmitted()`——崩溃恢复不算新 run 提交，runs_total 略失真。
- **影响**：M6-3 指标统计口径偏差（恢复场景罕见）。待办：恢复走独立指标（onRunResumed）。

### P1-3 direct-answer 落库失败静默（低危）
- `submitDirectAnswer` 落库 catch 后仅 warn——可追溯性静默丢失（已确认 warn 有日志）。
- 待办：失败时记录到事件流（前端可见"追溯不可用"），或 Metrics 计数。

---

## P2（改进项，非缺陷）

| # | 项 | 说明 |
|---|----|------|
| P2-1 | AgentStep.state 仍是 String（"THINK"/"TOOL"）| 是 ai_step 表持久化契约，枚举化需兼容旧数据——建议 StepState 枚举 + name() 序列化（同 HarnessEventType 模式）|
| P2-2 | /api/ai/query 的 GREETING/OUT_OF_SCOPE 直答不落库 | 与 /api/runs 直答（落库）不一致，审计不完整；前端走 /api/runs 不受影响 |
| P2-3 | ToolJson.error() 无 window 字段 | 错误响应契约与 ok 响应字段不完全一致（前端按 status 区分，无影响）|
| P2-4 | purgeDone 在 submit 内联调用 | 高频提交时 O(n) 扫描，n 小（TTL 1h）无实际影响；可移到定时器 |
| P2-5 | resume 的 query 兜底为 runId | loadRun 失败时展示 runId 代替 query，可接受 |

---

## 已验证无问题（深度审查确认）

1. **streamThreads/streamTokens 清理竞态**：`remove(key, value)` 均为条件移除——旧泵 finally 不会误删新订阅的 token/thread ✓
2. **direct-answer 事件顺序**：queue FIFO，先 RUN_STARTED 后 COMPLETED ✓（落库在事件前，SSE 订阅不丢事件）
3. **purgeDone 与泵线程**：泵在 run 终态 drain 后退出，purge 时泵已死，无悬挂引用 ✓
4. **executeLoop 拆分行为等价**：LoopStep 透传 invalidOutputs；DECLINE/ANSWER/TOOL 分支与重构前逐行一致（192 测试覆盖全部终止路径）✓
5. **resume 计步语义**：仅 THINK 计步（TOOL/ANSWER 不计）与原始执行一致；evidence 从 store 恢复 ✓
6. **McpClient 会话自愈**：异常重置 sessionId 重试一次，synchronized 防并发 initialize 竞态 ✓
7. **LlmIntentClassifierImpl 失败路径**：异常/非法枚举 → null → 降级语义层/默认引导（不保守 AGENT 防空跑）✓
8. **ToolJson 序列化契约**：record 字段名与手拼 JSON 一致（OrderMetricsToolTest 等断言未改全绿）✓

---

## 审查结论

重构方向正确，核心链路在拆分/类型化后**没有引入行为回归**（192 测试 + 真实 E2E 佐证）。发现并修复 1 个真实 P0（路由行为不一致——这正是深度 review 的价值：日常 E2E 只测"正常路径"，一致性问题需要对照两入口才暴露）。

**遗留建议**：P1 三项 + P2 按需处理；P2-1（StepState 枚举化）与下批架构重构（Bridge 泛型化/@ConfigurationProperties）合并执行。
