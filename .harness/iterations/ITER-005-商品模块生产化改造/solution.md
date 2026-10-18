---
iteration: ITER-005
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：本文件仅可引用已 confirmed 的 prd.md 中的 REQ/AC；本文件 confirmed 前不得进入 coding
---

# 技术方案: 商品模块生产化改造

> 机器可读的迭代协议产物之一。把 prd.md 的 REQ 转化为**实现任务与观测点**。
> 纪律：具体方法逻辑以代码为准，本文件只说明「调哪个接口/方法 + 它在整条链路中的位置」。

## 方案概述

```
缓存一致性（重构后三层）：
  ① afterCommit 立即删（快路径，低延迟）
  ② 删失败 → CACHE_EVICT_TOPIC MQ 兜底（写入侧，既有）
  ③ Canal（PRODUCT_INDEX_TOPIC 新消费组）→ 删 SPU 缓存（最终保证，替代延迟双删）
```

四阶段（禁止 big bang）：

| 阶段 | 内容 | 独立价值 |
|---|---|---|
| ① 业务正确性 | 默认下架 + 价格校验 + 库存补偿（+@EnableScheduling） | 真问题清零 |
| ② 一致性重构 | Canal 失效替代延迟双删 | 生产正解落地 |
| ③ 可观测与质量 | 指标族 + 日志降噪 + N1/N2 + 重建异步 + 清理 | 与改造水准对齐 |
| ④ 收口 | 分类刷新端点 + seed 脚本 + 回归 + §7 回写 | 运维缺口关闭 |

## REQ → 任务映射

### 阶段① 业务正确性（REQ-1/2/3 / AC-1~4）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-1 | T1.1 `createSpu` 默认状态 `ON_SHELF` → `OFF_SHELF`（一行）；注释同步"建 SPU(下架)→加 SKU→上架" | `service/SpuService.java:330` | 单测：默认下架 + 空商品不可见 |
| AC-2 | T1.2 `seed-data.py`：建 SKU 后 `PUT /api/product/spu/{id}/status`（status=1）显式上架 | `scripts/seed-data.py` | 脚本语法 + 流程核验 |
| AC-3 | T1.3 `SkuCreateRequest.price` + `@DecimalMin(value="0")`；`createSku` 服务层防御（price < 0 → BizException） | `dto/request/SkuCreateRequest.java`、`service/SkuService.java` | 单测：负数拒绝 / 0 合法 |
| AC-4 | T1.4 补偿：`initInventory` 失败 → `SADD myxhs:product:inventory-init:pending {skuId}`；新增 `InventoryInitRepairJob`（@Scheduled fixedDelay 5min + Redis 锁；逐 skuId：SKU 不存在→移除；initStock 成功或"已初始化"→移除；失败→保留+日志）；`ProductApplication` 补 `@EnableScheduling`；手动端点保留 | `service/SkuService.java`、`job/InventoryInitRepairJob.java`（新）、`ProductApplication.java` | 单测：记录/重试/移除 |

### 阶段② 一致性重构（REQ-4 / AC-5/6）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-5 | T2.1 `ProductCacheCanalEvictConsumer`（topic=`PRODUCT_INDEX_TOPIC`，group=`product-cache-canal-evict-group`，maxReconsume=3）：canal JSON 解析（{table,type,data[]}）；t_spu → 逐行 `id` → 删 `product:spu:{id}`；t_sku → 逐行 `spu_id` → 删父 SPU 缓存；DELETE/deleted=1 同样失效；失败抛错重试；trace 还原 | `consumer/ProductCacheCanalEvictConsumer.java`（新） | 单测：四类消息 |
| AC-6 | T2.2 移除延迟双删：删 `SPU_DELAY_EVICT_SCHEDULER`（声明/关闭/调度调用 3 处）；`updateSpu` afterCommit 保留"立即删"；类 javadoc 与注释同步为三层一致性描述 | `service/SpuService.java` | 全仓零残留 + 测试通过 |

