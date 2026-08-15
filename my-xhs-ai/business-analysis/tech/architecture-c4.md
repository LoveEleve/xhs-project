# my-xhs-ai C4 架构图

> 日期：2026-08-15 | 配套：HANDOFF-AI-v3.md、production-roadmap.md §14（面试证据映射）
> 说明：V1 单 Agent（受限多步归因），无多智能体；安全横切（认证/审计/护栏/评测门禁）。

## 1. 容器图（Container Diagram）

```mermaid
flowchart TB
    U["👤 用户（运营 L1 / 技术 L2）"]

    subgraph WEB["浏览器"]
        FE["frontend（React+antd）<br/>AI 诊断台薄壳：SSE 消费 / 证据链可点 / 取消"]
    end

    subgraph PLATFORM["平台（同一内网）"]
        GW["Gateway :19000<br/>/api/ai/** + /api/runs/**（M8-3 待接入）"]
        APP["my-xhs-ai-app :19020<br/>意图路由 · AgentHarness · RunManager<br/>Run Store · 评测门禁 · 指标"]
        MCP["my-xhs-ai-mcp :19021<br/>14 个只读工具（MCP Streamable HTTP）<br/>认证 MCP_API_KEY + 审计"]
    end

    subgraph INFRA["真实基础设施（远端 21.130.247.89）"]
        MYSQL["MySQL 16 分片 t_order_0..3<br/>+ 事件表 ×4 + my_xhs_ai 库"]
        PROM["Prometheus :19090<br/>+ textfile 管道（MQ 指标）<br/>+ mysqld-exporter"]
        ES["ES :19200<br/>RAG 知识（BM25+dense）"]
        SKY["SkyWalking :12800<br/>跨服务链路（备用）"]
    end

    LLM["OpenCode Go<br/>deepseek-v4-flash（成本红线单模型）"]
    EMB["火山 Agent Plan<br/>doubao-embedding-vision-large"]

    U -->|浏览器| FE
    FE -->|/api 或 /ai-api| GW
    GW -.待接入.- APP
    FE -.dev 直连.- APP
    APP -->|MCP 协议| MCP
    APP -->|SSE 事件流| FE
    APP -->|OpenAI 兼容| LLM
    APP -->|dense/语义路由 embedding| EMB
    APP -->|第二数据源写账号| MYSQL
    MCP -->|只读账号 SELECT| MYSQL
    MCP -->|HTTP 查询| PROM
    MCP -->|日志文件白名单读| LOGS
    MCP -->|知识检索| ES
    MCP -.trace 查询.- SKY
```

## 2. 组件图（Component Diagram，app 内部）

```mermaid
flowchart TB
    subgraph API["API 层"]
        RC["RunController<br/>POST /api/runs · GET/{id} · DELETE · /stream"]
        QC["AiQueryController<br/>/api/ai/query（确定性指标/问候/超范围直答）"]
        RC2["AgentRunController（D4 同步保留）"]
        RAG["RagController"]
    end

    subgraph ROUTE["意图路由（三阶）"]
        IR["IntentRouter<br/>L0 规则 → L1 语义 → L2 LLM → L3 默认"]
        SC["SemanticIntentClassifier<br/>49 条种子 few-shot + cosine"]
        EC["EmbeddingClient（共享 RAG）"]
    end

    subgraph CORE["Agent 引擎"]
        AH["AgentHarness 状态机<br/>THINK→VALIDATE→TOOL→LOOPCHECK→ANSWER"]
        PG["PolicyGuard<br/>deny-by-default allowlist + 参数白名单"]
        PG2["PolicyGuard L3 → DECLINE 零证据豁免"]
        TR["ToolResultRegistry<br/>存在性校验（防编造）"]
        EC2["EvidenceChain（证据链+反证+不确定性）"]
    end

    subgraph DURABLE["Durable & 可观测"]
        RM["RunManager<br/>事件缓冲 · 单消费者 · TTL · 崩溃恢复"]
        RS["JdbcRunStore<br/>ai_run/ai_step checkpoint + 心跳"]
        MET["RunMetrics<br/>runs/tokens/cost/duration"]
    end

    subgraph TOOLS["工具面（经 MCP 14 个）"]
        T1["业务：order/payment/content/funnel/事件 ×8"]
        T2["观测：http_errors/latency/mq/mysql ×6"]
        T3["log.search（M9-1 受控日志检索）"]
    end

    subgraph EVAL["评测与安全（横切）"]
        E1["EvalGate PR 门禁（幻觉≤10% 通过≥60%）"]
        E2["红队用例（注入/越权/PII）"]
    end

    API --> ROUTE
    ROUTE --> EC
    API --> CORE
    CORE --> PG
    CORE --> TR
    CORE --> EC2
    CORE --> DURABLE
    CORE --> TOOLS
    ROUTE -.直答.-> API
    EVAL -.门禁.- CORE
```

## 3. 关键请求流（诊断 run）

```mermaid
sequenceDiagram
    participant U as 用户
    participant FE as 前端薄壳
    participant RM as RunManager
    participant AH as AgentHarness
    participant MCP as my-xhs-ai-mcp
    participant LLM as deepseek-v4-flash

    U->>FE: "为什么订单量下降了？"
    FE->>RM: POST /api/runs（立即返回 runId）
    RM->>AH: 后台执行（意图预检非问候/超范围）
    loop 多步归因（≤15 步，预算三重封顶）
        AH->>LLM: THINK（JSON 决策）
        AH->>AH: VALIDATE（PolicyGuard allowlist+参数白名单）
        AH->>MCP: TOOL（只读工具）
        MCP-->>AH: 结果登记证据（registry）
    end
    AH-->>RM: COMPLETED/PARTIAL/FAILED（证据链+反证+不确定性）
    RM-->>FE: SSE 事件流（RUN_STARTED→THINK/TOOL→终态）
    FE-->>U: 时间线 + 可点证据 + 结论
```
