# my-xhs 交接文档 — Task7（G3 闭环 + 三大修复 + 全量回归 → G4 起）

> 2026-08-14 | 承接 Task6（G2 完成）→ 本阶段：G3 商品与购物车全量执行（32 用例）、T-046/T-047/T-048 三大问题修复、G1/G2/G3 全量回归、日志体系实证。
> 给下一个 AI 的**全量交接**。**重点：问题清单（§五）、修复明细（§六）、坑点速查（§八）、执行纪律（§九）、下一步 G4（§十）。**

---

## 零、状态速览（2026-08-14 10:15）

```
微服务 15 个 UP（本机 21.214.97.212，start-all.sh 95s 冷启动）| 中间件 27 容器（试验机 21.130.247.89）
测试：G1 ✅（9 文件 78 用例，文件内最大编号合计 6+8+10+10+10+8+13+7+6=78；另有深度 REVIEW 补跑未写入文档，历史口径曾记 101）+ G2 43/43 ✅ + G3 32/32 ✅（首轮含 1 修复）
      第二轮全量回归：G3 32/32 全绿（2026-08-14，T-046/047/048 修复后）
修复：T-046（BigDecimal 序列化，common）✅ / T-047（SPU 下架购物车有效性，product+cart）✅ / T-048（gateway 连接池生命周期）✅
脏数据：MySQL 全 0（t_user/t_note/t_spu/t_cart_item/t_like/t_notification 等）| ES note_index=0/product_index=0 | Redis 已清
遗留观察：T-047 下单层双状态校验归 G5 / T-048 低频根除需生产观察 / 幂等窗口过期重复创建 / SKU 详情不过滤 SPU 下架
日志：15 服务各一个 /logs/my-xhs-{服务}.json（本地分散）+ TCP 15044 推 ES myxhs-logs-YYYY.MM.DD（聚合，Kibana 查）
Token→/tmp/test_token.txt（已过期，需重新登录）| .secrets/tokens.env（随机化）| 凭据: Xhs@2026#*
```

## 一、环境拓扑
- **微服务机 = 本机容器 21.214.97.212**（15 JVM：gateway19000/user19001/content19002/analytics19003/counter19004/product19006/cart19008/inventory19009/coupon19010/order19011/payment19012/notification19013/im19014/home19015/search19016）
- **中间件机 = 21.130.247.89**（27 容器，对方管理，无 docker 权限——**改动只能给脚本/配置**）
- **Redis Sentinel**：主 6379/从 6380/哨兵 26379（master 稳定=6379；**读注意主从复制延迟，写后 sleep 1-2s 再断言**）
- **Redis key 注意**：cart key 含**字面花括号** `myxhs:cart:{uid}:items`（hash tag）——python f-string 访问须 `{{{uid}}}` 转义（#81-1 教训）
- **MySQL 主从**：3306 主/3307 从（读写分离 readwrite.enabled=true）
- 微服务→中间件走 iptables 白名单

## 二、日志体系（Task7 实证，非推断）
- **本地文件（按服务分开）**：`/logs/my-xhs-{APP_NAME}.json` ×15，每行 JSON：`@timestamp/@version/APP_NAME/level/level_value/logger_name/thread_name/message/traceId`（LogstashEncoder，includeMdcKeyName=traceId/spanId/userId）
- **本地控制台/滚动文件**：`/data/workspace/my-xhs/logs/{服务}/info.log` + `error.log`（logback FILE_INFO/FILE_ERROR，${LOG_HOME}）
- **聚合索引**：全部服务经 LogstashTcpSocketAppender → `21.130.247.89:15044` → ES `myxhs-logs-YYYY.MM.DD`（Kibana：myxhs-logs-*）
- **链路关联**：gateway RequestLogFilter 生成 UUID traceId → 注入 X-Trace-Id 头 → 下游服务 MDC 读取 → 全部日志带同一 traceId
- **查全链路 = 搜 ES**（一个 traceId 出 gateway+user+...全服务时间线）；**本地文件只能看单服务**
- **ES 访问**：业务 ES 19200（elastic/Xhs@2026#Elastic）；SW 存储 ES 19201（elastic/Xhs@2026#ElasticSW，密码不同！）

