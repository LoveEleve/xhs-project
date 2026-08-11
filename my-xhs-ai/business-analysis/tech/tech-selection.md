# 技术选型矩阵（my-xhs-ai）

> 定位：在进入任何代码前，用**项目硬约束 + 生产就绪度**过滤候选，产出短名单。
> 原则：① 主依据=项目约束 ② 版本须在 D0 重新核验锁定（本表为 2026-08 初判，非最终）③ 最终以"同场景 PoC"数据决策。
> 状态：**先选型矩阵（本文件）→ 再补 JD/行业印证 → 再设计 PoC**。

---

## 1. 硬约束（不可变，先过滤）

| 约束 | 值 | 影响 |
|------|-----|------|
| Java | 17 | 排除需更高 JDK 的库 |
| Spring Boot | **3.2.5** | ⚠️ 关键约束：Spring AI 1.0 GA 需 3.4.x |
| Spring Cloud Alibaba | 2023.0.1.2 | 与 Nacos/Sentinel 集成 |
| Jackson | **2.16.1（Jackson 2）** | MCP 需用 jackson2 模块 |
| Elasticsearch | 8.12.2 | 已有 ES，RAG 可复用 |
| 现有中间件 | Redis/MySQL/RocketMQ/SkyWalking/Nacos/Sentinel | 复用，不新引中间件 |
| 团队语言 | Java（16 服务全是 Java） | AI 服务用 Java，不引 Python 到生产 |

## 2. 决策矩阵（按能力类别）

### 2.1 LLM / Agent 核心框架（本项目最关键决策）
> ⚠️ **2026-08 实测更新**：AgentScope Java 2.0 GA 已于 2026-07 发布（约 1 个月），其内置能力与我们的 Harness 需求高度契合。**从"挑战者"上调为"并列主线候选"**。

| 候选 | 约束兼容(3.2.5/JDK17) | 生产就绪度 | 对需求的贴合 | 初判 |
|------|:---:|------|------|------|
| **LangChain4j Core** | ✅ 框架 Spring 无关，starter 支持 3.2 | Apache-2.0，活跃，12.8k★，生态大 | 通用 LLM 库；Harness/HITL/审批/会话要自己搭 | **主线候选①**（成熟/主流） |
| **AgentScope Java 2.0** | ✅ 需 JDK17+ | Apache-2.0，**v2.0.0 GA 2026-07(新)**，5.0k★ | **极高**：Permission(allow/approve/deny=L3审批)、分布式会话(Redis/MySQL=Run State)、Workspace沙箱、28类事件流(=SSE)、HITL、多Agent | **主线候选②**（贴合度最高，但新） |
| Spring AI | ⚠️ **1.0 GA 需 Boot 3.4.x**，3.2.5 只能用旧版 0.8.x | Spring 官方，1.0 较新 | 中 | 挑战者（需确认/升级 Boot） |

> ⚠️ **关键约束冲突**：Spring AI 1.0 GA 要求 Spring Boot 3.4.x，而整个 my-xhs 是 3.2.5 + BOM 对齐。若选 Spring AI，需评估升级 Boot 对 16 服务 Nacos/Sentinel 的连锁影响——**风险高**。LangChain4j 与 AgentScope 均无此约束。
> **初判**：**LangChain4j（成熟/主流）vs AgentScope Java（贴合度最高/新）** 双主线对决，由 PoC 数据决策；Spring AI 因 Boot 升级风险列为挑战者。

### 2.2 MCP
| 候选 | 约束 | 生产就绪 | 初判 |
|------|:---:|------|------|
| **官方 MCP Java SDK** | ✅ 有 jackson2 模块(mcp-json-jackson2) | MIT，官方 | **主线**（协议不自研） |
| 框架内置 MCP 适配 | 随 LangChain4j/Spring AI | 随主线 | 挑战者 |

### 2.3 Durable Workflow
| 候选 | 约束 | 生产就绪 | 初判 |
|------|:---:|------|------|
| **Temporal Java SDK** | ✅ JDK17 | Apache-2.0，成熟 | 主线候选（长任务） |
| AgentScope persistence | ✅ | 较新 | 挑战者 |
| Restate | ✅ | 较新 | 实验 |
| 本地状态机+MySQL Run Store | ✅ | 自研 | **V1 先用**，触发条件成立才引 Temporal |

