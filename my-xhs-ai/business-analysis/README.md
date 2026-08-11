# my-xhs 业务分析与规划（AI 项目前置）

> 目的：让 AI 项目**真正读懂业务与系统、学会概念、确定技术**，再开始开发。
> 原则：不盲从旧规划；先懂业务，再评/改规划；交付 + 运维 + 安全 + 学习四线并进。
> ⚠️ 本目录**全是文档/规划/审计**，无代码。代码从 D1（`../08-execution/` 或 `../../my-xhs-ai/` 工程）开始。

## 目录结构

```
business-analysis/
├── README.md              # 本索引 + 阅读顺序
├── 01-overview/           # 概览与规划判断
│   ├── business-reality.md    # 全平台业务概览
│   └── plan-review.md         # 对旧 PLAN(v5) 的批判性再判断（三处偏科）
├── 02-plan/               # 规划 + 学习
│   ├── PLAN-v6.md             # ★ 整合规划（业务+O&M+安全三能力面）
│   ├── learning-path.md       # 学习路线（零基础 L0-L7）
│   └── concepts-glossary.md   # 概念词典（ReAct/A2A/SDD/TDD/Spec/MCP/RAG…）
├── 03-pillars/            # 四大支柱（详见各 _overview）
│   ├── pillar-1-content/      # 内容与社交（业务）
│   ├── pillar-2-commerce/     # 电商交易（业务）
│   ├── pillar-3-ops/          # 技术运维与排障（O&M）
│   └── pillar-4-ai-security/  # 大模型/AI 安全（Guardrails，跨切）
├── tech/                  # 技术选型 + 详细架构
│   ├── tech-selection.md      # 选型矩阵 + 行业印证 + 短名单（LangChain4j 主）
│   └── architecture-design.md # ★ 详细架构设计(DAD)：组件/时序/数据模型/接口/韧性/待定
├── 05-decisions/          # ADR 决策记录（随开发增长）
│   └── README.md + ADR-001~004
├── 06-d0/                 # D0 阶段工程产物
│   ├── D0-audit.md            # 数据/观测缺口审计（12项）
│   └── D0-tech-verification.md # 技术核验（模型接入已打通）
├── 07-conventions/        # 全局约定
│   ├── 00-conventions.md      # 口径/时区/鉴权/权限分级/数据边界
│   └── 01-operation-discipline.md # ★ 操作纪律卡（盘问闸/小步review/事实vs决策/标记）
├── 08-execution/          # 后续 D1-D7 执行产物（随增，按阶段建夹）
├── 09-reference/          # 参考材料（行业印证/论文/资料）
│   └── jd/                    # ★ 你提供的 Java AI/Agent 岗位 JD（按公司/来源命名保存）
└── _legacy/               # 旧版按服务划分文档（归档）
```

> ⚠️ `pillar-*` 命名保留（自解释），未改名以减 churn；各阶段执行产物后续按需在 `08-execution/` 下建 `d1/ d2/ …`。

## 阅读顺序建议
1. `07-conventions/00-conventions.md` —— 统一口径与可信边界
2. `01-overview/business-reality.md` —— 业务全景
3. 两大业务支柱 + O&M 支柱的 `_overview.md` —— 三能力面全景
4. `02-plan/PLAN-v6.md` —— 整合规划
5. `05-decisions/` —— 技术决策（ADR）
6. 学习：`02-plan/concepts-glossary.md`（先懂名词）→ `learning-path.md`（边做边学）
7. `01-overview/plan-review.md` —— 对旧规划的判断（理解取舍背景）

## 核心结论（一句话版）
- my-xhs = **内容+社交+电商**双业务支柱 + **高复杂度分布式系统**。
- AI 服务 = **业务诊断(A) + O&M排障(B) + AI安全(C)** 三能力面，缺一不可。
- **技术已定**：Agent 核心 = LangChain4j（主）；模型 = 火山方舟 deepseek-v4-flash（已打通）；MCP 官方 SDK jackson2；V1 本地状态机。
- **硬缺口**（D0 门禁）：支付失败码、published_at、漏斗/曝光、mysql/redis exporter、慢查询管道、DLQ。
- **安全基调**：固定只读工具 + deny-by-default + L1/L2/L3 分级；V1 无写工具。
- **建设者无 AI 基础**：从概念词典 + 学习路线 L0 起步。

## 面向 AI 消费者的统一模板
```
## 业务/排障问题（能回答什么）
## 口径与定义（怎么算/单位/时区）
## 数据来源与就绪度（能否答"为什么"）
## 关键不变量 / 可信边界
## 关联诊断（跨支柱联动）
```
