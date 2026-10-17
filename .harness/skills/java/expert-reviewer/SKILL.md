---
name: expert-reviewer-java
stage: ④ 专家评审
description: 双轴并行代码审查（Spec 需求匹配 + Standards 规范合规），0 个严重问题方可放行
---

# 专家评审技能（expert-reviewer）— Java 版

> **流水线阶段**: ④ 第四步
> **输入**: 代码 + 单元测试
> **产出**: `.harness/changes/<id>/review.md`
> **出口门禁**: 0 个 🔴 严重问题

### 核心规则（一句话摘要）

> **不手下留情。双轴并行审查：Spec 轴查"是否做对了事"，Standards 轴查"是否合规做事"。两轴独立报告，防止一个轴掩盖另一个轴。**

---

## 前置检查

1. **定位目标 change**（按 `.harness/rules/变更定位规则.md`）：
   - 扫描 `.harness/changes/*/change.md`，过滤 `status: reviewing`
   - 用户已指定 `<id>` → 校验该 change 状态是否为 `reviewing`，否则报错
   - 恰好 1 个 → 自动选中
   - 0 个 → 报错：无处于 `reviewing` 状态的 change，退回 ③ unit-test-write
   - ≥ 2 个 → **列出候选清单（id + 标题 + 摘要），停下请用户选择**，不得擅自默认取第一个
2. **锁定审查范围**（确保每次审查同一份代码）：
   - **规则**：使用 `git diff HEAD` 作为审查范围，包含工作区未提交的变更
   - 如果 `git diff HEAD` 为空，报错：无变更可审查，退回 ③ unit-test-write
   - 在报告开头明确标注审查范围（文件数、增减行数、分支名）
3. 验证 `review.md` 不存在或可覆盖
4. 缺前置 → 退回 ③ unit-test-write

---

## 领域技能加载（混合模式）

expert-reviewer 支持混合加载领域专家技能，自动根据代码特征引入对应的专业知识：

| 检测条件 | 加载技能 | 封装类型 | 存放位置 |
|---------|---------|---------|---------|
| RedisTemplate / Jedis / Lettuce / `redis client` 调用 | `redis-cache-wrapper` | 多级缓存封装（穿透/击穿/雪崩防护） | `harness-core/skills/` |
| `V*__*.sql` / `migrations/*.sql` | `database-migration-toolkit` | 迁移工具（迁移模板/回滚/回填辅助） | `harness-core/skills/` |
| 消息队列生产者/消费者代码 | `kafka-toolkit` / `rocketmq-toolkit` | 消息工具（消费者封装/DLQ/监控） | `harness-core/skills/` |
| `*.yaml` / `k8s/` 清单 | `k8s-release-toolkit` | 发布工具（Deployment模板/HPA/探针/灰度） | `harness-core/skills/` |
| 性能异常/线程 dump/GC 日志 | `performance-toolkit` | 诊断工具（火焰图/GC分析/慢SQL/线程dump） | `harness-core/skills/` |
| 安全敏感模式（密钥/注入/鉴权/URL） | `security-toolkit` | 安全工具（脱敏/加密/校验/XSS/鉴权） | `harness-core/skills/` |
| HTTP 客户端调用 | `http-client-toolkit` | HTTP 工具（连接池/超时/重试/熔断/traceId） | `harness-core/skills/` |
| 日志配置/日志打印代码 | `logging-toolkit` | 日志工具（traceId/MDC/脱敏/动态级别） | `harness-core/skills/` |
| cron 表达式 / 定时任务 | `scheduler-toolkit` | 调度工具（分布式锁/幂等/重试/补偿） | `harness-core/skills/` |
| OSS/S3/MinIO/COS 客户端代码 | `oss-toolkit` | 存储工具（统一接口/分片上传/预签名/CDN） | `harness-core/skills/` |
| Excel 导入导出代码 | `excel-toolkit` | Excel 工具（模板导出/大数据量/导入校验） | `harness-core/skills/` |
| 事件发布/订阅/EventBus | `eventbus-toolkit` | 事件总线工具（同步/异步/事务事件/追踪） | `harness-core/skills/` |

