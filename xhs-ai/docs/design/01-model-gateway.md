# D01 · 模型网关设计（G3 / ADR-8）

> 目标：把"调模型"从一次 HTTP 请求升级为可控的工程组件——路由、可靠性、预算、缓存友好、可观测

## 1. 范围

- 管：LLM（chat/streaming）+ Embedding 调用的统一入口与策略
- 不管：Agent 推理循环（AgentScope 负责）、提示词内容治理（见 D03/Skill 层）

## 2. 分层

```
AgentScope Model 抽象（OpenAIChatModel.baseUrl 指向网关）
      │
┌─────▼──────────────────────────────────────────┐
│ ModelGateway                                   │
│  Route → Retry → CircuitBreaker → Budget → Call│
│  └─ Metric/Trace（每次调用一个 span）           │
└─────┬──────────────────────────────────────────┘
      ▼
siyu-all(主)  →  DeepSeek 官方(备)  →  私有 vLLM(可选)
Ark Embedding（独立通道：批量/限流/降级 BM25）
```

## 3. 路由策略（任务分级）

| 任务 | 模型 | 依据 |
|------|------|------|
| 诊断推理 / 知识问答（主链路） | `deepseek-v4-pro` | 需要推理与工具调用 |
| 摘要 / 分块打标 / 意图分类 / 引用校验 | `deepseek-v4-flash` | 成本敏感、批量 |
| Embedding | `doubao-embedding-vision-large`（2048d） | 知识入库/检索 |
| 降级链 | pro → flash → **只读检索模式**（不生成，仅返回查询结果与引用） | 网关故障时保底可用 |

- reasoning 模型适配：`reasoning_content` 与最终 `content` 分离处理；`max_tokens` 需覆盖 reasoning + answer
- 每次调用记录：provider/model/tokens(in/out/cache/reasoning)/cost/latency/finish_reason

## 4. 可靠性

| 机制 | 策略 |
|------|------|
| 超时 | 连接 2s / 首字节 10s / 总 120s（chat），流式按空闲 30s |
| 重试 | 仅幂等调用；429/5xx/网络错误；指数退避 + jitter；最多 2 次；**零内容/空响应**视为可重试 |
| 熔断 | 滑动窗口错误率 ≥50%（≥10 次）→ OPEN 30s → HALF_OPEN 探针 |
| 降级 | 主模型不可用 → 备 provider → flash → 只读检索模式（明确告知用户） |
| 并发 | 按 provider 限流（信号量 + 队列）；超时排队拒绝而非无限等待 |

## 5. 预算与成本

- **以网关侧计量为准**（不采信模型自报）：usage 落 `ai_message` 与 `ai_audit`
- 会话预算：默认 100k tokens；80% 告警；超限自动切 `v4-flash` 并提示
- 单次诊断预算：由 Skill/工具循环上限 + `EXCEED_MAX_ITERS` 保护
- 成本看板：按用户/会话/工具维度（Prometheus 指标）

## 6. Prompt Cache 友好（D6）

1. System prompt + 工具 schema **字节级稳定**（版本化模板；变更需显式升版）
2. 动态信息（当前时间、会话摘要、DLQ 积压）只放 **turn tail**
3. 变更类操作（装技能/改记忆）默认**下会话生效**，避免中途破坏缓存
4. 观测：cache_read/cache_write tokens 入指标，评估缓存命中率

## 7. 多 Provider 与配置

```yaml
ai.model.primary:   { base-url: ${MYXHS_LLM_BASE_URL}, api-key: ${MYXHS_LLM_API_KEY}, model: deepseek-v4-pro }
ai.model.fallback:  { base-url: ${MYXHS_LLM_FALLBACK_URL:}, model: deepseek-v4-flash }
ai.model.light:     deepseek-v4-flash
ai.embedding:       { base-url: ${ARK_PLAN_BASE_URL}, api-key: ${ARK_PLAN_API_KEY}, model: doubao-embedding-vision-large, dims: 2048 }
```

## 8. 测试

- 契约（fixture）：429/5xx/超时/空 body/零内容/推理-only 响应
- 单测：路由选择、退避序列、熔断状态机、预算降级
- 集成：主→备切换、预算耗尽降级、只读兜底模式
- 指标：每 provider 成功率/延迟/成本；告警规则
