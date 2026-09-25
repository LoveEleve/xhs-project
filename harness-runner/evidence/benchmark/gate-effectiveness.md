# 门禁有效性基准（故障注入）

> 生成: 2026-09-25
> 方法：对最小 Maven 工程注入 8 类故障/对照场景（7 类应拦截 + 1 类对照），另跑 5 项基线（含增量覆盖率基线）验证无假阳性；全部真实执行 `mvn`。

**结论**：故障用例 7，检出 7（100%），基线全绿 5/5

| 场景 | 门禁 | 期望 | 实际 | 判定 | 原因 | 日志 |
|---|---|---|---|---|---|---|
| baseline_compile | COMPILE | 绿 | 绿 | 符合 | exitCode=0 | logs/compile-1790310291528-1.log |
| baseline_test | TEST | 绿 | 绿 | 符合 | exitCode=0 | logs/test-1790310293344-2.log |
| baseline_coverage | COVERAGE | 绿 | 绿 | 符合 | 行覆盖率 100.0% ≥ 阈值 80.0% | logs/coverage-1790310296282-3.log |
| baseline_mutation | MUTATION | 绿 | 绿 | 符合 | 变异得分 100.0% ≥ 阈值 80.0%（KILLED=4, SURVIVED=0, NO_COVERAGE=0） | logs/mutation-1790310300347-4.log |
| compile_error | COMPILE | 红 | 红 | 检出 | exitCode=1 | logs/compile-1790310304922-5.log |
| failing_test | TEST | 红 | 红 | 检出 | exitCode=1（testsRun=3, failures=1, errors=0） | logs/test-1790310306861-6.log |
| no_tests | TEST | 红 | 红 | 检出 | exitCode=0，未解析到 surefire 汇总，测试证据缺失 | logs/test-1790310309811-7.log |
| all_tests_skipped | TEST | 红 | 红 | 检出 | exitCode=0，测试全部被跳过（有效执行 0/2），测试证据不足 | logs/test-1790310311786-8.log |
| fake_test | TEST | 绿 | 绿 | 对照（边界说明） | exitCode=0 | logs/test-1790310314713-9.log |
| fake_test_mutation | MUTATION | 红 | 红 | 检出 | 变异得分 0.0% 低于阈值 80.0%（KILLED=0, SURVIVED=0, NO_COVERAGE=4） | logs/mutation-1790310317639-10.log |
| coverage_drop | COVERAGE | 红 | 红 | 检出 | 行覆盖率 23.1% 低于阈值 80.0% | logs/coverage-1790310320868-11.log |
| diff_coverage_baseline | DIFF_COVERAGE | 绿 | 绿 | 符合 | 增量覆盖率 100.0% ≥ 阈值 80.0%（覆盖 1/1 变更可执行行） | logs/diff_coverage-1790310324413-12.log |
| diff_coverage_drop | DIFF_COVERAGE | 红 | 红 | 检出 | 增量覆盖率 0.0% 低于阈值 80.0%（覆盖 0/10 变更可执行行） | logs/diff_coverage-1790310327965-13.log |

## 边界说明

- `fake_test` 对照：TEST 门禁只验证"测试发生"（汇总 + 有效执行数 > 0），不验证断言有效性 → 绿；这是设计边界，不是缺陷。
- `fake_test_mutation`：MUTATION 门禁用变异得分 `KILLED / (KILLED + SURVIVED + NO_COVERAGE)` 判定，假测试全 NO_COVERAGE → 红——假测试漏检由变异门禁闭合。
- 变异门禁前提：项目声明 `pitest-maven` + `pitest-junit5-plugin`；覆盖率门禁无此前提（CLI 注入插件坐标）。
