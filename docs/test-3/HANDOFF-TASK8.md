# my-xhs 交接文档 — Task8（G4 优惠券 + G5 交易 + 可观测性事件流 + 22 项修复 → G6 起）

> 2026-08-14 | 承接 Task7（G3 完成）→ 本阶段：**G4 优惠券（23 用例）+ G5 交易（41 用例）+ 可观测性事件流 4 表 + 测试前/执行中修复 22 项（T-056~079）**
> 给下一个 AI 的**全量交接**。**重点：问题清单（§五）、修复明细（§六）、坑点速查（§八，新增 #82-87）、执行纪律（§九）、下一步 G6（§十）。**

---

## 零、状态速览（2026-08-14 15:40）

```
微服务 15 个 UP（本机容器 21.214.97.212，start-all.sh 95s 冷启动）| 中间件 27 容器（21.130.247.89，对方管理无 docker 权限）
测试进度：G1 ✅78 | G2 ✅43 | G3 ✅32+回归 | G4 ✅23+全量回归23 | G5 ✅41（23+18）+修复后回归11组全绿
修复：本轮 22 项全部已修并验证（T-056~079，含 P1 级 6 项；T-049~055 观察项登记）——详见 §六
脏数据：MySQL 全 0（含 5 张新表）| ES 双索引 0（REVIEW 时清理 note=4/product=12 残留）| Redis 已清 | 无未修缺陷（唯一观察项 T-004/006 业务决策）
新表 5 张：t_cart_event / t_note_event / t_product_behavior / t_payment_event / t_inventory_prededuct_idem
日志：15 服务 /logs/my-xhs-{服务}.json（Logstash TCP 15044 推 ES myxhs-logs-YYYY.MM.DD）
Token→/tmp/test_token.txt（已过期需重新登录）| .secrets/tokens.env（随机化）| 凭据 Xhs@2026#*
```

## 一、环境拓扑（与 Task7 一致 + 本轮确认）

- **微服务机 = 本机容器 21.214.97.212**（15 JVM：gateway19000/user19001/content19002/analytics19003/counter19004/product19006/cart19008/inventory19009/coupon19010/order19011/payment19012/notification19013/im19014/home19015/search19016）
- **中间件机 = 21.130.247.89**（27 容器，对方管理，改动只能给脚本/配置）
- **Redis**：主 6379/从 6380/哨兵 26379；**AOF 持久化 + maxmemory-policy=noeviction**（本轮实证——key 不因重启/淘汰丢失，T-079 风险评估依据）；写后 sleep 1-2s 再断言
- **MySQL 主从**：3306 主/3307 从（读写分离）；**JDBC 返回 found-rows 语义**（T-079 教训：ON DUPLICATE 探测不可靠）
- **RocketMQ**：nameserver 9876、dashboard 18081（**登录 403 不可投递消息**——G4 实证，投递类用例降级 Outbox/SQL/触发链路）；broker 11911
- **ES**：业务 19200（elastic/Xhs@2026#Elastic）、SW 存储 19201（elastic/Xhs@2026#ElasticSW——密码不同）
- **xxl-job**：18080 admin（admin/123456）；21 任务启用状态见 §四表

## 二、日志体系（Task7 实证 + 本轮不变）

- 本地文件 `/logs/my-xhs-{APP_NAME}.json`（每行 JSON：@timestamp/APP_NAME/level/message/traceId/userId）
- 控制台/滚动 `/data/workspace/my-xhs/logs/{服务}/info.log` + error.log
- 聚合：LogstashTcpSocketAppender → 21.130.247.89:15044 → ES myxhs-logs-YYYY.MM.DD
- 链路：gateway RequestLogFilter UUID traceId → X-Trace-Id → 下游 MDC
- **gateway 未知路径已修 404**（T-059）——扫描流量不再污染 5xx

## 三、测试进度（主线，2026-08-14 15:40 全绿）

| 组 | 用例数 | 状态 | 备注 |
|---|---|---|---|
| G1 认证用户 | 78（9 文件）| ✅ | Task6/7 |
| G2 内容社交 | 43/43 | ✅ | Task6 |
| G3 商品购物车 | 32/32 + 回归 | ✅ | Task7（T-046/047/048）|
| **G4 优惠券** | **23/23 + 全量回归 23/23** | ✅ | 本轮（T-058 修复后）|
| **G5 交易** | **41/41（G5-01 23 + G5-02 18）+ 修复后回归 11 组全绿** | ✅ | 本轮（T-060~079）|
| G6 搜索首页 | 38/38 + 3 轮回归 | ✅ | 本轮（T-082/083/087/088/092/093 等）|
| G7 通知IM计数 | 36/36 + 3 轮回归 | ✅ | 本轮（T-094~098）|
| **G8 可观测性（L4）** | 6 用例 | ✅ | 基础设施完整：Prometheus 23 targets/SW 15 服务/日志 traceId 关联/Grafana 10 看板 |

