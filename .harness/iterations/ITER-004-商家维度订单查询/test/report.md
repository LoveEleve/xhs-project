---
iteration: ITER-004
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：真实测试证据齐备前，本迭代不得进入发布准备（见开发流程规范.md「阶段间接口」）
---

# 测试报告: 商家维度订单查询（异构索引）

> 机器可读的迭代协议产物之一。记录**真实执行证据**，是发布准备的前置条件。

## 执行摘要

- 执行时间: 2026-10-17（P0 + P1 + P0 深审查修复后的最终回归）
- 环境 profile: JDK 17（`/env/javaEnv/jdk17`）+ Maven 3.9 离线（`-o`）；**无运行时环境**（项目规则：单测口径；删 `target/test-classes` 强制全量重编防增量假绿）
- 命令: `mvn -o -pl my-xhs-common,my-xhs-product,my-xhs-coupon,my-xhs-order,my-xhs-inventory,my-xhs-payment,my-xhs-search -am test` + 全仓 `mvn -o -q compile`
- 结果: ✅ 通过（BUILD SUCCESS；**368 全绿**：common 93 / product 14 / coupon 11 / **order 118** / inventory 20 / **search 39** / payment 73——较 352 基线净增 16，零回归）

## 用例执行结果

| TC | 归属 AC | 结果 | 断言/证据 | 备注 |
|----|---------|------|----------|------|
| TC-1 | AC-1 | 🟢 | canal order_instance 配置核验（review-p0 R-01~R-05：16 表正则全匹配 / slaveId=1004 唯一 / partitionHash 16/16 / compose 挂载 / 键集合与既有 instance 一致） | 脚本+文件核验 |
| TC-2 | AC-2 | 🟢 | `SellerSubOrderIndexSyncConsumerTest#insertUpsertsDocument`（列名→文档映射 + 版本=ts） | |
| TC-3 | AC-2 | 🟢 | `#updateReplayIsIdempotent`（同消息 ×2 → 同 id 同版本 upsert，ExternalGte 兜底） | |
| TC-4 | AC-2 | 🟢 | `#deleteEventSoftDeletes` + `#logicalDeleteSoftDeletes`（DELETE/deleted=1 → 软删除标记） | |
| TC-5 | AC-2 | 🟢 | `#invalidMessagesSkipped` + `#rowWithoutSubOrderNoSkipped`（跳过不重试） | |
| TC-6 | AC-3 | 🟢 | `#esFailureRecordsCompensationAndRetries`（Redis 补偿键 + 上抛）；`#multiRowBatchFailureRecordsRemaining`（批次覆盖：当前失败行+未处理行） | 深审查修复 R-17 |
| TC-7 | AC-4 | 🟢 | `SellerOrderSearchControllerTest#missingSellerIdRejected` / `#negativeSellerIdRejected`（40002 防全量泄漏） | |
| TC-8 | AC-4 | 🟢 | `#nonAdminCallRejected`（403 不触达 service） | |
| TC-9 | AC-5 | 🟢 | `SellerOrderSearchServiceTest#buildSearchRequestBasic` / `#buildSearchRequestWithFilters`（必带 sellerId+deleted；status/range 可选；倒序；from/size） | |
| TC-10 | AC-5 | 🟢 | `#paginationDefaultsAndCap`（默认 20；上限 100 截断） | |
| TC-11 | AC-6 | 🟢 | "ES 只做发现；写操作/终态以 DB+CAS 为准"注释落位 ×4（消费者/写入器/服务/控制器） | 深审查补齐 R-33 |
| TC-12 | AC-7 | 🟢 | `OrderServiceTest#sellerSubOrderDetailResolvesRoute`（subOrderNo→orderNo→映射表→userId→路由读 + 行明细） | |
| TC-13 | AC-7 | 🟢 | `#sellerSubOrderDetailMappingMissing`（明确报错，不静默不 scatter）+ `#sellerSubOrderDetailBadFormat` + `#sellerSubOrderDetailSubMissing` | |
| TC-14 | AC-8 | 🟢 | `SellerSubOrderIndexBackfillJobTest#resumesFromCheckpoint`（断点续传）+ `#writeFailureThrowsAndKeepsCheckpoint`（整批成功才推进；可重入） | |
| TC-15 | AC-8 | 🟢 | `#pagesThroughAndAdvancesCheckpoint`（满批继续/不满批收尾）+ `#scanFailureKeepsCheckpoint`（失败保留断点） | |
| TC-16 | AC-9 | 🟢 | 七模块全量 368 全绿 + 全仓编译 | 强制全量重编 |
| TC-17 | AC-5 | 🟢 | `#sellerIdZeroAllowed`（平台自营合法） | |
| TC-18 | AC-5 | 📝 | 深分页 from+size 限制：代码 javadoc 记录（search_after 为演进） | 边界记录 |
| TC-19 | AC-3 | 📝 | ES 不可用 → 列表报错降级；写路径零依赖（架构性保证） | 边界记录 |
| TC-20 | AC-2 | 🟢 | 物理表名（t_sub_order_0/1/2）正常处理（表名不参与映射） | |

## 深审查记录（P0，见 test/review-p0.md）

- 方式：**40 项清单先行 → 逐项校验 → 回填结论**（脚本核验：properties 转义/正则全匹配/partitionHash 覆盖/JSON 合法性/DDL 列名对照）
- 🔴 严重问题 0；🟡 发现并修复 4（R-17 批次补偿覆盖 / R-20 版本冲突判定对齐既有实现 / R-29 服务层纵深守卫 / R-33 写入器守卫注释）；📝 记录 6
- P1 执行中修正：详情测试数据缺陷（"NO-DASH-FORMATLESS" 含横杠 → 换无横杠输入）；文档构建器抽取为共享组件（增量/存量同一映射，杜绝结构漂移）

## 部署说明（运维口径）

1. **Canal**：新增 `order_instance` 配置后需**重启 canal 容器**生效（新 instance 随容器加载）
2. **顺序**：**先回填、后切流量**——Canal 只捕增量，存量须先回填（`POST /api/search/seller-order/backfill` 手动触发或等待 4:30 定时任务）
3. **依赖**：search 域需配置 `INTERNAL_TOKEN`（env）供内部 Feign 调用 order 扫描端点

## 覆盖对账

- AC 总数: 9 / 已覆盖: 9（其中 2 条 📝 边界记录、无独立用例——深分页/ES 降级为架构性/文档性结论）
- 无真实证据的 AC 不得标记为「已验证」

## 结论

✅ 可进入发布准备（代码与单测口径）；⚠️ 运行时验证（真实 Redis/MySQL/MQ/Canal/ES 全链路）按项目规则后置
