---
iteration: ITER-005
status: confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 用途：C-005 深度代码审查（清单先行 → 逐项校验 → 回填结论）
---

# 深度审查: C-005（商品模块生产化改造）

## 审查范围（文件清单）

| # | 文件 | 类型 |
|---|------|------|
| 1 | `my-xhs-product/.../service/SpuService.java`（默认下架/指标/日志/双删移除/重建异步/缩进/常量） | 修改·服务 |
| 2 | `my-xhs-product/.../service/SkuService.java`（价格防御/pending set/N1N2） | 修改·服务 |
| 3 | `my-xhs-product/.../dto/request/SkuCreateRequest.java`（@DecimalMin） | 修改·DTO |
| 4 | `my-xhs-product/.../job/InventoryInitRepairJob.java` | 新增·Job |
| 5 | `my-xhs-product/.../consumer/ProductCacheCanalEvictConsumer.java` | 新增·消费者 |
| 6 | `my-xhs-product/.../consumer/CacheEvictConsumer.java`（注释同步） | 修改·注释 |
| 7 | `my-xhs-product/.../service/CategoryService.java`（refresh） | 修改·服务 |
| 8 | `my-xhs-product/.../controller/ProductController.java`（refresh 端点） | 修改·控制器 |
| 9 | `my-xhs-product/.../ProductApplication.java`（@EnableScheduling） | 修改·配置 |
| 10 | `my-xhs-common/.../constants/RedisKeyConstants.java`（新常量） | 修改·常量 |
| 11 | `my-xhs-product/src/main/resources/application.yml`（repair 配置） | 修改·配置 |
| 12 | `scripts/seed-data.py`（显式上架） | 修改·脚本 |
| 13 | 测试 ×5 | 新增·测试 |

## 审查清单（逐项校验结论）

> 结论标记：✅ 通过 / 🟡 发现并修复 / 📝 记录（边界）

### A. 业务正确性

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-01 | createSpu 默认下架路径闭环 | 全模块 ON_SHELF 使用点核对 | ✅ 列表（仅 ON_SHELF）/详情（非上架 null）/上架校验（≥1 上架 SKU）/bloom add 不受影响；SKU 创建即上架在 SPU 下架时不可见（过滤链正确） |
| R-02 | 价格校验顺序/边界 | 读码 + 测试 | ✅ DTO @DecimalMin("0") + 服务层防御（insert 前、负数拒/0 过）；null 由既有 @NotNull 拦截 |
| R-03 | pending set 记录完整性 | 读码 | ✅ 三分支全覆盖（业务失败/异常/r=null 均落 else → recordInitFailure）；Redis 写失败降级告警（依赖 metric+手动） |
| R-04 | 补偿 Job 幂等/锁/中断 | 读码 + 测试 | ✅ 移除语义（成功/已初始化/SKU 不存在）；锁 tryLock(0,120s) + `isHeldByCurrentThread` 保护解锁；InterruptedException 恢复中断位 |
| R-05 | Job 与手动端点共存 | 读码 + 测试 | ✅ "已初始化"视为成功移除（幂等）；手动重试成功后 Job 下轮幂等收敛 |

### B. 缓存一致性重构

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-06 | Canal 消费者：解析/过滤/去重/幂等 | 读码 + 测试 | ✅ canal JSON 解析（对齐既有消费者）；t_spu/t_sku 过滤、非目标表 debug 跳过；LinkedHashSet 同批去重；删除幂等 |
| R-07 | 行缺失 id/spu_id 静默跳过 | 读码 | 🟡 **发现并修复**：原静默跳过（行镜像/格式异常不可见）→ 补 WARN 日志 + 测试（DELETE 行镜像边界注释） |
| R-08 | 失败重试/指标/trace | 读码 + 测试 | ✅ 抛 IllegalStateException 触发 MQ 重试；`myxhs_product_cache_evict_total{source=canal,result}`；trace restore/clear |
| R-09 | 双删移除完整性 | grep + 读码 | ✅ `SPU_DELAY_EVICT_SCHEDULER` 代码零残留（仅说明性注释）；`updateSpu` 行为=立即删+Canal；@PreDestroy/类 javadoc/注释同步 |
| R-10 | 三层一致性语义 | 读码 | ✅ ① 立即删（快路径，保留）② 删失败 CACHE_EVICT_TOPIC 兜底（写入侧，保留）③ Canal 最终保证（新增）；降级路径（Redis 故障 WARN）保留 |