## 四、Task8 工作内容（2026-08-14，三大部分）

### 4.1 G4 优惠券（coupon 19010，23 用例）
- 文档：G4-01-coupon.md（模板管理 7/领券 6/我的券 2/用券退券 6/定时任务 2）
- 执行 + 全量回归全绿；**T-058 修复**（couponExpireJob SQL）
- 安全矩阵：用户端全要 HMAC、管理端点 JWT+X-Admin-Call 免 HMAC、template/list 公开、内部接口 X-Internal-Call

### 4.2 可观测性事件流（A1/A2/A3 分析场景输入，4 表）
| 表 | 服务 | 事件 | 验证 |
|---|---|---|---|
| `my_xhs_cart.t_cart_event` | cart CartEventSinkConsumer（独立 group）| ADD/UPDATE/DELETE/CHECK/CHECK_ALL/CLEAR（msgId 幂等+uk_msg_id）| 加购 2s 落库 ✅ |
| `my_xhs_content.t_note_event` | content NoteService（publish/publishDraft 同事务）| PUBLISH（发布即审计通过，无需 audit 事件）| 发布/草稿发布 ✅ 草稿不产生 ✅ |
| `my_xhs_product.t_product_behavior` | product 详情入口异步（spu-view 池 DiscardPolicy）| 浏览（仅 userId!=null 埋点；批量不埋）| 缓存命中也记 ✅ Feign 补全 0 噪音 ✅ |
| `my_xhs_payment.t_payment_event` | payment 四节点（pay-event 池）| CREATE/PAY_SUCCESS/PAY_FAIL/REFUND/TIMEOUT | 五类型全验证 ✅ |
- **设计纪律**：append-only + 独立线程池（DiscardPolicy）+ try-catch 不阻塞主链路 + 幂等；DDL 备份 `docs/test-3/review/observability-events-ddl.sql`

### 4.3 G5 交易（order 19011 + payment 19012 + inventory 19009，41 用例）
- 文档：G5-01-order.md（下单/状态机/支付联动/关单/补偿/本地消息/映射）+ G5-02-inventory-payment.md（库存三态/TCC/对账/补偿 + 支付/回调/退款/超时）
- **关键运行配置**（代码实证）：`pay.type: remote`（order pay/create 走 payment 服务+模拟器 90%，MockPayService 30% 分支当前不加载）；`order.close.delay-level: 16`（30min，可改 5=1min）；isMockMode=payType 99（同步）；payType 1/2 走 PayCallbackSimulator（5s 周期、90% 成功、1-3s 延迟、pending 5min）
- **分片**：t_order 库=uid%4 表=(uid/4)%4；t_order_no_mapping 主库非分片；t_local_message 同分片
- **伪订单 ID**：SHA-256(orderNo) 前 8 字节 signed long（T-067 统一，负值可能——Redis key 为负 long）

### 4.4 xxl 任务全景（运行库实测 2026-08-14，21 个）
| id | 组 | 任务 | cron | 状态 |
|---|---|---|---|---|
| 3 | g2 | followCounterRepair | 0 0 */1 * * ? | ON |
| 4 | g3 | counterReconcileJob | 0 0 3 * * ? | ON |
| 5 | g5 | unreadReconcileJob | 0 0/5 * * * ? | ON |
| 6 | g6 | paymentTimeoutCheckJob | 0/30 * * * * ? | ON |
| 7 | g6 | paymentNotifyCompensateJob | 0 0/2 * * * ? | ON |
| 8 | g6 | refundNotifyCompensateJob | 0 0/3 * * * ? | ON |
| 9 | g6 | refundTimeoutCheckJob | 0 * * * * ? | ON |
| 10 | g8 | orderCloseJob | 0 * * * * ? | ON |
| 11 | g8 | localMessageRetryJob | 0 * * * * ? | ON |
| 12 | g8 | deadLetterScanJob | 0 * * * * ? | ON |
| 13 | g8 | orderMappingRepairJob | 0 * * * * ? | ON |
| 14 | g4 | inventoryReconcileJob | 0 * * * * ? | ON |
| 15 | g10 | couponReconcileJob | 0 * * * * ? | ON |
| 16 | g10 | couponExpireJob | 0 * * * * ? | ON |
| 17 | g9 | cartReconcileJob | 0 0 * * * ? | ON |
| 18 | g11 | feedCleanupJob | 0 0 3 * * ? | ON |
| 19 | g12 | recommend 特征索引 | 0 0 * * * ? | ON |
| 20 | g12 | recommend 热池 | 0 */10 * * * ? | ON |
| 21 | g12 | recommend ItemCF | 0 0 2 * * ? | ON |

