---
iteration: ITER-011
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
---

# 测试设计: 搜索模块生产化改造

## 验证策略

- **单测为主**（无运行时；ES/Redis/JDBC 全 mock；删 target/test-classes 强制全量重编）：
  - **补偿收敛矩阵**：version_conflict 豁免（计成功不回填）/ 真失败逐项回填 / 构建失败丢弃不回填 / 混合批
  - **版本域**：note flat 断言 ts 域 + ExternalGte
  - **别名矩阵**：resolve（别名/legacy/缺失）/ next 版本号 / switch 调用 / 删旧；重建流程（建→写→切→重放→删）
  - **墓碑**：物理删墓碑（版本+deletedAt）；pruneOldTombstones 的 delete_by_query 条件
  - **DLQ 重放**：5 组重投/超限/失败不吞
  - **限流**：注解核验
- 集成/E2E：不做。

## AC → 测试方法映射

| AC | 测试方法 | 层级 | 观测方式 |
|----|---------|------|---------|
| AC-1 | 收敛矩阵单测 | 单测 | bulk 结果/回填集合断言 |
| AC-2 | flat 版本断言 | 单测 | IndexRequest version/versionType |
| AC-3 | 别名矩阵 + 重建流程单测 | 单测 | ES 调用序列断言 |
| AC-4 | 墓碑/清理单测 | 单测 | IndexRequest 字段 / delete_by_query |
| AC-5 | DLQ 单测 + ThreadLocal 核验 | 单测 | 重投目标/属性 |
| AC-6 | 限流/注释核验 | 单测+手工 | 注解/grep |
| AC-7 | 十一模块回归 | 单测 | 全绿 |

## 动态事实接入

| 事实 | 来源 | 用途 |
|------|------|------|
| rebuild 的 version_conflict 豁免（"关键配套"） | IndexRebuildJob:400-410（已核） | 补偿对齐基准 |
| product flat 正确实现（timestamp+Gte） | ProductIndexSyncConsumer:330-337（已核） | note flat 对齐基准 |
| 失败集 re-SADD 恒真 + 1h 续期 | IncrementalIndexSyncJob:132-142（已核） | 循环证据 |
| IndexInitializer mapping 常量（4 索引） | IndexInitializer:75+（已核） | 别名新索引复用 |
| 5 消费组名 | 各消费者注解（已核） | DLQ 重放 topic |
| 物理删现状（note 物理删 / product 墓碑） | 两消费者（已核） | 墓碑统一基准 |
