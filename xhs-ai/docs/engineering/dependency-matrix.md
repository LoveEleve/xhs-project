# 依赖矩阵与冲突处置（M1-1 产出）

> 校验命令：`mvn -f xhs-ai/pom.xml validate`（enforcer: Java17 / BanDuplicateClasses / DependencyConvergence）
> 结果：**BUILD SUCCESS**（三规则全过）

## 1. 最终版本基线（实测解析结果）

| 组件 | 版本 | 来源/说明 |
|------|------|----------|
| Spring Boot | 3.2.5 | 对齐主工程（BOM import） |
| JDK | 17 | enforcer `[17,)` |
| AgentScope | 2.0.1 | harness + model-openai + redis 扩展 |
| Jackson | **2.21.1** | AgentScope 要求；覆盖 Boot 托管的 2.16.1 |
| Reactor | **3.8.2** | AgentScope 要求；覆盖 Boot 托管的 3.6.5 |
| OTel API | **1.61.0** | AgentScope 要求 |
| SnakeYAML | **2.6** | AgentScope 要求 |
| Jedis | **7.4.1** | 对齐 AgentScope redis 扩展（覆盖 Boot 托管 5.0.2） |
| okhttp / okhttp-jvm | **5.3.2** | AgentScope 要求（覆盖 Boot 托管 4.12.0） |
| okio / okio-jvm | **3.16.4** | 随 okhttp 5.3.2 统一 |
| MyBatis-Plus | 3.5.7 | 对齐主工程 |
| mysql-connector-j | 8.3.0 | Boot 托管 |
| Flyway | 9.22.3 | Boot 托管（含 flyway-mysql） |
| MCP SDK | 0.17.0 | AgentScope 传递（M1.5 已启用运行时路径） |

## 2. 冲突与处置（Enforcer 抓出，全部闭环）

| # | 冲突 | 现象 | 处置 |
|---|------|------|------|
| 1 | okhttp 双版本 | Boot BOM 把 AgentScope 的 okhttp **降级**为 4.12.0，与 okhttp-jvm 5.3.2 并存（连带 okio 3.6/3.16 双版本） | dependencyManagement 显式 pin okhttp/okhttp-jvm 5.3.2 + okio/okio-jvm 3.16.4 |
| 2 | jedis 版本 | Boot 托管 5.0.2，AgentScope 扩展按 7.4.1 开发 | pin jedis 7.4.1（M1 冒烟验证 RedisAgentStateStore 行为） |
| 3 | 重复类 org.json | `jsonassert:1.5.1` 内嵌 `org/json/JSONString.class`，与 jedis 传递的 `org.json:json` 重复（test classpath） | 从 `spring-boot-starter-test` 排除 jsonassert（项目用 AssertJ/Jackson） |
| 4 | MCP SDK 上游重叠 | `mcp-core` 与 `mcp-json`（0.17.0）均含 `io/modelcontextprotocol/json/**`（各 10 个同名类） | **登记豁免**：`ignoreClass io.modelcontextprotocol.json.*`（上游制品问题；M1.5 已启用，升级后移除豁免） |

## 3. 例外登记（需跟踪）

| 例外 | 理由 | 退出条件 |
|------|------|---------|
| `io.modelcontextprotocol.json.*` 重复类豁免 | MCP SDK 0.17.0 官方打包重叠 | 升级 MCP SDK（>0.17.0）后移除豁免并重验 |
| jsonassert 排除 | 单一类重复，测试无需 JSONassert | 若后续需要 JSON 断言，改用 AssertJ + ObjectMapper |

## 4. M1 冒烟验证清单（编码后的第一组测试）

1. JSON 序列化：Jackson 2.21.1 与 Spring MVC 消息转换（自定义对象 + LocalDateTime）
2. SSE：`ResponseBodyEmitter` 流式输出与断开
3. MyBatis-Plus：连接池 + 分页查询
4. Redis：`RedisAgentStateStore`（Jedis 7.4.1 + Sentinel）读写与 CAS
5. AgentScope：`OpenAIChatModel(baseUrl=siyu-all)` 最小对话调用