### 2.4 RAG / 向量
| 候选 | 约束 | 生产就绪 | 初判 |
|------|:---:|------|------|
| **LangChain4j + 现有 ES** | ✅ 复用 ES 8.12 | 成熟 | 主线（先做容量/质量基线） |
| 独立向量库(pgvector/Milvus) | 需新中间件 | — | 挑战者，先不引 |

### 2.5 模型访问
| 候选 | 约束 | 初判 |
|------|:---:|------|
| OpenAI-compatible client | ✅ | 主线（D0 确认现有 key/网关） |
| LiteLLM | 需 Python 代理 | 多 Provider/配额成立才引 |

### 2.6 LLM 可观测
| 候选 | 初判 |
|------|------|
| **Langfuse(自托管)** | 主线（OTLP/HTTP 集成，避免 SDK 锁定） |
| 自研 Dashboard | 挑战者 |

### 2.7 评测
| 候选 | 初判 |
|------|------|
| **JUnit(确定性) + Promptfoo(HTTP黑盒)** | 主线 |
| DeepEval / RAGAS / Inspect | 限时对比 |

### 2.8 策略 / PII / 推理
| 能力 | 主线 | 条件 |
|------|------|------|
| 策略 | Java deny-by-default allowlist | 复杂后 OPA |
| PII | Java 确定性脱敏 | 中文 PoC 达标后 Presidio |
| 本地推理 | 不作 V1 前置 | 数据主权/规模成立后 vLLM |

---

## 3. 短名单（PoC 候选）

| 角色 | 短名单 |
|------|--------|
| LLM/Agent 核心 | **LangChain4j Core（已定为 V1 主）**；AgentScope Java 2.0（验证替代） |
| MCP | 官方 MCP Java SDK（jackson2） |
| Durable Workflow | V1 本地状态机 + MySQL Run Store（暂不引 Temporal） |
| RAG | LangChain4j + 现有 ES |
| LLM 可观测 | Langfuse（自托管） |
| 评测 | JUnit + Promptfoo |

> ### 选型状态小结（2026-08-10 定稿）
> - **Agent 核心框架 = LangChain4j（V1 默认，已定）**。理由：成熟、招聘主流、无 Boot 3.2.5 冲突、生态大、Harness 可控。
> - AgentScope Java 2.0 保留为**验证替代**：其权限/HITL/会话贴合度高，若后续 PoC 证明显著更优再切，**不阻塞 D1 开工**。
> - 其余技术项全部已定（见上表）。
> - **结论：技术选型已完整定稿，PoC 是开工后的质量验证点，非前置条件。**

## 3.1 行业 / JD 印证（公开源实测，2026-08）
> 大厂 JD 反爬，用 GitHub 官方仓库作为"市场采用/招聘热度"代理（后续可补真实 JD 原文复核）。

| 框架 | 采用度信号 | 印证结论 |
|------|-----------|---------|
| LangChain4j | 12.8k★ / 2.4k fork / 3837 commit / 活跃 | Java AI 主流库，招聘常客，生态大 |
| AgentScope Java | 5.0k★ / 1.1k fork / **v2.0.0 GA 2026-07** | 阿里系，新但增长快，贴合"Agent Harness"岗 |
| Spring AI | Spring 官方 | 招聘常见，但与本项目 Boot 3.2.5 冲突 |

> **印证结论**：市场主流是 LangChain4j（广度）+ AgentScope（深度 Harness）双强，我们的双主线 PoC 判断与市场一致。最终仍以本项目 PoC 数据为准，不因 star 数定生死。

## 4. 待 D0 核验项（锁定前必做）
- [ ] 确认**模型接入现状**（key/网关/Provider）—— D1 卡点
- [ ] LangChain4j 最新版对 Boot 3.2.5 / JDK17 的兼容性报告
- [ ] MCP Java SDK 的 jackson2 模块版本锁定 + conformance
- [ ] Spring AI 是否值得为此升级 Boot（评估 16 服务连锁影响）—— 若风险高，淘汰
- [ ] Langfuse 自托管部署方式（Docker）与 OTel 集成路径

## 5. 后续（选型闭环）
1. 本矩阵 → 短名单（双主线：LangChain4j vs AgentScope）
2. **行业/JD 印证**：已用 GitHub 公开源印证（§3.1）；可补真实 JD 原文复核
3. **同场景 PoC**：LangChain4j vs AgentScope Java，同一"订单异常调查"最小链路，用数据决策（质量/延迟/成本/恢复 + 对 HITL/会话/权限的工程实现成本）
4. 产出框架 ADR（采用/拒绝/替代/退出条件）
