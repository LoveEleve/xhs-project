# D05 · MCP 集成设计（G18 / ADR-12）

> 目标：复用 MCP 生态扩展工具能力，同时保持企业级治理（白名单/隔离/审批/审计）

## 1. 原则

1. **仅桥接 Tools**（MCP resources/prompts 不支持）
2. **默认关闭**：每个 server 显式开启；白名单（server 级 + 工具级）
3. **统一治理**：MCP 工具与原生工具进入同一 `ToolRegistry`，走同一策略引擎/审批/审计
4. **隔离**：独立进程；env 清洗；超时与帧上限；不继承宿主敏感环境

## 2. 架构与命名

```
ToolRegistry ── native tools（health/log/…）
      │
      └── McpToolBridge
             ├── stdio server（本地进程，exec 形式无 shell）
             └── http/streamable server（远程）
                      │
              server-qualified 命名：<server>__<tool>（如 fs__read_file）
```

- 命名冲突：前缀隔离；校验器确保与原生工具无重名
- 工具 schema 缓存 + 启动时校验（非法 schema 拒载）

## 3. 治理规则

| 规则 | 说明 |
|------|------|
| server 白名单 | `ai.mcp.servers[].name/command/allow-tools`；未列出 = 禁止 |
| 工具白名单 | 每 server 显式 allow-tools；默认空（不自动全量暴露） |
| env 清洗 | 仅传显式声明的变量；清除 `*TOKEN*`/`*SECRET*`/`*KEY*`（除非在白名单中） |
| 风险分级 | MCP 工具默认 `ask`；只读类可配置 `allow`；写类 `deny`（v1 不开放） |
| 版本 pin | server 镜像/命令版本固定；配置入 catalog + CI 校验 |
| 超时/帧 | 调用超时（默认 10s）；帧上限（8MiB）；进程健康探测与重启 |

## 4. 生命周期

1. 启动：读取白名单配置 → 启动 stdio/连接远程 → `initialize` 握手 → 拉取 tools 列表
2. 运行：健康探测（周期）；调用失败分类（连接/超时/协议/业务）；连续失败熔断该 server
3. 变更：配置热更（新增/移除 server）→ 反注册旧工具（disposer）→ 注册新工具；**变更延迟生效**（D6 缓存友好）
4. 关闭：优雅停止子进程；清理临时文件

## 5. 接入范围（ADR-23 修订：v1.5 直接接入）

- **v1.5 接入官方 MCP**：Elastic / Prometheus / Grafana（功能对标见 D09）
- 社区 MCP（RocketMQ/XXL-Job/MySQL）优先**翻译到 Java**（T1/T2），必要时自托管接入
- **工具集按需加载**：MCP 工具数量大（60+），必须按场景装载（D02 §11），禁止全量注册
- v2 扩展：内部服务 MCP 封装、记忆/子代理生态评估

## 6. 可观测与审计

- 每个 MCP 调用一个 tool span（server/tool/duration/status）
- 指标：`ai_mcp_calls_total{server,tool,status}`、失败分类计数
- 审计：与原生工具一致（action=`mcp.<server>.<tool>`，参数脱敏）

## 7. 测试

- 越权：白名单外 server/工具不可见不可调
- env 清洗断言：进程中不存在未声明的敏感变量
- 故障：server 挂/超时/非法响应 → 受控错误，不阻塞循环
- 热更：新增/移除 server 后工具注册零残留
- 命名：`server__tool` 无冲突且可读
