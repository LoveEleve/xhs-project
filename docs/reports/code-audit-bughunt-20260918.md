# 代码级 Bug 猎捕审计（2026-09-18 深夜）

> 方法：静态模式扫描（7 类）+ 全服务日志异常聚类 + 关键机制核对。目的：找"还没暴露的 bug"。

## 一、静态模式扫描（main 源码全量）

| 模式 | 命中 | 结论 |
|---|---|---|
| 空 catch | 37 | 逐一 triage：4 处疑点=2 处归档模块（my-xhs-ai-app/mcp 不参与构建）+ 2 处"失败删版本号让 MQ 重试"的合理兜底 → 非 bug |
| 字符串 `==` | 12 | 全部为日志分隔线（`"====="`）误报 → 无 |
| BigDecimal.equals / SimpleDateFormat / printStackTrace | 0 | 无 |
| e.getMessage 直返客户端 | 11 | HomeController 降级消息（业务可读文案），非泄漏敏感栈 → 可接受 |
| Thread.sleep | 20 | 均在 Job/Buffer/退避，非请求热路径 → 可接受 |
| 双重检查无 volatile | 0 | 无 |
| Lua 脚本 | 23 | **全部被 Java 引用**；KEY/ARGV 声明与调用一致（运行期零 script 错误） |

## 二、全服务日志异常聚类（今日 + 昨日归档）

| 异常类 | 次数 | 归属窗口 | 状态 |
|---|---|---|---|
| CommunicationsException | 1530 | 僵尸连接事件（9-17）+ 重启窗口 | 已修（连接清理/发布纪律），comment 接口现 200 |
| MyBatisSystemException / RecoverableDataAccessException | 819/765 | 同上（t_comment MDL 阻塞） | 同上 |
| CompletionException / AnnotatedConnectException | 438/438 | 服务重启窗口（cart→product 12:13、payment→order 19:22） | 发布窗口预期，现健康 |
| ApplicationContextException / BeanCreationException | 104/74 | cart zone LB 接线风暴（09:39-09:57） | 已修（zone 接线修复后归零） |
| RedisConnectionException | 100 | Redis 演练/重启窗口 | 预期 |
| UnsatisfiedDependencyException | 7 | order Demo 首次启动（旧类未重编译） | 已修 |
| AsyncRequestNotUsableException | 6 | SSE 客户端主动断开 | 良性（可降噪） |

## 三、关键机制核对

- **MQ 消费者幂等**：27 个消费者逐一核对——helper（msgId SETNX）/ Lua 原子去重 / 条件更新 / 天然幂等（ZADD member、DELETE）三类覆盖，**无裸消费者**；
- **@Scheduled 与 XXL 双跑**：18 处 @Scheduled 均在进程内任务（带 Redisson 锁或幂等），与 XXL 任务无重叠（PaymentReconcileJob 的"双注解"为注释误报）；
- **运行时接口抽查**：comment/order/payment/cart/search 关键接口 200。

## 四、结论

- **本轮未发现新的活跃 bug**；历史异常全部可归因到已修复事件或发布窗口；
- 当日新写代码（GlobalExceptionHandler 400、ZoneAware 数据源、JPA/Multi-ORM Demo、契约测试）已随构建/测试/发布验证；
- 已知可优化项（不构成 bug）：SSE 断连日志可降噪、HomeController 降级文案可统一错误码。

---

## 五、运行态数据一致性审计（补充，2026-09-18 深夜）

| 域 | 结果 | 结论 |
|---|---|---|
| 订单分片 vs 映射表 | 16 物理分片 t_order 合计 **75** = 映射表 **75** | ✓ 一致 |
| 商品索引 vs DB | product_index **9** = t_spu 全量 **9**（含 3 条逻辑删除 tombstone） | ✓ 一致 |
| 笔记索引 vs DB | 审计初查 ES 37 vs DB 36 → 定位为 **1 条乱序测试遗留幽灵文档**（`2999999999999999999`，title=防乱序-旧版本，version=2e12）→ 已删除，refresh 后 **36=36** | ✓ 修复 |
| 删除语义核对 | 逻辑删除→ES **status=-1** tombstone（版本保护防复活）；搜索过滤 `status=2` 排除删除/未发布 | ✓ 设计正确 |
| 附带修复 | `NoteSearchService` Javadoc 写 `status=1（已发布）` 与实现 `status=2` 不一致 → 已修正 | ✓ |

> 追溯建议：压测/乱序类测试若直接向 ES 写入合成文档，应在用例结束清理（本次为历史遗留，已清）。