**语言/框架专属技能**（如 `java-code-review`、`spring-api-convention`、`mybatis-toolkit` 等，存放在语言包 `harness-{lang}/skills/` 中，按 `.harness/skills/{lang}/` 部署）同样按检测条件注入。

**加载方式**：检测到匹配条件时，将对应技能的完整检查清单作为"领域知识包"注入 Standards 轴，**不替代既有的 10 维度审查**，而是作为维度 4~6（编码规范/代码质量/安全）的**补充细则**。

**独立命令**：每个技能也可通过 `/skill-name` 直接调用（如 `/redis-cache-wrapper`、`/kafka-toolkit`、`/security-toolkit`、`/http-client-toolkit`、`/logging-toolkit`、`/scheduler-toolkit`、`/oss-toolkit`、`/excel-toolkit`、`/eventbus-toolkit`），绕过 expert-reviewer 主流程，适合专项代码生成或排查场景。

---

## 审查设计

本技能采用**双轴并行子智能体**架构：

- **Spec 轴**：对照 `change.md` 检查代码是否实现了该做的，且没做不该做的
- **Standards 轴**：对照 `.harness/rules/` 和代码质量基线，检查代码是否合规

两轴由独立子智能体运行，最终汇总报告。**不合并、不排序**——分离报告就是为了防止一个轴掩盖另一个轴（代码完全合规范但实现了错的东西 → Standards 过 Spec 挂；代码照做了但破坏约定 → Spec 过 Standards 挂）。

---

## 1. Spec 轴（需求匹配）

### 维度 1: 功能完整性（对照 change.md / iterations 协议）
- [ ] 实现了 **所有** AC？
- [ ] 处理了 **所有** 边界情况？
- [ ] 有 scope creep（未要求的代码/功能）？
- [ ] 实现与设计约束一致？
- [ ] （若走 iterations）`prd.md` 每条 REQ 都有 `solution.md` 任务承接、每条 AC 都有 `test/cases.md` 的 TC 覆盖？

### 维度 2: SDD 合规
- [ ] `change.md`（或 `prd.md`）是否足够构成规格真相源？
- [ ] 实现是否严格对齐 change.md / prd.md，而非擅自扩 scope？
- [ ] （若走 iterations）REQ/AC/TC 编号一致、无孤立 TC（TC 无 AC 归属）、无缺失 AC 的 TC？

---

## 2. Standards 轴（规范合规）

### 维度 3: 架构合规（对照 工程结构.md）
- [ ] 模块间仅通过接口通信？依赖方向正确（按 `工程结构.md` 的依赖方向约定）？
- [ ] 新架构决策是否补了 ADR？

### 维度 4: 编码规范（对照 编码规范.md）
- [ ] 命名、Javadoc 合规？
- [ ] 无硬编码密钥 / 魔法值？
- [ ] LLM/外部调用有超时+重试+限频+降级？
- [ ] 异常/错误处理完整（不吞、不空 catch / `_ = fn()` / unwrap）？

### 维度 5: 代码质量
- [ ] 函数/方法 ≤50 行 / 文件 ≤500 行 / 圈复杂度 ≤10？
- [ ] 无未使用 import/变量/方法？
- [ ] 无重复代码块？
- [ ] 日志级别合适？

**代码味道基线**（Fowler, _Refactoring_ ch.3）——以下味道是"可能性判断"而非硬违规，且仓库规范优先于基线：

