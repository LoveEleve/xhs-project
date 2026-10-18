---
iteration: ITER-005
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：真实测试证据齐备前，本迭代不得进入发布准备（见开发流程规范.md「阶段间接口」）
---

# 测试报告: 商品模块生产化改造

> 机器可读的迭代协议产物之一。记录**真实执行证据**，是发布准备的前置条件。

## 执行摘要

- 执行时间: 2026-10-17（四阶段：业务正确性 → 一致性重构 → 可观测与质量 → 收口）
- 环境 profile: JDK 17（`/env/javaEnv/jdk17`）+ Maven 3.9 离线（`-o`）；**无运行时环境**（项目规则：单测口径；删 `target/test-classes` 强制全量重编防增量假绿）
- 命令: `mvn -o -pl my-xhs-common,my-xhs-product,my-xhs-coupon,my-xhs-order,my-xhs-inventory,my-xhs-payment,my-xhs-search -am test` + 全仓 `mvn -o -q compile`
- 结果: ✅ 通过（BUILD SUCCESS；**397 全绿**：common 93 / **product 35** / coupon 11 / order 122 / inventory 20 / search 43 / payment 73——较 376 基线净增 21，零回归（含审查轮 +1））

## 用例执行结果

| TC | 归属 AC | 结果 | 断言/证据 | 备注 |
|----|---------|------|----------|------|
| TC-1 | AC-1 | 🟢 | `SpuServiceTest#createSpuDefaultsOffShelf`（captor 断言落库 status=0） | |
| TC-2 | AC-1 | 🟢 | 空 SPU（下架）不可见：列表仅 ON_SHELF + 详情非上架返回 null（既有用例 + 代码路径） | |
| TC-3 | AC-2 | 🟢 | seed-data.py 显式上架（`PUT status=1`，py_compile 通过） | 脚本核验 |
| TC-4 | AC-3 | 🟢 | `SkuServiceTest#createSkuRejectsNegativePrice`（负数 BizException + 未落库） | |
| TC-5 | AC-3 | 🟢 | `#createSkuAllowsZeroPrice`（0 元单合法） | |
| TC-6 | AC-4 | 🟢 | `#createSkuInventoryFailureRecordsPending`（失败 → set add） | |
| TC-7 | AC-4 | 🟢 | `InventoryInitRepairJobTest#retrySuccessRemoves` | |
| TC-8 | AC-4 | 🟢 | `#alreadyInitializedRemoves`（幂等） | |
| TC-9 | AC-4 | 🟢 | `#skuMissingRemoves`（SKU 不存在移除、不调库存） | |
| TC-10 | AC-4 | 🟢 | `#stillFailingKeeps` + `#callExceptionKeeps` | |
| TC-11 | AC-5 | 🟢 | `ProductCacheCanalEvictConsumerTest#spuChangeEvicts` | |
| TC-12 | AC-5 | 🟢 | `#skuChangeEvictsParentDeduplicated`（同批去重） | |
| TC-13 | AC-5 | 🟢 | `#deleteEventsEvict`（DELETE + deleted=1） | |
| TC-14 | AC-5 | 🟢 | `#invalidMessagesSkipped`（非法/非目标表） | |
| TC-15 | AC-5 | 🟢 | `#redisFailureRetries`（抛错重试） | |
| TC-16 | AC-6 | 🟢 | 全仓 grep `SPU_DELAY_EVICT_SCHEDULER` 零代码残留 + 既有测试通过 | |
| TC-17 | AC-7 | 🟢 | `CategoryServiceTest#refreshCategoryTreeRebuilds`（删 key + 重建返回节点数） | |
| TC-18 | AC-8 | 🟢 | 指标族 `myxhs_product_*`（hit{l2}/null_hit/miss/refresh/evict{source,result}/bloom_block/view_drop）+ 热路径 INFO→DEBUG（代码核验） | |
| TC-19 | AC-9 | 🟢 | `SkuServiceTest#getSkuDetailQueriesSpuOnce`（verify times(1)） | |
| TC-20 | AC-9 | 🟢 | `#listSkusBySpuIdQueriesSpuOnce`（times(1) + never selectBatchIds） | |
| TC-21 | AC-9 | 🟢 | `SpuServiceTest#rebuildBloomFilterReturnsTriggered`（异步返回语义） | |
| TC-22 | AC-10 | 🟢 | 七模块全量 396 全绿 + 全仓编译 | 强制全量重编 |
| TC-23 | AC-1 | 🟢 | 上架校验（至少一个上架 SKU）代码未动 + 默认下架后流程自洽（代码核验） | |
| TC-24 | AC-4 | 🟢 | Job 重入幂等（`#emptyQueueShortCircuits` + 移除幂等） | |
| TC-25 | AC-5 | 🟢 | 批量多行消息去重（TC-12 同用例） | |
| TC-26 | AC-6 | 🟢 | 写路径 afterCommit 立即删保留（代码核验；updateSpu 用例通过） | |

## 关键变更（评审修订落地）

- **缓存一致性重构**：Canal 失效通道（`ProductCacheCanalEvictConsumer`，新消费组消费既有 `PRODUCT_INDEX_TOPIC`）**替代**延迟双删——binlog 因果链（确定性）替代猜时间魔数；移除 `SPU_DELAY_EVICT_SCHEDULER`（3 处引用全清）；三层一致性（立即删/MQ 兜底/Canal 保证）注释同步
- **业务正确性**：建 SPU 默认下架（三处自相矛盾收口）+ 价格非负 + 库存初始化失败自动补偿（pending set + Job，补 @EnableScheduling）
- **可观测**：缓存指标族 + 埋点丢弃计数（原 DiscardPolicy 静默）+ 热路径日志降噪
- **质量**：N1/N2 重复查询消除 + 布隆重建异步化 + 缩进/常量清理

## 深审查记录（见 test/review-c005.md）

- 方式：**27 项清单先行 → 逐项校验 → 回填结论**
- 🔴 0；🟡 发现并修复 1（R-07 行缺失 id/spu_id 静默跳过 → WARN 日志 + 测试）；📝 记录 3（重建失败 flag 残留/Redis 降级返回 0 歧义/租约过期重复执行幂等 benign）

## 覆盖对账

- AC 总数: 10 / 已覆盖: 10（其中 3 条为代码核验/脚本核验，无独立用例）
- 无真实证据的 AC 不得标记为「已验证」

## 结论

✅ 可进入发布准备（代码与单测口径）；⚠️ 运行时验证（真实 Redis/MySQL/Canal/MQ）按项目规则后置