进程内 @Scheduled：PreDeductTimeoutJob(60s)/TccTimeoutJob(60s)/InventoryCompensationJob(30s)/InventoryOutboxSenderJob(5s)/CouponOutboxSenderJob(5s)/PayCallbackSimulator(5s)/Content Feed 重试等

## 五、问题清单状态（ISSUES.md 全文 T-001~079）

- **已修验证**：T-001~003/005/007~013/016~019/021/022/022b/024/025/030~036/034b/035b/040~042/045~048（Task7 前）+ **T-049~079 本轮**
- **待业务决策**：T-004（注册枚举）、T-006（JWT secret 明文，P-D1 关联）
- **观察项（不修，已分析）**：T-020/023/027/028/029、O-系列、幂等窗口过期重复创建、SKU 详情不过滤下架
- **本轮新增观察**：T-049（模板 status 无校验）、T-050（createTemplate 无幂等）、T-051（getTemplate 不过滤 status）、T-052（available 不检查 validStart）、T-053（claimed key 无 TTL）、T-054（claim -3 重试报 30014）、T-055（券对账无防误删）

## 六、Task8 修复明细（代码级，22 项）

### 6.1 G4 阶段（4 项修复 + 1 环境事实）
| # | 修复 | 文件 | 验证 |
|---|---|---|---|
| T-056【文档纠错】| 参数校验先于 isAdminCall（推翻 G3 #80-4）| 文档 + product createSpu 同理 | 非法 body 无 Admin→40002 |
| T-057【文档纠错】| 领券中心 validStart 矛盾（新模板不进列表，语义合理）| 文档 | 到点自动进入 ✅ |
| T-058【P1 已修】| couponExpireJob batchExpire 多表 UPDATE+LIMIT 非法 → 派生表子查询 | `my-xhs-coupon/.../mapper/UserCouponMapper.java` | xxl#16 handle 200"标记 2 张券过期" ✅ |
| T-059【已修】| gateway NoResourceFoundException→500 应 404（WebFlux 版漏映射）| `my-xhs-gateway/.../handler/GlobalExceptionHandler.java` | /no-such-path 404 ✅ |
| — | RocketMQ dashboard 登录 403（新版 API）| 环境事实 | 投递类用例降级 |

### 6.2 测试前修复（G5 前置，6 项，用户要求"坑点先修"）
| # | 修复 | 文件 | 验证 |
|---|---|---|---|
| T-060【P1 已修】| 下单层无 SPU 校验（T-047 遗留）→ SkuInfoDTO+spuStatus+createOrder 拦截 30003；catch context null NPE 一并修 | `order SkuInfoDTO.java + OrderService.java` | 下架后下单 30003 ✅ |
| T-061【已修】| payment refund/status 无内部鉴权（JWT 直连可越权退款）→ 补 X-Internal-Call | `payment PaymentController.java` | JWT 直连 403 ✅（Feign 链路带令牌不受影响）|
| T-062【已修】| checkPaymentTimeout 恒真误标+只改 Redis → DB 扫描（created<30min 乐观锁+事件）| `payment PaymentService.java` | 超时单 2/新鲜单不误标 ✅ |
| T-063/065【已修】| 补偿 Job release.lua 缺 KEYS[4]=index + ARGV[2]=orderId → 回退成功但标记失败→重复回退超发 | `inventory InventoryCompensationJob.java` | resolved 单次回退 ✅ |
| T-064【已修】| t_inventory_compensation id 丢 AUTO_INCREMENT（P-D22 遗留）→ 补偿兜底从未生效 | DDL ALTER | INSERT+处理 ✅ |

