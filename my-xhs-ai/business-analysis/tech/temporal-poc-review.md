# Temporal PoC 对照实验（已跑通 restart 恢复闭环）

> 2026-08-19 | 目标：验证审批型长任务在 Temporal 中的 durable execution 边界

---

## 一、结论先行

Temporal PoC 已经拿到**最值钱的证据**：

- workflow 可启动
- 可进入 `WAITING_APPROVAL`
- worker 进程可被 kill
- worker 重启后 workflow 状态不丢
- approve signal 仍可送达
- 最终 `getResult()` 返回 `COMPLETED`

也就是说：

> **审批型长任务在独立 worker 进程被 kill 后，可以在 Temporal 上恢复并继续完成。**

---

## 二、已完成内容

### 2.1 依赖接入
- `temporal-sdk:1.24.1`
- `temporal-testing:1.24.1`

### 2.2 最小审批型 Workflow 骨架
- `ApprovalWorkflow`
- `ApprovalWorkflowImpl`
- `ApprovalActivities`
- `ApprovalActivitiesImpl`
- `WorkflowState`

### 2.3 基础语义测试通过
- `TemporalApprovalWorkflowTest`
  - 审批通过 → `COMPLETED`
  - 审批拒绝 → `REJECTED`

### 2.4 更真实的 restart PoC 已跑通
新增：
- `TemporalWorkerBootstrap`
- `TemporalApprovalClient`
- `demo-temporal-restart.sh`

真实实验链路：
1. 启动本机 Temporal dev server
2. 启动独立 worker
3. `start workflow`
4. `query state` → `WAITING_APPROVAL`
5. kill worker
6. restart worker
7. `approve workflow`
8. `get result` → `COMPLETED`

---

## 三、关键根因排查结果

### 3.1 为什么 `TestWorkflowEnvironment` 路线卡住
之前卡点不在 workflow 建模，而在：
- 使用 `TestWorkflowEnvironment` 模拟“进程级 restart”
- restart 后 `signal + getResult()` 阻塞

### 3.2 真正根因
**不是 Temporal 不行，也不是 workflow 写错。**

真正根因是：
1. `TestWorkflowEnvironment` 更适合 workflow 语义/时间跳跃测试，不适合作为“真实 worker restart”最终证据；
2. 客户端在 restart 场景下使用 typed stub / query / signal 的方式不对；
3. 正确做法是：
   - 用真实 Temporal dev server
   - 用独立 worker 进程
   - 用 **workflowId 对应的 untyped stub** 进行 `query / signal / result`

修正后，restart 闭环跑通。

---

## 四、这条 PoC 的价值

### 4.1 对当前主线的意义
当前主线已经有：
- `RunManager`
- `RunStore`
- `WAITING_APPROVAL`
- `approve / resume`

Temporal PoC 不是替换主线，而是提供一个清晰对照：

| 维度 | 当前主线（RunStore + Harness） | Temporal PoC |
|------|-------------------------------|--------------|
| 短会话诊断 | ✅ 足够 | ✅ |
| HITL 审批 | ✅ 已实现 | ✅ |
| 应用层状态机控制 | ✅ 自研 | ❌ 交给 Temporal |
| worker crash 恢复 | ⚠️ 需要自管 | ✅ 原生支持 |
| 长任务 durable execution | ⚠️ 复杂度会持续上升 | ✅ 更自然 |

### 4.2 是否建议立即切主线
**不建议立刻切主线。**

当前更合理的判断是：
- 短会话诊断：继续用自研 `Harness + RunStore`
- 真长任务 / 定时等待 / 多次审批：保留 Temporal 作为下一阶段方向

---

## 五、可讲的面试口径

> 当前主线采用自研 Harness + RunStore，已经足够支撑短会话诊断和 HITL 审批；同时我做了一个 Temporal PoC，验证审批型长任务在独立 worker 被 kill 后可以恢复并继续完成。所以我不仅知道怎么把 Agent 跑起来，也知道什么时候应用层状态机够用，什么时候应该升级到 durable workflow。

---

## 六、相关文件

- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/labs/temporal/ApprovalWorkflow.java`
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/labs/temporal/ApprovalWorkflowImpl.java`
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/labs/temporal/ApprovalActivities.java`
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/labs/temporal/ApprovalActivitiesImpl.java`
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/labs/temporal/TemporalWorkerBootstrap.java`
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/labs/temporal/TemporalApprovalClient.java`
- `my-xhs-ai-app/src/test/java/com/myxhs/ai/app/labs/temporal/TemporalApprovalWorkflowTest.java`
- `my-xhs-ai/demo-temporal-restart.sh`
