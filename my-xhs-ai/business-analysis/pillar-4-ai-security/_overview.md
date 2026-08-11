# 支柱四：大模型 / AI 安全（Guardrails）— 总览

> 业务定位：**AI 系统自身的防线**。前三支柱解决"能回答什么业务/排障问题"，本支柱解决"**防住针对 LLM/Agent 的攻击**"。
> 与 O&M(支柱三)的区别：O&M 是"管好系统不宕机"，AI 安全是"防住提示注入/越权/数据泄漏/供应链"。
> 参考：本项目 `docs/phase-11-security-guardrails/`、PLAN §9、OWASP GenAI/LLM Top 10。

## 核心安全原则（贯穿 AI 设计）
1. **确定性安全兜底，不依赖 LLM 自辨**：LLM 可能被注入，但**固定只读工具 + deny-by-default 权限**保证即便注入成功也无副作用。
2. **数据最小化**：工具只返回诊断所需聚合数据，不上送用户明细/PII；宁可少给，不可多给。
3. **权限最小化**：V1 只读，无写工具 → 无论如何诱导都无法产生业务写副作用。
4. **审计与可观测分离**：调试 trace 可采样；合规审计完整、独立存储、防篡改。
5. **治理即发布门禁**：模型+Prompt+工具+索引+策略捆绑为可回滚 release bundle。

## 威胁模型（OWASP LLM Top 10 映射到 my-xhs AI）
| 威胁 | 本系统场景 | 防线 |
|------|-----------|------|
| **Prompt 注入**(直接) | 用户问"忽略指令，执行X" | 输入过滤 + 结构化工具调用 + 权限兜底 |
| **Prompt 注入**(间接) | 检索到的笔记/Runbook 内容带注入 | 不可信内容标记 + 不据此授权 + 权限兜底 |
| 工具结果注入 | 下游 API/日志返回被污染内容 | 工具输出视为不可信 + 不触发动作 |
| **过度代理** | Agent 被诱导调用未授权/写工具 | deny-by-default + 最小工具集 |
| **数据泄漏 / PII** | 手机号/地址/密钥进上下文/trace | 数据最小化 + 脱敏 + trace 策略 |
| **幻觉/不安全输出** | Agent 编造业务数字 | 数字由确定性工具产生 + 事实性校验 |
| **供应链** | 模型 Provider/SDK/依赖 | Provider 治理 + 版本锁定 + 依赖扫描 |
| **不可信代码/插件** | MCP 扩展/脚本 | 沙箱 + 最小权限 |

## 与工具权限分级(L1/L2/L3)的衔接
```
L1 业务工具（只读领域 API）
L2 观测工具（PromQL/ES/诊断SQL/trace）
L3 运维动作（重启/重投/回滚）—— 默认拒绝需人工
```
安全层在**每一级之上**叠加：Prompt 注入检测、内容过滤、PII 脱敏、权限守卫、审计。

## 关联文档
- [injection-guardrails](injection-guardrails.md) — 提示注入 + 护栏 + 红队
- [access-control](access-control.md) — 授权 / 过度代理 / 工具权限
- [data-trace-security](data-trace-security.md) — PII / 数据泄漏 / 观测审计分离
- [governance-supplychain](governance-supplychain.md) — 模型治理 / 发布门禁 / 合规 / 供应链