### C. 可观测与质量

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-11 | 指标族命名/标签/注册 | 读码（8 个指标清单） | ✅ 统一 `myxhs_product_*` 族；evict_total 双标签集（source=write\|canal × result）一致；gauge 在 @PostConstruct 注册 |
| R-12 | 日志降噪清单 | 逐行核对 | ✅ 6 处热路径 INFO→DEBUG（布隆拦截/空值命中/空值过期/L2 逻辑过期/L2 miss/非上架详情）；降级路径（Redis 故障/锁等待/刷新完成/evict 失败）保留 INFO/WARN/ERROR |
| R-13 | N1/N2 单查 + 无残留 | grep + 测试 | ✅ `resolveSpuImage` 仅注释提及；getSkuDetail/listSkusBySpuId 均有 times(1) 断言 |
| R-14 | 布隆重建异步 | 读码 + 测试 | ✅ 异步线程内取锁/放锁（M18 教训）；降级窗口=flag false 期间直查（正确）；失败残留 flag=false 与启动加载路径同口径（📝 记录）；返回语义测试覆盖 |
| R-15 | 缩进/常量/注释 | 读码 | ✅ initBloomFilter 嵌套缩进修复（嵌套层级正确）；`CacheEvictMessage.TOPIC` 引用；CacheEvictConsumer 注释同步 |

### D. 集成/部署

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-16 | @EnableScheduling 副作用 | 全模块 grep | ✅ product 仅新 Job 一个 @Scheduled——无意外激活 |
| R-17 | yml 键 ↔ 占位符 | 核对 | ✅ `myxhs.product.inventory-init.repair-interval-ms`（yml:151 ↔ @Scheduled 占位符）一致 |
| R-18 | 新常量一致性 | 读码 | ✅ `PROJECT_PREFIX("myxhs:") + "product:inventory-init:pending"` 与文档值一致 |
| R-19 | seed-data.py | py_compile + 读码 | ✅ PUT + query 参数 + `{'X-User-Id','1', **ADM}`（ADM=X-Admin-Call）头部；位于建 SKU 后（流程正确） |
| R-20 | 网关鉴权链 | 网关配置 | ✅ `/api/product/category/**` 在 HMAC 白名单（376）、JWT 仍需；refresh 端点保护=X-Admin-Call（服务层）——与既有管理端点同口径（管道类） |
| R-21 | 分类刷新 Redis 降级语义 | 读码 | 📝 记录：Redis 不可用时 delete/warmUp 均降级返回 0 → 端点消息"0 个节点"有歧义（WARN 日志可溯源；不阻塞） |

### E. 测试与回归

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-22 | 用例 ↔ AC 对账 | 26 TC 对照 | ✅ AC-1~10 全覆盖（3 条为代码/脚本核验） |
| R-23 | 全量回归 | mvn test | ✅ 七模块（见测试报告回填） |
| R-24 | 测试盲点 | 用例集评估 | ✅ 本审查已补 R-07 用例；📝 余下：租约过期重复执行（幂等 benign，记录）、降级路径（Redis 故障返回 0，记录） |

### F. 一致性/范围

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-25 | 与 solution.md 四阶段对照 | 逐条核对 | ✅ ①业务正确性（T1.1~1.4）②一致性重构（T2.1/2.2）③可观测质量（T3.1~3.5）④收口（T4.1/4.2）全部落地 |
| R-26 | 无 scope creep | 变更文件核对 | ✅ 未做：审核流/版本/强制主库/CAS（记录）；未碰权限类 |
| R-27 | 命名一致性 | 全局核对 | ✅ 指标/Redis 键/消费组/端点命名与既有惯例一致 |

## 校验执行记录

| 项 | 命令/证据 | 结果 |
|----|----------|------|
| R-01 | 全模块 ON_SHELF 使用点 grep（11 处逐一核对） | 过滤链闭环 ✓ |
| R-07 | 修复后测试（missingSpuIdWarnsAndSkips） | ✓ |
| R-11 | 指标名清单 grep | 8 个统一族 ✓ |
| R-16 | @Scheduled 全模块 grep | 仅新 Job ✓ |
| 修复后回归 | `mvn -o -pl my-xhs-product -am test` | product 35 全绿（+1） |
| 全量回归 | 七模块 | 见测试报告 |

## 总结

- 🔴 严重问题：**0**
- 🟡 发现并修复：**1**（R-07 行缺失字段静默跳过 → WARN 日志 + 测试）
- 📝 记录（边界）：R-14 重建失败残留 flag=false（与启动路径同口径）；R-21 Redis 降级返回 0 的消息歧义；R-24 租约过期重复执行（幂等 benign）
- 🟢 通过项：其余 23 项
- **结论：C-005 可收口（回归通过后提交）**
