# Phase 16: AI 编程工具效能

## 前置依赖

- **Phase 1 (Python基础)**：Python 能力是 AI 编程工具的前提
- 与 Phase 2-15 可并行——一边学 AI 一边用 AI 写 AI 系统的代码

## 为什么第十六

你会写 AI 系统，但也要会用 AI 写代码。SDD（Spec-Driven Development）是 2026 年 AI 编程新范式——先用规格文档锁定意图，再让 AI 生成代码。本 Phase 贯穿所有 Phase——每个 Phase 的代码都可以用 AI 工具加速。

## 与 my-xhs 的关联

| 工具 | 用途 | my-xhs 场景 |
|------|------|-----------|
| Claude Code | 多文件编辑+代码审查 | 生成 MCP Server 脚手架、Agent 代码 |
| Cursor | IDE 原生 Agent | 日常 Java/Python 开发 |
| OpenSpec | SDD 框架 | Phase 0 架构文档生成代码骨架 |

## 学什么

| 模块 | 内容 |
|------|------|
| SDD 方法论 | 规格文档（输入/输出/边界/失败场景）→AI 生成代码→人工 Review |
| Claude Code | SWE-bench 80.9%、多文件编辑、代码审查、测试生成 |
| Cursor | Composer 模式、多文件上下文、项目级记忆 |
| OpenSpec | 开源 SDD 框架：Spec 文件→AI 生成→验证 |
| AI 代码评审 | AI 生成代码常见问题：过度抽象/循环依赖/忽略边界条件 |
| 多 Agent 编程 | Code Agent→Review Agent→Test Agent→人工确认 |

## 生产工程问题

| 问题 | 本 Phase 如何解决 | 验证方式 |
|------|-----------------|---------|
| AI 生成代码安全性 | Code Review 检查清单：SQL 注入/硬编码密钥/不合理权限，不通过不合并 | 审查发现的 3+ 安全问题已修复 |
| AI 生成代码性能 | Review 清单：N+1 查询/循环内 API 调用/缺失缓存/大对象复制 | 关键路径代码经过性能审查 |

## 文档清单（4 篇）

| # | 文档 | 内容要点 |
|---|------|---------|
| 01 | sdd-methodology.md | SDD 方法论：需求→Spec（输入/输出/边界/失败场景）→AI 生成→人工 Review→测试；工具对比（OpenSpec/GitHub Spec Kit/Kiro） |
| 02 | claude-code-practice.md | Claude Code 实战：生成 my-xhs MCP Server 骨架（pom.xml+Application+Tool 类+SKILL.md+测试）|
| 03 | cursor-practice.md | Cursor Composer 模式：多文件上下文编辑+项目级记忆+Java/Python 开发流程 |
| 04 | code-review-checklist.md | AI 代码评审清单（安全/性能/可维护性/边界处理/测试覆盖 5 维度） |

## 验证

1. 用 SDD 方法让 Claude Code 生成 1 个 MCP Server 骨架（含 SKILL.md+测试）
2. Code Review 找到 AI 生成代码中 3+ 个问题
3. 多 Agent 编程流程跑通一次

## 对后续的影响

贯穿所有 Phase——每个 Phase 的代码都可以用 AI 工具加速开发
