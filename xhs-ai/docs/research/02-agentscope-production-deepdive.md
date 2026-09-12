# R02 · AgentScope 2.0 Java 生产化深读（Phase 1）

> 来源：官方 v2 文档（Quickstart / Going to Production / Harness / Skill 相关页）实测 + Maven Central 验证
> 结论：框架能力完全覆盖"多副本 + 多租户 + HITL + 技能治理 + 观测"需求，**不需要自研 Harness 层**

## 1. 关键 API 事实（已核）

- 入口：`HarnessAgent.builder()`（`agentscope-harness` 自动带 `agentscope-core`）；JDK 17+（我们用 21）
- 调用：`agent.call(msg, RuntimeContext.builder().userId(...).sessionId(...).build())` / `agent.streamEvents(...)` 返回类型化事件（TEXT_BLOCK_DELTA / TOOL_CALL_START / …）
- **无状态单例**：同一实例并发服务多 `(userId, sessionId)`；同 session 自动串行、不同 session 并行
- 人格与记忆：`workspace/AGENTS.md`（人格）、`memory/YYYY-MM-DD.md` → `MEMORY.md`（自动合并 + 注入 system prompt）
- 上下文压缩：`CompactionConfig(triggerMessages, keepMessages)` + `ToolResultEvictionConfig`
- 状态：`AgentStateStore` 按 `(userId, sessionId)` 寻址，承载对话/压缩摘要/权限规则/Plan 状态/tool state
- 技能：四层合成（项目全局 < Marketplace < `workspace/skills/` < `<userId>/skills/`）；`skillRepository(...)` 可多注册、同名后注册覆盖
- HITL/权限：框架内置 PermissionEngine（三态）；文档见 `building-blocks/permission-system`（**待源码级验证挂起/恢复 API**）
- 观测：`OtelTracingMiddleware` + OTel SDK；优雅停机 `GracefulShutdownManager`（默认 JVM hook）
- 限流：自写 `MiddlewareBase`（onModelCall），文档有 "限速 middleware" 示例

## 2. 生产化选型（对应 xhs-ai）

| 维度 | 推荐 | 理由 |
|------|------|------|
| 一键分布式 | `DistributedStore store = RedisDistributedStore.fromJedis(jedis)`（或混合 builder） | 自动注入 stateStore + baseStore + snapshot + executionGuard |
| AgentState | **Redis（Sentinel）** `RedisAgentStateStore` | 多副本首选、versioning CAS；我们已有 Redis Sentinel |
| 工作区文件 | `RemoteFilesystemSpec` + `IsolationScope.USER` + `WorkspaceIndex`（BaseStore=RedisStore） | 多副本共享 MEMORY/skills/sessions；**v1 不配 shell**（工具全部走 HTTP，无不可信代码执行） |
| 沙箱 | **v1 不启用**；v2 若需执行代码再用 `DockerFilesystemSpec` + Snapshot | v1 无代码执行场景，避免过度设计 |
| 技能治理 | `GitSkillRepository(我们的 harness-skills 仓库)`；平台期换 `MysqlSkillRepository(writeable=false)` 或 `NacosSkillRepository` | 改技能走 git PR；Nacos 若用必须 `@PreDestroy close()` |
| 模型 | `agentscope-extensions-model-openai` + 自定义 baseURL（siyu-all） | OpenAI 兼容；**待验证 builder 自定义 endpoint 与 key 环境变量注入方式** |
| MySQL 用途 | 我们的业务表（session/message/approval/audit/knowledge/eval）+ 可选 `MysqlSkillRepository` | 不用于 AgentState（避免热路径打 DB） |
| 观测 | `OtelTracingMiddleware` + OTLP → Langfuse | 框架原生；无 Langfuse 时落本地 collector |

## 3. 已验证的 Maven 依赖（2.0.1，本地已拉取成功）

```xml
io.agentscope:agentscope-harness:2.0.1
io.agentscope:agentscope-extensions-model-openai:2.0.1
io.agentscope:agentscope-extensions-redis:2.0.1
io.agentscope:agentscope-extensions-mysql:2.0.1        <!-- 备用 -->
io.agentscope:agentscope-extensions-skill-git-repository:2.0.1
```

## 4. 生产坑位（直接写进设计约束）

1. **必须每次传 RuntimeContext**：不传 `sessionId` 会串到 `defaultSessionId`
2. `tools.json` 的 allow 白名单会**连内置工具一起过滤**（`read_file`/`memory_search`/`agent_spawn` 需保留）
3. `IsolationScope` 上线前定死，改动=换命名空间（旧数据不迁移）
4. 本地 state store + 远程 filesystem 组合会 `IllegalStateException`（设计使然，防状态留 pod 本地盘）
5. Ollama/远程模式禁 shell；需要 shell 必须沙箱
6. `NacosSkillRepository` 不 close 会泄漏订阅

## 5. 源码级验证结果（2026-09-12 已全部关闭，详见 reviews/01）

| # | 事项 | 结果 |
|---|------|------|
| 1 | OpenAI 自定义 endpoint | ✅ `baseUrl/apiKey/endpointPath/httpTransport/proxy` 均支持 |
| 2 | PermissionEngine 挂起/恢复 | ✅ ask + RequireUserConfirmEvent/UserConfirmResultEvent/ConfirmResult/external execution 全链路存在 |
| 3 | 事件枚举 | ✅ 30 种 AgentEventType 已获取（含 REQUIRE_USER_CONFIRM / USER_CONFIRM_RESULT） |
| 4 | Redis Sentinel | ✅ `jedisClient/lettuceClient/lettuceClusterClient/redissonClient`（Redisson 支持 Sentinel） |
| 5 | Skill 格式 | ✅ `AgentSkill(name,description,metadata,skillContent,resources)`；harness 有 catalog/registry/curator |
| 6 | MySQL DDL | ✅ 扩展含 `MysqlAgentStateStore/MysqlDistributedStore/JdbcStore`；jar 无 .sql → 框架自管，M1 运行时验证 |

### 原待验证清单（历史存档）

| # | 事项 | 验证方式 |
|---|------|---------|
| 1 | OpenAI 扩展自定义 baseURL/apiKey 的确切 builder 写法（siyu-all 需自定义 endpoint） | 解包 jar javap / 官方 model 文档 |
| 2 | PermissionEngine 三态 + 挂起/恢复（external execution loop）API | `building-blocks/permission-system` + core jar |
| 3 | 28 种 AgentEvent 枚举与 SSE 映射（前端契约） | core jar `io.agentscope.core.event.*` |
| 4 | Redis Sentinel 适配（Jedis Sentinel / Redisson）与 key 前缀 | redis 扩展 jar |
| 5 | Skill 目录格式要求（与 harness-skills SKILL.md 的兼容度；frontmatter 是否需扩展字段） | harness jar skill 加载器 |
| 6 | MySQL DDL：`agentscope_*` 状态表由框架自动建还是需手工迁移 | mysql 扩展 jar / 文档 |

## 6. 对 02-architecture 的修订建议

- ADR-2（状态存储）：MySQL → **Redis(Sentinel) DistributedStore**；MySQL 仅存业务表
- 新增 ADR-6：**v1 不启用沙箱**（无代码执行），filesystem 用 Remote（无 shell）
- 新增 ADR-7：技能仓库 v1 用 **GitSkillRepository(harness-skills)**，平台期演进 Nacos/Mysql
- 架构图"Memory 层"标注：`AgentStateStore(Redis)` + `RemoteFilesystem(Redis BaseStore)`
