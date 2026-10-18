---
id: C-005
slug: 商品模块生产化改造
status: done
created: 2026-10-17
updated: 2026-10-17
iteration: ITER-005     # 机器可读迭代协议：.harness/iterations/ITER-005-商品模块生产化改造/
relations:
  relates_to:
    - slug: docs/学习/模块对照/01-商品
      reason: §7 十二条线索的收口（深度探索核实 + 9 个新发现）
    - slug: docs/学习/模块对照/99-总表
      reason: "最大缺口"行（建 SPU 默认上架）的修复
  depends_on: []
---

# C-005 商品模块生产化改造

> **完成记录（2026-10-17）**：四阶段全部落地（业务正确性 → Canal 失效替代延迟双删 → 可观测与质量 → 收口）；
> 证据：七模块全量 **396 全绿**（product 34，+20）+ 全仓编译；详见 `.harness/iterations/ITER-005-商品模块生产化改造/test/report.md`。
> 评审修订已落地：Canal 失效通道替代延迟双删（binlog 因果链 vs 猜时间魔数）。
> 运行时验证（真实 Canal→MQ→Redis 链路）按项目规则后置。

> **立项（2026-10-17）**：模块对照阶段二第一站。深度探索结论：12 条线索全部实锤（含 3 个真问题）+ 9 个新发现。
> **评审修订（关键）**：缓存一致性方案**不用"统一延迟双删"**——延迟双删是"没有 binlog 基建时的将就"（猜时间的魔数，非确定性）；
> 生产正解 = **binlog 驱动失效**（Canal→MQ→删缓存，大厂标配、本项目库存模块同款、基建零新增）——**替换**延迟双删。
> 规划详见 `.harness/iterations/ITER-005-商品模块生产化改造/`。

## 用户故事

作为商品域开发者，我想要把商品模块从"单点强、一致性机制半吊子"升级到"生产形态"（业务正确性 + binlog 级缓存一致性 + 可观测），
以便学习的是最终形态，且与订单/库存/支付的改造水准对齐。

## 非目标（Out of Scope）

- 应然大差距（属性体系/多价格/品牌库/审核流/定时上下架/版本留痕）——记录 + 文章讲
- 权限类（creatorUserId 越权 TODO）；AI 直读商品库（范围外）
- 强制主库读（Canal 失效已收敛写后读窗口，记录）
- 版本号 CAS 回写（终极强一致，面试深水记录）

## 验收标准

- AC-1 ~ AC-10 见 `.harness/iterations/ITER-005-商品模块生产化改造/prd.md`（本变更走迭代协议）

## 边界情况

- 建 SPU 默认下架后：seed-data.py 需显式上架（脚本同步）；SQL 种子显式 status=1 不受影响
- Canal 宕机期间：一致性靠 TTL 兜底（与搜索索引同级别降级，记录）
- 库存补偿 Job：SKU 已删除 → 从 pending set 移除；"已初始化"响应视为成功
- 双删移除：全仓仅 SpuService 3 处引用；测试不依赖（已核实）

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | N1/N2 重复查询消除（同一请求内单查）；热路径日志降噪 |
| 可靠性 | Canal 失效确定性（binlog 因果链 + MQ 重试 + 分区保序）；补偿 Job 幂等 |
| 可观测 | 缓存指标族（hit/miss/refresh/evict/bloom-block/埋点丢弃）；热路径 debug 降噪 |

## 设计约束

- 零新基建：Canal 消费组复用既有 `PRODUCT_INDEX_TOPIC`（filter/partitionHash 已覆盖 t_spu/t_sku）；补偿 Job 走 @Scheduled+Redis 锁（补 @EnableScheduling）
- 生产标准：业务/工程/分布式/微服务/性能/可观测全维度；不改文章正文（模块对照 §7 讨论结论回写）
- 分层一致性：① afterCommit 立即删（快路径）② 删失败 MQ 兜底（写入侧）③ Canal binlog 删（最终保证）

## 契约影响

- REST: 新增 `POST /api/product/category/refresh`（admin）；`POST /api/product/spu` 默认状态变更（下架——行为变更）
- MQ: 新增消费组 `product-cache-canal-evict-group`（消费 `PRODUCT_INDEX_TOPIC`）；无新 topic
- 数据模型: 无 DDL（Redis pending set `myxhs:product:inventory-init:pending`）
- 部署: 无 canal 配置改动；product 需 @EnableScheduling

## 影响面

- 模块/服务: my-xhs-product（主体）；scripts/seed-data.py（显式上架）
- wiki: 无（归档时回流）

## 测试策略

- 先写失败测试：默认下架/价格负数/补偿 Job/Canal 消费/分类刷新/N1N2 单查
- 边界测试：Canal 非法消息跳过、补偿幂等、双删移除后行为
- 回归：全模块基线保持（七模块）全绿
