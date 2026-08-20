# 卷 AI · my-xhs-ai 深度解剖

> 基于 `my-xhs-ai` 当前代码、测试、E2E、Langfuse、Temporal PoC 的重新审视版深度分析。
> 目标不是重复功能清单，而是解释：**这个 AI 项目到底是什么、为什么成立、哪里强、哪里还不能夸大。**

## 阅读入口

1. 先读 `HANDOFF-AI-VOL.md` 了解这卷现在写到了哪里、该怎么读
2. 再读 `METHODOLOGY.md` 了解本卷写作纪律与证据分级
3. 再读 `00-overview-architecture/` 建立全局心智模型
4. 再按模块推进：Harness → Tool 边界 → Memory → HITL/DLQ → Eval/收官
5. 最后按需看 `06-temporal-durable-poc/`（PoC / 收尾增强）

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
