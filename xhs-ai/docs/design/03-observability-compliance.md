# D03 · 观测与合规设计（G15/G1 / ADR-13）

> 目标：对话/工具/成本全链路可观测；敏感数据出网可控、可审计、可举证

## 1. 双平面观测

| 平面 | 内容 | 去向 | 数据许可 |
|------|------|------|---------|
| **业务 trace** | 用户消息、模型调用、工具调用、审批、回答 | OTel → Langfuse（云或自托管） | 受 capture mode 控制 |
| **运维 OTLP** | 仅 ID/耗时/状态/错误码/token/成本（**content-free**） | Prometheus/VM + 告警 | 无内容，恒可发 |

- 关联键：`traceId`（全链路）、`sessionId`、`approvalId`、`msgId` — 业务与运维两平面可 join
- 每次模型调用一个 generation span；每个工具调用一个 tool span；审批一个 HITL span

## 2. 内容捕获模式（默认 sanitized）

| 模式 | 行为 | 使用场景 |
|------|------|---------|
| `metadata` | 内容字段替换为 `{omitted:true,type,chars}`；ID/角色/工具名/usage/耗时恒采集 | 高敏/共享 Langfuse |
| `sanitized`（默认） | **先脱敏再截断**（密钥/PII 正则），截断上限可配 | 日常 |
| `full` | 原始内容仅截断 | 仅内网私有模型 + 显式审批 |

- `capture_mode` 写入每条 trace 与 `ai_audit`；模式切换需审计
- 脱敏规则（版本化）：
  - 凭据：`sk-…`/`ark-…`/`Bearer …`/JWT（三段 base64url）/PEM 私钥/`password=`/`token=`
  - PII：手机号、邮箱、身份证、详细地址（按 xhs 字段名白名单：`addressSnapshot`/`phone`/`receiverPhone`）
  - 平台特有：`X-Internal-Call`、`ADMIN_TOKEN`、`JWT_SECRET` 值
- 误报控制：规则命中计数；抽样人工复核；规则变更走 PR + 回归用例

## 3. 遥测健壮性（failsafe）

- 遥测调用全部包 failsafe：**任何异常不得阻塞/失败 Agent 回合**
- TraceState 上限（每 turn）**256 + LRU 驱逐**，防"永不结束的 turn"泄漏
- Langfuse key 前缀校验（`pk-lf-`/`sk-lf-`），模板残留立即告警
- 会话结束/异常：关闭未完成 span 并 flush（防悬挂 trace）

## 4. 用量与成本

- usage 字段：`input / output / cache_read / cache_write / reasoning` tokens
- 成本：费率表（按 provider/model，版本化）；成本=Σ(tokens×rate)
- 指标：`ai_tokens_total{user,session,model,kind}`、`ai_cost_total`、`ai_tool_calls_total{name,status}`、`ai_approval_pending`、`ai_llm_latency`
- 预算：会话 100k（80% 告警），超限降级 flash（D01）

## 5. 审计与留存

- `ai_audit` 只追加；记录 actor/trace/action/target/params(脱敏)/content_categories/redaction_count/result
- 留存：审计 ≥180 天；trace 云端按 Langfuse 项目策略；运维指标按 VM 30d
- 出网审计：每次外发记录"目标 provider + 模式 + 内容类别 + 脱敏计数"

## 6. Langfuse 部署选项

| 选项 | 说明 |
|------|------|
| 云版 | `LANGFUSE_OTEL_ENDPOINT=https://cloud.langfuse.com/api/public/otel` + keys |
| 自托管 | docker-compose 增 langfuse（avoid 出网；推荐生产） |

采样率（`sample_rate`）可配；错误 trace 100% 保留。

## 7. 告警

- LLM/embedding 错误率 >5%（5min）→ 告警
- P95 首字 >3s 或端到端 >20s → 告警
- 会话成本超预算 80% / 单日总成本超阈值 → 告警
- 脱敏异常（规则未命中疑似敏感模式）→ 告警

## 8. 测试

- 脱敏单测：规则覆盖（正例/负例/边界）与误报样例
- capture 模式：三种模式产物断言（metadata 无内容、sanitized 有脱敏、full 原始）
- failsafe：Langfuse 不可达时对话正常完成；TraceState 驱逐生效
- 成本：usage→cost 计算校准；预算降级触发
- 审计：出网计数与内容类别正确；不可修改（仅插入）
