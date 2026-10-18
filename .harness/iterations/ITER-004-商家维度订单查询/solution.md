---
iteration: ITER-004
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：本文件仅可引用已 confirmed 的 prd.md 中的 REQ/AC；本文件 confirmed 前不得进入 coding
---

# 技术方案: 商家维度订单查询（异构索引）

> 机器可读的迭代协议产物之一。把 prd.md 的 REQ 转化为**实现任务与观测点**。
> 纪律：具体方法逻辑以代码为准，本文件只说明「调哪个接口/方法 + 它在整条链路中的位置」。

## 方案概述

```
商家查询：ES(seller_sub_order_index) ← SellerSubOrderIndexSyncConsumer ← SUB_ORDER_INDEX_TOPIC ← Canal(order_instance)
点查/详情：subOrderNo → orderNo(解析) → t_order_no_mapping(既有) → userId → 路由查询（不依赖 ES）
```

两阶段（P0 核心链路 → P1 完整性）：

| 阶段 | 内容 | 独立价值 |
|---|---|---|
| ① P0：数据链路 + 列表 | canal instance + 消费者 + ES 索引 + 列表 API + 守卫 | 商家"看得见"（发现层闭环） |
| ② P1：详情 + 回填 | order 域详情/扫描端点 + 回填 Job | 点查闭环 + 存量可见 |

## REQ → 任务映射

### 阶段① P0：数据链路 + 列表（REQ-1/2/3/6 / AC-1~6/9）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-1 | T1.1 canal `order_instance/instance.properties`：filter=`my_xhs_order_\d+\.t_sub_order_\d+`；slaveId=**1004**（1001-1003 已占）；topic=`SUB_ORDER_INDEX_TOPIC`；partitionsNum=3；partitionHash=16 条 `schema.table:id`（同单事件保序）；deploy zip compose 加挂载 + 重启说明 | `deploy/docker/my-xhs-deploy-zip/config/canal/conf/order_instance/`、`docker-compose.yml` | 配置核验 |
| AC-2/3 | T1.2 消费者 `SellerSubOrderIndexSyncConsumer`（复用 `ProductIndexSyncConsumer` 形态：canal JSON 解析 {database,table,type,data[],ts}；INSERT/UPDATE→upsert（doc id=sub_order_no）、DELETE/deleted=1→delete；`MqTraceHelper.restoreTraceId`；ES 异常→Redis Set `myxhs:es:sync:failed:seller-sub-order` + 上抛重试；maxReconsumeTimes=3） | `my-xhs-search/.../consumer/SellerSubOrderIndexSyncConsumer.java`（新） | 单测：四类消息 |
| AC-2 | T1.3 ES 索引 `seller_sub_order_index`（字段：subOrderNo/orderNo/orderId/userId/sellerId/status/totalAmount/discountAmount/payAmount/createdAt/updatedAt；`IndexInitializer` 加建索引；yml `search.seller-order.index-name`） | `IndexInitializer`、`application.yml` | 索引创建核验 |
| AC-4/5 | T1.4 列表 API：`GET /api/search/seller-order/list`（X-Admin-Call；**sellerId 必填**（null/<=0 → 400）；status/startTime/endTime 可选；createdAt 倒序；page/size；ES bool+range+sort 单次查询） | `controller/SellerOrderSearchController.java`、`service/SellerOrderSearchService.java`、dto（新） | 单测：必填/过滤/排序/分页 |
| AC-6 | T1.5 正确性守卫注释：消费者+API 明示"**ES 只做发现；写操作/终态以 DB+CAS 为准**"（prd 决策 5） | 同上 | 代码核验 |
| AC-9 | T1.6 回归：全模块（六模块 + search）全量测试 | 全仓 | 测试报告 |

### 阶段② P1：详情 + 回填（REQ-4/5 / AC-7/8）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-7 | T2.1 详情端点 `GET /api/order/seller/sub-order/{subOrderNo}`（X-Admin-Call；解析 subOrderNo→orderNo（`-N` 后缀）→`OrderNoMappingRepository`→userId→`selectBySubOrderNo(subOrderNo, userId)` 路由读；映射缺失→明确错误；返回子单+行（复用既有查询） | `my-xhs-order`：`OrderController`/`OrderService` | 单测：解析/路由/缺失 |
| AC-8 | T2.2 扫描端点 `GET /api/order/internal/sub-order-scan?cursor=&limit=`（X-Internal-Call；游标分页；覆盖全部物理表）+ search 域回填 Job（`SellerSubOrderIndexBackfillJob`：调扫描 → bulk upsert ES；幂等可重入） | `my-xhs-order`、`my-xhs-search/job/` | 单测：幂等/分页 |
| AC-9 | T2.3 回归 + deploy 说明（部署顺序：先回填后切流量） | 全仓 | 测试报告 |

## 接口与数据变更

- **对外接口**：
  - 新增 `GET /api/search/seller-order/list?sellerId=&status=&startTime=&endTime=&page=&size=`（X-Admin-Call）
  - P1 新增 `GET /api/order/seller/sub-order/{subOrderNo}`（X-Admin-Call）、`GET /api/order/internal/sub-order-scan`（X-Internal-Call）
- **消息**：新 topic `SUB_ORDER_INDEX_TOPIC`（canal 生产；search 消费）
- **数据模型**：无 SQL 变更；ES `seller_sub_order_index`；Redis `myxhs:es:sync:failed:seller-sub-order`
- **部署**：canal `order_instance` + compose 挂载（重启 canal 生效）

## 关键设计决策

| 决策 | 结论 | 理由 / 权衡 |
|------|------|------------|
| 路由 | 异构索引（ES）+ 映射表解析（点查） | 候选全对比胜出；项目既有双形态 |
| 索引粒度 | t_sub_order 单表 | 商家视角=子单；富化演进 |
| API 位置 | search 域 | ES 单一所有者；备选 order 域 Feign 记录 |
| 鉴权 | X-Admin-Call + sellerId 必填 | 防全量泄漏；真实商家身份演进 |
| 一致性 | 最终一致 + "ES 只做发现"制度化 | 延迟窗口不造成错误操作（CAS 保护） |
| 存量 | 回填 Job（P1）；canal 位点回拨备选 | canal 只捕增量 |

## 风险与降级

| 风险 | 影响 | 缓解 / 降级 |
|------|------|------------|
| canal 正则/partitionHash 配错 | 事件丢失/乱序 | 配置核验清单 + 16 条 hash 显式列出 |
| ES 索引字段与 binlog 列名漂移 | 同步失败 | 消费者字段映射集中定义 + 单测 |
| 深分页 | ES 默认 from+size 上限（10000） | 记录：深分页需 search_after（演进；商家场景页码浅） |
| ES 不可用 | 列表不可用 | 写路径零依赖（发货等不受影响） |
| 存量未回填就切流量 | 老单不可见 | 部署顺序说明（先回填后切） |

## 人工决策点（已决策，2026-10-17——Owner 授权 AI 决策）

- 决策 1~6 见 prd.md §人工决策点（路由/索引粒度/API 位置/鉴权/一致性/存量）