### 6.3 G5 执行中修复（11 项）
| # | 修复 | 文件 | 验证 |
|---|---|---|---|
| T-066【P1 已修】| canal INSERT 删库存 key → 下单不扣库存（超卖）→ preDeduct 自愈重建 | `inventory InventoryService.java` rebuildStockFromDb | 删 key 后重试自愈 ✅ |
| T-067【P1 已修】| 伪订单 ID 算法不一致（消费端 fold-hash vs 释放 SHA-256）→ 库存泄漏 → 消费端统一 SHA-256 | `inventory OrderTransactionConsumer.java` | 释放链正确 ✅ |
| T-070【已修】| pay-fail 取消缺 cancelled_at | `order OrderService.java` onPaymentFailed | 4/1 ✅ |
| T-072【已修】| 死信重投/补发失败 updateById 分片键错误 → 专用 SQL（markSuccess/updateRetryStatus/updateDeadRetry）| `order LocalMessageRetryJob.java + LocalMessageMapper.java` | xxl#12 handle 200 ✅ |
| T-073【已修】| Lua userId%bucketCount 双精度溢出 → 路由恒偏 → Java 侧 floorMod 传 ARGV[7] | `inventory prededuct.lua + InventoryService` | uid%4=2→桶2 ✅ |
| T-074【P1 已修】| t_tcc_freeze_detail 表结构漂移（id PK vs 联合 PK）→ TCC 完全不可用 → ALTER 对齐 | DDL | TCC 三态全过 ✅ |
| T-075【已修】| 异步渠道退款无回调闭环（卡"退款中"）→ 模拟器补 refund-callback | `payment PayCallbackSimulator.java + PaymentService` | 竞态退款闭环 1/3 ✅ |
| T-076【已修】| 部分退款置支付单 3 → 剩余不可退 → 累计=全额才置 3 | `payment PaymentService.java` | 50+49.90 两次正确 ✅ |
| T-077【P1 已修】| 全额退款后订单不置 5（order 无 REFUND_RESULT 消费者）→ Feign 直调 notifyRefundSuccess | `payment PaymentService.java` | 全额退后订单 5 ✅ |
| T-078【已修】| canal INSERT 延迟删 key（T-066 自愈被二次破坏）→ INSERT 不再删缓存 | `inventory InventoryCacheEvictConsumer.java` | init 后 key 稳定 ✅ |
| T-071【已修】| 退款不回退库存 → 独立 refund-restore 接口（Redis+MQ REFUND_RESTORE 同步 MySQL）| `inventory Controller+Service+Mapper+Consumer + order Feign+onRefundSuccess` | 98→100/MySQL 100/0/幂等 ✅ |

### 6.4 用户质疑后深挖（1 项）
| # | 修复 | 文件 | 验证 |
|---|---|---|---|
| T-079【已修】| 预扣幂等依赖 Redis key 存活 → key 丢失+重投重复扣 → MySQL 幂等表 INSERT IGNORE 探测 | `inventory InventoryMapper+InventoryService` + 新表 t_inventory_prededuct_idem | DEL key 后重投不再扣 ✅；正常链路不受影响 ✅ |

### 6.5 本轮教训（实现过程踩坑）
1. **改 resources 下 Lua 必须重新打包**（T-073 踩坑：改了 Lua 没打包 → 旧脚本 500）
2. **JDBC found-rows 语义**（T-079：ON DUPLICATE 探测返回 1 失效 → INSERT IGNORE）
3. **多模块打包 reactor 失败 → 全部模块不产出新 jar**（T-071 时 inventory 没更新 → 404）
4. **注释里 `*/` 会终止 Java 注释**（T-066 注释含 bucket*/ 导致编译错）
5. **测试清理删 prededuct key → MQ 延迟消息重投重新预扣**（测试方法问题：清理应保留幂等记录或等 MQ 排空）

## 七、文档地图（docs/test-3/）

