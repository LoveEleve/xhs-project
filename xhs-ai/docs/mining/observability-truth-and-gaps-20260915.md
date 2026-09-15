# xhs-ai 可观测真相与缺口清单（2026-09-15）

## 1. OTel→Langfuse 未落地（设计态）
- 文档承诺：README 技术选型、PRD AC-6.1、D03 设计双平面 + capture 三模式 + failsafe
- 代码事实：`langfuse` 全仓 0 命中；`opentelemetry` 仅出现在 dependencyManagement 与"给 ES MCP 子进程关闭 OTel"（方向相反）；AgentScope 传递依赖里有 OtelTracingMiddleware/TracerRegistry 但未注册未接线；reviews/09 自认"未做，推迟"
- **实际观测**：Micrometer→Prometheus（17 个 ai_* 指标）+ traceId（MDC/审计/消息表）+ JSON 日志→ELK + 审计哈希链 + token 计量（无成本指标，成本靠脚本估算）+ 7 条告警

## 2. 运行时/部署配置
- systemd：Restart=always/RestartSec=10、StartLimit 300s×10、TimeoutStopSec=45、KillMode=mixed（主进程 SIGTERM 后杀 MCP 子进程组）、**无 MemoryMax**
- application.yml：19020、graceful、Hikari 10/2/3s、MVC async 5min、actuator health/info/prometheus（details never）
- logback：JSON 100MB 滚动 ×7 天、总 2GB、MDC 含 traceId、APP_NAME=xhs-ai

## 3. 旧评测资产（不可挪用）
- eval-gate 7/7（100%/100%/0% 幻觉，阈值 60/40/10）、nightly-100：通过 100%/完成 96%/幻觉 0%/发散 35%/均 23.3s/10,324 tokens
- 归属：**my-xhs-ai-app（旧模块，已下线，零参考）**，模型是 mimo-v2.5-pro ≠ 现用 deepseek/qwen3.8；报告正文与表格数字自相矛盾（99%/98%/1% vs 100/96/0）→ 只能作"历史对照"，xhs-ai 引用自己的证据

## 4. 缺口清单（承诺 vs 代码，面试主动认）
| # | 承诺 | 证据 |
|---|------|------|
| 1 | OTel→Langfuse | 0 命中（见上） |
| 2 | capture 三模式 | 列存在，代码 0 读写 |
| 3 | DLP/PII 脱敏 | 仅密钥正则，无手机/邮箱/身份证 |
| 4 | 出网审计 categories/redaction_count | 列存在未写 |
| 5 | 成本指标+费率表 | 仅 token 计数，无成本计算 |
| 6 | 告警：错误率>5%/首字>3s/成本 80% | 无这三类 |
| 7 | Prompt 版本化 | 0 命中 |
| 8 | 契约测试/CI 分层 | CI 仅 mvn test |
| 9 | 多租户 | 0 命中（仅 user_id 隔离） |
| 10 | 向量/混合检索 | 主动止损（非欠债） |
| 11 | 沙箱 | 有意不做（ADR-6，v2 触发） |
| 12 | jdtls/JGit | v1.1 触发项，现为文件扫描 |
| 13 | 长期记忆/Skill/subagent | 0 接线（扩展 jar 在） |
| 14 | LLM-judge kappa | 0 命中 |
| — | LangGraph | 文档未承诺（实现为 AgentScope ReAct） |
