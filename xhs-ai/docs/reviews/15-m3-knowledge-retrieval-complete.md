# RV15：M3 知识检索闭环完成（知识卡 + BM25 + Agentic Retrieval + code_locate）

> 日期：2026-09-13 ｜ 状态：**M3 主干闭环**（向量按 RV09/RV14 结论不启动）
> 证据：RV13（卡片/索引）、RV14（Agent E2E + 模型网关）、KB 评测报告、本报告（code_locate）

---

## 1. 本增量：code_locate（v1 轻量代码定位）

| 项 | 说明 |
|----|------|
| 实现 | `CodeLocateService`：文件扫描（跳过 target，≤8000 文件）+ 类/方法/关键字匹配，返回 `文件:行号 + 片段` |
| 工具 | `code_locate(query, limit)` 注册进 Agent；审计 `code.locate`；只读 |
| 冒烟 | 直测 `InventoryService.doPreDeduct` → `InventoryService.java:239/252` |
| Agent E2E | 5 次工具调用（code_locate → knowledge_search → card_read → code_locate ×2）→ 回答带 `文件:行号` 引用 |

## 2. M3 主干闭环清单

- ✅ 三层知识卡 54 张（architecture/business/code-map/failure），catalog 渐进披露
- ✅ ES `xhs_ai_knowledge` BM25 索引 + `knowledge_search/card_read`
- ✅ KB EVAL 30 条：**hit@1=100%**，门禁通过 → 向量实验不启动（RV09 止损规则生效）
- ✅ Agentic Retrieval：目录→检索→读整卡→引用回答（catalog/search/read 审计落库）
- ✅ code_locate v1（代码类问题闭环，引用 `文件:行号`）
- ✅ D01 模型网关（重试/熔断/降级/指标）+ 双通道模型（chat=pro / agent=qwen3.8-flash）

## 3. 剩余（M3 收尾 / M4 前）

| # | 事项 | 触发/里程碑 |
|---|------|------------|
| 1 | 卡片引用校验 CI（类/方法/表/topic 存在性机械校验） | M3 收尾 |
| 2 | EVAL 增强：口语化改写问法 + 知识库外拒答负例；答案级抽样（引用有效性） | M3→M4 |
| 3 | jdtls 完整 LSP | 触发：代码定位精确率 <90% 或跨文件跳转需求 |
| 4 | 工具预算护栏（当前 Agent 侧 27 个） | 工具 >40 时加 tool_search |
| 5 | M2.x 挂账（审批超时/跨实例、消费位点核验） | 按 RV10 清单 |
