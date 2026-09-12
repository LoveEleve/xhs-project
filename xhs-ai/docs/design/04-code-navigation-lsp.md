# D04 · 代码导航设计（LSP + JGit 双轨，G17 / ADR-11）

> 目标：回答"这个类/方法在哪、谁调用、谁实现、最近谁改过"时给出**精确、可回链**的答案

## 1. 双轨分工

| 能力 | 实现 | 说明 |
|------|------|------|
| definition / references / implementations / hover | **LSP（jdtls）** | 语义精确（含接口实现、重载消歧） |
| blame / log / diff / 最近提交 | **JGit（本地仓）** | 历史与作者信息 |
| 全文/模糊搜索 | ES 或 ripgrep 兜底 | LSP 不可用/跨仓搜索 |

## 2. 架构

```
tools/code.{definition,references,implementations,hover,blame,history,search}
        │（Provider 抽象：归一化结果）
┌───────▼───────────────┐     ┌────────────────────┐
│ LspCodeProvider       │     │ GitCodeProvider    │
│ lsp4j + jdtls(常驻)    │     │ JGit(本地镜像)      │
└───────┬───────────────┘     └─────────┬──────────┘
        ▼                               ▼
  jdtls workspace（xhs-project 仓）   .git（只读）
```

- jdtls 单例常驻（内存/CPU 限额），workspace 指向 `/data/workspace/xhs-project`
- 首次启动需构建索引（Maven 依赖已本地缓存）；启动完成前请求 **降级 JGit/文本搜索**
- 崩溃自愈：健康探测 + 重启（指数退避），期间降级

## 3. 归一化输出契约

```json
{ "file": "src/main/java/.../InventoryService.java", "line": 224, "column": 12,
  "symbol": "com.myxhs.inventory.service.InventoryService#doPreDeduct",
  "kind": "method", "preview": "private boolean doPreDeduct(...)" }
```
- 行号/文件路径保证可回链（引用校验的输入）
- 结果上限：默认 50 条/次，截断时提示收窄查询

## 4. 工具元数据（D02 规范）

| 工具 | risk | timeout | maxBytes | cacheable |
|------|------|---------|----------|-----------|
| code.definition | allow | 5s | 64KB | 60s |
| code.references | allow | 8s | 128KB | 60s |
| code.implementations | allow | 8s | 128KB | 60s |
| code.hover | allow | 3s | 16KB | 300s |
| code.blame | allow | 5s | 64KB | 60s |
| code.history | allow | 5s | 64KB | 60s |

## 5. 与知识库协同

- **代码卡片生成**：LSP 提取方法签名/javadoc/调用方 → 入 `ai_knowledge_doc(code 层)`
- **引用校验**：回答中的文件/方法先经 LSP 校验存在（KB-03/05 的 AC）
- **变更影响**（KB-07）：references + Feign/MQ 映射（知识层）联合出影响面

## 6. 资源与风险

| 风险 | 缓解 |
|------|------|
| jdtls 内存/CPU 占用 | 限额（-Xmx1g）+ 空闲降载；与 15 个微服务共存需压测（注意本机内存） |
| 首次索引慢 | 预热任务（启动后异步）；期间降级 |
| Maven 依赖缺失 | 依赖本地仓库；离线模式；失败降级 |
| 仓库多分支 | workspace 固定只读检出（refactor/elk-auth-simplify）；切换需审批 |

## 7. 测试

- 黄金样本：对 8-10 个已知类/方法断言 definition/references 结果
- 降级：jdtls 未就绪/崩溃时 JGit 结果可用
- 性能：冷启动索引时长、热查询 P95、内存占用
- 引用校验：伪造方法名必须校验失败
