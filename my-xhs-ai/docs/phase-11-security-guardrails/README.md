# Phase 11: AI 安全 + Guardrails

## 前置依赖

- **Phase 5 (Agent架构)**：安全的对象是 Agent 系统
- **Phase 10 (AgentOps)**：AgentOps 第四层是护栏

## 为什么第十一

EU AI Act 2026 年 8 月生效——安全不再可选。Phase 2 做了 Prompt 注入防御（单层），Phase 11 做**全栈安全防线**：输入安全+输出安全+数据安全+工具安全+沙箱执行。

## 与 my-xhs 的关联

| 安全层 | my-xhs 已有 | 本 Phase 新增 | 保护什么 |
|--------|-----------|-------------|---------|
| 输入安全 | Gateway HmacSignatureFilter | Prompt 注入检测+越狱检测 | 防止恶意 Prompt 操作 Agent |
| 输出安全 | 无 | 内容安全过滤+幻觉检测+事实性校验 | 防止 Agent 输出有害/错误信息 |
| 数据安全 | Redis 密码/MySQL 只读账号 | PII 脱敏（手机号/身份证/银行卡）+审计日志 | 防止敏感数据进入 LLM 上下文 |
| 工具安全 | 无 | 权限模型（6 维）+SQL 注入防护+沙箱执行 | 防止 Agent 误操作/恶意操作 |
| 审计 | SkyWalking | Event Log+防篡改哈希链 | 可追溯+合规 |

## 学什么

| 模块 | 内容 |
|------|------|
| OWASP LLM Top 10 | 10 类威胁：Prompt 注入/不安全输出/供应链/过度代理/信息泄露等 |
| Prompt 注入攻防 | 直接注入（"Ignore all..."）/间接注入（检索文档注入）/越狱（Jailbreak）+三层防御 |
| Guardrails 工具 | GuardrailsAI（输出验证）/NeMo（对话护栏）/Lakera（实时检测）/Invariant（MCP 级） |
| PII 脱敏 | 手机号/身份证/银行卡识别（正则+NER）+脱敏策略+审计 |
| 工具安全 | 6 维权限模型（用户/角色/工具/数据/时段/审批）+SQL 注入防护+只读兜底 |
| 沙箱执行 | Docker 容器隔离不可信代码 |

## 生产工程问题

| 问题 | 本 Phase 如何解决 | 验证方式 |
|------|-----------------|---------|
| **幻觉** | 输出事实性校验：LLM 输出中的数字/日期/实体与 Tool 返回结果对比 | 50 条评测中幻觉率 < 5% |
| **SQL 注入** | 参数化查询（Phase 6）+本 Phase 输入校验+只读账号 | 10 种注入攻击全部拦截 |
| **Prompt 注入** | 输入过滤+结构化约束+权限最小化 三层防御 | Phase 2 防御层基础上增加 Guardrails 层 |
| **PII 泄露** | MCP Server 返回结果中识别+脱敏→不进入 LLM 上下文 | 手机号 → `138****1234` |
| **过度代理** | 工具权限模型：写操作→审批或拒绝 | 非只读操作被拦截率 100% |

## 文档清单（6 篇）

| # | 文档 | 内容要点 |
|---|------|---------|
| 01 | owasp-llm-top10.md | 10 类威胁详解+每类 my-xhs 场景+防御策略 |
| 02 | prompt-injection.md | 三类注入攻击（直接/间接/越狱）+检测方法+三层防御 |
| 03 | guardrails-tools.md | 四工具对比：GuardrailsAI/Lakera/NeMo/Invariant 选型分析 |
| 04 | pii-masking.md | 手机号/身份证/银行卡 识别+脱敏策略+审计日志 |
| 05 | tool-security.md | 6 维权限模型+SQL 注入防护+沙箱 |
| 06 | audit-logging.md | Event Log 结构+ES 存储+防篡改哈希链 |

## 代码结构

```
src/main/java/com/myxhs/ai/security/
├── PromptInjectionDetector.java  # 注入检测（多模式匹配+置信度评分）
├── ContentSafetyFilter.java      # 输出内容安全过滤
├── HallucinationChecker.java     # 幻觉检测（事实性校验）
├── PiiMasker.java                # PII 识别+脱敏+审计
├── GuardrailsFilter.java         # 安全过滤链（输入→输出→数据）
├── ToolPermissionGuard.java      # 6 维工具权限守卫
├── AuditLogger.java              # 审计日志（ES+防篡改哈希链）
└── SandboxExecutor.java          # Docker 沙箱执行
```

## 验证标准

1. 三类注入攻击（直接/间接/越狱）100% 拦截
2. PII 脱敏：手机号→`138****1234`，身份证前 6 后 4 保留，不进入 LLM 上下文
3. 工具权限：非只读操作被拒绝+审计记录
4. 幻觉检测：50 条评测中幻觉率 < 5%
5. 审计日志：每次 Agent 操作可追溯（谁/何时/做了什么/结果/权限判定）

## 对后续的影响

- **Phase 13 (治理)**：安全防线是 EU AI Act 合规的基础
