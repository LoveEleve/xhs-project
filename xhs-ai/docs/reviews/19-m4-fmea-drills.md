# RV19：M4 FMEA 演练 4 项与 Agent→ES MCP 挂起 P0 修复

> 日期：2026-09-13/14｜方式：故障注入演练（停 ES / Redis failover / 滚动重启 / kill MCP）
> 结论：**演练 4 项全部完成；发现并修复 1 个 P0（ES MCP 工具经 Agent 永久挂起）、1 个 P1（MCP 无自愈）**；单测 25/25；MTTR 案例 10 归因更正并复测通过（10/10）

## 1. 演练结果

| # | 演练 | 注入 | 观测 | 判定 |
|---|------|------|------|------|
| D1 | 停 Elasticsearch | `docker stop my-xhs-elasticsearch` | `knowledge/stats` 返回 `indexed=-1`（受控降级，不抛 500）；health UP；非 ES 工具正常；ES 重启后 `indexed=55` **自动恢复**（123s 恢复 green/yellow） | ✅ 符合 F2/F3 |
| D2 | Redis Sentinel failover | `SENTINEL FAILOVER mymaster` | 主 6379→6380 切换 **2.3s**；458 个 state key 无损；failover 后 Agent 调用成功（state key 458→460）；日志见 SentineledConnectionProvider 重建连接池 | ✅ 符合 F6 |
| D3 | xhs-ai 滚动重启 | `systemctl restart` | 停机 **13s**；重启后同会话追问，Agent 正确复述上文（Redis state 恢复）；审批表状态不变（approved=19/expired=1/pending=0） | ✅ 符合 F11 |
| D4 | kill MCP 子进程 | `kill -9` ES MCP node | 直连 MCP 端点正常但 Agent 路径空答复 503；**服务不会自动拉起 MCP 子进程**（无健康探测/重启） | ⚠️ F16 部分未达标（P1） |

## 2. P0：Agent→ES MCP 工具永久挂起（已修复）

| 项 | 内容 |
|----|------|
| 现象 | Agent 调用任何 ES 工具（`list_indices`/`get_mappings`…）均返回 503 空答复；Redis state 中 `tool_use` 永远停留在 `asking` |
| 根因 | AgentScope 权限引擎对**无只读注解**的 MCP 工具默认 **ASK**（等人确认）；本服务是非交互 API，无人应答 → 工具不执行、模型循环结束无文本 |
| 为何此前未发现 | 注册成功≠调用成功：Prom MCP（17 工具）/自研工具正常，KB/答案评测走自研 `knowledge_*`，ES 工具仅做过**直连**验证；同类问题在 MTTR 案例 10 被误判为"模型+网关抖动" |
| 修复 | `ReActAgent.builder().permissionContext(mode=BYPASS)`；白名单注册 + HITL 审批（DLQ 重投等）仍在应用层兜底；`DONT_ASK` 实测仍被默认规则拒绝，故用 BYPASS |
| 验证 | Agent 调 `list_indices` 返回 32 索引；MTTR 案例 10 复测 **123.0s 通过**（ES 检索 37 命中） |
| 影响面更正 | MTTR 报告案例 10 归因更正；`mttr-raw/log-rerun.json` 归档；统计更新为 **10/10、均值 1.63min、保守降幅 92.1%** |

## 3. P1：MCP 子进程无自愈

- 现状：`McpClientManager` 仅在启动时注册；子进程崩溃后该 server 工具全部不可用，需重启 xhs-ai 恢复（D4 实测）。
- 设计承诺（F16）："熔断该 server + 动态移出工具集 + 自动重启/恢复探针"——**未实现**。
- 处置：记入 M4 遗留；建议实现"心跳探测 + 指数退避重启 + 连续失败熔断该 server 工具"。

## 4. 附带发现

- ES 停机时 `knowledge/stats` 以 `indexed=-1` 表达降级——**受控但语义隐晦**（HTTP 200），后续可考虑附加 `degraded` 标志。
- 空答复 503 的文案"模型网关未返回内容"在工具权限问题下具有误导性；已修复根因，文案建议保留但补充"或工具不可用"（低优先级）。

## 5. 复现

```bash
# D1
docker stop my-xhs-elasticsearch && curl .../api/ai/knowledge/stats   # indexed=-1
# D2
docker exec my-xhs-redis-sentinel redis-cli -p 26379 SENTINEL FAILOVER mymaster
# D3
systemctl restart xhs-ai && 续接同 sessionId 追问
# D4
kill -9 <es-mcp-node-pid>
```

## 6. 遗留（更新）

1. ⬜ MCP 自愈：心跳 + 自动重启 + 熔断移出（P1，RV19 新建）
2. ⬜ 平台侧 DlqMetrics 覆盖差 8 组（挂账）
3. ✅ 压测 / 工具预算护栏 / KB 复跑 / FMEA 演练 4 项 —— 已完成
