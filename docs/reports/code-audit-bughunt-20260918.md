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

### 库存 Redis 分桶 vs DB 账本（逐 SKU）

```
sku=1 DB=80 Redis=80 桶=2 ✓   sku=2 DB=146 Redis=146 ✓   sku=3 DB=117 Redis=117 ✓
sku=4 DB=198 Redis=198 ✓      sku=5 DB=179 Redis=179 ✓   sku=6 DB=78 Redis=78 ✓
sku=7 DB=300 Redis=300 ✓      sku=990001 DB=97 Redis=97 ✓ sku=20 DB=100 Redis=100 ✓
sku=27411 DB=998 Redis=998 ✓  sku=27825 DB=499 Redis=499 ✓
（sku=8/9/10 未缓存=惰性加载正常）
```

- **11 个已缓存 SKU 的 Redis 分桶合计与 DB available_stock 逐一精确相等**；惰性未缓存 SKU 属预期；
- 期间修正两处审计脚本自身错误（列名 `available`→`available_stock`、逻辑表名 `t_order`→物理 `t_order_N`），确保结论可信。

---

## 六、逐业务域一致性审计（2026-09-18 深夜·第二轮）

| 业务域 | 口径 | 结果 |
|---|---|---|
| 库存 | Redis 分桶合计 vs DB 账本 | **11/11 精确相等** |
| 优惠券 | Redis 库存 vs DB remain_count | **3/3 相等** |
| 计数 | Redis 计数 vs DB t_counter | **15/15 相等** |
| 计数投影 | counter DB vs ES note_index（赞/藏/评） | 精确相等（2 笔记抽查） |
| 订单 | 16 物理分片 vs 映射表 | **75 = 75** |
| 订单↔支付 | 已付款单必须有成功支付记录 | 新链路 0 异常（2 条为 test-09 直调回调的**历史测试脏数据**，脚本已改真实链路） |
| 通知未读 | Redis vs DB is_read=0 | **4/4 相等** |
| 用户地址 | 每用户默认地址唯一 | 无重复 ✓ |
| 商品/笔记索引 | ES vs DB | 相等（笔记曾多 1 条乱序测试遗留，已清） |
| 推荐 | 行为上报→落库→离线计算→热池 | **全链路验证**：上报 200 → `content.t_user_behavior` +1 → 热门池 `myxhs:recommend:hot:global` 更新（score=1，日志"2 条，cost=6ms"） |

### 本轮发现（文档/口径类）

1. **遗留重复表**：`my_xhs_analytics.t_user_behavior`（空，旧 `sql/mysql-user-init.sql` 建） vs 活跃 `my_xhs_content.t_user_behavior`（search 服务写入）——xhs-ai 的 business-analysis 文档仍指向前者（易误导 BI 查询）；
2. **跨库硬编码**：`RecommendComputeJob` 有 `FROM my_xhs_analytics.t_favorite`——单实例同机可行，**生产拆库即断**（部署耦合提醒）；
3. **特征增量语义**：`t_item_feature` 只处理"有新行为但无特征记录"的笔记，已有指纹的 like_count 不刷新（设计如此，但口径需注明）。

### 追加域（第三批）

| 业务域 | 口径 | 结果 |
|---|---|---|
| 购物车 | Redis items vs DB t_cart_item（无 deleted 列，物理删除） | user 2100874164006232066: **1 = 1** ✓ |
| 内容评论数 | DB 评论 vs 计数器（countType=3） | **实时链路验证**：发 1 条评论 → DB 5→6、计数器 8→9 **精确同步**；历史偏差（5/8、1/0）= 09-06/08 种子数据（无事件写入） |
| IM | 消息表 + Redis unread/seq | 5 条消息、Redis key 正常（轻量 sanity；深度对账需设计口径） |
| Feed | inbox/outbox | **纯 Redis 设计**（无 DB 镜像），key 正常（轻量 sanity） |

### 追加域（第四批：IM/Feed 深度对账）

| 业务域 | 口径 | 结果 |
|---|---|---|
| IM · seq | Redis `im:seq:{conv}` vs DB max(seq_no) | conv=2097329110419406849: **Redis=5 = DB_max=5** ✓ |
| IM · 未读 | Redis Hash vs 关系表 vs 消息表（三口径） | user10001: 0=0=0；user10002: **5=5=5** ✓ |
| Feed · 收件箱成员 | ZSet 成员须存在于内容库 | 真实用户 10001 的 3 个成员全部在库 ✓；测试用户含 `999999999999`（test-13 dev 端点写入=测试污染） |
| Feed · 清理机制 | feedCleanupJob（7 天过期 + 500 上限裁剪） | 任务定义正确；修复后**首次生效于次日 3:00**（当日无日志属预期） |

> 结论：**13 个业务域全部审计完毕、全部一致或可归因**；IM 三口径未读完全吻合，Feed 真实数据有效。
