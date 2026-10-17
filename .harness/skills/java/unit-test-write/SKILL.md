---
name: unit-test-write-java
stage: ③ 单元测试编写
description: 为实现代码编写 Java 单元测试，核心逻辑覆盖率 ≥80%，覆盖 AC 与边界
---

# 单元测试编写技能（unit-test-write）— Java 版

> **流水线阶段**: ③ 第三步
> **输入**: ② 阶段的实现代码 + change.md
> **出口门禁**: 测试通过 · 静态检查零新增告警 · 核心逻辑覆盖率 ≥80% · 覆盖全部 AC、边界与降级路径
> **测试框架**: JUnit 5 + Mockito + AssertJ

### 核心规则（一句话摘要）

> **每条 AC 至少一个测试，每条降级路径一个专门测试，核心覆盖率 ≥80%。禁止只测 happy path。**

---

## 1. 职责

你是测试驱动质量的工程师。你的职责分两段：编码前让失败测试先存在，编码后补齐边界、降级、回归测试。目标不是为覆盖率而测，而是验证每条 AC 与每个边界、每条降级路径真实成立。

---

## 2. 前置检查

1. **定位目标 change**（按 `.harness/rules/变更定位规则.md`）：
   - 扫描 `.harness/changes/*/change.md`，过滤 `status: testing`
   - 用户已指定 `<id>` → 校验该 change 状态是否为 `testing`，否则报错
   - 恰好 1 个 → 自动选中
   - 0 个 → 报错：无处于 `testing` 状态的 change，退回 ② coding-skill
   - ≥ 2 个 → **列出候选清单（id + 标题 + 摘要），停下请用户选择**，不得擅自默认取第一个
2. 验证 `change.md` 的 `status: testing` 与编码阶段交付物一致
3. 缺前置 → 退回 ② coding-skill

---

## 3. 工作流程

### Step 1: 梳理测试矩阵
从 `change.md`（默认）或 `.harness/iterations/<id>/test/design.md`（若走迭代协议）提取所有 AC 与边界情况，建立"测试点 → 测试用例"映射表，确保无遗漏。

### Step 2: 分层测试
| 层级 | 内容 |
|------|------|
| Spec Tests | 每条 AC 的 happy path / 核心行为 |
| Boundary Tests | 空/零值、极值、并发、重复执行；**性质类**：对称/交换律（排序、归一化、会话 ID 拼接等函数**必测反序输入**）、幂等性（closeOnce/开关重入/重复执行）、往返一致性（编码↔解码、归一↔解析） |
| Failure/Fallback Tests | 依赖故障时的降级逻辑（超时/限频/API 不可用） |
| Regression Tests | 已修复 bug / 历史故障回归 |

### Step 3: 编写测试
- 命名: `method_should_x_when_y`
- 用 JUnit 5 + Mockito + AssertJ + Mockito，断言用 AssertJ
- 每个测试**单一断言意图**，Arrange-Act-Assert 三段清晰
- **降级逻辑必须实测**，不满足于"应该能处理"

### Step 4: Mock 原则
- Mock 外部依赖（LLM、第三方 API、数据库、缓存）
- **禁止 Mock 自己写的业务类**（那样测的是 Mock 不是逻辑）
- 用 Mockito 提供可控的故障场景（超时、异常、空返回）

### Step 5: 覆盖率核验
- 跑 `mvn -o jacoco:report（未验证：插件待接入）`，核心逻辑 ≥80%
- 覆盖率不足 → 补测试，而非降低标准
- 纯 getter/setter/配置类可豁免

### Step 5.5: 阶段出口全量门禁复核
- 跑 `mvn -o checkstyle:check（未验证：插件待接入）`（涉及包）——**零新增告警**（**含本阶段新增的全部测试文件**；本阶段是 vet 的最后一道门禁，测试文件里的死代码/自赋值等由 vet 兜住，AC-1 类"零新增告警"门槛以此为准）
- 跑 `mvn -o -q -pl <module> test 空` 检测竞态（空 为空可跳过，在 change.md 标注待 amd64 CI 验证）

### Step 6: 诊断辅助
如果遇到无法稳定复现的 Bug 或非确定性故障，建议运行 `/diagnosing-bugs` 进行 6 阶段诊断。
诊断结果记录到 `.harness/changes/<id>/diagnosis.md`。

---

## 4. 测试质量红线

- ❌ 只测 happy path，边界靠注释"应该能处理"
- ❌ 断言空泛（`assertTrue` 当主断言）
- ❌ 测试间相互依赖、有顺序耦合
- ❌ Mock 自己写的类
- ✅ 每条 AC 至少一个测试
- ✅ 每个边界情况至少一个测试
- ✅ 降级逻辑有专门测试

---

## 5. 完成标志

`mvn -o checkstyle:check（未验证：插件待接入）`（涉及包）零新增告警 + `mvn -o -q -pl <module> test 空` + `mvn -o jacoco:report（未验证：插件待接入）` 全绿 + 覆盖率达标 → 更新 `change.md` 状态 `testing → reviewing`，进入 ④ 专家评审。
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
