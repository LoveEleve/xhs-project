# 门禁有效性基准（故障注入）证据包

> 生成: 2026-09-25 ｜ 工具: harness-runner（`GateEffectivenessBenchmarkTest`）
> 入口报告：[gate-effectiveness.md](gate-effectiveness.md) ｜ 全量日志：[logs/](logs/)

## 结论（一屏）

- **故障检出 7/7（100%）**：编译错误、失败测试、无测试、全部跳过、覆盖率跌破、假测试（变异门禁）、**新增未测代码（增量覆盖率门禁）**
- **基线 5/5 全绿**：编译 / 测试 / 覆盖率 / 变异 / **增量覆盖率** 均无假阳性
- **对照 1**：`fake_test` 在 TEST 门禁下为绿（设计边界：TEST 只验证"测试发生"），同一故障在 MUTATION 门禁下红（闭合）

## 场景清单

| 场景 | 注入方式 | 门禁 | 结果 |
|---|---|---|---|
| baseline_compile / test / coverage / mutation | 原始工程 | 四关 | 全绿（覆盖 100%、变异 100%） |
| **diff_coverage_baseline** | 新增已测方法 `mul` + 对应测试 | **DIFF_COVERAGE** | 绿（变更行覆盖 1/1 = 100% ≥ 80%） |
| compile_error | Calc.java 追加非法语法 | COMPILE | 红（exitCode=1） |
| failing_test | 注入 fail() 测试 | TEST | 红（testsRun=3, failures=1） |
| no_tests | 移除测试文件 | TEST | 红（无 surefire 汇总） |
| all_tests_skipped | @Disabled 全部测试 | TEST | 红（有效执行 0/2） |
| coverage_drop | Calc 增加 10 个未测方法（全量口径） | COVERAGE | 红（23.1% < 80%） |
| **diff_coverage_drop** | 同上（**增量口径**） | **DIFF_COVERAGE** | 红（变更行覆盖 0/10 = 0% < 80%） |
| fake_test | assertTrue(true) 替换断言 | TEST | 绿（对照：设计边界） |
| **fake_test_mutation** | 同一故障 | **MUTATION** | **红（变异得分 0%，4 个 NO_COVERAGE）** |

## 复现

```bash
HARNESS_BENCH=1 mvn -pl harness-runner-app -am test \
  -Dtest=GateEffectivenessBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false
# 报告默认写入 evidence/benchmark/（可用 HARNESS_BENCH_DIR 覆盖）
```

## 前提与边界

- **变异门禁前提**：被测项目声明 `pitest-maven` + `pitest-junit5-plugin`（JUnit5 生态要求）；覆盖率门禁无此前提。
- **增量覆盖率前提**：被测项目是 git 仓库且提供基线 ref（`GateSpec.diffBase`）；测试代码行不计入（JaCoCo 只插桩主代码）；多包同名文件按路径后缀匹配。
- 变异得分口径：`KILLED / (KILLED + SURVIVED + NO_COVERAGE)`；`TIMED_OUT / NON_VIABLE` 等状态不计入。

## 真实性说明

本基准全部使用真实 Maven 执行（非 mock），日志为 `mvn -B -ntp` 全量输出；
基准开发过程中暴露并修复了两个"假绿"变体（`testsRun - skipped <= 0` 判红；假测试由变异门禁闭合），
并新增增量覆盖率门禁，形成"全量阈值 + 增量阈值 + 变异"三层覆盖判定。