## 三、测试进度（主线）
- **G1 认证与用户：✅ 78 用例**（9 文件，文件内最大编号：captcha6/register8/login10/token10/hmac10/userinfo8/follow13/address7/block6；**注：历史执行口径曾记 101，含未写入文档的深度 REVIEW 补跑；新 AI 按文件内编号执行为准**）
- **G2 内容与社交：✅ 43/43**（note15/comment13/like-favorite15）
- **G3 商品与购物车：✅ 32/32**（product15/cart17）——Task7 首轮执行 + 修复后第二轮全绿
- **G4-G7：未开始**（下一步 G4 优惠券，按 G1-G3 模式）

## 四、Task7 工作内容（2026-08-13 晚 ~ 08-14）

### 4.1 前置：G3 测试文档三轮深度 REVIEW（全量代码核对）
- G3-01-product.md：15 用例（初稿 13 + 幂等 + 限流）
- G3-02-cart.md：17 用例（初稿 15 + 乱序 C-05 + 下架 SKU）
- 三轮 REVIEW 修正的文档级错误（执行前消除）：
  1. 管理写端点需 **X-Admin-Call**（非仅 JWT）——createSpu/updateSpu/updateSpuStatus/createSku
  2. product 读接口经 gateway 需 JWT（JWT white-list 不含 /api/product/**）；**服务间 Feign 直连公开**（端口信任模型）
  3. /api/product/sku/batch 是**内部接口**（X-Internal-Call + 1-100）
  4. 下架 SPU 详情 → **30001**（P2-2 已修，loadSpuDetailFromDb 过滤）
  5. ES product_index=**SPU 粒度**（消费者只处理 t_spu）；price=首 SKU 价（非最低）；T-042=status=-1 标记
  6. HotSkuDetector 窗口 **10s**（矩阵 #32 写 30s 有误）、触发点=inventory preDeduct（内部接口+需先 init）
  7. xxl#17 cartReconcileJob cron=**每小时**（`0 0 * * * ?`，job_group=9；交接文档写"每天4点"有误）
  8. add 超 99=**Lua 截断**（非报错）；updateQuantity 超 99=40002（@Max）；满 50 种新商品才 30007
  9. cart 列表 P2-7 恢复（Redis 丢失→MySQL 回写）、CartItemVO.valid/invalidReason、C-05 全事件时间戳保护
  10. merge 无独立匿名 key（items 来自请求体）；@Size≤50；MergeItem @Min/@Max

### 4.2 G3 执行（2026-08-13，逐用例，禁止批量）
- **G3-01 product 15 用例全过**，运行态实证关键结论：
  - 下架详情 30001 + ES status=0 同步 + 列表不含
  - **SKU 详情不过滤 SPU 下架**（SkuService.getSkuDetail 无 SPU 判断）→ 观察项
  - **SKU 列表过滤 SKU.status 非 SPU.status**（下架 SPU 后 sku/list 仍返回）→ 观察项
  - 逻辑删除 deleted=1 → 详情 30001 + ES status=-1（T-042）；**逻辑删除不删 Redis 缓存**（残留至 TTL）→ 测试注意
  - 幂等窗口(10s)过期后重复创建 SPU（无业务去重）→ 观察项
  - HotSku 触发需库存 init（InventoryService:224 未初始化先拦，recordAndCheck 在其后）
- **G3-02 cart 17 用例全过**：
  - 50 种上限：HLEN=50 后新 SKU 30007、已存在 200
  - 对账三场景：补录 MySQL / 修复数量 / 删除残留 / **itemsKey 缺失跳过删除（防误删）** / 纯 Redis 用户补录
  - 对账锁互斥（"已有实例执行中，跳过"）
  - 管理端点三重鉴权（JWT+HMAC+X-Admin-Call）
  - 幽灵 SKU（add 无校验→列表 valid=false"商品信息获取失败"）
  - 乱序 C-05（加购+改数量→DB 最终值）
  - **T-047 实证**：SPU 下架后 cart 列表 SKU 仍 valid=true（修复前）

### 4.3 三大问题发现与修复（§六 详情）
- **T-046**：G3-01-07 逻辑过期验证失败 → PolymorphicTypeValidator 漏配 java.math → product 缓存 L2 恒失效
- **T-047**：G3-02-17 下架 SPU 购物车条目仍有效 → SKU.status 与 SPU.status 解耦
- **T-048**：G3-01-03 建 51 SKU 时第 36 个 500 → gateway 连接池 max-life-time 120s > product keep-alive 60s

### 4.4 修复后全量回归（2026-08-14，G3 32/32 全绿）
- 含 T-046（缓存命中路径）、T-047（下架→valid=false/恢复→true/checkedAmount=0）、T-048（并发 100/50 无 500）专项验证
- **G1/G2 未复跑**（无关联改动：common 序列化白名单新增 java.math 是白名单扩展，G1/G2 缓存均为 String 标量；如需保险可复跑 G2-02-11 comment:count 缓存命中）

## 五、问题清单状态（docs/test-3/review/ISSUES.md 全文）
- **已修验证**：T-001~025（Task5 前）、T-030~036、T-034/035b/040、T-042、**T-045**（comment:count Integer/Long）、**T-046**（BigDecimal 序列化）、**T-047**（SPU 下架购物车）、**T-048**（连接池生命周期）
- **待决策**：T-004（注册枚举）、T-006（JWT secret 明文）
- **观察项（不修，已分析）**：T-020/023/027/028/029、O-Comment-7/8/9、O-Like-7、O3（OFFLINE 不可达）、O4（URL 前缀）、O5（补偿重投）、O-Note-1~5、**T-047 下单层双状态校验（归 G5）**、**T-048 低频根除需生产观察**、幂等窗口过期重复创建、SKU 详情不过滤 SPU 下架、createSpu 幂等过期无业务去重
- **待办**：slow log 进 ES 管道（云主机部署时）

## 六、Task7 修复明细（代码级）

### T-046【已修】BigDecimal 序列化 → product 缓存 L2 恒失效
- **现象**：G3-01-07 改 DB 后立即查详情返回新值（预期缓存旧值）；product 日志"[多级缓存] L2 Redis 未命中"每次出现；error.log `SerializationException: Could not resolve type id 'java.math.BigDecimal' ... PolymorphicTypeValidator denied resolution`
- **根因**：`RedisConfig.createJsonSerializer` 的 BasicPolymorphicTypeValidator 白名单只有 com.myxhs./java.util./java.lang./java.time.，**漏 java.math.**；activateDefaultTyping(NON_FINAL) 序列化 SkuVO.price(BigDecimal) 写 @class=java.math.BigDecimal → 反序列化被拒 → redisOperator.get 捕获异常返回 null → L2 形同虚设每次穿 DB
- **修复**：`my-xhs-common/src/main/java/com/myxhs/common/config/RedisConfig.java` validator 增加 `.allowIfBaseType("java.math.")`
- **构建**：common `mvn install` → 依赖服务 rm -rf target 重打包 → 重启
- **验证**：回填→改DB→立即查=缓存旧值 ✅；删缓存→新值 ✅
- **教训**：与 T-045 同族——缓存对象含数值类型（Integer/Long/BigDecimal）时，序列化器类型白名单/泛型转换必须验证；**回归必须覆盖"缓存命中路径"**（首次 miss 正常，二次命中才暴露）

### T-047【已修】SPU 下架后购物车 SKU 仍 valid=true（可结算）
- **现象**：下架 SPU 后 `GET /api/cart/list` 该 SKU 仍 valid=true；product batch 仍返回（过滤 SKU.status 非 SPU.status）
- **根因**：SKU.status 独立于 SPU.status（SKU 无独立下架入口）；batch/list 只过滤 SKU.status；cart valid 防御层只判断 SKU.status
- **修复（3 文件）**：
  1. `my-xhs-product/.../dto/response/SkuVO.java`：新增 `private Integer spuStatus`
  2. `my-xhs-product/.../service/SkuService.java`：新增 `buildSpuStatusMap`（同批 IN 查询）；batchGetSkuDetails/listSkusBySpuId 填充 spuStatus；toSkuVO 重载带 spuStatus
  3. `my-xhs-cart/.../feign/ProductFeignClient.java` SkuDTO + `my-xhs-cart/.../service/CartService.java`：valid 判断增加 `spuStatus==null || spuStatus != ON_SHELF → "商品已下架"`
- **验证**：上架 valid=true → 下架 valid=false"商品已下架" → checkedAmount=0、totalCount 保留 → 恢复上架 valid=true ✅
- **遗留**：G5 下单层仍需 SPU/SKU 双状态校验（防御纵深；当前展示层已拦，下单层在 G5 验证）

### T-048【已修】gateway→product 偶发 PrematureCloseException(500)
- **现象**：建 51 SKU 循环第 36 个 500；gateway `PrematureCloseException: Connection has been closed BEFORE response, while sending request body`；product `[请求体不可读] I/O error`
- **根因**：gateway httpclient pool `max-life-time: 120000`(2min) > product Tomcat `keep-alive-timeout: 60000`(1min)——gateway 复用"已过保活期被对端关闭"的连接发送 body
- **修复**：`my-xhs-gateway/src/main/resources/application.yml` pool `max-life-time: 120000 → 45000`（必须 < 下游 keep-alive 60s）
- **验证**：并发 100 详情全 200、并发 50 createSku=5×200+45×40202 无 500、闲置 50s 后复用正常 ✅
- **遗留**：低频（~2%）根除需生产流量长期观察

### 其他运行态确认（非代码改动）
- createSku 第 36 个 500 = T-048（已查清，非幂等交错）
- list 61 次压测 gateway 超时 = 连续无间隔请求的连接排队副作用（限流本身 61 次 40202 正常）

## 七、文档地图
| 文档 | 内容 |
|---|---|
| docs/test-3/README.md | test-3 总览（G1-G7 分组）|
| docs/test-3/cases/G1-auth-user/ | G1 九份用例（已执行，首轮记录）|
| docs/test-3/cases/G2-content-social/ | G2 三份用例（已执行，首轮记录）|
| docs/test-3/cases/G3-product-cart/ | **G3 README + G3-01-product.md(15) + G3-02-cart.md(17)**（三轮 REVIEW 收敛，含运行态实证修正）|
| docs/test-3/cases/00-time-matrix.md | 时间机制（**#32 HotSku 10s 已修正**；#40 对账锁 600s）|
| docs/test-3/REVIEW-METHODOLOGY.md | 三层验证法（L0/L1/L2）|
| docs/test-3/review/ISSUES.md | **T-001~048 全量清单**（含本轮 T-045/046/047/048）|
| docs/test-3/helpers/testlib.py | 测试工具（**query 签名 + headers 自定义头 + X-Admin-Call 支持**）|
| docs/test-3/pitfalls.md | 踩坑 #1~#81（**#79 G1/G2 回归坑、#80 G3 REVIEW 坑、#81 G3 执行坑**）|
| docs/test-3/HANDOFF-TASK6.md | Task6 交接（G2 状态，历史）|
| **docs/test-3/HANDOFF-TASK7.md** | **本交接文档** |
| /data/workspace/SYNC-NOTES-FOR-MASTER.md | 给对方 9 条同步说明（**注意：本轮修复后需追加**）|
| config/deploy-cloud/DEPLOY-NOTES.md | 部署实测坑 |

## 八、坑点速查（执行 G4 前必扫 pitfalls.md 全文，重点 #79/#80/#81）
1. **#79-1 最坑**：手动重启服务（非 start-all.sh）必须带 `INTERNAL_TOKEN/ADMIN_TOKEN` 环境变量（`.secrets/tokens.env`）；缺失→Feign 内部调用 401→关注 10001/点赞静默失败。**判定**：`tr '\0' '\n' < /proc/<pid>/environ | grep -c INTERNAL_TOKEN`
2. **#81-1**：含字面花括号的 Redis key（cart）用 python 访问必须 `{{{uid}}}` 转义；查不到先 print 实际 key 与 scan 对比
3. **#79-3**：限流窗口跨用例共享（@RateLimit 按 prefix+Class+Method+uid 60s）；@Idempotent 5s/10s 窗口——用例前 DEL key，幂等分支等窗口
4. **#79-2**：GenericJackson2JsonRedisSerializer 数字反序列化（Integer vs Long vs BigDecimal）——缓存命中路径必须测
5. **#79-4**：gateway 层 401 码映射（过期/黑名单 access → HTTP401+body 401，非 40101/40103）
6. **#80-2**：ES product_index=SPU 粒度、price=首 SKU 价、T-042=status=-1 标记
7. **#80-3**：product 服务无鉴权拦截器——服务间 Feign 直连公开；测"需 JWT"断言必须经 gateway
8. **#81-4**：HotSku 触发需库存 init（未 init 的 SKU preDeduct 直接 40002）
9. **#81-6**：连续无间隔压测会 gateway 连接排队超时——限流用例间隔 0.3-0.5s 或分批
10. **#81-3**：cart check-all 是 @RequestParam（query）；check 是 body
11. **逻辑删除不删缓存**：deleted=1 后详情断言前须 DEL 缓存 key
12. **写后读主从延迟**：Redis 写后 sleep 1-2s 再断言；MySQL 落库等 MQ 1-3s

## 九、方法论与执行纪律（必读）
1. **三层验证法**：L0 静态 / L1 框架语义（源码/jar 实证）/ L2 运行态——结论分级，禁止"没问题"（REVIEW-METHODOLOGY.md）
2. **改 common 后**：`mvn install -pl my-xhs-common`（看 BUILD SUCCESS）→ 依赖服务 **rm -rf target** 重打包 → 验证 jar 内 class（解压 BOOT-INF/lib 嵌套 jar）
3. **pgrep/pkill -f 自匹配**：用 `[b]in/bash`/`[s]tart` 括号技巧
4. **curl 一律 --max-time**（健康检查/脚本内）
5. **start-all.sh**：95s 冷启动（15 服务并行）；改 JAVA_OPTS 行后必须 bash -n + 引号配对校验
6. **R4**：全局 Long→ToStringSerializer，JSON 中 id/计数均为字符串（断言 int() 转换）
7. **Redis 操作**：redis-py（无 redis-cli）；6379=主；**max_connections=1 连接池**（多连接实例并发会串话，见 G3 教训）
8. **测试数据**：统一前缀（g4x_ 等），执行后清理；**禁止批量测试**（用户明确要求逐用例）
9. **服务重启**：带 INTERNAL_TOKEN/ADMIN_TOKEN（.secrets/tokens.env）+ mbeanregistry + skywalking agent（参照 start-all.sh 的 JAVA_OPTS）
10. **ES 查询**：带认证（业务 19200 / SW 19201 密码不同）；逻辑删除/索引残留清理 `_delete_by_query`

## 十、下一步计划（路线图）
- **G4 优惠券（下一步）**：coupon(19010)——模板/领券/用券 + couponExpireJob(xxl#16) + couponReconcileJob(xxl#15)；管理端点 `/api/coupon/template/**` 在 gateway hmac 白名单（JWT+X-Admin-Call 双层，参照 product 模式）
- **G5 交易**：下单(P2-12)/支付(P2-4)/退款/关单/库存 + 订单全部定时任务 + 事务消息/延时/重试 + **T-047 下单层 SPU/SKU 双状态校验验证**
- **G6 搜索首页**：搜索/热搜/推荐/Feed + feedCleanupJob(18)/recommend×3 + 索引重建/增量
- **G7 通知IM计数**：通知/IM/计数 TTL + unreadReconcile/counterReconcile/CounterBuffer/SSE/IM 心跳
- 每组均按 G1-G3 模式：代码实证→写用例→深度 REVIEW→修复→逐用例执行（testlib 复用 + L2 数据验证）；**执行等用户指示**

## 十一、给下一个 AI 的执行要点（G4——待用户指示再启动）
1. **测试主线**：G4 优惠券（模板 CRUD/领券/用券/券状态机 + couponExpireJob(xxl#16) + couponReconcileJob(xxl#15)）——按 G1-G3 模式：代码实证→写用例→深度 REVIEW→修复→逐用例执行 + L2 数据验证；**禁止批量**
2. **G4 已知素材**（Task7 实测核对）：coupon 19010；管理端点 `/api/coupon/template` 与 `/api/coupon/template/**` 在 gateway hmac 白名单（368 行"管理端点已由 JWT + X-Admin-Call 双层保护"区域）——**写端点需 X-Admin-Call，对照 product 模式**；**xxl 实测（2026-08-14 核对 xxl_job_info）：#15 couponReconcileJob + #16 couponExpireJob 均 job_group=10、cron `0 * * * * ?`=每分钟、trigger_status=1 已启用**（Task6 曾记录"落入 sample 组"——已核对现在在 10，无需再改）
3. **测试前**：确认 coupon 服务 UP、xxl 任务 15/16 存在且启用（xxl_job_info 核对 job_group）、t_coupon 相关表结构（my_xhs_coupon 库）
4. **风险提醒**：coupon 若涉及 Redis 缓存（模板/库存），先扫 T-045/046 同族序列化问题；改 common 必须全量重建
5. **执行记录**：每用例 L1+L2 断言，发现新问题登记 T- 系列（延续 T-049+）

## 十二、本交接文档 REVIEW 记录（2026-08-14 10:15）
- **数据核对**：15 服务 UP、DB 全 0、ES 双索引 0、Redis 已清（用户残留 1 条 g3log_ 已删）
- **代码核对**：T-046（RedisConfig.java）、T-047（SkuVO/SkuService/ProductFeignClient/CartService 4 文件）、T-048（gateway application.yml）改动文件与行号如上
- **文档自检**：§0-§十一 与当前实际状态一致；G3 用例数 15+17=32 与文档一致
- **遗留确认**：G1/G2 未复跑（common 改动为白名单扩展，G1/G2 缓存为 String 标量）；如需保险复跑 G2-02-11（comment:count 缓存命中）
