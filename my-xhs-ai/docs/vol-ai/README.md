# 卷 AI · my-xhs-ai 深度解剖

> 基于 `my-xhs-ai` 当前代码、测试、E2E、Langfuse、Temporal PoC 的重新审视版深度分析。
> 目标不是重复功能清单，而是解释：**这个 AI 项目到底是什么、为什么成立、哪里强、哪里还不能夸大。**

## 阅读入口

1. 先读 `00-overview-architecture/` 建立全局心智模型
2. 再读 `01-intent-router-harness/` 理解核心执行状态机
3. 再读 `02-tool-mcp-policy/` 看工具与权限边界
4. 再读 `03-memory-conversation-rag/` 看会话、记忆、RAG 的分层
5. 再读 `04-hitl-dlq-observability/` 看审批、DLQ、Langfuse
6. 再读 `05-eval-quality-release/` 看评测与收官边界
7. 最后读 `06-temporal-durable-poc/` 看 Durable Execution PoC

## 目录结构

```text
vol-ai/
├── 00-overview-architecture/   — 项目定位、服务边界、核心控制面
├── 01-intent-router-harness/   — 路由、Harness、状态机、约束逻辑
├── 02-tool-mcp-policy/         — ToolRegistry、MCP、PolicyGuard、执行边界
├── 03-memory-conversation-rag/ — 会话、向量记忆、RAG 分层
├── 04-hitl-dlq-observability/  — HITL、DLQ、Langfuse trace、demo 证据
├── 05-eval-quality-release/    — 评测、质量边界、收官判断
├── 06-temporal-durable-poc/    — Temporal PoC、restart 恢复、对照结论
└── README.md
```

## 项目源码位置

- 代码仓库：`/data/workspace/my-xhs/`
- AI App：`/data/workspace/my-xhs/my-xhs-ai-app/`
- AI MCP：`/data/workspace/my-xhs/my-xhs-ai-mcp/`
- Tools：`/data/workspace/my-xhs/my-xhs-ai-tools/`
- 展示页：`/data/workspace/my-xhs/my-xhs-ai/SHOWCASE.md`
- 收官页：`/data/workspace/my-xhs/my-xhs-ai/FINAL-SUMMARY.md`
