# D2 状态（MCP 工具层）

> 版本：2026-08-10 | 状态：**核心交付完成（server+client+契约测试），剩余项见 §六**
> 定位：D2「可信工具层与 MCP」进度记录。

---

## 一、已完成

### 1. 共享工具模块 `my-xhs-ai-tools` ✅
- 3 个真实指标工具（order/payment/content）+ `MetricTimeWindow` 移入（纯类）；`my-xhs-ai-app` 依赖并 @Bean 装配；测试随迁。

### 2. MCP SDK 解析（阻塞解除）✅
- **根因**：坐标 group 写错——正确是 **`io.modelcontextprotocol.sdk`**。
- **版本选择**：core 2.0.0 是 GA，但 Spring 集成模块（webmvc/webflux/servlet/http-client）仅到 0.18.3 → 统一 **0.18.3 线**（含安全修复 GHSA-hv2w-8mjj-jw22）。
- 依赖：`mcp-core` + `mcp-spring-webmvc` + `mcp-json-jackson2`（0.18.3，Jackson2 与项目 2.16.1 对齐，dependency:tree 验证无冲突）。

### 3. `my-xhs-ai-mcp` MCP 服务 ✅
- Spring Boot 3.2.5 + Streamable HTTP（`WebMvcStreamableServerTransportProvider`，端点 `/mcp`）。
- 注册 3 个真实只读工具（@Tool → MCP Tool，含口径描述 + window schema）。
- **认证**：`McpAuthFilter`（`MCP_API_KEY` env；设了必须带 `Authorization: Bearer`，否则 401）。
- **审计**：callHandler 记 `[mcp-audit] tool/window/costMs`。
- 实测：initialize / tools/list（3 工具）/ tools/call（真实 61）全通。

### 4. MCP client 桥接（my-xhs-ai-app）✅
- **`McpToolBridge`**：JDK HttpClient + Jackson 按 MCP Streamable HTTP 协议实现薄 client（initialize → Mcp-Session-Id → tools/call），暴露 3 个 @Tool。
- **`/api/ai/mcp/check`** 全链路入口（try-catch → error JSON）。
- 会话失效自动重连（失败重置 sessionId 重试一次）；SSE 解析稳健（汇总 data: 行）。
- **全链路实测**：app → 桥 → mcp server → 真库 → **61**，两端日志齐备。
- ⚠️ **为何弃用 SDK client-jdk-http-client**：0.18.3 该 artifact 是 15MB fat jar（内嵌 1188 个未 relocate 的新版 jackson，与项目 2.16.1 冲突）→ 薄协议客户端自实现。

### 5. MCP 契约 + 认证测试 ✅
- **`McpContractTest`**（my-xhs-ai-mcp，@SpringBootTest + H2 + MockMvc，3 用例全绿）：
  - initialize → serverInfo + tools 能力
  - tools/list → 3 工具 + window schema
  - tools/call → 口径正确 value=3（H2 fixture，排除已删/窗外）
- **`McpAuthTest`**（真实 HTTP RANDOM_PORT，设 MCP_API_KEY，3 用例全绿）：
  - 未带 token → **401**；错 token → **401**；对 token → **200**（Gate「非授权调用 100% 拒绝」）
- 全量测试：**41/41**（tools 13 + app 22 + mcp 6）。

---

## 二、踩坑记录（写码不踩）
| 坑 | 解决 |
|----|------|
| `io.modelcontextprotocol`（无 sdk）坐标 → 解析失败 | 正确 group `io.modelcontextprotocol.sdk` |
| Maven Central IP 403 + 各镜像无此 group | 阿里云镜像实际有（group 写对即可）|
| `McpJsonMapper` bean 缺失 | 手动 `@Bean JacksonMcpJsonMapper(ObjectMapper)` |
| 工具 bean 缺失 | 复用 app 的 `MetricToolsConfig` 模式 |
| /mcp 404 | transport 的 `getRouterFunction()` 暴露为 `RouterFunction` bean |
| Accept 头校验失败 | 客户端必须带 `Accept: application/json, text/event-stream` |
| tools/call 失败 | 需带 `Mcp-Session-Id`（initialize 响应头）|
| Streamable HTTP 异步响应 202 + SSE | 解析 `data:` 行（桥与测试同法）|
| SDK client-jdk-http-client 是 fat jar（内嵌 jackson）| 弃用，薄协议客户端自实现 |

---