### 阶段③ 可观测与质量（REQ-6/7 / AC-8/9）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-8 | T3.1 指标族（MeterRegistry，命名 `myxhs_product_*`）：`cache_hit_total{layer=l2}`、`cache_null_hit_total`、`cache_miss_total`、`cache_refresh_total`、`cache_evict_total{source=write\|canal,result=ok\|mq_fallback\|fail}`、`bloom_block_total`、`view_drop_total` | `service/SpuService.java`、`consumer/ProductCacheCanalEvictConsumer.java` | 单测（计数断言可选）/代码核验 |
| AC-8 | T3.2 日志降噪：`SpuService` 热路径 per-request INFO → DEBUG（布隆拦截/空值命中/空值过期重查/L2 miss→DB/DB 未查到→空值/非上架详情不可见）；降级 WARN/ERROR 保留 | `service/SpuService.java` | 代码核验 |
| AC-9 | T3.3 N1：`getSkuDetail` 复用已查 `spu`（删 `resolveSpuImage` 二次查，改用已查对象取首图）；N2：`listSkusBySpuId` 复用已查 `spu`（`buildSpuImageMap(List.of(spu))`） | `service/SkuService.java` | 单测：verify 单查次数 |
| AC-9 | T3.4 `rebuildBloomFilter` 异步化：`CompletableFuture.runAsync` 内获取锁→重建→释放（锁同线程，对齐 search `manualRebuild`）；返回"已触发" | `service/SpuService.java` | 单测：返回语义 |
| AC-9 | T3.5 清理：`initBloomFilter` 缩进修复；`evictSpuCache` 用 `CacheEvictMessage.TOPIC` 常量 | `service/SpuService.java` | 代码核验 |

### 阶段④ 收口（REQ-5/8 / AC-7/10）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-7 | T4.1 `POST /api/product/category/refresh`（admin）：`CategoryService.refreshCategoryTree()`（删 key + 重建预热，返回节点数） | `controller/ProductController.java`、`service/CategoryService.java` | 单测：刷新返回 |
| AC-10 | T4.2 全模块回归（七模块）+ `docs/学习/模块对照/01-商品.md` §7 讨论结论回写（12 线索裁决 + 新发现 + 决策记录） | 全仓 + 文档 | 测试报告 |

## 接口与数据变更

- **对外接口**：新增 `POST /api/product/category/refresh`（admin）；`POST /api/product/spu` 行为变更（默认下架）
- **消息**：新消费组 `product-cache-canal-evict-group`（消费既有 `PRODUCT_INDEX_TOPIC`）；无新 topic
- **数据模型**：无 DDL；Redis `myxhs:product:inventory-init:pending`（set）
- **部署**：无 canal 配置改动；`@EnableScheduling`（product）

## 关键设计决策

| 决策 | 结论 | 理由 / 权衡 |
|------|------|------------|
| 缓存一致性 | Canal 失效**替代**延迟双删（保留立即删 + MQ 兜底） | 确定性因果链 vs 猜时间魔数；大厂标配；库存同款；基建零新增 |
| 默认状态 | 建 SPU 默认下架 | 对齐宣称流程/上架校验；消灭空商品在架 |
| 补偿机制 | Redis pending set + @Scheduled Job | 最轻够用；DB outbox 重装备选记录 |
| 价格校验 | @DecimalMin("0") + 服务层防御 | 0 元单合法（payment 有零元单路径） |
| 分类失效 | admin refresh 端点 | 简单显式；Canal 扩展备选记录 |
| 布隆重建 | 异步（锁同线程） | HTTP 线程不做分钟级任务（对齐 search 先例） |

## 风险与降级

| 风险 | 影响 | 缓解 / 降级 |
|------|------|------------|
| Canal 宕机 | 失效通道停 | TTL 兜底（与搜索索引同级降级，记录） |
| 默认下架影响存量脚本 | 种子商品不可见 | seed-data.py 同步（已入范围）；SQL 种子显式 status 不受影响（已核实） |
| 补偿 Job 与多实例 | 重复重试 | Redis 锁互斥 + initStock 幂等（"已初始化"处理） |
| 双删移除的残留引用 | 编译失败 | 全仓 grep 零残留核验（已核实仅 3 处） |
| 指标命名漂移 | 监控失配 | 统一 `myxhs_product_*` 前缀（对齐既有 init_fail 指标） |

## 人工决策点（已决策，2026-10-17——Owner 授权 AI 决策；缓存方案经 Owner 评审修订）

- 决策 1~6 见 prd.md §人工决策点（默认下架/缓存一致性/补偿机制/价格校验/分类失效/记录不做）
