# 深度 Review：M12 实现（工具注册表）

> 日期：2026-08-15 | 对象：M12 工具注册表实现（design-m12-tool-registry.md）| 视角：契约保持/单一事实源/克制
> 结论：**1 个 P0（已修复）、0 个 P1、若干 P2**——209 测试全绿 + 真库冒烟

---

## P0-1：校验规则漂移（keyword 白名单复制而非委托）

**问题链**：
1. 原 PolicyGuard 的 logSearch 校验**直接委托** `DirectLogSearchAccess.validateKeyword/parseTailLines`（单一事实源）
2. M12 迁移时我在 `ToolParamValidators` **凭记忆复制**了正则——允许集合从
   `[A-Za-z0-9_\-.:/\[\]{}() ,=#@$%^&*+~'"]`（含空格/括号/`#@$%` 等）缩水为 `[A-Za-z0-9_\-\[\].:/=]`
3. 后果：**合法 keyword（"OutOfMemory Error" 带空格）被 PolicyGuard 拒绝**（可用性回归），
   且 PolicyGuard 与工具侧两层规则不一致
4. 同时 `parseTailLines` 语义漂移：原实现 clamp（99999→5000，恒放行），新实现返回 -1 拒绝——行为差异

**根因**：迁移静态工具方法时"复制实现"而非"委托原实现"——复制必然漂移，委托才有单一事实源。

**修复**：`ToolParamValidators.validateKeyword/parseTailLines` 改为**委托** DirectLogSearchAccess；
logSearch validator 只校验 keyword（tailLines 工具侧 clamp 恒合法，与原 PolicyGuard 实际行为一致——原检查恒过是死代码）。
回归测试：含空格合法 keyword 放行 / shell 语义拒绝 / tailLines 越界 clamp 放行。

## 注意点（记录在案，非缺陷）

1. **MCP 工具顺序是既有契约**：McpContractTest 锁定 14 个工具名/顺序/schema——catalog 顺序即 MCP 顺序（log.search 排观测首位）。新增工具加在合适位置，勿随意插队破坏契约断言。
2. **注册表两份实例**：AgentHarness 构造器内部装配一份（PolicyGuard/callTool 用）+ AgentToolRegistryConfig bean 一份（测试/审计用）——同 catalog 同源，无共享状态，可接受。若未来需要统一实例，从 Config 注入即可。
3. **P2-a：groupValidator 参数名 fallback**：`group != null ? group : consumerGroup`——原实现按工具名取参（mqConsumerLag→group / mqDlqBacklog→consumerGroup）。新逻辑宽松方向（多接受一种参数名），可接受。
4. **P2-b：invoker==null 且非 L3 → requiresApproval**：若未来 L1/L2 装配漏绑执行器，会误判为 L3 审批（安全方向：不执行）。当前 14 工具全绑定，无场景。
5. **P2-c：SYSTEM_PROMPT 未随注册表更新**：新增工具需手动加进 prompt（design 已知取舍）；"30 分钟接入"门禁只证明 Harness/PolicyGuard 零改动，不含 prompt 更新。

## 方法论复盘

- **迁移的黄金规则：委托而非复制**——静态校验/解析方法迁移时复制实现必然漂移（本 P0 就是复制导致的正则缩水），委托原实现才能保证单一事实源
- **行为对比要用"实际效果"而非"字面逻辑"**：原 PolicyGuard 的 tailLines 检查看似拒绝越界，实际 parseTailLines clamp 后恒过（死代码）——按字面迁移反而改变行为
- **写后 review 必须做规则级比对**：keyword 白名单这种安全规则，只测"几个样例"不够，要逐字符比对原实现

## 结论

- M12 完成（含 P0 修复）：注册表驱动落地，209 测试全绿，真库 E2E 冒烟通过
- 下一步：M11 HITL（dlq.redeliver 挂注册表：catalog 加执行器 + WAITING_APPROVAL 状态机）

