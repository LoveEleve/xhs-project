---
name: opencode
description: >-
  通过 xhs 命令调用 OpenCode + DeepSeek V4 Pro（thinking 模式，high 推理深度）。
  自动加载 CodeGraph + Sverklo MCP 双引擎。
  触发词：opencode、deepseek、xhs、让AI深度分析、批量生成。
tools: Bash
---

# xhs 命令 — OpenCode + DeepSeek V4 Pro 深度分析

## 终端使用（最简单）

```bash
xhs "分析 InventoryService 的 preDeduct 方法"
xhs "审查最近 20 个提交的风险"
xhs "用 codegraph 查 BizException 的影响面"
```

## 原理

```
xhs "任务"
  ├─ export DEEPSEEK_API_KEY
  ├─ cd /data/workspace/my-xhs
  └─ opencode run --model deepseek/deepseek-v4-pro --variant high --thinking --dangerously-skip-permissions "任务"
```

配置链：`/usr/local/bin/xhs` → `opencode.json`（Provider + 性能参数）→ `.mcp.json`（CodeGraph + Sverklo）

## 性能参数

| 参数 | 值 | 位置 |
|------|-----|------|
| `reasoning` | true | `opencode.json` |
| `--variant high` | 高深度推理 | `xhs` CLI 脚本 |
| `--thinking` | 显示思考过程 | `xhs` CLI 脚本 |
| `temperature` | 0.2 | `opencode.json` → agent.build |
| `limit.context` | 1,000,000 | `opencode.json` → models |
| `limit.output` | 65,536 | `opencode.json` → models |
| `steps` | 30 | `opencode.json` → agent.build |

## 与 CodeBuddy 对话分工

| 场景 | 用哪个 |
|------|--------|
| 代码编辑/修复/重构 | **CodeBuddy 对话** |
| 全量审计/深度分析 | **`xhs "..."`**（1M tokens + thinking） |
| 多文件批量搜索 | **`xhs "..."`**（MCP 自动调用） |
| 快速查询符号 | **CodeBuddy 对话** |

## 文件清单

| 文件 | 位置 | 作用 |
|------|------|------|
| `/usr/local/bin/xhs` | 全局 | CLI 入口脚本 |
| `opencode.json` | 项目根 | Provider + 性能参数 |
| `.mcp.json` | 项目根 | CodeGraph + Sverklo MCP |
| `.codebuddy/commands/opencode.md` | 项目 | 本命令说明 |
