# xhs-ai 演练：模型主备切换 + 预算硬拒（2026-09-20，真注入真观测）

> 方法：通过 EnvironmentFile 覆盖（drop-in `Environment=` 会被 EnvironmentFile 覆盖，已记录）注入故障，观测真实调用、日志与指标；演练后恢复并回归。

## 一、模型主备切换（注入：主模型名改为不存在 `no-such-model-drill`）

| 轮次 | 结果 |
|---|---|
| 1 | 200 / 14.5s（主通道 404 → 重试 → **切备用 deepseek-v4-flash** 回答） |
| 2 | 200 / 8.1s（同上） |
| 3 | 200 / 12.6s（第 3 次失败后 **熔断开启 60s**） |
| 4-5 | 200 / **4.6s、4.5s**（熔断期内"直接使用备用通道"） |

**指标**（`ai_model_calls_total`）：primary error/retry 各 3、primary breaker_open 1、fallback failover 3、breaker_open 2、**fallback/deepseek-v4-flash ok 5**。
**日志**：`主通道失败（no-such-model-drill），切换备用通道` → `熔断开启 60000ms（连续失败 3 次）` → `熔断开启，直接使用备用通道`；另捕获 `主通道流中断（已输出部分内容），不降级重放`（流式安全语义真实生效）。
**判定**：✅ 重试→降级→熔断→冷却探测全链路真实工作；5/5 业务不中断。

## 二、预算硬拒（注入：`MYXHS_BUDGET_DAILY_TOKENS=0`）

| 路径 | 结果 |
|---|---|
| `/api/ai/agent/chat` | **429 / 0.2s**：`今日 AI 用量已用完（0/0 tokens）`；日志 `[Token预算] … 拒绝请求`；指标 `ai_model_budget_total{result="hard_reject"}=1` |
| `/api/ai/chat`（修复前） | **200（未受限）** ← 真缺陷 |
| `/api/ai/chat`（修复后） | **429 / 0.3s** ✓ |

### 发现并修复的真缺陷：普通对话路径绕过预算
- 根因：`ChatController` 未把 `X-User-Id` 写入 Reactor 上下文 → `ModelGateway.streamWithBudget` 因 `userId==null` 直接放行（**既不拒绝、也不计量用量**）。
- 修复：`chat`/`stream` 接收 `X-User-Id` 并 `contextWrite(TokenBudget.USER_ID_KEY)`；预算异常映射 **429**（同步与 SSE error 事件）。
- 验证：预算=0 下 chat → 429/0.3s；恢复 500k 后 chat → 200/7.3s；E2E **10/10**。

## 三、暴露的边界（建议改进）
1. **主备并非多供应商**：primary 与 fallback 共用同一 `base-url`（仅模型名不同）→ **不抗供应商/网络级故障**；ARK 凭据在环境变量里但未接入模型通道。建议把 ARK/第二供应商接为真正 fallback（跨供应商才叫容灾）。
2. 降级延迟：重试预算导致切换耗时 8–15s（首两轮）；可下调 retry/backoff 或用熔断快速通道。
3. 运维注意：systemd drop-in 的 `Environment=` 会被 `EnvironmentFile`（tokens.env/.env.local）覆盖——演练/临时改参要改 EnvironmentFile 并重启。

## 四、恢复与回归
- 已还原 `.env.local`（模型名/预算恢复）并重启；chat 200/7.3s；`e2e.sh` 10/10。
- 产物：`ChatController.java`（预算上下文修复）；本报告。
