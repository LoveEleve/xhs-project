---
id: C-004
slug: 商家维度订单查询（异构索引）
status: done
created: 2026-10-17
updated: 2026-10-17
iteration: ITER-004     # 机器可读迭代协议：.harness/iterations/ITER-004-商家维度订单查询/
relations:
  relates_to:
    - slug: docs/学习/订单-讲解/09-拆单
      reason: 商家维度（seller_id）的查询侧补齐——ITER-001 只完成模型+操作（发货），缺"发现"层
    - slug: docs/学习/订单-改造/02-改造后文章修订记录
      reason: 落地后补修订条目（商家侧查询形态）
  depends_on:
    - C-001
---

# C-004 商家维度订单查询（异构索引）

> **完成记录（2026-10-17）**：P0（Canal order_instance + 消费者/ES 索引 + 商家列表 API）+ P1（详情路由解析 + 扫描端点 + 存量回填 Job）全部落地；
> P0 深审查 40 项 + P1 深审查 36 项（0 严重 / 8 修复，含 2 个真 bug）；证据：七模块全量 **376 全绿**（order 122 / search 43 / 其余同基线）+ 全仓编译。
> 详见 `.harness/iterations/ITER-004-商家维度订单查询/test/report.md`（含部署顺序说明：先回填后切流量）。
> 运行时验证（真实 Canal→MQ→ES 全链路）按项目规则后置。

> **立项（2026-10-17）**：ITER-001 引入商家维度（`t_sub_order.seller_id`）后，商家侧只有"操作"（发货，X-Admin-Call）
> 没有"发现"（看单）——子单查找强制带分片键 `userId`（`SubOrderMapper.selectBySubOrderNo(subOrderNo, userId)`），
> 而商家手里只有 subOrderNo/sellerId。**分库分表经典问题**：分片键（user_id）只能伺候买家维度，商家维度必须副索引。
> 方案已过"场景逻辑 + 解法正确性"双验证（候选解法全对比：scatter/映射表/双写/基因法/改分片键 → 淘汰；异构索引为业界标准解）。
> 规划详见 `.harness/iterations/ITER-004-商家维度订单查询/`。

## 用户故事

作为商家（管理角色），我想要按自己的 sellerId 查看子单列表与详情（筛选/排序/分页），以便发货/履约；
作为平台（学习目标），我想要把"分片键只伺候一个维度"的经典问题在 xhs 里做成标准解（Canal 异构索引 + 映射表路由解析）。

## 非目标（Out of Scope）

- 真实商家身份体系（现状：X-Admin-Call 共享令牌 + sellerId 查询参数；演进记录）
- 商品行/买家昵称等文档富化（首版=t_sub_order 单表索引；演进=消费端富化/宽表）
- ES 与 DB 的强一致/对账（最终一致；对账演进记录）
- 发货链路改造（商家端仍按现有 X-User-Id+X-Admin-Call 口径；详情端点的路由解析供未来复用）
- 商家端前端页面（记录）

## 验收标准

- AC-1 ~ AC-9 见 `.harness/iterations/ITER-004-商家维度订单查询/prd.md`（本变更走迭代协议）

## 边界情况

- Canal 只捕增量 → **存量回填必须做**（P1；不是可选项）
- 列表 ES 延迟窗口 → **ES 只做发现；一切操作/终态以 DB+CAS 为准**（正确性制度化）
- sellerId 缺失 → 400 拒绝（**防全量泄漏**——不允许"查所有商家"）
- ES 不可用 → 列表降级报错（不影响任何写操作/发货）
- 子单状态变更（取消/全退/发货）→ binlog 捕获 → ES 收敛

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 列表=ES 单次查询（无 scatter/无 N+1）；binlog→ES 秒级延迟 |
| 可靠性 | 消费者幂等 upsert/delete；失败 ID→Redis 补偿（复用既有模式）；回填幂等可重入 |
| 安全 | X-Admin-Call 守卫 + sellerId 必填；详情端点服务端解析路由（商家不持有买家 userId） |

## 设计约束

- 零新服务：Canal 加 1 个 instance（第 4 个）+ search 域加 1 条索引链路 + order 域加内部端点
- 模式一致性：消费者复用 `ProductIndexSyncConsumer` 形态；路由解析复用 `OrderNoMappingRepository`
- 生产标准：业务/工程/分布式/性能全维度；不改文章正文（修订清单更新至 `docs/学习/订单-改造/02`）

## 契约影响

- REST: 新增 `GET /api/search/seller-order/list`（X-Admin-Call）；P1 新增 `GET /api/order/seller/sub-order/{subOrderNo}`、`GET /api/order/internal/sub-order-scan`
- 模块间通信: Canal→RocketMQ（新 topic `SUB_ORDER_INDEX_TOPIC`）；回填（P1）search→order Feign
- 数据模型: 无 SQL 变更（ES 索引 `seller_sub_order_index`；Redis 失败补偿键）
- 部署: canal `order_instance` 配置 + compose 挂载（部署配置变更）

## 影响面

- 模块/服务: my-xhs-search（主体：消费者/索引/列表 API）、my-xhs-order（P1：详情/扫描端点）、deploy（canal 配置）
- 网关: 零改动（/api/search/** 已路由；新端点不在白名单=自动需鉴权）
- wiki: 接口协议（归档时回流）

## 测试策略

- 先写失败测试：canal 消息解析→ES 操作（INSERT/UPDATE/DELETE 幂等）、sellerId 必填、筛选/排序/分页、详情路由解析
- 边界测试：非法消息跳过、ES 异常→补偿键、越权（非 Admin-Call 拒绝）
- 回归：全模块基线保持（六模块 + search）全绿