| 味道 | 检查点 | 说明 |
|------|--------|------|
| Mysterious Name | 函数/变量名是否揭示意图 | 想不到好名字意味着设计不清 |
| Duplicated Code | 相同逻辑形状出现在多处 | 提取共享部分 |
| Feature Envy | 方法取别人数据多于自己数据 | 把方法移到它羡慕的数据上 |
| Data Clumps | 相同字段/参数总一起出现 | 打包成新类型 |
| Primitive Obsession | 原始类型代表领域概念 | 给概念一个专有类型 |
| Repeated Switches | 同一类型的 switch/if 链重复 | 多态或映射表 |
| Shotgun Surgery | 一个逻辑改动散落多处 | 聚集到同一模块 |
| Divergent Change | 一个文件因多种原因被改 | 拆分为单一职责 |
| Speculative Generality | 为未来需求提前加的抽象 | 删掉，直到真有需求 |
| Message Chains | 长链 a.b().c().d() | 在第一个对象上隐藏路径 |
| Middle Man | 类/函数只是转发 | 砍掉，直接调目标 |
| Refused Bequest | 子类大部分继承被重写/忽略 | 放弃继承，用组合 |

> **规则**：仓库规范优先于味道基线；味道是标签不是硬违规；工具已能查的跳过。

### 维度 6: 安全
- [ ] 外部输入已校验？
- [ ] 日志无敏感信息（Token/密码/隐私）？
- [ ] **无危险数据库操作**（DROP / TRUNCATE / ALTER / DELETE 等 DDL/DML 是否经过了审批流程）？
- [ ] **无破坏性文件操作**（`rm -rf`、`os.RemoveAll`、`File.Delete` 等递归删除是否受控）？
- [ ] **无硬编码生产环境密钥**（数据库连接串、API Token、Secret 是否通过环境变量/配置中心注入）？

---

## 3. 公共维度

### 维度 7: 测试质量
- [ ] 覆盖所有 AC 与边界？
- [ ] 测了降级逻辑？
- [ ] 核心覆盖率 ≥80%？
- [ ] Mock 合理（不 Mock 自己写的类）？

### 维度 8: TDD 合规
- [ ] 核心业务逻辑是否体现测试先行？
- [ ] 测试是否覆盖 AC / 边界 / 降级路径？

### 维度 9: 流程合规
- [ ] 变更范围与 change.md 一致？
- [ ] 无"偷偷"夹带的额外变更？

### 维度 10: 领域语言一致性（对照 CONTEXT.md）
- [ ] 代码中的术语与 `.harness/CONTEXT.md` 一致？
- [ ] 新引入的领域概念是否已在 CONTEXT.md 中定义？

---

## 4. 输出格式（写入 review.md）

> **⛔ 强制规则：无论审查结果是否有 🔴 严重问题，都必须将完整报告写入 `.harness/changes/<id>/review.md`。**
> **未写入 review.md 不得进入下一步。**

```markdown
# 📋 评审报告: C-NNN

## 总览
- 审查范围: `git diff HEAD` — N 个文件，+N/-N 行
- 分支: <branch-name>
- 🔴 严重问题: N（必须修复）
- 🟡 建议改进: N（推荐修复）
- 🟢 通过项: N

## Spec 轴报告（需求匹配）
### 🔴 严重问题
### 🟡 建议改进

## Standards 轴报告（规范合规）
### 🔴 严重问题
### 🟡 建议改进

## 测试质量
### 🔴 严重问题
### 🟡 建议改进

## 结论
<一句话结论>
```

---

## 5. 严重程度标准

| 级别 | 定义 | 示例 |
|------|------|------|
| 🔴 严重 | 功能异常 / 安全漏洞 / 架构腐化 | 模块间直接调用内部方法，Missing AC |
| 🟡 建议 | 不立即出错但影响可维护/性能 | 方法过长、缺 Javadoc、TDD 痕迹不足 |

---

### 架构审查建议
如果评审发现架构层面的摩擦信号（浅模块、耦合过深、接缝缺失、测试困难），建议运行 `/arch-review` 进行深入架构体检。
如果发现领域语言不一致，建议调用 `domain-modeling` 技能修正术语。

---

## 6. 完成标志

无论审查结果如何，**必须先完成**：

1. ✅ 将完整报告写入 `.harness/changes/<id>/review.md`
2. 然后根据检查结果执行分支：
   - **0 个 🔴** → 更新 `change.md` 状态 `reviewing → ci`，进入 ⑤ CI 门禁
   - **有 🔴** → 退回 ② 编码实现修复（review.md 作为修复参考依据）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
