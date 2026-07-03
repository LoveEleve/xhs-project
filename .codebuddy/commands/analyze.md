---
name: analyze
description: >-
  CodeGraph + Sverklo 双引擎代码分析。
  触发词：analyze、分析、审查、audit、查符号、调用方、caller、callee、影响面、impact、review、爆炸半径、blast-radius、token消耗、receipt、搜索代码、索引更新、代码质量、风险审查。
  AI 自动根据输入意图选择 CodeGraph 或 Sverklo 执行。
tools: Bash
---

# CodeGraph + Sverklo 双引擎分析

## 项目索引

| 工具 | 索引 | 数据 |
|------|------|------|
| CodeGraph | 479 文件 | 8,970 节点 / 13,613 边（SQLite @ `.codegraph/`） |
| Sverklo | 实时 | 注册名 "my-xhs"（`.sverklo/`） |

---

## 引擎分工

### CodeGraph — 精确符号级查询

核心能力：tree-sitter AST 解析 → 符号关系图（调用链、继承、引用）

| 子命令 | 用途 | 典型问题 |
|--------|------|---------|
| `query <关键词>` | 全局搜索符号/类/方法 | "项目中哪些地方用了 @Transactional" |
| `callers <符号>` | 谁调用了这个方法 | "preDeduct 被谁调用" |
| `callees <符号>` | 这个方法调用了谁 | "resizeBuckets 内部调了什么" |
| `impact <符号>` | 改动这个符号的影响范围 | "改 generateConversationId 会影响哪些文件" |
| `files` | 项目文件树 | "项目有哪些模块/文件" |
| `sync` | 增量更新索引 | 代码改动后执行 |
| `status` | 索引统计 | 看当前索引覆盖 |

执行格式：
```bash
cd /data/workspace/my-xhs && codegraph <子命令> <参数>
```

### Sverklo — 整体质量与风险评估

核心能力：符号图 + PageRank + 语义搜索 + 爆炸半径分析

| 子命令 | 用途 | 典型问题 |
|--------|------|---------|
| `audit` | 全量代码审计（含评分） | "项目代码质量怎么样" |
| `review` | 未提交变更风险审查 | "刚才的改动有风险吗" |
| `review --ref A..B` | 指定范围审查 | "最近 5 个提交安全吗" |
| `review --fail-on high` | CI 严格模式 | PR 合并前卡点 |
| `receipt` | Token 消耗统计 | "AI 会话用了多少 token" |
| `history` | 审计评分趋势 | "代码质量在变好还是变差" |
| `doctor` | MCP 诊断 | "Sverklo 怎么不工作了" |
| `reindex [--force]` | 重建索引 | 索引异常修复 |

执行格式：
```bash
cd /data/workspace/my-xhs && sverklo <子命令>
```

---

## AI 自动路由规则

根据用户输入，AI 自行判断用哪个工具，**无需用户指定**：

| 用户说 / 意图 | → 选择 | → 执行什么 |
|-------------|--------|-----------|
| "查 XX 的调用方" "谁调用了 XX" | CodeGraph | `codegraph callers "XX"` |
| "XX 调用了哪些" "XX 的依赖" | CodeGraph | `codegraph callees "XX"` |
| "改 XX 会影响哪" "爆炸半径" | CodeGraph | `codegraph impact "XX"` |
| "搜 XX" "找一下 XX" "有没有用到 XX" | CodeGraph | `codegraph query "XX"` |
| "有哪些模块" "文件结构" | CodeGraph | `codegraph files` |
| "代码质量" "审计" "评分" "audit" | Sverklo | `sverklo audit` |
| "审查改动" "有没有风险" "review" | Sverklo | `sverklo review` |
| "最近 N 个提交安全吗" | Sverklo | `sverklo review --ref HEAD~N..HEAD` |
| "token 消耗" "花了多少" | Sverklo | `sverklo receipt` |
| "诊断" "MCP 连不上" | Sverklo | `sverklo doctor` |
| "代码改完了" "更新索引" | CodeGraph | `codegraph sync` |
| 不确定 / 都想看 | 两个都跑 | CodeGraph query + Sverklo audit |
| "review + 特定方法的影响面" | 两者串联 | Sverklo review → CodeGraph impact |
