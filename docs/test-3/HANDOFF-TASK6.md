# my-xhs 交接文档 — Task6（G2 闭环 + 监控/部署/启动体系修复 → G3 起）

> 2026-08-13 | 承接 Task5（G1 完成）→ 本阶段：G2 全量测试闭环、监控看板体系重建、部署包与对方同步、启动脚本根治、业务埋点 P1 修复。
> 给下一个 AI 的全量交接。**重点：测试进度（§四）、问题清单（§五）、本轮修复（§三）、执行纪律（§八）。**

---

## 零、状态速览（2026-08-13 17:30）

```
微服务 15 个 UP（本机 21.214.97.212，start-all.sh 95s 冷启动）| 中间件 27 容器（试验机 21.130.247.89）
测试：G1 107/108 ✅ | G2 43/43 ✅（3 轮回归 + 补做验证）| G3 未开始（已暂停，待用户指示）
修复：G2 期间约 22 项代码/配置/脚本修复（见 §三）
监控：10 看板 150 面板（jvm31/mysql18/node33/redis12/mq11/api9/biz11/tomcat4/hikaricp10/es11）| 14/14 服务 tomcat_threads + Hikari/业务 Timer bucket + MQ 消费/DLQ 完整标签
部署包：config/my-xhs-deploy-package.zip（336 文件，含 9 条 master 修复）；SYNC-NOTES-FOR-MASTER.md（给对方同步说明）
Token→/tmp/test_token.txt | .secrets/tokens.env（随机化）| 凭据: Xhs@2026#*
```

## 一、环境拓扑
- **微服务机 = 本机容器 21.214.97.212**（15 JVM：gateway19000/user19001/content19002/analytics19003/counter19004/product19006/cart19008/inventory19009/coupon19010/order19011/payment19012/notification19013/im19014/home19015/search19016）
- **中间件机 = 21.130.247.89**（27 容器，对方管理，无 docker 权限——**改动只能给脚本/配置**）
- **Redis Sentinel**：主 6379/从 6380/哨兵 26379（testlib 直连 6379=主）
- 微服务→中间件走 iptables 白名单

## 二、测试进度（主线）
- **G1 认证与用户：✅ 107/108**（docs/test-3/cases/G1-auth-user/）
- **G2 内容与社交：✅ 43/43**（docs/test-3/cases/G2-content-social/：G2-01 笔记 15 用例 / G2-02 评论 13 用例 / G2-03 点赞收藏 15 用例）
  - 3 轮回归全过（发布→Feed→ES 链路、评论→计数→通知、点赞→收藏→通知）
  - 补做验证：删除笔记 ES 同步（T-040）、Feed 死信分支、counterReconcileJob 对账
- **G3 商品与购物车：未开始**（下一步——product/cart + cartReconcileJob(P2-7/8) + product 索引 canal→ES + HotSkuDetector）

## 三、本轮修复清单（G2 期间，约 20 项——全部运行态验证）

### 代码修复（13 项）
| 项 | 内容 | 状态 |
|---|---|---|
| O1 | 批量详情 VIEW 事件放大 → readNoteDetail 无 VIEW 路径 | ✅ |
| T-031 | comment:list 死缓存键（P2-13 同款遗漏）| ✅ |
| T-032 | 子评论预载全局 LIMIT 截断 → 窗口函数每根取 4 | ✅ |
| T-033 | childCount 精确计数 Map key 类型失效 → List<Map> 转换 | ✅ |
| T-034 | 点赞/关注通知链路缺失（P1）→ analytics ContentFeignClient + LikeService/FollowService | ✅ |
| T-035/035b | 计数 key 无 TTL（INCR + Set-based 两路径）→ Lua EXPIRE（**注意 EXPIRE 必须在 INCR 后**，否则首次创建无效）| ✅ |
| T-036 | UNCOMMENT 固定减 1 → Consumer 读 count 字段 + CounterService delta | ✅ |
| T-040 | 逻辑删除笔记 ES 不同步（P1）→ indexNoteFromCanal 检查 deleted | ✅ |
| R5 | 关注通知 targetType=2（商品语义错误）→ 不填 | ✅ |
| R6 | testlib.call 加 query 参数（query 参与签名 T-009/011）| ✅ |
| 死锁 Binder | micrometer 1.12.5 无 JvmDeadlockMetrics → 自定义 MeterBinder（ThreadMXBean）| ✅ 14/14 |
| gateway 标签（P1）| gateway 不依赖 common → 指标无 application 标签 → GatewayMetricsConfig 注入 | ✅ |
| 业务埋点（P1）| **预注册无标签 vs 业务带标签同名冲突 → Prometheus 输出层吞带标签系列 → 业务指标恒 0** → 预注册改带业务标签空值 | ✅ 验证 mode="publish"=1 |
| T-042 | **ProductIndexSyncConsumer 忽略 deleted 列 → 逻辑删除 SPU 残留 ES（T-040 同款）** → indexProductFromCanal 检查 deleted=1 → deleteProduct + search 重启 | ✅ |
| 埋点标签落地（P1 收尾）| common v2（预注册带标签）**13 服务 rm -rf target 重打包 + 全量重启** → `mq_consume_total{topic,consumerGroup,result}` 完整标签出数（验证 FEED_TOPIC 消费=1）| ✅ 无需对方改代码 |

