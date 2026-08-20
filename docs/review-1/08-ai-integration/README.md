# 08-ai-integration

## 目标

审查 AI 相关入口如何接入主系统，以及其边界是否安全、可控、可观测。

## 重点服务

- `my-xhs-ai-app`
- `my-xhs-ai-tools`
- `my-xhs-ai-mcp`
- `my-xhs-gateway`
- `frontend`

## 重点问题

1. AI 请求身份是否完全来自可信网关注入
2. `/ai-api/**` 路由、SSE、限流、角色边界是否闭环
3. MCP 是否只暴露在内网，是否存在越权工具调用
4. prompt/tool 调用是否可能引出高成本、高时延、未鉴权操作
5. AI 统计、诊断、会话归属是否存在伪造或串号

## 预期证据

- gateway ai route 与鉴权配置
- ai-app controller 与 header 读取逻辑
- ai-tools 的数据读取口径
- mcp 暴露方式与环境变量注入
- 前端 API baseURL 与事件流路径

## 初步产出建议

- `gateway-contract.md`
- `identity-propagation.md`
- `tool-trust-boundary.md`
- `sse-and-timeouts.md`
- `mcp-exposure.md`