## 三、深度 Review 记录（四轮，均已闭环）
### 第零轮：契约测试自查（2026-08-10）- **发现**：初版 McpContractTest 第 4 用例「未授权调用_401」是**空壳**（只重复 initialize，注释说"由单测/手动覆盖"）——无实际断言。
- **修复**：删空壳 → 新增 **`McpAuthTest`**（真实 HTTP + 设 MCP_API_KEY）：无 token 401 / 错 token 401 / 对 token 200，3 用例全绿。
- 教训：**"用例数全绿"不等于"覆盖到位"——空壳用例必须清零**。

### 第三轮：工具访问全面切 MCP（D2 收尾，2026-08-10）

#### 实现
- `MetricToolAccess` 接口（3 个指标方法）+ 两实现：`McpToolBridge`（MCP 路径）/ `DirectMetricToolAccess`（直连，测试/降级）。
- 配置 `myxhs.ai.tools.mode=mcp`（默认）；AgentConfig + AiQueryController 走接口。
- 全量 40/40 绿；mcp 模式全链路实测（固定查询 61 + agent 归因走 MCP 工具）。

#### 🔴 深度 Review 实锤（本轮最重要发现）：免费模型工具调用不可靠（会虚构）
- **现象**：同一天同一类查询，两种模式表现不一致——
  - **mcp 模式 + server 在线**：agent **真调工具**（mcp server 审计记录 12 次），回答"订单量未下降反升"（证据驱动）✅
  - **direct 模式**：agent **虚构工具调用**——声称"工具返回 status=error"，但工具**零日志**（从未执行）；另一轮还虚构过 12000/15000/8,000,000 等假数字与错误日期
- **结论**：工具注册/链路本身**没问题**（jar 内 @Tool 注解已验证 + mcp 模式真实调用）；问题在 **deepseek-v4-flash-free 工具调用行为不稳定**——偶尔真调、偶尔虚构"调用了工具"。
- **影响（红线）**：违反"数字必须来自工具结果，不得编造"——模型可能虚构"工具返回了 X"。
- **处置（分层）**：
  1. **D4 Harness 必做**：**工具结果存在性校验**——模型声称用了工具但 Harness 无对应步骤记录 → 拒绝/标记该结论（确定性兜底，不靠模型自觉）
  2. **模型选型**：免费档工具调用可靠性不足 → 关键路径/生产用付费档或更强模型（量化对比）
  3. 系统提示已含"数字必须来自工具结果"，但**不够**——需 Harness 层强制（提示词是软约束）

### 第一轮：MCP server（修复 3 项）
| 项 | 修复 | 实测 |
|----|------|------|
| `mcpSyncServer` 未使用参数 `port`（死代码）| 移除 | 编译通过 |
| **/mcp 无认证**（Gate「非授权 100% 拒绝」不满足）| `McpAuthFilter`（MCP_API_KEY env）| 无/错 token 401，对 token 200 ✅ |
| **/mcp 无审计**（Gate「并审计」）| `[mcp-audit] tool/window/costMs` | 日志 ✅ |

### 第二轮：MCP client 桥（修复 3 项）
| 项 | 修复 | 实测 |
|----|------|------|
| 会话失效无重连（MCP 重启后 sessionId 陈旧）| 失败重置 + 重试一次 | 重新 init ✅ |
| check 端点无 try-catch（MCP 挂 → 500）| 包 try-catch → error JSON | 停 mcp → error JSON 非 500 ✅ |
| SSE 解析脆弱（只取首个 data: 行）| 汇总所有 data: 行 | 全链路 61 ✅ |

---

## 四、遗留 / 下一步（如实）
| # | 项 | 归属 |
|:--:|----|------|
| 1 | ~~app 工具访问全面切换走 MCP~~ | ✅ **已完成（2026-08-10）**：`MetricToolAccess` 接口 + `myxhs.ai.tools.mode=mcp`（默认）——Agent/路由经 MCP 取数；`direct` 模式保留（测试/降级）|
| 2 | 越权/注入/超时用例、conformance suite | D2/D6 |
| 3 | 0.18.3 → 2.0（Spring 集成模块跟上后升级）| 记录 |
| 4 | 审计为应用日志，非独立不可变审计流（DAD「审计与可观测分离」）| D6 |
| 5 | 只读账号主机白名单收敛 + 密码轮换 | 部署 |
| 6 | MCP_API_KEY 未设时放行（dev）；生产必须设置 | 部署 |

## 五、D2 总结
- ✅ **核心交付完成**：共享工具模块 / MCP server（认证+审计+401测试）/ MCP client 桥 / **全链路验证** / 契约测试 / **工具访问默认走 MCP**（41→40 测试全绿）。
- ⏳ 剩余（非阻塞）：测试深化（越权/注入/超时）、部署加固、0.18.3→2.0。
