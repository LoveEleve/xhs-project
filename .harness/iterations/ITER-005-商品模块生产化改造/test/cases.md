---
iteration: ITER-005
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：每条 TC 归属 prd.md 中一条 AC；上游 AC 变更时本文件必须同步
---

# 测试用例: 商品模块生产化改造

> 机器可读的迭代协议产物之一。TC 编号归属 AC，供 test/report.md 与 harness-quality 的 flow→test 对账使用。

## 用例清单

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | — | createSpu | 落库 status=OFF_SHELF（默认下架） | 是 |
| TC-2 | AC-1 | 空 SPU（下架） | listSpus / getSpuDetail | 列表不含（仅 ON_SHELF）；详情不可见（非上架返回 null） | 是 |
| TC-3 | AC-2 | — | seed-data.py review | 建 SKU 后显式 PUT status=1 | 手工 |
| TC-4 | AC-3 | — | createSku(price=-1) | 服务层 BizException（负数拒绝） | 是 |
| TC-5 | AC-3 | — | createSku(price=0) | 通过（0 元单合法） | 是 |
| TC-6 | AC-4 | initInventory 失败 | createSku | pending set 记录 skuId | 是 |
| TC-7 | AC-4 | pending set 有 skuId | Job 执行（initStock 成功） | initStock 调用 + set 移除 | 是 |
| TC-8 | AC-4 | pending set 有 skuId | Job 执行（响应"已初始化"） | 视为成功 + set 移除 | 是 |
| TC-9 | AC-4 | pending set 有 skuId（SKU 已删） | Job 执行 | set 移除（不再重试） | 是 |
| TC-10 | AC-4 | pending set 有 skuId | Job 执行（initStock 失败） | set 保留 + 日志 | 是 |
| TC-11 | AC-5 | canal t_spu 消息 | 消费者 | 删 `product:spu:{id}` | 是 |
| TC-12 | AC-5 | canal t_sku 消息 | 消费者 | 删父 SPU 缓存 | 是 |
| TC-13 | AC-5 | canal DELETE / deleted=1 | 消费者 | 同样失效 | 是 |
| TC-14 | AC-5 | 非法消息（空/无 data/非目标表） | 消费者 | 跳过不重试 | 是 |
| TC-15 | AC-5 | Redis delete 异常 | 消费者 | 抛错（MQ 重试） | 是 |
| TC-16 | AC-6 | 全仓 | grep SPU_DELAY_EVICT_SCHEDULER | 零残留 + 既有测试通过 | 是 |
| TC-17 | AC-7 | 缓存存在 | POST category/refresh | evict + 重建返回节点数 | 是 |
| TC-18 | AC-8 | 代码核验 | 指标族命名/日志级别 | `myxhs_product_*` 族 + 热路径 DEBUG | 手工 |
| TC-19 | AC-9 | sku 存在 | getSkuDetail | SPU 单查（verify times(1)） | 是 |
| TC-20 | AC-9 | spu 存在 | listSkusBySpuId | SPU 单查（复用已查） | 是 |
| TC-21 | AC-9 | — | rebuildBloomFilter | 返回"已触发"（异步） | 是 |
| TC-22 | AC-10 | 全仓 | 全量重编测试 | 七模块基线全绿 + 新增用例通过 | 是 |

## 边界与降级用例

| TC | 归属 AC | 场景 | 期望 |
|----|---------|------|------|
| TC-23 | AC-1 | 上架校验保持：无上架 SKU 时上架 | 拒绝（既有行为不回归） | 是 |
| TC-24 | AC-4 | Job 重入（同 set 连续执行） | 幂等（已移除的不再处理） | 是 |
| TC-25 | AC-5 | 批量多行消息（一次改多个 SKU） | 逐行失效（含父 SPU 去重） | 是 |
| TC-26 | AC-6 | 写路径立即删保留 | updateSpu afterCommit 仍删缓存（既有断言） | 是 |
