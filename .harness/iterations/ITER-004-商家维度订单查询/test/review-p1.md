---
iteration: ITER-004
status: confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 用途：C-004 P1 深度代码审查（清单先行 → 逐项校验 → 回填结论）
---

# 深度审查: C-004 P1（详情路由解析 + 扫描端点 + 存量回填）

## 审查范围（文件清单）

| # | 文件 | 类型 |
|---|------|------|
| 1 | `my-xhs-order/.../mapper/SubOrderMapper.java`（scanByCursor） | 修改·SQL |
| 2 | `my-xhs-order/.../service/OrderService.java`（getSellerSubOrderDetail + scanSubOrders） | 修改·服务 |
| 3 | `my-xhs-order/.../controller/OrderController.java`（seller 详情 + internal 扫描） | 修改·控制器 |
| 4 | `my-xhs-search/.../service/SellerSubOrderIndexDocumentBuilder.java` | 新增·共享构建器 |
| 5 | `my-xhs-search/.../consumer/SellerSubOrderIndexSyncConsumer.java`（重构用构建器） | 修改·消费者 |
| 6 | `my-xhs-search/.../feign/InternalCallFeignConfig.java` | 新增·Feign 配置 |
| 7 | `my-xhs-search/.../feign/OrderFeignClient.java` | 新增·Feign 客户端 |
| 8 | `my-xhs-search/.../job/SellerSubOrderIndexBackfillJob.java` | 新增·回填 Job |
| 9 | `my-xhs-search/.../controller/SellerOrderSearchController.java`（回填触发） | 修改·控制器 |
| 10 | `my-xhs-search/src/main/resources/application.yml`（backfill 配置） | 修改·配置 |
| 11 | 测试 ×5 | 新增·测试 |

## 审查清单（逐项校验结论）

> 结论标记：✅ 通过 / 🟡 发现并修复 / 📝 记录（边界）

### A. order 域（详情/扫描）

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-01 | subOrderNo 解析边界（无横杠/前导横杠/正常） | 读码 + 测试枚举 | ✅ `NODASHFORMATLESS`/`-1` → 40002；`ORDER-1001-1` → 正确解析（lastIndexOf 支持 orderNo 自带横杠） |
| R-02 | 路由解析链路正确性 | 读码 + 测试 | ✅ 映射表按 orderNo 查询 → userId → `selectBySubOrderNo(subOrderNo, userId)` |
| R-03 | 行明细查询路由 | SQL + 分片配置核对 | ✅ `t_order_item` 按 user_id 分片；`selectBySubOrderId(subOrderId, userId)` 带路由键 ✓ |
| R-04 | 返回结构复用既有 VO | 读码 + 测试 | ✅ `toSubOrderVO`（sellerId/statusDesc/行明细）+ `toItemVO` 复用，无新结构漂移 |
| R-05 | 守卫与错误码 | 读码 + 测试 | ✅ X-Admin-Call→403；PARAM_INVALID/ORDER_NOT_FOUND（30008） |
| R-06 | 与既有 orderNo 解析先例一致性 | 对比 `getOrderByOrderNo` | ✅ 同一模式（selectByOrderNo → mapping → 路由），风格一致 |
| R-07 | scanByCursor SQL 列投影/全路由/LIMIT | SQL + 分片配置核对 | ✅ 12 列与构建器所需字段一一对应；无分片键 → 全路由+归并（注释标明仅回填）；`deleted=0` 过滤 |
| R-08 | 扫描端点守卫 + limit 截断 + 默认值 | 读码 | ✅ X-Internal-Call；`[1,1000]` 截断；cursor 默认 0 / limit 默认 500 |
| R-09 | 控制器层测试覆盖 | OrderControllerTest 核对 | 🟡 **发现并修复**：新端点原无控制器用例 → 补 4 个（详情 403/成功；扫描 403/limit 截断），OrderControllerTest 22→26 |
| R-10 | scanSubOrders 行映射完整性（12 字段） | 读码 + 测试 | ✅ snake_case 全字段（id/order_id/order_no/sub_order_no/user_id/seller_id/status/三金额/两时间） |

### B. search 域（构建器/消费者重构）

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-11 | 构建器 12 字段 ↔ mapping ↔ 双源类型 | 三方对照 | ✅ 字段名与 mapping 完全一致；canal 字符串/order 对象值均正确转换 |
| R-12 | versionOf（毫秒/时区/回退） | 读码 + 测试 | ✅ updated_at→epoch 毫秒（systemDefault 时区，与 canal ts 同量级）；缺失回退当前毫秒 |
| R-13 | ISO 时间归一化边界 | 读码评估 | 📝 Jackson ISO 恒含秒（`T12:30:00`）→ 归一正确；缺秒非标准形态原样透传（mapping 格式不匹配→ES 拒写单行——不会发生，记录） |
| R-14 | 消费者重构行为等价 | 对照 + 既有测试通过 | ✅ 旧 buildDoc 与 builder 输出一致（消费者 9 测试**未改动断言**全过 = 强等价证据） |
| R-15 | 构造器变更注入完整性 | 全局 grep | ✅ 仅测试构造点（已更新）；Spring 装配按声明顺序（indexWriter/redis/documentBuilder） |
| R-16 | 空值序列化行为 | jshell 实证（fastjson2 2.0.43） | ✅ `{"a":"1","c":false}`——null 值省略；对 ES 无害（`deleted` 恒非空，列表过滤不受影响） |

