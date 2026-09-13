# RV12：search 两组历史 DLQ 排查与韧性修复

> 日期：2026-09-13 ｜ 范围：`%DLQ%product-index-sync-consumer-group`(7) + `%DLQ%recommend-behavior-consumer-group`(1)
> 结论：**历史遗留失败（平台搭建期问题，数据合法）**；重放成功清账；重放过程暴露并修复 **2 个消费者韧性缺陷**。

---

## 1. 排查结论（为什么积压）

| 组 | 消息时间 | 根因（日志实证） | 判定 |
|----|---------|----------------|------|
| product-index-sync | 09-06 19:01 / 21:00 / 21:14 / 21:18、09-08 22:58 | `IllegalStateException: 商品详情获取失败: spuId=3/2/1` —— 09-06 搭建期 product 服务/表未就绪，Feign 调用失败；09-08 起已能正常索引 | 环境期失败，数据合法（SPU 1/2/3/6 现存） |
| recommend-behavior | 09-08 22:21 | `BadSqlGrammarException: Table 'my_xhs_content.t_user_behavior' doesn't exist` —— 表当时未建（后续补 DDL） | 环境期失败，数据合法 |

## 2. 重放与验证（治理流程）

- product-index：7 条全部经审批重投（单号 11、13-18）→ `SPU 1/2/3/6` ES 文档验证 `found:true`；批次内 `SPU 4/5` 同步成功。
- recommend-behavior：1 条重投（单号 12）→ 落库 `my_xhs_content.t_user_behavior`（id=2097329371787476993）。
- 清账：两个 `%DLQ%` topic 删除 → **全平台无 DLQ topic（零积压）**。
- 核验：审批单经异步核验器窗口收口（`verified_no_reentry`）。

## 3. 重放暴露的 2 个韧性缺陷（已修）

| # | 缺陷 | 证据 | 修复 |
|---|------|------|------|
| 1 | **陈旧版本消息重试成灾**：ExternalGte 拒绝旧版本（409 `version_conflict_engine_exception`）被当作可重试错误 → 反复重试入 DLQ | 重放 09-06 消息时 `spuId=1/2` 冲突（ES 已有更新版本） | 捕获版本冲突 → 视为"幂等已应用"跳过（`isVersionConflict`），不再重试 |
| 2 | **确定性失败不可重试**：商品不存在返回 `ResultCode.PRODUCT_NOT_FOUND(30001)`，消费者仍抛异常重试 → 永久死信 | 重放含 snowflake SPU 的批次：`商品详情获取失败: spuId=2096584332488638466` | 显式识别 `PRODUCT_NOT_FOUND` → 跳过（不可重试）；超时/服务不可用仍重试 |

验证：修复后重试消息按新逻辑消费（日志：`陈旧版本消息已忽略`、`商品不存在，跳过（不可重试）`），DLQ 水位零新增。

## 4. 沉淀门禁（追加到 rocketmq-toolkit）

- **失败分类**：消费者必须区分"可重试"（超时/5xx/连接）与"不可重试"（业务不存在/参数非法/版本过期），后者跳过并留日志；
- **版本幂等**：基于 ExternalGte/乐观锁写入的消费者，遇版本冲突必须视为"已应用更新版本"跳过，不得重试；
- **历史 DLQ 重放=韧性探针**：重放前先小批量验证，观察是否暴露当前代码的韧性缺口（本例即由重放发现）。

## 5. 全平台 DLQ 状态

```
%DLQ%* 全量：无（cart / product-index / recommend-behavior 均已清账）
```
