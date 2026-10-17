---
name: unit-test-ci-java
stage: ⑤ CI 与质量门禁
description: 机械化执行全量质量门禁——静态分析、竞态检测、架构约束、全量测试、安全扫描
---

# CI 与质量门禁技能（unit-test-ci）— Java 版

> **流水线阶段**: ⑤ 第五步
> **输入**: 通过 ④ 评审的完整变更
> **出口门禁**: 所有 CI 阶段全绿

### 核心规则（一句话摘要）

> **机械化执行：编译 → 静态分析 → 竞态检测 → 架构约束 → 单元测试+覆盖率 → 安全扫描。任一检查失败即红灯，不放过。**

---

## 前置检查

1. **定位目标 change**（按 `.harness/rules/变更定位规则.md`）：
   - 扫描 `.harness/changes/*/change.md`，过滤 `status: ci`
   - 用户已指定 `<id>` → 校验该 change 状态是否为 `ci`，否则报错
   - 恰好 1 个 → 自动选中
   - 0 个 → 报错：无处于 `ci` 状态的 change，退回 ④ expert-reviewer
   - ≥ 2 个 → **列出候选清单（id + 标题 + 摘要），停下请用户选择**，不得擅自默认取第一个
2. **迭代协议证据门禁（若走 iterations）**：`test/report.md` 必须有真实执行证据（覆盖率/通过数），无证据 → 退回 ③ unit-test-write 补齐
3. 缺前置 → 退回 ④ expert-reviewer

---

## 1. 职责

你是质量门禁的机械化执行者。把约束从"靠人记"变成"靠机器验"。任一检查失败即红灯，禁止放行。

---

## 2. Java CI 流水线

```
stage-1  编译检查
  mvn -o dependency:tree
  mvn -o -q -pl <module> -am compile

stage-2  静态分析
  静态分析命令（实际合并到 LINT_CMD）
  mvn -o checkstyle:check（未验证：插件待接入）          # 风格：文件≤500行/方法≤50行/圈复杂度≤10
  # 静态分析 + 重复代码（如 Checkstyle + PMD + SpotBugs（待接入） 的对应检查）

stage-3  竞态检测
  mvn -o -q -pl <module> test 空   # 检测数据竞争（空 为空可跳过）

stage-4  架构约束
  （未配置：建议接入 ArchUnit）
  # 模块依赖方向正确、分层不越级等

stage-5  单元测试 + 覆盖率
  mvn -o -q -pl <module> test
  mvn -o jacoco:report（未验证：插件待接入）             # 核心逻辑覆盖率 ≥80%

stage-6  安全扫描
  （未配置）          # 依赖版本一致、禁快照版本
  扫描硬编码密钥
  grep -nE 'DROP\s+(DATABASE|TABLE|SCHEMA|INDEX|VIEW|PROCEDURE|FUNCTION|TRIGGER|EVENT)' --include='*.java' -r . || true  # 扫描危险 DDL
  grep -nE 'TRUNCATE\s+(TABLE|DATABASE)' --include='*.java' -r . || true  # 扫描危险 DML
  grep -nE 'rm\s+-rf|rmdir\s+/[sz]|del\s+/[fs]|Remove-Item\s+-Recurse|os\.RemoveAll|File\.Delete' --include='*.java' -r . || true  # 扫描破坏性文件操作
  grep -nE 'password\s*=|secret\s*=|token\s*=|api.key\s*=|jdbc:mysql://|jdbc:postgresql://' --include='*.java' -r . | grep -v '\$\{\|env\|ENV\|getenv\|os\.Getenv\|System\.getenv' || true  # 扫描硬编码凭据（排除环境变量引用）

stage-7  集成测试（PR 时）
  （未配置：项目无独立集成测试阶段）
```

---

## 3. 门禁判定表

| 检查项 | 通过标准 | 失败处理 |
|--------|---------|---------|
| 编译 | 0 error | 退回 ② 编码 |
| 静态分析 | 0 violation | 退回 ② 编码 |
| 竞态检测 | 0 data race | 退回 ②（严重） |
| 架构约束 | 全部通过 | 退回 ②（架构腐化，严重） |
| 单元测试 | 0 failed | 退回 ② / ③ |
| 覆盖率 | 核心 ≥80% | 退回 ③ 补测试 |
| 安全扫描（含危险代码扫描） | 0 命中 | 退回 ②（安全红线） |

---

## 4. 完成标志

全绿 → 更新 `change.md` 状态 `ci → verifying`，进入 ⑥ 部署验证。
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
