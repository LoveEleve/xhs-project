---
iteration: ITER-004
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：本文件仅可引用已 confirmed 的 prd.md / solution.md；本文件 confirmed 前不得进入 coding
---

# 测试设计: 商家维度订单查询（异构索引）

> 机器可读的迭代协议产物之一。定义**验证策略与观测方式**，是 test/cases.md 与 test/report.md 的上游。

## 验证策略

- **单测为主**（项目规则：无运行时环境；编译 + 单测为验证口径）：
  - 消费者：`SellerSubOrderIndexSyncConsumerTest`（canal 消息 JSON → ES 客户端 mock 断言 upsert/delete；非法消息跳过；ES 异常 → Redis 补偿键 + 上抛）——复用 `NoteIndexSyncConsumerTest` 形态；
  - 列表 API：`SellerOrderSearchControllerTest`（standalone MockMvc：sellerId 必填 400 / 非 Admin-Call 403 / 过滤/排序/分页参数 → ES 查询构造断言）；
  - 详情（P1）：`OrderService` 解析/路由单测（subOrderNo→orderNo→映射→userId→selectBySubOrderNo；映射缺失报错）；
  - 回填（P1）：Job 幂等/游标分页单测（mock Feign 扫描 + ES bulk）。
- **集成/E2E**：不做（无运行时）；canal 配置以文件核验（filter/slaveId/topic/partitionHash）。
- **手工验证**：deploy compose 挂载核验；部署顺序说明核验。

## AC → 测试方法映射

| AC | 测试方法 | 层级 | 观测方式 | 证据类型 |
|----|---------|------|---------|---------|
| AC-1 | canal 配置核验（filter/slaveId/topic/hash 16 条） | 手工 | 文件比对 | 检查记录 |
| AC-2 | 消费者单测（INSERT/UPDATE upsert、DELETE 删除、deleted=1 删除、非法跳过） | 单测 | ES 客户端调用断言 | 断言结果 |
| AC-3 | 消费者单测（ES 异常 → Redis Set + 上抛） | 单测 | Redis 调用断言 + 异常 | 断言结果 |
| AC-4 | API 单测（sellerId 缺失 400；非 Admin-Call 403） | 单测 | HTTP 响应断言 | 断言结果 |
| AC-5 | API 单测（过滤/排序/分页 → ES 查询构造） | 单测 | 查询对象断言 | 断言结果 |
| AC-6 | 守卫注释核验（消费者/API） | 手工 | 代码核验 | 检查记录 |
| AC-7 | 详情单测（解析/路由/映射缺失） | 单测 | 调用断言 + 错误断言 | 断言结果 |
| AC-8 | 回填单测（幂等重入/游标分页） | 单测 | ES bulk 断言 | 断言结果 |
| AC-9 | 全模块回归（六模块 + search）+ 配置核验 | 单测 | 全绿 | 测试报告 |

## 动态事实接入

| 事实 | 来源 | 用途 |
|------|------|------|
| canal 消息格式（{database,table,type,data[],ts}） | `ProductIndexSyncConsumer`（既有消费者，已核） | 消息解析基准 |
| 物理表命名（my_xhs_order_0..3 / t_sub_order_0..3） | `sharding-config.yaml`（已核） | canal filter/partitionHash |
| slaveId 占用（1001-1003） | canal 三 instance 配置（已核） | 新 instance=1004 |
| 子单号格式（orderNo-N） | `OrderTransactionService`（ITER-001，已核） | 详情解析 |

## 证据回填约定

- 每次执行回填：模块 / 测试类 / 断言结果 / 执行时间
- 以 Maven 测试输出（surefire 摘要）为证据
