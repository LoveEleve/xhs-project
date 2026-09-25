# D4 证据包 · xhs 真实工程跑通（含红→绿）

> 生成: 2026-09-24 ｜ 变更: chg-f76e814e ｜ 项目: my-xhs-common（`/data/workspace/source-code/xhs-project/my-xhs-common`）
> 工具: harness-runner CLI 0.1.0-SNAPSHOT（JDK 17 / Maven 3.9）
>
> ⚠️ **流程说明**：本证据生成于 D4（HITL 之前），当时 ① 阶段通过后直接进入 ②。
> D5 引入 `AWAITING_APPROVAL` 后，复现时 ① 之后需先 `approve`（见第 5 节更新后的命令序列）。

## 1. 目标

在真实工程上跑 6 阶段流水线，并验证两件核心事：

1. 门禁真的拦得住（注入失败测试 → 红灯 → 状态 FAILED）
2. 修复后能续跑（attempt 2 → 绿灯 → 继续推进到 DONE）

## 2. 关键结果

| 阶段 | 尝试 | 结果 | 关键数据 | 耗时 |
|---|---|---|---|---|
| ① HARNESSING | #1 | PASS | AC_TESTABLE 3 条（fake provider，prompt 全留档） | 1 ms |
| ② CODING | #1 | PASS | COMPILE exitCode=0 | 3.2 s |
| ③ TEST_WRITE | #1 | **FAIL（红）** | testsRun=94, failures=1（注入失败测试） | 11.5 s |
| ③ TEST_WRITE | #2 | PASS（绿） | exitCode=0 | 10.9 s |
| ④ REVIEW | #1 | PASS | 无可执行门禁（LLM 阶段，圈 3 未实现） | - |
| ⑤ CI | #1 | PASS | COMPILE 3.4s + COVERAGE 行 22.0% ≥ 阈值 20.0% | 14.8 s |
| ⑥ DEPLOY_VERIFY | #1 | PASS | 无可执行门禁 | - |

最终状态：**DONE**（version=9）；7 次阶段运行、7 个产物版本、1 次 LLM 调用。

## 3. 红→绿证据链

| 证据 | 文件 |
|---|---|
| 红灯阶段报告 | `changes/chg-f76e814e/artifacts/test_design/v1-test_design.md` |
| 红灯全量日志（94 测试 1 失败） | `logs/test-1790252375120-1.log` |
| 红灯事件 | `changes/chg-f76e814e/events.jsonl`（STAGE_FAILED → CHANGE_FAILED，随后 attempt=2） |
| 绿灯阶段报告 | `changes/chg-f76e814e/artifacts/test_design/v2-test_design.md` |
| 绿灯全量日志 | `logs/test-1790252419038-1.log` |
| CI 覆盖率门禁 | `changes/chg-f76e814e/artifacts/test_report/v1-test_report.md` |

## 4. LLM ① 留档（fallback 口径）

演示环境未配置 `HARNESS_LLM_API_KEY`，按设计走 fake provider：

- prompt 原文：`changes/chg-f76e814e/llm/llm-33da2901-prompt.txt`
- 原始输出：`changes/chg-f76e814e/llm/llm-33da2901-response.json`
- 调用记录：`changes/chg-f76e814e/llm-calls.jsonl`

真实端点（OpenAI 兼容）已实现并有单测覆盖（本地 HttpServer 验证请求体/鉴权/响应解析/错误处理），配置环境变量即切换：

```bash
export HARNESS_LLM_API_KEY=sk-xxx
export HARNESS_LLM_BASE_URL=https://api.deepseek.com
export HARNESS_LLM_MODEL=deepseek-chat
```

## 5. 复现步骤

```bash
JAR=harness-runner-app/target/harness-runner-app-0.1.0-SNAPSHOT.jar
WS=evidence/d4-xhs
XHS=/data/workspace/source-code/xhs-project/my-xhs-common

java -jar $JAR create --requirement "为 my-xhs-common 补充读写分离路由异常恢复的可测验收条件" \
  --project $XHS --project-id my-xhs-common --min-coverage 0.2 --workspace $WS

java -jar $JAR advance --change chg-f76e814e --workspace $WS   # ① LLM AC → AWAITING_APPROVAL
java -jar $JAR approve --change chg-f76e814e --workspace $WS   # D5 起：人工确认
java -jar $JAR advance --change chg-f76e814e --workspace $WS   # ② 编译

# 注入失败测试（my-xhs-common/src/test/java/com/myxhs/common/FaultInjectionTest.java）
java -jar $JAR advance --change chg-f76e814e --workspace $WS   # ③ 红，exit=1
# 删除注入测试
java -jar $JAR advance --change chg-f76e814e --workspace $WS   # ③ 重试（attempt=2）绿
java -jar $JAR advance --change chg-f76e814e --workspace $WS   # ④ 评审
java -jar $JAR advance --change chg-f76e814e --workspace $WS   # ⑤ CI
java -jar $JAR advance --change chg-f76e814e --workspace $WS   # ⑥ → DONE
```

## 6. 声明

- 未修改 xhs 业务代码；注入测试文件在红→绿演示后删除，无残留。
- 日志为 `mvn -B -ntp` 全量输出（内存截断阈值 256KB 未触发，`truncated=false`）。
