---
iteration: ITER-005
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 上游依赖：solution 未确认前，本文件不得进入 coding（见 .harness/rules/开发流程规范.md「阶段间接口」）
# 阶段间接口：REQ 编号一旦确认，禁止在不更新下游产物的情况下改写
---

# PRD: 商品模块生产化改造

> 机器可读的迭代协议产物之一。本文件定义**业务需求与验收标准**，是 solution 的唯一事实来源。

## 背景与目标

**深度探索结论**（证据：模块对照 `01-商品` §7 十二条线索逐条核实 + 代码深扫）：

- **真问题**：① 建 SPU 默认上架（与上架校验/注释宣称流程三处自相矛盾 → 空商品在架可被搜到）② 延迟双删不对称且**方案本身非生产正解**（猜时间魔数）③ initInventory 失败无自动补偿 ④ 价格无正数校验（负数可入库）
- **新发现**：getSkuDetail/listSkusBySpuId 重复查询；热路径 per-request INFO 日志 + 全模块无缓存指标；埋点丢弃无指标；布隆重建同步长阻塞；topic 常量未引用；@EnableScheduling 缺失

**目标**：业务正确性修复 + **缓存一致性重构（Canal 失效替代延迟双删）** + 可观测补齐——与订单/库存/支付改造水准对齐。

## 非目标（Out of Scope）

- 应然大差距（属性体系/多价格/品牌库/审核流/定时上下架/版本留痕）——记录+文章
- 权限类（creatorUserId）；AI 直读商品库；强制主库读（记录）；版本号 CAS 回写（记录）

## 需求（REQ）

| ID | 需求描述 | 优先级 |
|----|---------|--------|
| REQ-1 | **建 SPU 默认下架**：`createSpu → OFF_SHELF`（对齐"建 SPU(下架)→加 SKU→上架"流程与 `updateSpuStatus` 的"至少一个上架 SKU"校验；消灭空商品在架）；配套 `seed-data.py` 显式上架步骤 | P0 |
| REQ-2 | **价格正数校验**：`SkuCreateRequest.price` 加 `@DecimalMin("0")`（允许 0 元单场景，拒绝负数）+ 服务层防御校验 | P0 |
| REQ-3 | **库存初始化失败自动补偿**：失败 → Redis pending set（`myxhs:product:inventory-init:pending`）→ 重试 Job（@Scheduled + Redis 锁；幂等：成功/"已初始化"移除；SKU 不存在移除）；保留管理端手动重试；补 `@EnableScheduling` | P0 |
| REQ-4 | **缓存一致性重构（评审修订）**：**Canal 失效通道替代延迟双删**——新消费组 `product-cache-canal-evict-group` 消费 `PRODUCT_INDEX_TOPIC`（t_spu/t_sku binlog）→ 删 SPU 缓存（失败抛错重试）；**移除**延迟双删 + `SPU_DELAY_EVICT_SCHEDULER`；注释同步为三层一致性（立即删/MQ 兜底/Canal 保证） | P0 |
| REQ-5 | **分类缓存失效端点**：`POST /api/product/category/refresh`（admin；evict + 重建预热）——关闭"改 DB 后 2h 生效"的运维缺口 | P1 |
| REQ-6 | **可观测补齐**：缓存指标族（`myxhs_product_cache_hit_total{layer}`/`null_hit`/`miss`/`refresh`/`evict_total{source,result}`/`bloom_block_total`）+ 埋点丢弃 counter（`view_drop_total`）+ 热路径 per-request INFO → DEBUG | P1 |
| REQ-7 | **工程质量**：getSkuDetail/listSkusBySpuId 重复查询消除（同请求单查）；`rebuildBloomFilter` 异步化（对齐 search `manualRebuild` 模式，锁同线程）；`initBloomFilter` 缩进修复；`evictSpuCache` 引用 `CacheEvictMessage.TOPIC` 常量 | P1 |
| REQ-8 | **收口**：seed 脚本 + 全模块回归 + 模块对照 §7 讨论结论回写（12 线索裁决 + 新发现清单） | P0 |

## 验收标准（AC）

