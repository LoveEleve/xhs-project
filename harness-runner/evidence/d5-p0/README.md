# D5 证据包 · P0 加固（并发防护 + HITL 人工确认）

> 生成: 2026-09-24 ｜ 变更: chg-94ed7673 ｜ 项目: coverage-demo（真实 Maven）
> 来源：`专题-深度自拷打.md` 弱点清单 P0-1 / P0-2 的修复落地

## 1. 修了什么

| # | 弱点（自拷打） | 修复 |
|---|---|---|
| P0-1 | 乐观锁仅设计：两个推进并发会互相覆盖 | `FileChangeLocks`（跨进程 `FileLock` + 同进程 `Semaphore`）+ `advance/approve` 全程持锁 + `save` 版本前置条件（版本未前进即拒绝） |
| P0-2 | ① 阶段 LLM 规格无人工确认（GIGO） | 新状态 `AWAITING_APPROVAL`：① 阶段通过后**必须人工 approve** 才能进入 ②；未审批的 advance 被拒绝 |

## 2. HITL 流程实录（本证据包）

```
advance #1  HARNESSING  AC 3 条 → PASS → AWAITING_APPROVAL（exit 3：需要人工确认）
advance #2  → 拒绝：等待人工确认（需求分析），请先 approve（exit 3）
approve     → 审批通过，当前阶段: 编码实现（IN_PROGRESS）
advance #3  编码实现  COMPILE 1.9s PASS
advance #4  单测编写  TEST 2.7s PASS
advance #5  专家评审  通过
advance #6  CI 门禁   COMPILE 1.7s + COVERAGE 66.7% ≥ 50.0% PASS
advance #7  部署验证  通过 → DONE
```

事件流证据（`changes/chg-94ed7673/events.jsonl`）：
```json
{"type":"APPROVAL_REQUESTED","stage":"HARNESSING","message":"阶段通过，等待人工确认", ...}
{"type":"APPROVED","stage":"CODING","message":"人工确认通过", ...}
```

## 3. 并发防护证据（单测）

- `FileChangeLocksTest`：同一变更第二次 acquire 被拒（并发冲突）；不同变更互不阻塞
- `ChangeEngineTest.advanceFailsFastWhenChangeLockHeld`：锁被持有期间 advance 立即报并发冲突
- `FileStoreTest.rejectsStaleVersionWrite`：版本未前进的写入被拒绝（文件版 CAS）

## 4. 测试基线（本次修复后）

domain 38 ｜ assets 12 ｜ llm 14 ｜ gate 36+1IT ｜ engine 23 ｜ app 6 ｜ **合计 130 + 1IT**

## 5. 复现步骤

```bash
JAR=harness-runner-app/target/harness-runner-app-0.1.0-SNAPSHOT.jar
WS=evidence/d5-p0
java -jar $JAR create --requirement "..." --project /tmp/opencode/coverage-demo \
  --project-id coverage-demo --min-coverage 0.5 --workspace $WS
java -jar $JAR advance --change chg-94ed7673 --workspace $WS   # ① → AWAITING_APPROVAL
java -jar $JAR advance --change chg-94ed7673 --workspace $WS   # 被拒
java -jar $JAR approve --change chg-94ed7673 --workspace $WS   # 人工确认
# 之后 advance ×5 到 DONE
```

## 6. 边界说明

- 锁是**单机 advisory 文件锁**（POSIX flock 语义），跨主机不生效；多实例部署需 DB 锁/分布式锁（设计）。
- HITL 当前只覆盖 ① 阶段；④ 评审的人工确认点未实现（设计）。
- approve 目前不要求"审阅产物"证据（无签名/无 diff），只记录确认事件。
