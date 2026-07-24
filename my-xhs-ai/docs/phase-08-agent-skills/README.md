# Phase 8: Agent Skills 技能工程

## 前置依赖

- **Phase 5 (Agent架构)**：Skills 在架构全景中的位置
- **Phase 6 (MCP)**：MCP 是 Skills 的底层执行技术

## 为什么第八

Anthropic 2026 年 5 月开源 Skills Repo（**138K⭐ 仅 3 天**）→AI 开发从"写 Prompt"进入"写 Skill"。本 Phase 把 Phase 6 的 10 个 MCP Server 标准化为可复用、可版本化、可跨 Agent 共享的 Skill 模块。

## 与 my-xhs 的关联

| 10 个 MCP Server | → | 10 个标准 Skill |
|-----------------|---|----------------|
| order-mcp | → | `query-order` Skill (v1.0.0) |
| user-mcp | → | `query-user` Skill |
| payment-mcp | → | `query-payment` Skill |
| ... | → | ... |
| log-mcp | → | `query-log` Skill |

每个 Skill 包含：`SKILL.md`（标准规范）+实现代码（MCP Server）+测试用例。元数据在 Nacos Config 管理，支持热更新。

## 学什么

| 模块 | 内容 |
|------|------|
| Paradigm Shift | Prompt 工程 vs Skill 工程：复用性/版本管理/测试/生态对比 |
| SKILL.md 规范 | 名称/描述/输入参数/输出格式/执行步骤/前置条件/示例 |
| Anthropic Repo | 仓库结构+Skill 组织+生命周期 |
| Nacos Skill Registry | Nacos Config 存储 Skill 元数据+热更新 |
| Skill 生命周期 | SemVer 版本+依赖声明+热加载+宽限期废弃 |

## 生产工程问题

| 问题 | 本 Phase 如何解决 | 验证方式 |
|------|-----------------|---------|
| **Skill 版本兼容** | SemVer 版本号+依赖声明+升级兼容性检查 | 旧版本 Agent 调用新版本 Skill→降级到兼容版本 |

## 文档清单（6 篇）

| # | 文档 | 内容要点 |
|---|------|---------|
| 01 | prompt-to-skill.md | 范式转移对比表 |
| 02 | anthropic-repo-analysis.md | Anthropic Skills 仓库架构 |
| 03 | skill-spec-design.md | SKILL.md 标准格式 |
| 04 | skill-lifecycle.md | SemVer+依赖+热加载+废弃 |
| 05 | myxhs-skill-registry.md | Nacos Skills 注册中心 |
| 06 | skill-ecosystem.md | Skills 市场+生态展望 |

## 验证标准

1. 10 个 MCP Server 各有标准 SKILL.md+版本号+测试用例
2. Nacos 管理 Skills 元数据，修改后 Agent 5 秒内热加载
3. Agent 自动发现匹配的 Skills
4. Skill 版本更新→Agent 自动使用最新兼容版本

## 对后续的影响

- **Phase 9 (评测)**：每个 Skill 有独立评测集
