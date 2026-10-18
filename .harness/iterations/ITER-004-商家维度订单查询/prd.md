---
iteration: ITER-004
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 上游依赖：solution 未确认前，本文件不得进入 coding（见 .harness/rules/开发流程规范.md「阶段间接口」）
# 阶段间接口：REQ 编号一旦确认，禁止在不更新下游产物的情况下改写
---

# PRD: 商家维度订单查询（异构索引）

> 机器可读的迭代协议产物之一。本文件定义**业务需求与验收标准**，是 solution 的唯一事实来源。

## 背景与目标

**问题（分库分表经典问题的 xhs 实例）**：

- 订单域分片键 = `user_id`（买家维度，`sharding-config.yaml`：t_order/t_order_item/t_sub_order 均按 user_id 路由）；
- ITER-001 引入商家维度：`t_sub_order.seller_id`（子单=商家维度履约主体）+ 发货端点（X-Admin-Call）；
- **缺口**：子单点查强制带路由键（`SubOrderMapper.selectBySubOrderNo(subOrderNo, userId)`），商家手里只有 subOrderNo/sellerId → **商家看不到自己的单**（发现层缺失）；
- 直接 scatter（`WHERE seller_id=?` 扫 16 张物理表）违背代码库现状（刻意避免 scatter）且深分页必坏。

**目标**：买家维度分片不动；商家维度走**异构索引**（Canal→MQ→ES）做检索、**映射表解析**做点查路由——
把经典问题做成 xhs 的标准解（全部复用既有设施，零新服务）。

## 非目标（Out of Scope）

- 真实商家身份体系（X-Admin-Call + sellerId 参数=现状；演进记录）
- 文档富化（商品行/买家昵称；首版=t_sub_order 单表索引；演进=消费端富化）
- ES 强一致/对账（最终一致；演进记录）；发货链路改造；商家端前端

## 需求（REQ）

| ID | 需求描述 | 优先级 |
|----|---------|--------|
| REQ-1 | **数据链路**：Canal 新增 `order_instance`（filter=`my_xhs_order_\d+\.t_sub_order_\d+`，16 张物理表；slaveId=1004；topic=`SUB_ORDER_INDEX_TOPIC`；partitionHash 按 id 保序）→ search 域消费者 `SellerSubOrderIndexSyncConsumer`（canal 消息 INSERT/UPDATE→upsert、DELETE→delete，幂等；失败 ID→Redis 补偿，复用既有模式）→ ES 索引 `seller_sub_order_index` | P0 |
| REQ-2 | **商家列表 API**：`GET /api/search/seller-order/list`（X-Admin-Call 守卫；**sellerId 必填**（防全量泄漏）；可选 status/startTime/endTime；createdAt 倒序；分页）——ES 单次查询，无 scatter/无 N+1 | P0 |
| REQ-3 | **正确性守卫（制度化）**：**ES 只做发现；一切写操作/终态判断以 DB + CAS 为准**（ES 延迟窗口不得导致错误操作）；文档化到消费者与 API 注释 | P0 |
| REQ-4 | **详情与路由解析**（P1）：`GET /api/order/seller/sub-order/{subOrderNo}`（X-Admin-Call；服务端解析：subOrderNo→orderNo→`OrderNoMappingRepository`→userId→路由查询；不依赖 ES；商家不持有买家 userId） | P1 |
| REQ-5 | **存量回填**（P1）：Canal 只捕增量 → order 域 `GET /api/order/internal/sub-order-scan`（游标分页，X-Internal-Call）+ search 域回填 Job（幂等 bulk 写 ES，可重入） | P1 |
| REQ-6 | **收口**：deploy 配置（canal instance + compose 挂载 + 重启说明）；全量回归（六模块 + search）；修订记录/CONTEXT 术语 | P0 |

## 验收标准（AC）

