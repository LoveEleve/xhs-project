---
iteration: ITER-005
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：本文件仅可引用已 confirmed 的 prd.md / solution.md；本文件 confirmed 前不得进入 coding
---

# 测试设计: 商品模块生产化改造

> 机器可读的迭代协议产物之一。定义**验证策略与观测方式**，是 test/cases.md 与 test/report.md 的上游。

## 验证策略

- **单测为主**（项目规则：无运行时环境；编译 + 单测为验证口径）：
  - 业务正确性：`SpuServiceTest`（默认下架 + 空商品不可见路径）、`SkuServiceTest`（价格负数/0）；
  - 补偿：`InventoryInitRepairJobTest`（pending set 记录/重试/移除/幂等）——SkuService 侧验证失败入 set；
  - Canal 消费：`ProductCacheCanalEvictConsumerTest`（t_spu/t_sku/DELETE/deleted=1/非法消息——复用既有消费者测试形态）；
  - 一致性：双删移除后 `SpuServiceTest` 既有用例通过 + 全仓 grep 零残留；
  - 可观测：指标计数断言（可选，Mockito mock MeterRegistry）或代码核验；日志降噪代码核验；
  - 质量：N1/N2 用 `verify(times(1))` 断言单查；布隆重建返回语义；
  - 分类刷新：`CategoryServiceTest` 新增。
- **集成/E2E**：不做（无运行时）；seed 脚本以语法 + 流程 review 口径。
- **手工验证**：§7 回写核验；指标命名核验。

## AC → 测试方法映射

| AC | 测试方法 | 层级 | 观测方式 | 证据类型 |
|----|---------|------|---------|---------|
| AC-1 | createSpu 默认下架单测 + 空商品不可见（列表筛选/详情非上架） | 单测 | status 断言 | 断言结果 |
| AC-2 | seed 脚本 review（显式上架步骤） | 手工 | 脚本 diff | 检查记录 |
| AC-3 | 价格校验单测（负数拒绝/0 合法） | 单测 | BizException/通过 | 断言结果 |
| AC-4 | 补偿 Job 单测（记录→重试→移除；SKU 缺失移除；"已初始化"视为成功） | 单测 | Redis set 操作断言 + initStock 调用 | 断言结果 |
| AC-5 | Canal 消费者单测（t_spu/t_sku/删除/非法） | 单测 | Redis delete 调用断言 | 断言结果 |
| AC-6 | 双删移除：grep 零残留 + 既有测试通过 | 单测 | 全仓 grep + 测试 | 断言结果 |
| AC-7 | 分类刷新单测（evict + 重建节点数） | 单测 | 返回断言 | 断言结果 |
| AC-8 | 指标/日志：代码核验（+可选计数断言） | 手工/单测 | 代码核验 | 检查记录 |
| AC-9 | N1/N2 单查（verify times(1)）+ 重建异步返回 | 单测 | 调用次数断言 | 断言结果 |
| AC-10 | 七模块全量回归 | 单测 | 全绿 | 测试报告 |

## 动态事实接入

| 事实 | 来源 | 用途 |
|------|------|------|
| canal 消息格式（{table,type,data[],ts}） | `ProductIndexSyncConsumer`（既有，已核） | 新消费者解析基准 |
| `PRODUCT_INDEX_TOPIC` 覆盖 t_spu/t_sku（partitionHash 按 id） | product_instance 配置（已核） | 零部署改动依据 |
| 双删引用面（SpuService 3 处） | 全仓 grep（已核） | 移除范围 |
| SQL 种子显式 status=1 | test-data-init.sql（已核） | lead-1 影响面收窄 |

## 证据回填约定

- 每次执行回填：模块 / 测试类 / 断言结果 / 执行时间
- 以 Maven 测试输出（surefire 摘要）为证据