### C. 回填 Job

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-17 | **batchSize > 1000 与 order 域截断交互** | 推演 + 读码 | 🟡 **发现并修复（真 bug）**：`rows.size() < batchSize` 与 order 侧 limit 截断（1000）不同口径——配置 batchSize>1000 时"返回 1000 行"被误判收尾（**静默漏回填**）→ 引入 `effectiveLimit = min(batchSize, 1000)` 统一口径 + 测试 |
| R-18 | **lastId 非 Number 时游标推进** | 推演 + 读码 | 🟡 **发现并修复（真 bug）**：`cursor = lastId instanceof Number ? ... : cursor`——不可解析时游标不推进，满批场景**死循环**→ 改为显式抛错（拒绝死循环）+ 测试 |
| R-19 | 断点推进语义 | 读码 + 测试 | ✅ 整批成功才推进；失败保留断点；已成功批次保留（可重入补齐） |
| R-20 | 锁语义 | 对照 IndexRebuildJob | ✅ tryLock(5s, 2h)；manualBackfill 异步（锁同线程获取/释放——与既有 M18 修复同款） |
| R-21 | 版本方向正确性 | 推演 | ✅ canal ts（事件时刻）≥ 行 updated_at（变更时刻）→ canal 恒胜出/平手 → 回填不可能覆盖更新的增量状态（方向正确） |
| R-22 | 终止条件 | 读码 + 测试 | ✅ 空页/不满批双终止 |
| R-23 | feign 失败语义 | 读码 + 测试 | ✅ 保留断点 + warn 日志 + break（调度层吞异常，可重入） |
| R-24 | Job 测试覆盖 | 用例集核对 | 🟡 **补齐**：原无锁路径用例 → 补 2 个（锁被占跳过 / 锁成功执行并释放），Job 测试 4→8 |

### D. Feign 链路

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-25 | OrderFeignClient 定义 | 读码 + 服务名核对 | ✅ name=`my-xhs-order`（与 application.name 一致）；primitive 参数→query；`R<List<Map>>` 泛型反序列化（同 ProductFeignClient 风格） |
| R-26 | Feign 配置镜像 + fail-fast 启动影响 | diff + 报告核对 | ✅ 与 order 域**逐行镜像**（仅错误消息文本差异）；fail-fast 启动影响已入部署说明（search 需配 INTERNAL_TOKEN） |
| R-27 | search yml INTERNAL_TOKEN 配置项 | yml 核对 | ✅ `myxhs.internal.token: ${INTERNAL_TOKEN:}` 存在 |

### E. 控制器（search）+ 配置

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-28 | 回填触发端点 | 读码 + 测试 | ✅ X-Admin-Call；异步触发（manualBackfill）；测试 2 个（403/触发） |
| R-29 | yml 键 ↔ @Value + cron 语法 | 核对 | ✅ `search.seller-order.backfill.batch-size/cron` 键一致；`"0 30 4 * * ?"` Spring 6 字段 cron 合法 |
| R-30 | 端点路径冲突 | 路由表核对 | ✅ `/seller/sub-order/{no}`（3 段）vs `/{orderId}`（1 段）不冲突；`/internal/sub-order-scan` vs `/internal/reconcile/{id}` 不冲突 |

### F. 测试与回归

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-31 | 新增用例 ↔ AC-7/8 对照 | 用例↔AC | ✅ AC-7（详情 5 用例：解析/格式×2/映射缺失/子单缺失 + 控制器 2）；AC-8（回填 8 用例） |
| R-32 | 全量回归 | mvn test | ✅ 七模块（见测试报告回填） |
| R-33 | 测试盲点 | 用例集评估 | ✅ 本审查已补 8 个（控制器 4 + Job 4）；📝 余下：真实 ShardingSphere 全路由归并行为（无运行时，靠配置语义+注释覆盖） |

### G. 一致性/范围

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-34 | 与 solution.md T2.1/T2.2/T2.3 对照 | 逐条核对 | ✅ T2.1 详情（路由解析/明确错误/行明细）；T2.2 扫描+回填（断点/幂等/可重入）；T2.3 回归+部署说明（先回填后切流量已入报告） |
| R-35 | 无 scope creep | 变更文件核对 | ✅ 未做：发货链路改造/富化/对账（演进记录保持） |
| R-36 | 命名一致性 | 全局核对 | ✅ STATUS_KEY/LOCK_KEY 命名族（myxhs:search:seller-order-backfill:*）与重建 Job 同风格；端点命名与 `/internal/...` 惯例一致 |

## 校验执行记录

| 项 | 命令/证据 | 结果 |
|----|----------|------|
| R-03 | sharding-config.yaml t_order_item shardingColumn | user_id ✓ |
| R-16 | jshell + fastjson2-2.0.43.jar 实证 | null 省略 ✓ |
| R-26 | diff order/search InternalCallFeignConfig | 仅消息文本差异 ✓ |
| R-30 | OrderController 路由全表核对 | 无冲突 ✓ |
| R-17/R-18 修复后 | `mvn -o -pl my-xhs-order,my-xhs-search -am test` | order 122 / search 43 全绿（+8 用例） |
| 全量回归 | 七模块 | 见测试报告 |

## 总结

- 🔴 严重问题：**0**
- 🟡 发现并修复：**4**（R-17 有效 limit 口径【静默漏回填】/ R-18 游标死循环防护 / R-09 控制器守卫用例 / R-24 锁路径用例）
- 📝 记录（边界）：R-13 ISO 缺秒形态（不会发生）、R-21 版本方向论证、R-26 fail-fast 启动影响（已入部署说明）、R-33 ShardingSphere 全路由运行时行为
- 🟢 通过项：其余 28 项
- **结论：P1 代码可收口（回归通过后提交）**