| ID | 归属 | 验收标准 | 来源 |
|----|------|---------|------|
| AC-1 | REQ-1 | canal `order_instance` 配置就位（filter/slaveId=1004/topic/partitionHash；deploy zip + compose 挂载） | 部署配置核验 |
| AC-2 | REQ-1 | 消费者：canal 消息（INSERT/UPDATE）→ ES upsert 幂等（重放不产生重复）；DELETE → 文档删除；deleted=1 → 删除；非法消息跳过不重试 | 单测 |
| AC-3 | REQ-1 | ES 异常 → 失败 ID 落 Redis 补偿键（复用既有模式）；异常上抛触发重试（maxReconsumeTimes=3） | 单测 |
| AC-4 | REQ-2 | sellerId 缺失/非法 → 400 拒绝（防全量泄漏）；非 X-Admin-Call → 403 | 单测 |
| AC-5 | REQ-2 | 列表查询正确：sellerId 过滤 + status/时间范围 + createdAt 倒序 + 分页（from/size）；ES 单次查询 | 单测 |
| AC-6 | REQ-3 | 正确性守卫文档化（消费者/API 注释明示"ES 只做发现；写操作以 DB+CAS 为准"） | 代码核验 |
| AC-7 | REQ-4 | 详情：subOrderNo→orderNo→映射表→userId→路由查询成功；映射缺失 → 明确错误（不静默） | 单测 |
| AC-8 | REQ-5 | 回填幂等可重入（重复执行不产生重复文档）；游标分页覆盖全部 16 张物理表 | 单测 |
| AC-9 | REQ-6 | 全模块回归全绿（六模块 + search）+ 新增用例通过；canal/compose 配置核验 | 测试报告 |

## 边界情况

- 列表 ES 延迟窗口：写操作/发货前以 DB 为准（CAS 已有）——延迟不造成错误操作
- ES 不可用：列表报错降级（不影响任何写路径/发货）
- 子单状态变更（取消/全退/发货/完成）→ binlog → ES 收敛（幂等）
- 存量数据：回填前不可见（P1 回填解决；部署顺序=先回填后切流量，记录）
- 平台自营单（seller_id=0）：正常可查（sellerId=0 是合法查询）

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 列表=ES 单次查询（无 scatter/无 N+1/深分页天然支持）；binlog→ES 秒级 |
| 可靠性 | 消费者幂等（upsert/delete 重放安全）；失败补偿；回填幂等可重入 |
| 安全 | X-Admin-Call + sellerId 必填；详情服务端解析路由；无敏感字段外泄（买家仅 userId 引用） |

## 依赖与影响面

- 模块/服务: my-xhs-search（消费者/索引/列表 API）、my-xhs-order（P1 详情/扫描）、deploy（canal）
- 外部 API: 新增 `/api/search/seller-order/list`（P0）；`/api/order/seller/sub-order/{subOrderNo}`、`/api/order/internal/sub-order-scan`（P1）
- 数据模型: 无 SQL 变更；ES `seller_sub_order_index`；Redis `myxhs:es:sync:failed:seller-sub-order`
- 涉及 wiki: 接口协议（归档时回流）

## 人工决策点（已决策，2026-10-17——Owner 授权 AI 决策）

- **决策 1（路由）** = B 路（异构索引）为主 + 映射表解析点查：候选解法全对比后胜出（scatter/纯映射表/双写/基因法/改分片键均淘汰或不适配）；项目既有双形态（Canal×3 + t_order_no_mapping）。
- **决策 2（索引粒度）** = t_sub_order 单表（商家视角=子单，字段自足）；富化演进。
- **决策 3（API 位置）** = search 域（ES 单一所有者，与 note/product 检索一致）；备选"order 域 Feign 包一层"记录。
- **决策 4（鉴权）** = X-Admin-Call + sellerId 必填（防全量泄漏）；真实商家身份演进。
- **决策 5（一致性）** = 最终一致 + **"ES 只做发现，写操作/终态以 DB+CAS 为准"制度化**（正确性根基）。
- **决策 6（存量）** = 回填必须做（P1；canal 位点回拨作为部署侧备选记录）。
