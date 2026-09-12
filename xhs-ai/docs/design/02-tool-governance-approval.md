# D02 · 工具治理与审批设计（G5 / ADR-9/10 / DELTA-1~4）

> 目标：让"模型调用工具"具备企业级可控性——风险分级、策略引擎、审批状态机、执行契约、审计闭环

## 1. 工具元数据（每个工具必须声明）

| 字段 | 说明 | 示例 |
|------|------|------|
| `risk` | allow / ask / deny | `dlq.redeliver` = ask |
| `timeout` | 单次执行上限 | 只读 3s；变更 10s |
| `idempotent` | 幂等语义（可否重试） | 查询 true；重投 true（幂等键） |
| `maxBytes` | 结果上限（超出截断+落盘引用） | 256KB |
| `rateLimit` | 按用户/工具的限流 | 30/min |
| `cacheable` | 可缓存与 TTL | 健康 5s；指标 15s |
| `audit` | 审计级别 | 全量 / 仅动作类 |

## 2. 策略引擎（ADR-9）

- 规则结构：`{permission(工具/类别), pattern(资源), action: allow|ask|deny}`
- 求值：`findLast(wildcard(permission) && wildcard(pattern)) ?? ask`（**last-match-wins + 默认 ask**）；`deny` 短路
- 来源与优先级：系统默认 < 用户配置 < 组织策略（后追加覆盖）；**技能/插件不可修改策略**
- 工具可见性：`deny + pattern=*` 从模型工具列表中隐藏（不可见 > 可见但拒绝）

## 3. 审批状态机（ADR-5 / DELTA-1/4/5）

```
pending ──reply(once)────→ approved（本调用放行）
   │   ──reply(always)──→ approved + 写会话授权 → 批量放行同规则 pending
   │   ──reply(reject)──→ rejected（级联拒绝同会话全部 pending）
   └──超时/关闭─────────→ expired/rejected（fail-closed）
```

- 持久化：`ai_approval`（含 `raw_input_hash` 指纹、`kind`、`patterns`）
- 授权：`ai_session_grant` 可序列化；Agent 状态恢复时 **snapshot/restore**；重启后不丢
- 不变量：恢复流程**每步必须推进状态**，否则抛不变量错误（防静默挂起）
- 应用关闭：所有 pending fail-closed（不悬挂）

## 4. 工具执行契约（ADR-10 / DELTA-3）

1. **永不向 Agent 循环抛异常**：基础设施错误→受控错误结果（错误码+message+duration）
2. 超时：ToolRunner 统一施加 `timeout`，超时返回明确"timed out"结果（模型可换策略）
3. 结果边界：按 `maxBytes` 截断；大结果落盘/表并以引用返回
4. 幂等：变更工具必须具备幂等键（`(tool, args_hash)`），重复提交返回同一 settlement
5. 限流/缓存：执行前检查；缓存命中直接返回（标注 cache_hit）
6. Guard（G16）：重复相同调用提醒；连续失败熔断该工具（如数据源不可用）

## 5. 变更类三段式（intent→effect→settlement）

以 `dlq.redeliver` 为例：
```
intent     ：校验参数+权限 → 生成幂等键 → 写审计(intent) → 返回"待执行"卡片
effect     ：rocketmq-tools 执行重投（携带幂等键）→ 记录原始响应
settlement ：核验结果（消息离开 DLQ / 被消费 / 仍在 DLQ）→ 写审计(settled) → 返回结论
```
- 执行后**必须核验**，不能只凭"调用成功"
- 失败可安全重试（幂等键去重）；"仍在 DLQ"需给出下一步建议

## 6. 中间件合并语义（DELTA-2）

同一调用被多个中间件命中：`deny(3) > ask(2) > allow(1)`；**理由只保留获胜档**；`stop` 首个即 sticky；上下文按顺序累积并注入。

## 7. 审计（只追加）

