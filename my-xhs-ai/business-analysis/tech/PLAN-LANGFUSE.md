# Langfuse 接入深度规划（D6 可观测性）

> 2026-08-19 | 目标：Agent 运行 trace 可视化，面试时能现场展示

---

## 一、为什么接 Langfuse

| 理由 | 说明 |
|------|------|
| 面试可视化 | "我能现场展示 trace" 比讲代码强 100 倍 |
| 调试必需 | 当前 Agent 失败只能看日志，无法回溯每步决策 |
| 评测增强 | Trace + 评测报告联动，定位幻觉 case 的具体步骤 |
| D6 交付物 | PLAN.md 要求"一张 Langfuse trace + 一套 Grafana Dashboard" |

---

## 二、技术方案

### 2.1 集成方式：Micrometer Tracing Bridge + OTLP Exporter

Langfuse 没有原生 Java SDK（`langfuse-java:0.2.0` 只是 REST API 客户端，不支持 trace）。
推荐方式是 **OpenTelemetry OTLP 导出到 Langfuse**。

项目已有基础：
- ✅ `spring-boot-starter-actuator`
- ✅ `micrometer-registry-prometheus`
- ✅ `HarnessEvent` 回调系统（SSE 事件）
- ❌ 没有 OTel SDK（需新增）

### 2.2 架构

```
AgentHarness
  ├─ run() entry → OTel Span: agent.run (trace root)
  ├─ callModel() → OTel Span: agent.think (generation)
  ├─ callTool()  → OTel Span: agent.tool.<name>
  ├─ answer()    → OTel Span: agent.answer
  └─ events      → HarnessEvent → SSE 推送（已有）
                         ↓
                  OTel SDK (micrometer-tracing-bridge-otel)
                         ↓
                  OTLP HTTP Exporter
                         ↓
                  Langfuse Cloud (https://cloud.langfuse.com/api/public/otel)
```

### 2.3 与 SkyWalking 的关系

| 维度 | SkyWalking | OTel → Langfuse |
|------|-----------|-----------------|
| 协议 | gRPC（javaagent） | HTTP/protobuf（SDK） |
| 后端 | OAP 21.130.247.89:11800 | Langfuse Cloud |
| 范围 | 全微服务（基础设施 trace） | 仅 AI App（LLM/Agent trace） |
| 采集方式 | 自动（javaagent） | 手动（代码埋点） |

**无冲突**：两套独立的 trace 系统，互不影响。

---

## 三、实施步骤

### Step 1：添加 Maven 依赖

```xml
<!-- my-xhs-ai-app/pom.xml -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

### Step 2：配置 application.yml

```yaml
management:
  tracing:
    sampling:
      probability: 1.0  # 开发环境 100% 采样

otel:
  exporter:
    otlp:
      endpoint: ${LANGFUSE_OTEL_ENDPOINT:https://cloud.langfuse.com/api/public/otel}
      headers: ${LANGFUSE_OTEL_HEADERS:}
```

### Step 3：创建 LangfuseTracingListener

监听 `HarnessEvent`，为每个事件创建 OTel span：

| HarnessEvent 类型 | OTel Span 名称 | Langfuse 属性 |
|-------------------|---------------|--------------|
| START | `agent.run` | `langfuse.trace.name`, `langfuse.user.id` |
| THINK | `agent.think` | `gen_ai.usage.*`, `langfuse.observation.type=generation` |
| TOOL | `agent.tool.<name>` | `gen_ai.tool.name`, `langfuse.observation.input/output` |
| ANSWER | `agent.answer` | `langfuse.observation.output` |
| FAILED | `agent.failed` | `langfuse.observation.level=ERROR` |
| POLICY_DENIED | `agent.policy_denied` | `langfuse.observation.level=WARNING` |

### Step 4：注入 AgentHarness

`RunManager` 已经通过 `HarnessEvent listener` 回调事件。新增 `LangfuseTracingListener` 作为额外的 listener 传入。

### Step 5：Langfuse 配置

需要注册 Langfuse 账号（免费版够用）：
- 获取 Public Key（`pk-lf-...`）和 Secret Key（`sk-lf-...`）
- 生成 Basic Auth header：`base64(pk-lf-...:sk-lf-...)`
- 设置环境变量 `LANGFUSE_OTEL_HEADERS`

### Step 6：验证

1. 启动 AI App
2. 提交一个诊断任务
3. 在 Langfuse Dashboard 看到 trace
4. 验证每个 step 的 span 嵌套正确

---

## 四、Langfuse 数据模型映射

| Langfuse 概念 | AgentHarness 对应 |
|---------------|------------------|
| Trace | 一次 `run()` 调用（runId） |
| Span | 一个步骤（THINK/TOOL/ANSWER） |
| Generation | `callModel()` 调用（含 model name, token usage） |
| Event | `HarnessEvent`（START/TOOL/ANSWER/FAILED） |
| User | `userId`（从 X-User-Id header 传入） |
| Session | `convId`（会话 ID） |
| Metadata | `runId`, `profile`, `budget` |

---

## 五、产出清单

| 产出 | 说明 |
|------|------|
| `LangfuseTracingListener.java` | OTel span 创建逻辑 |
| `OtelConfig.java` | OTel SDK 配置（Tracer bean） |
| `application.yml` 更新 | tracing + otel 配置 |
| `pom.xml` 更新 | 2 个新依赖 |
| Langfuse trace 截图 | 面试用 |
| 测试验证 | 1 个诊断任务的完整 trace |

---

## 六、时间线

| 天 | 任务 | 产出 | 状态 |
|----|------|------|------|
| Day 1 | 依赖 + 配置 + LangfuseTracingListener | 代码编译通过 | ✅ 已完成 |
| Day 1 | OtelConfig + RunManager 注入 | 复合 listener 就绪 | ✅ 已完成 |
| Day 2 | 注册 Langfuse + 配置 API Key | 连通性验证 | ❌ 待用户操作 |
| Day 2 | 验证 trace + 截图 + 面试话术更新 | 可面试展示 | ❌ 待验证 |

---

## 七、风险与降级

| 风险 | 降级方案 |
|------|---------|
| Langfuse Cloud 不可达 | 用本地 Jaeger/Zipkin 替代（OTel exporter 改目标） |
| OTel 依赖冲突 | 用 `opentelemetry-bom` 管理版本 |
| 采样率影响性能 | 生产环境改为 10% 采样 |
| SkyWalking 冲突 | 已确认无冲突（独立协议和后端） |

---

## 八、面试话术

> 我在 Agent 可观测性上做了两层：基础设施层用 SkyWalking 做全链路 trace，Agent 层用 OTel + Langfuse 做 LLM 调用、工具执行、证据链的可视化。每次诊断任务在 Langfuse 上能看到完整的 think → tool → observe → answer 链路，包括每步的 token 消耗、工具输入输出、决策理由。这让我能快速定位幻觉 case 的具体步骤，也让评测报告有 trace 支撑。
