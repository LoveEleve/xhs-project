---
iteration: ITER-001
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-16
updated: 2026-10-16
# 阶段间接口：真实测试证据齐备前，本迭代不得进入发布准备（见开发流程规范.md「阶段间接口」）
---

# 测试报告: 订单拆单与行级售后改造

> 机器可读的迭代协议产物之一。记录**真实执行证据**，是发布准备的前置条件。

## 执行摘要

- 执行时间: 2026-10-16 22:51（含审查轮修复后的最终回归）
- 环境 profile: JDK 17（`/env/javaEnv/jdk17`）+ Maven 3.9 离线（`-o`）；**无运行时环境**（项目规则：单测口径；删 `target/test-classes` 强制全量重编防增量假绿）
- 命令: `mvn -o -pl my-xhs-order,my-xhs-inventory,my-xhs-product -am test`
- 结果: ✅ 通过（BUILD SUCCESS；**251 全绿**：common 93 / product 14 / coupon 11 / order 113 / inventory 20）

## 用例执行结果

| TC | 归属 AC | 结果 | 断言/证据 | 备注 |
|----|---------|------|----------|------|
| TC-1 | AC-1 | 🟢 | `OrderTransactionServiceTest#executeLocalTransaction_splitBySeller`（1 父+2 子+3 行；Σ子单实付=父实付=360） | |
| TC-2 | AC-1 | 🟢 | `#executeLocalTransaction_insertOrderFails`（整体回滚，不写明细/消息） | 存量用例 |
| TC-3 | AC-2 | 🟢 | `#executeLocalTransaction_allocationPersisted`（200/100 摊 20/10；Σshare=30） | |
| TC-4 | AC-2 | 🟢 | `#executeLocalTransaction_allocationRounding`（3.34/3.33/3.33，余分给最小行 ID）+ `AftersaleServiceTest`（部分退：90 = 100 − 10） | |
| TC-5 | AC-3 | 🟢 | `AftersaleServiceTest#apply_duplicateByItemType`（同 item+type 拒绝） | |
| TC-6 | AC-3 | 🟢 | `AftersaleServiceTest#apply_bindsItemIdAndUsesStoredShare`（item 锚点 + 落库分摊 + 闸门路径） | |
| TC-7 | AC-4 | 🟢 | `SubOrderServiceTest#computeParentStatus_exhaustive`（25 组表驱动穷尽，含混合终态） | |
| TC-8 | AC-5 | 🟢 | `OrderServiceTest#deliverOrder_success`（子单 CAS + 聚合 + 子单通知） | |
| TC-9 | AC-5 | 🟢 | `OrderTerminationServiceTest`（父+全子同事务取消，verify cancelAll） | |
| TC-10 | AC-6 | 🟡 | 部分退款不动父单：聚合规则表驱动（AC-4）+ onRefundSuccess 守卫间接覆盖 | 无独立端到端用例（无运行时） |
| TC-11 | AC-6 | 🟢 | `OrderServiceTest#onRefundSuccess_shipped`（status=2 全退收敛 + 全子单退款 verify） | 审查轮补测 |
| TC-12 | AC-7 | 🟢 | 五模块全量：common 93 / product 14 / coupon 11 / order 113 / inventory 20 = **251 全绿** | 强制全量重编 |
| TC-13 | AC-7 | 🟢 | `SubOrderServiceTest#recompute_conflictRetry`（CAS 冲突重试收敛）；回调 vs 关单为存量用例 | |
| TC-14 | AC-8 | 🟢 | 拆单用例含 seller 分组断言（2001/2002 两子单） | |
| TC-15 | AC-9 | 🟡 | 详情/列表批量查询：代码审查（子单一次 IN + 行按 subOrderId 分组）；无查询计数用例 | 记补测 |
| TC-16 | AC-10 | 🟢 | 《改造后文章修订记录》+ wiki 数据模型更新 | 人工检查 |

## 失败 / 未覆盖项

- ⚠️ TC-10 / TC-15：无独立自动化用例（无运行时环境 + 未引入查询计数断言）——保留人工核对，记为补测项

## 审查轮修复记录（2026-10-16 深审查）

| 发现 | 严重度 | 修复 |
|------|--------|------|
| 崩溃窗口无兜底：父单状态已提交但子单推进/收敛未执行（如支付 0→1 后进程崩溃）→ 子单永久滞留 | 中 | ① 支付/退款重复通知路径**机会式自愈**（`advanceAllToPaid` / `refundAll` 幂等重放）；② `OrderCalibrationJob` 日终**聚合兜底**（recomputeParentStatus，幂等前向收敛） |
| 商品域创建流不支持 seller_id（新 SKU 只能默认 0） | 低 | `SkuCreateRequest` 增 `sellerId`（缺省自营 0） |
| `SpuServiceTest` 构造器过期（存量：SpuService 新增 rocketMQTemplate 后测试未同步 → 商品测试整体编译不过） | 中 | 补 mock + 构造参数；商品域 14 测恢复全绿 |
| 调用方未同步新 API（3 个 e2e 脚本 + smoke-test 还传 orderId） | 中 | 脚本已适配子订单号（先查详情取 `subOrderNo`；`py_compile`/`bash -n` 语法校验通过，运行时按项目规则后置）；**前端 `order.ts` + 页面归入"前端适配"待办**（UI 需先有按子单展示才能取 subOrderNo） |

## 覆盖对账

- AC 总数: 10 / 已覆盖: 10（其中 3 条 🟡 部分证据）
- 无真实证据的 AC 不得标记为「已验证」

## 结论

✅ 可进入发布准备（代码与单测口径）；⚠️ 运行时验证（真实 Redis/MySQL/MQ）按项目规则后置（不需要跑起来）