| 字段 | 示例 |
|------|------|
| actor / trace_id | userId / traceId |
| action / target | `dlq.redeliver` / `group=counter-consumer-group,msgId=...` |
| params | 脱敏后参数（捕获模式 sanitized） |
| content_categories / redaction_count | `["secret","phone"]` / 3 |
| result | status + 核验结论 + duration |

审计不可修改；支持按 traceId/用户/时间检索（D05 会话查询提供）。

## 8. API 与事件契约

| 接口 | 说明 |
|------|------|
| `GET /api/ai/approvals?status=pending` | 审批列表（按用户/会话） |
| `POST /api/ai/approvals/{id}/reply` | `{reply: once|always|reject, message?}` |
| SSE `REQUIRE_USER_CONFIRM` | 审批请求（含工具/参数/风险/指纹） |
| SSE `USER_CONFIRM_RESULT` | 审批结果（恢复执行） |

## 9. 测试矩阵

| 类型 | 用例 |
|------|------|
| UT | 策略矩阵（wildcard/last-match/默认 ask/deny 短路）；指纹计算；状态机迁移 |
| IT | 持久化+恢复（重启后 pending/授权恢复）；级联拒绝；超时 fail-closed |
| E2E | DLQ 重投全流程（未审批不执行→审批→执行→核验→审计）；Job 触发 |
| 负向 | 越权审批（他人 approval）；伪造 rawInput（指纹不匹配拒绝） |
| 治理 | 工具注册→卸载零残留；工具元数据完整性校验（CI） |
| Guard | 重复调用提醒；工具超时返回受控错误 |


## 10. 多副本下的 HITL（跨实例恢复）

> 场景：审批等待期间持有会话的副本崩溃；用户在其他副本提交审批 → 必须能继续

设计：

1. **登记先行**：审批写入 MySQL 成功后才对模型/前端可见（状态只在提交点发布）；挂起的工具调用随 AgentState 持久化到 Redis
2. **等待不占线程**：`CompletableFuture + 超时` 事件驱动；会话标记 `WAITING_APPROVAL`，释放执行线程
3. **回复任意副本可达**：`reply` 先 CAS 更新审批状态（pending→approved/rejected），再经 Redis pub/sub 广播 `ai:approval:decided:{id}`：
   - 原副本存活：订阅者唤醒本地等待并继续
   - 原副本已死：回复副本触发**恢复执行**（加载 AgentState + 注入 `USER_CONFIRM_RESULT` 重放）；受"无进展即失败"不变量保护
4. **幂等**：决定带 revision CAS；重复 reply 返回已决状态；`raw_input_hash` 指纹校验防调包
5. **超时**：`expired`（默认 10 分钟，可配）→ 全部副本按拒绝处理，fail-closed
6. **等待期间新消息**：进入 durable inbox（steer/queue），恢复后按会话边界处理，不打断在途审批

测试：`kill` 持等待副本 → 另一副本 reply 后成功续跑；重复 reply 幂等；超时唤醒；指纹不匹配拒绝。


## 11. 工具集与渐进加载（上下文预算，ADR-23）

**背景**：MCP 工具数量可达 60+（Prometheus ~20 / Grafana / ES ~5 / XXL-Job 12-20 / RocketMQ / MySQL），而工具 schema **每次请求全量下发** → 输入 token 暴涨、模型误选工具、成本上升（F17）。

设计：
1. **场景化 Toolset**：`diagnosis` / `knowledge` / `ops` 三套；会话按意图路由装载对应工具集
2. **渐进加载**：默认只暴露 `tool_search`（检索可用工具与简介）→ 命中后动态激活具体工具（借鉴 pi deferred-tools / opencode 技能懒加载）
3. **白名单**：`tools.json` allow（保留内置 `read_file`/`memory_search` 等）；MCP 工具 server-qualified 且必须显式 allow
4. **schema 预算**：每 Toolset 上限（默认 ≤8k tokens）；超限自动裁剪工具 description 并告警
5. **受控只读 SQL（ADR-23）**：`db.query` 风险=allow（受限）；三层防护 + 行数/超时 + 结果脱敏 + 审计；诱导绕过入红队（TC-RED-05 扩展）