| ID | 归属 | 验收标准 | 来源 |
|----|------|---------|------|
| AC-1 | REQ-1 | createSpu 默认 OFF_SHELF；空 SPU（无 SKU）不出现在列表（仅 ON_SHELF）与详情（非上架不可见）；显式上架后正常可见；上架校验保持 | 01-商品 §7-1 |
| AC-2 | REQ-1 | `seed-data.py` 创建 SPU→SKU 后显式调用上架（PUT status=1） | 脚本核验 |
| AC-3 | REQ-2 | price 负数 → 拒绝（DTO 400 + 服务层 BizException）；price=0 合法 | 深扫 N3 |
| AC-4 | REQ-3 | 失败 → pending set 记录；Job 重试成功（含"已初始化"）→ 移除；SKU 不存在 → 移除；多实例锁互斥；幂等可重入 | 01-商品 §7-5 |
| AC-5 | REQ-4 | Canal 消费者：t_spu 事件 → 删 `product:spu:{id}`；t_sku 事件 → 删父 SPU 缓存；DELETE/deleted=1 同样失效；非法消息跳过；失败抛错重试 | 01-商品 §7-3 |
| AC-6 | REQ-4 | 延迟双删移除：`SPU_DELAY_EVICT_SCHEDULER` 全仓零残留；注释同步三层一致性描述 | 评审修订 |
| AC-7 | REQ-5 | 分类刷新端点：admin 守卫；evict + 重建返回节点数 | 01-商品 §7-10 |
| AC-8 | REQ-6 | 指标族落位（命名 `myxhs_product_*` 族）；热路径日志降噪（per-request INFO → DEBUG，降级保留 WARN/ERROR） | 深扫 N4/N5 |
| AC-9 | REQ-7 | getSkuDetail 单请求内 SPU 单查（verify 调用次数）；listSkusBySpuId 复用已查 SPU；布隆重建异步返回"已触发" | 深扫 N1/N2/N6 |
| AC-10 | REQ-8 | 七模块全量回归全绿 + 新增用例通过；§7 结论回写 | 回归基线 |

## 边界情况

- 建 SPU 默认下架：SQL 种子显式 status=1 不受影响；e2e 脚本仅 GET 不受影响（已核实）
- Canal 宕机期间：TTL 兜底（记录为基础设施降级）
- 补偿 Job：pending set 丢失（Redis flush）→ 有 metric + 手动端点兜底（记录）
- 双删移除后：写路径"立即删"保留（低延迟），Canal 为最终保证

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | N1/N2 同请求单查；热路径日志量下降；Canal 消费低频（管理端写） |
| 可靠性 | Canal 失效确定性（binlog 因果链 + 重试 + 分区保序）；补偿幂等可重入 |
| 可观测 | 缓存指标族全覆盖；降级/丢弃有计数 |

## 依赖与影响面

- 模块/服务: my-xhs-product（主体）；scripts/seed-data.py
- 外部 API: 新增 `POST /api/product/category/refresh`；`POST /api/product/spu` 行为变更（默认下架）
- 消息: 新消费组（无新 topic）；数据模型: 无 DDL；部署: @EnableScheduling + seed 脚本

## 人工决策点（已决策，2026-10-17——Owner 授权 AI 决策；缓存方案经 Owner 评审修订）

- **决策 1（建 SPU 默认下架）** = 对齐宣称流程（create→SKU→上架）与上架校验；理由：消灭空商品在架（三处自相矛盾的真问题）
- **决策 2（缓存一致性）** = **Canal 失效替代延迟双删**（评审修订）：延迟双删=猜时间魔数（非确定性、覆盖不了外部直改）；binlog 失效=确定性因果链（大厂标配、本项目库存同款、基建零新增）；保留立即删（快路径）+ MQ 兜底（写入侧）
- **决策 3（补偿机制）** = Redis pending set + @Scheduled Job（最轻且够用；DB outbox 记为重装备选）
- **决策 4（价格校验）** = @DecimalMin("0") + 服务层防御（0 元单合法——payment 有零元单路径）
- **决策 5（分类失效）** = admin refresh 端点（简单显式；"扩展 Canal 至 t_category"记录为备选）
- **决策 6（记录不做）** = 强制主库（Canal 已收敛窗口）/版本号 CAS（面试深水）/审核流版本（内部工具定位）