### 配置/脚本修复（7 项）
| 项 | 内容 |
|---|---|
| start-all.sh | **5 处 JAVA_OPTS 引号修复**（ANALYTICS/HEAVY 引号坏导致启动失败/噪音）+ 末尾多余 wait 删除 + 两处 curl 加 --max-time 2 → **冷启动 95s 正常退出**（原"几十分钟"=失败空等+脚本不退出）|
| 慢查询日志 | 主 0.5s/从 1s + slow-query-log-file 确定化 |
| mbeanregistry | 14 服务 yml + start-all.sh 三处 -D（tomcat_threads 可用）|
| Hikari/业务 Timer bucket | 14 服务 yml percentiles-histogram（hikaricp acquire/usage/creation + feed.push/orders.create/inventory.prededuct latency）|
| gateway | GatewayMetricsConfig + http.server.requests bucket + yml |
| 看板 10 个 | 150 面板（拆分 tomcat/hikaricp、重建 biz、修正 jvm、正则化延迟面板覆盖 gateway）|
| 部署包 | 336 文件 + SYNC-NOTES-FOR-MASTER.md（9 条差异给对方）|

## 四、问题清单状态（docs/test-3/review/ISSUES.md + ANSWERS-DEPLOY-PACKAGE.md）
- ✅ 已修验证：T-030~036、T-034/035b/040、**T-042**（G2 期间 + G3 前置 REVIEW）+ 前置 T-001~025（21 项）
- ✅ 已闭环：业务埋点标签（common v2，mq/dlq 完整标签出数）、启动脚本（95s）、看板体系（150 面板）
- ⏳ 待决策：T-004（注册枚举）、T-006（JWT secret 明文）
- 📝 观察：T-020/023/027/028/029、O-Comment-7/8/9、O-Like-5/7、O3（OFFLINE 不可达）、O4（URL 前缀部署配置）、O5（补偿重投）、O-Note-1~5
- **待办**：slow log 进 ES 管道（云主机部署时）

## 五、部署包与对方同步（重要）
- **对方上传的 zip 落后 master 9 条修复**（slow log 路径/init-all.sql 移库/5 个看板/DLQ 指标/README v2）
- **SYNC-NOTES-FOR-MASTER.md**（/data/workspace/ + config/deploy-cloud/）= 给对方逐条同步说明（含验证命令）
- **对方下次上传 zip 前务必先同步这 9 条**，否则回退
- 对方侧待办：① zip 删 broker-slave.conf ② 试验机清理裸名 order/payment 实例 ③ 应用 9 条

## 六、文档地图
| 文档 | 内容 |
|---|---|
| docs/test-3/README.md | test-3 总览（G1-G7 分组）|
| docs/test-3/cases/G2-content-social/ | G2 三份用例文档（已执行，含执行记录）|
| docs/test-3/REVIEW-METHODOLOGY.md | 三层验证法（L0/L1/L2，禁止"没问题"）|
| docs/test-3/review/ISSUES.md | T-030~041 登记 |
| docs/test-3/review/ANSWERS-DEPLOY-PACKAGE.md | 对方部署包问题逐条答复 + 本轮全部修复记录 |
| docs/test-3/helpers/testlib.py | 测试工具（**已支持 query 参数签名 + headers 自定义头（X-Admin-Call）**）|
| docs/test-2/execution/pitfalls.md | 踩坑 #1~#83 |
| /data/workspace/SYNC-NOTES-FOR-MASTER.md | 给对方 9 条同步说明 |
| config/deploy-cloud/DEPLOY-NOTES.md | 部署实测坑 |