| 文档 | 内容 |
|---|---|
| README.md | test-3 总览（G1-G7 分组）|
| cases/G1-auth-user/ G2-content-social/ G3-product-cart/ | 已执行（G1 78/G2 43/G3 32）|
| **cases/G4-coupon/** | README + G4-01-coupon.md（23 用例，三轮 REVIEW+L2 实证）|
| **cases/G5-trade/** | README + G5-01-order.md（23）+ G5-02-inventory-payment.md（18），四轮 REVIEW |
| cases/00-time-matrix.md | 时间机制（#32 HotSku 10s 修正、#14/#15 券、#3-6/10-14/18-20 交易）|
| REVIEW-METHODOLOGY.md | 三层验证法 |
| review/ISSUES.md | **T-001~079 全量清单** |
| review/observability-events-ddl.sql | 5 张新表 DDL 备份（对方部署用）|
| helpers/testlib.py | 测试工具（sign/query/headers 支持）|
| pitfalls.md | 踩坑 #1~#81 + **#82-87（本轮新增，见 §八）**|
| HANDOFF-TASK7.md | Task7 交接（G3 状态）|
| **HANDOFF-TASK8.md** | **本交接文档** |
| /data/workspace/SYNC-NOTES-FOR-MASTER.md | 对方同步说明（#1-19：9 部署包 + 10-15 微服务 + 16-19 事件流）|

## 八、坑点速查（G6 前必扫 pitfalls 全文；#82-87 为本轮新增）

1. **#79-1 最坑**：手动重启必须带 INTERNAL_TOKEN/ADMIN_TOKEN（.secrets/tokens.env）；判定 `tr '\0' '\n' < /proc/<pid>/environ | grep -c INTERNAL_TOKEN`
2. **#81-1**：Redis key 字面花括号（cart/coupon/inventory total）python 访问 `{{{key}}}` 转义
3. **#79-3**：限流/幂等窗口跨用例共享——执行前 DEL key；create 5次/60s、pay 10次/60s、refund 5次/60s
4. **#82【本轮】改 Lua 必须重打包**：resources/lua/*.lua 修改后 mvn package 才进 jar；Spring 启动加载
5. **#83【本轮】JDBC found-rows**：ON DUPLICATE 探测返回匹配行（1）不可用 → INSERT IGNORE（冲突恒 0）
6. **#84【本轮】测试清理勿删幂等记录**：`inventory:prededuct:*` 删除后 MQ 延迟消息重投会重新预扣（污染库存断言）；清理保留或等 MQ 排空
7. **#85【本轮】pay.type: remote**：order pay/create 走 payment 服务（模拟器 90%）；MockPayService 30% 分支仅 pay.type=mock 加载；payType=99 同步、1/2 异步 5-15s 回调
8. **#86【本轮】分片查询必须带 userId**（库=uid%4 表=(uid/4)%4）；裸查订单需遍历 4 库 4 表 + t_order_no_mapping 主库
9. **#87【本轮】伪订单 ID 有符号**：SHA-256 前 8 字节 signed long 可能为负（Redis key 带负号）——断言用 Java 同款转换
10. **#80-3**：product 无鉴权拦截器——服务间 Feign 直连公开；"需 JWT"断言必须经 gateway
11. **#81-6**：连续无间隔压测 gateway 连接排队超时——间隔 0.3-0.5s
12. **写后读延迟**：事务消息异步落库（下单 200 后等 1-3s）+ 主从 1-2s + MQ 消费 1-3s
13. **token 会话 30min**：长测试中途 refresh 续期（POST /api/user/auth/refresh body refreshToken）；**清理 Redis myxhs:user:* 会连带清会话——先注册新用户勿清**
14. **dashboard 403**：RocketMQ dashboard 登录不可用——投递类用例走 Outbox/SQL 构造/停服触发真实链路

## 九、方法论与执行纪律（必读，承接 Task7）

1. **三层验证法**：L0 静态/L1 框架语义/L2 运行态——结论分级，禁止"没问题"；"L0+L1 已核，L2 待实测"或"实测通过"
2. **改 common 后**：`mvn install -pl my-xhs-common` + 依赖服务 rm -rf target 重打包（验证 jar 内 class）
3. **多模块打包**：-pl 多模块任一失败 → 全部不产出（单独 -pl 重打）；**改 Lua 也须重打包**（#82）
4. **pgrep/pkill -f 自匹配**：`[b]in/bash` 括号技巧
5. **curl 一律 --max-time**；**start-all.sh** 95s 冷启动；JAVA_OPTS 改动后 bash -n
6. **R4**：Long→ToStringSerializer，id/计数断言 int() 转换
7. **Redis 操作**：redis-py（无 redis-cli）；6379 主；**max_connections=1 连接池**（多实例串话教训）
8. **测试数据**：统一前缀（g5r_ 等），执行后清理；**禁止批量测试**（逐用例）；**清理勿删幂等记录**（#84）
9. **服务重启**：带 INTERNAL_TOKEN/ADMIN_TOKEN + mbeanregistry + skywalking agent（参照 start-all.sh JAVA_OPTS）
10. **ES 查询带认证**：业务 19200 / SW 19201 密码不同；`_delete_by_query` 清理
11. **问题登记**：新发现问题登记 T- 系列（延续 T-080+）；修复后记录"现象→实证→修复→验证"

## 十、下一步计划（路线图，2026-08-14 晚更新）

- **G6/G7/G8 已完成**（§三 表）；**待决策：AI 服务（my-xhs-ai-app 19020 / ai-mcp 19021）是否纳入测试**（独立模块未运行未挂 gateway，含 /api/ai/chat|agent|agent/run|agent/run/stream|query|mcp-check 端点）
- **G2 回归**：2026-08-14 晚进行（43 用例）
- **G7 通知IM计数**：notification(19013) + im(19014) + counter(19004) —— unreadReconcileJob(5)/counterReconcileJob(4)/CounterBuffer/SSE/IM 心跳（心跳 30s/在线 90s TTL）
- 每组按 G1-G5 模式：代码实证→写用例→深度 REVIEW→修复→逐用例执行（testlib 复用 + L2 数据验证）；**执行等用户指示**

## 十一、给下一个 AI 的执行要点（G6——待用户指示再启动）

1. **测试主线**：G6 搜索首页——按 G1-G5 模式（代码实证→用例→REVIEW→逐用例+L2 验证）；**禁止批量**
2. **G6 已知素材**（跨 Task6/7/8 积累）：
   - search 数据源=my_xhs_content 库（**非 my_xhs_search**，my_xhs_search 已 DROP，#71）；跨库查询须 `my_xhs_product.t_spu` 前缀（#37/#59）
   - ES 索引：note_index（canal note_instance → NoteIndexSyncConsumer）、product_index（SPU 粒度、price=首 SKU、T-042 status=-1）、suggest_index（IndexRebuildJob 重建，S03 修复后）
   - 版本域：Canal 用 ts（毫秒）与补偿统一（#58 教训）；毒版本需 `_delete_by_query` + 重建
   - Feed：推模式收件箱 ZSet（#55 参数修复）、本地消息表补发（FeedMessageRetryJob 30s/60s）、大V 发件箱
   - 热搜：t_hot_search_snapshot（content 库）、HotSearchService 60s 周期、热搜 pin/block 管理端点
   - recommend×3（xxl 19/20/21）、BehaviorReportConsumer（t_user_behavior 7 值枚举，写入端在 search）
   - xxl：18 feedCleanup（每天3点）、19/20/21 recommend
3. **测试前**：确认 search/home UP、xxl 18-21 启用、ES 索引 mapping 正确、canal 监听（note/product_instance）
4. **风险提醒**：search 是"内容域消费者"聚合点（canal→ES/行为事件/推荐）——改动影响面大；行为事件表 t_user_behavior 与可观测性事件流（t_note_event/t_product_behavior）可复用验证
5. **环境现状**：全部 15 服务 UP、DB/Redis 全 0、5 张新表就绪、xxl 21 任务启用——可直接承接 G6

## 十二、本交接文档 REVIEW 记录（2026-08-14 15:40，两轮）

### 第一轮（成文时自检）
- **数据核对**：15 服务 UP（本轮 8 服务经手重启）、MySQL 全 0（含 5 新表）、Redis 0、xxl 21 任务状态实测
- **代码核对**：22 项修复文件/行号如 §六；5 张新表存在性实测
- **文档自检**：§0-§十一 与当前实际一致；用例数 G4 23 + G5 41 = 64 新增
- **遗留确认**：G1/G2/G3 未复跑（本轮改动均在后端服务，G1/G2 前端链路不受影响；如需保险可抽查）；T-004/006 待业务决策

### 第二轮（2026-08-14 16:00，深度 REVIEW 修正）
- ✅ **数字修正**：6.1"5 项"→"4 项修复+1 环境事实"；6.3"9 项"→"11 项"；"P1 级 8 项"→"6 项"（058/060/066/067/074/077）
- ✅ **DDL 遗漏修正**：observability-events-ddl.sql 原仅 4 表——**补 t_inventory_prededuct_idem（第 5 张，T-079 新增）**，对方部署必须执行
- ✅ **ES 状态实证**：REVIEW 时发现 note_index=4/product_index=12 残留（非文档所述 0）——`_delete_by_query` 清理后 0，文档已注明；note/product/suggest 三索引均存在（G6 素材准确）
- ✅ **引用有效性**：#37/55/58/59/71/79/80/81/82-87 全部在 pitfalls 存在（#37 为旧表格格式，语义有效）
- ✅ **运行态复核**：15/15 服务 UP、xxl 21 任务状态、5 表存在、Redis AOF+noeviction、pay.type=remote——全部与文档一致
