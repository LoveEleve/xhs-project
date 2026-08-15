# 深度 Review：M10 实现（写码后审查）

> 日期：2026-08-15 | 对象：M10 会话与记忆实现（commit 90eadc3）| 视角：批判性——并发/时序/数据一致性/契约
> 结论：**2 个 P0、1 个 P1、5 个 P2**——P0/P1 已修复（commit 92a099f），P2 记录在案

---

## P0-1：历史注入方向错误（loadMessages 取最早 N 条）

**问题链**：`JdbcConversationStore.loadMessages` 用 `ORDER BY id ASC LIMIT ?` → 长会话（>20 条消息）时注入**最早**的旧消息，**最新结论反而不注入**——多轮语义完全错乱。

**为什么没暴露**：E2E 与单测都是短会话（≤10 轮），id ASC 恰好 = 全部消息。

**修复**：先逆序取最近 N 条再正序（子查询）。回归测试：60 条消息 → 注入消息 40..59（问题20..结论29），断言首条=问题20、末条=结论29、不含问题0。

## P0-2：当前问题重复注入（buildContext 与 appendUserMessage 顺序）

**问题链**：submit 顺序 = `appendUserMessage`（写库）→ `buildContext`（读库）→ 历史注入**包含刚提交的当前问题**，且 harness 尾部又追加"用户问题：xxx" → 模型看到两条相同的用户问题（污染上下文 + 浪费 token）。

**修复**：调序为 `ensureConversation → buildContext → appendUserMessage`。回归测试：捕获模型调用角色序列，断言 Q2 首轮"用户问题："之前的 USER 消息恰 1 条（历史 Q1）。

## P1-1：run 无会话追溯（session_id 时序竞态）

**问题链**：`updateSessionId`（UPDATE）在 submit 主线程同步执行，而 `createRun`（INSERT）在异步线程稍后执行 → **UPDATE 先于 INSERT，影响 0 行**。单测 `会话锁_首个run完成后同会话可再提交` 捕获（count=0）。

**修复**：`updateSessionId` 移到 whenComplete（run 完成时 createRun 必已存在）。回归测试断言 session_id 关联成功。

## P2（记录在案，不阻塞）

| # | 项 | 说明 |
|---|----|------|
| P2-1 | cleanForConversation 误伤 | COMPLETED 结论恰好含"已收集证据"字样会被截断（低概率，方向安全）|
| P2-2 | FAILED 摘要内容 | "模型暂不可用"也进摘要（如实记录，可接受）|
| P2-3 | appendMessage 无事务 | INSERT + UPDATE message_count 分开（崩溃时计数可能不一致）|
| P2-4 | 直答 title | 问候创建会话时 title=问候语（可接受）|
| P2-5 | 崩溃恢复 run 无会话 | resume 路径不写会话消息/不追溯（不冲突，少追溯）|

---

## 方法论复盘（为什么这次 review 有收获）

1. **review 时机**：E2E 跑通 ≠ 正确——P0-1/P0-2 在短会话 E2E 下都不暴露，只有**读代码推演 + 边界测试**能抓到
2. **测试覆盖盲区**：原测试只覆盖"短会话正确路径"，没有"长会话边界"和"异步时序"——补上后 2 个 P0 立刻现形
3. **异步时序陷阱**：主线程 UPDATE vs 异步线程 INSERT 的竞态，靠"同步执行"的直觉写代码必然踩坑——测试必须验证持久化结果而非只看接口返回

## 结论

- M10 实现经 review 后有 2 个真实缺陷（历史方向、重复注入）——都发生在**边界场景**（长会话/时序），单轮正确性未受影响
- 修复 + 回归测试锁定后，M10 验收门禁全部达成（200 测试绿 + 真库 E2E）
- 下一步：M12 工具注册表（含 accessLevel）→ M11 HITL