## 七、方法论与执行纪律（必读）
1. **三层验证法**：L0 静态 / L1 框架语义（源码/jar 实证）/ L2 运行态——结论分级，禁止"没问题"（完整版 docs/test-3/REVIEW-METHODOLOGY.md）
2. **改 common 后**：`mvn install -pl my-xhs-common` 看 BUILD SUCCESS（**-q 会吞错误**）→ 依赖服务 **rm -rf target** 重打包 → 验证 jar 内 class（**解压 BOOT-INF/lib 嵌套 jar**，外层 unzip -l 看不到）
3. **pgrep/pkill -f 自匹配**：命令行含目标字符串会杀自己 shell → 用 `[b]in/bash`/`[s]tart` 括号技巧
4. **curl 一律 --max-time**（健康检查/脚本内）
5. **start-all.sh**：95s 冷启动（15 服务并行）；**修改 JAVA_OPTS 行后必须 bash -n + 引号配对校验**（引号外 -D 静默失效是高频事故）
6. **限流窗口跨用例共享**（O-Note-1）：测试前 DEL 限流 key 隔离
7. **R4**：全局 Long→ToStringSerializer，JSON 中 id/计数均为字符串（断言 int() 转换）
8. **Redis 操作**：redis-py（无 redis-cli）；6379=主节点
9. **测试数据**：统一前缀（g2x_/g3x_），执行后清理
10. **本轮新坑速查**：**pitfalls.md #84~#99**（指标预注册标签冲突/EXPIRE 顺序/unzip 嵌套 jar/pgrep 自匹配/start-all 引号/gateway 独立模块/动态桶等）——测试/改监控前先扫一遍

## 七·五、下一步计划（路线图）
- **G3 商品与购物车**（已暂停，待用户指示）：product 19006/cart 19008，SKU/加购/购物车 P2-7/8 + cartReconcileJob(xxl 17) + product 索引 canal→ES + HotSkuDetector 30s
- **G4 优惠券**：模板/领券/用券 + couponExpireJob(16)/couponReconcileJob(15)
- **G5 交易**：下单(P2-12)/支付(P2-4)/退款/关单/库存 + 订单全部定时任务 + 事务消息/延时/重试
- **G6 搜索首页**：搜索/热搜/推荐/Feed + feedCleanupJob(18)/recommend×3 + 索引重建/增量
- **G7 通知IM计数**：通知/IM/计数 TTL + unreadReconcile/counterReconcile/CounterBuffer/SSE/IM 心跳
- 每组均按 G1/G2 模式：代码实证→写用例→深度 REVIEW→修复→逐用例执行；**执行等用户指示**

## 八、给下一个 AI 的执行要点（G3——已暂停，待用户指示再启动）
> ⚠️ **G3 状态（2026-08-13 更新）**：G3 用例文档已按用户指示**重新梳理完成**（G3-01-product.md 13 用例 / G3-02-cart.md 15 用例，含代码实证与"实测"待确认项）；**G3 执行仍等用户指示**。
1. **测试主线**（恢复时）：G3 商品与购物车（SKU/加购/购物车 P2-7/8/对账 P2-8 + cartReconcileJob(xxl 17) + product 索引 canal→ES + HotSkuDetector 30s）——按 G1/G2 模式：代码实证→写用例→深度 REVIEW→修复→逐用例执行（testlib 复用）+ L2 数据验证；**禁止批量**
2. **G3 已知素材**（可复用）：product 19006/cart 19008；t_spu/t_sku 在 my_xhs_product 库（**IndexRebuildJob 需跨库前缀 my_xhs_product.t_spu**——#37 教训）；cart 数据 t_cart_item（my_xhs_cart 库）；HotSkuDetector 30s 窗口（矩阵 #32）；购物车对账锁 600s（矩阵 #40）；**T-042 已修**（product 逻辑删除 ES 同步）
3. **测试前**：确认 product/cart 服务 UP、ES product_index 可查、xxl 任务 17（cartReconcileJob）手动触发方法（矩阵 A 节）
4. **风险提醒**：product 曾有 /actuator/prometheus 67s 慢（#51/52 已修 DlqMetrics 缓存）；改动 common 必须全量重建（rm -rf target）
5. **执行记录**：每用例 L1+L2 断言，发现新问题登记 T- 系列（延续 T-042+）

## 九、交接文档深度 REVIEW 记录（2026-08-13 第二轮）
- **修正**：① 看板面板数 141→**150**（算术错误，10 看板逐个数核：31+18+33+12+11+9+11+4+10+11）② 补 T-042（product 逻辑删除 ES 同步，search 已重启）③ 补埋点标签落地（common v2 + 13 服务重打包 + mq_consume_total 完整标签验证）④ G3 状态更新（已暂停，文档已删，待指示）⑤ testlib headers 扩展
- **核对无误**：端口拓扑（15 服务 19000-19016）、G1 107/108、G2 43/43、部署包 336 文件、95s 冷启动、9 条同步说明、问题清单状态
- **文档自检**：§0-§八 全部与当前实际状态一致（2026-08-13 18:00）
- **REVIEW 补充（第三轮）**：pitfalls.md 补 #84~#99（本轮 16 个新坑：指标预注册标签冲突/EXPIRE 顺序/unzip 嵌套 jar/pgrep 自匹配/start-all 引号/gateway 独立模块/动态桶/看板审查方法等）；交接文档新增 §七·五 路线图（G3 已暂停待指示，G4-G7 规划）
