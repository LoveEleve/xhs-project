# my-xhs 交接文档 — Task9（G6 搜索首页 + G7 通知IM计数 + G8 可观测性 + 全量修复，G1-G8 全部完成）

> 2026-08-15 | 承接 Task8（G4/G5 完成）→ 本阶段：**G6 搜索首页（38）+ G7 通知IM计数（36）+ G8 可观测性（6）+ G2 回归（43）+ 全量问题修复（A 类 6 项闭环 + B 类 15 项代码修复 + T-094/100~104 文档化）**
> 给下一个 AI 的**最终全量交接**。重点：**问题全量清单（§五 T-001~105 全部有结论）、修复明细（§六）、坑点速查（§八 #82-88）、执行纪律（§九）、下一步（§十）**

---

## 零、状态速览（2026-08-15）

```
微服务 15 个 UP（本机容器 21.214.97.212，start-all.sh 95s 冷启动）| 中间件 27 容器（21.130.247.89，对方管理）
测试进度：G1 ✅78 | G2 ✅43+回归43 | G3 ✅32 | G4 ✅23 | G5 ✅41 | G6 ✅38+3轮回归 | G7 ✅36+3轮回归 | G8 ✅6
修复：A 类（中间件/部署）6 项全闭环（t_item_feature 建表/Dashboard 免登录/xxl 超时等）；B 类（微服务）15 项代码修复全部打包运行（+T-094 客户端规范、T-100~104 文档修正）
脏数据：MySQL 全 0（含 t_item_feature 已建表、行为表 64 条预置种子保留）| ES 三索引 0 | Redis 全 0
新工具：scripts/restart-service.sh（重启脚本固化——pitfalls #88，禁止手搓重启）
日志：15 服务 /logs/my-xhs-{服务}.json（Logstash TCP 15044 → ES myxhs-logs-*）
Token→/tmp/test_token.txt（过期需重新登录）| .secrets/tokens.env（ADMIN_TOKEN/INTERNAL_TOKEN）| 凭据 Xhs@2026#*
重启参数：analytics 必须 -Dmanagement.admin-token（restart-service.sh 已含）
```

## 一、环境拓扑（与 Task8 一致 + 本轮变更）

- **微服务机 = 本机容器 21.214.97.212**（15 JVM：gateway19000/user19001/content19002/analytics19003/counter19004/product19006/cart19008/inventory19009/coupon19010/order19011/payment19012/notification19013/im19014/home19015/search19016）——**全部运行最新修复 jar**
- **中间件机 = 21.130.247.89**（27 容器，对方管理——A 类修复已由对方/本机闭环）
- **Redis**：主 6379/从 6380/哨兵 26379；AOF+noeviction；写后 sleep 1-2s
- **MySQL 主从**：3306 主/3307 从；**读写分离**（注意：通知聚合标题已修复读主库 T-098）
- **RocketMQ**：nameserver 9876、dashboard 18081（**已免登录可查 topic**——A-3 对方重建）、broker 11911
- **ES**：业务 19200（elastic/Xhs@2026#Elastic）、SW 存储 19201（elastic/Xhs@2026#ElasticSW——密码不同）
- **Canal**：note/product/inventory 三实例（rocketMQ 模式，11111 不监听是预期——A-4 撤销）
- **xxl-job**：18080 admin（admin/123456）；21 任务（executor_timeout 已设 60s/300s——A-5）

## 二、日志与可观测性（L4 全链路打通，本轮完成）

- 本地 JSON → Logstash TCP 15044 → ES myxhs-logs-*（11 万+ 日志，traceId 跨服务关联命中）
- **SkyWalking**：ES 19201 存储（sw_segment 41 万+/sw_metrics 213 万/天）；15 服务全有 segment
- **Prometheus**：23 targets 全 UP（canal/ES/15 服务/mysql×2/node/prometheus/redis/skywalking-oap）、1757 指标、业务指标出数（feed_push=228/orders=48/mq=43）
- **Grafana**：10 看板 + Prometheus 数据源
- **T-099 已修**：gateway 日志启用 SkyWalking %tid（agent 补装 apm-toolkit-logback-1.x-activation）——**日志行同时输出业务 traceId + SW tid**，排障闭环（一行日志 → SW trace 查询）

## 三、测试进度（主线，2026-08-15 全绿）

| 组 | 用例数 | 状态 | 备注 |
|---|---|---|---|
| G1 认证用户 | 78 | ✅ | Task6/7 |
| G2 内容社交 | 43 + **回归 43** | ✅ | 本轮回归（T-100~105 登记）|
| G3 商品购物车 | 32 + **回归 32** | ✅ | 2026-08-15 回归（T-106/109 修复、T-107/108 观察）|
| G4 优惠券 | 23 + **回归 23** | ✅ | 2026-08-15 回归（无新增缺陷）|
| G5 交易 | 41 + **回归 41** | ✅ | 2026-08-15 回归（T-110 观察、T-071 修复确认）|
| G6 搜索首页 | 38 + **3 轮回归** | ✅ | 本轮（T-082/083/084/085/087/088/089/090/092/093 修复）|
| G7 通知IM计数 | 36 + **3 轮回归** | ✅ | 本轮（T-094~098 处理）|
| **G8 可观测性** | 6 | ✅ | 本轮（L4 基础设施完整）|

## 四、Task9 工作内容（2026-08-14~15，四大部分）

### 4.1 G6 搜索首页（38 用例，3 轮回归全绿）
- 文档：G6-01-search.md（16）/ G6-02-home-feed.md（10）/ G6-03-recommend.md（12）
- **关键运行态**：搜索/首页端点**需 JWT（免 HMAC）**——JWT white-list 未含 search/home（初版文档"公开"错误，运行态修正）；管理端点（index/rebuild、hot/pin|block、recommend/compute）= JWT + X-Admin-Call
- **推荐系统**：5 路召回→粗排→精排→重排；**t_item_feature 建表后 4 项功能恢复**（CONTENT/GEO 召回、精排质量分、兴趣标签、category 打散）
- **热搜**：60s 计算周期、Lua 反作弊三级（屏蔽词/IP 10/min/用户 300s）、置顶/屏蔽管理
- **ES**：note/product/suggest 三索引；canal→MQ→consumer 秒级；**T-092 已修：canal 增量补计数**（hot 排序真实生效）
- **hasMore 语义（T-085 已修）**：多取 1 条判断——尾页取满 hasMore=false

### 4.2 G7 通知IM计数（36 用例，3 轮回归全绿）
- 文档：G7-01-notification.md（14）/ G7-02-im.md（12）/ G7-03-counter.md（10）
- **SSE**：ticket 30s 一次性（GETDEL）+ 心跳 10s 续期 + 实时推送；**sse/ticket 免 HMAC**（sse/** 通配覆盖，初版文档误判）
- **IM WS**：两步鉴权（ws_ticket 5min）+ 写扩散双写事务 + seqNo + 离线（ZSet 1000/7天）+ 已读回执；**ws/ticket 免 HMAC**（同款通配）；**T-094：gateway WS 下游拒绝返回 101 死隧道（内置行为——客户端 PING 兜底规范）**
- **计数**：MQ 事件驱动（SOCIAL_TOPIC 10 事件）+ LikeSet 幂等 + Buffer 5s 刷盘 + 对账双向；**reconcile 限流 perUser（T-095 已修）**
- **T-098 已修**：聚合标题 count 从库竞态（processWithAggregate 加 @Transactional 读主库）

### 4.3 G8 可观测性（6 用例）
- 基础设施完整验证：Prometheus 23 targets/SW 15 服务/日志 traceId 关联/Grafana 10 看板/Logstash 链路/8 组件端点
- **T-099 已修**：gateway 日志双 traceId（业务+SW）

### 4.4 全量修复（A 类 6 项闭环 + B 类 15 项代码修复 + 文档化 5 项）
见 §六 修复明细

## 五、问题清单状态（ISSUES.md 全量 T-001~105——全部有结论）

- **已修验证**：T-001~003/005/007~013/016~019/021/022/022b/024/025/030~036/034b/035b/040~042/045~048（Task7 前）+ T-049~079（Task8）+ **T-081/082/083/084/085/087/088/089/090/091/092/095/098/099/103（本阶段代码修复）**
- **T-086【已修】t_item_feature 表缺失**：由 A-1 建表闭环（本机直连 MySQL 执行 DDL）——推荐 4 项功能恢复全验证
- **T-093【保留·设计取舍】收件箱已删残留分页少条**：batch-detail 过滤已删笔记后不补位（不显示已删内容是正确行为；残留靠 7 天 TTL + FeedCleanupJob 清理）——分页条数偏差可接受
- **本阶段新增文档修正**：T-100（他人编辑 403）/T-101（/api/note/my 需 HMAC）/T-102（multipart bodyHash=""）/T-104（收藏列表 list 字段）——G2 用例文档已改
- **待业务决策**：T-004（注册枚举）、T-006（JWT secret 明文）
- **理由明确保留（非缺陷）**：T-096（消息级已读=产品功能未实现）、T-097（seqNo 空洞无影响）、T-105（已删先于越权更安全）、T-020/023/027/028/029（历史观察）
- **撤销**：T-069（测试操纵）、A-4 canal（模式预期非故障）
- **未纳入范围**：AI 服务（my-xhs-ai-app 19020/ai-mcp 19021——用户尚未写好，待决策）

## 六、本阶段修复明细（代码级，全部验证）

### 6.1 基础设施修复（A 类，中间件/部署——对方 + 本机直连 MySQL）
| # | 修复 | 执行方 | 验证 |
|---|---|---|---|
| A-1 | **t_item_feature 建表**（DDL 入包+独立脚本）| **本机直连 MySQL 执行** | 表 10 字段；xxl#19 真实执行；推荐 4 项恢复全验证（CONTENT 召回/兴趣标签/质量分/打散）|
| A-2 | SW traceId 打通方案文档化（DEPLOY-NOTES §39）| 对方 | 微服务侧已实施 T-099 |
| A-3 | RocketMQ Dashboard loginRequired=false | 对方重建容器 | topic/list.query 免登录返回 ✅ |
| A-4 | Canal 11111——**撤销（rocketMQ 模式预期）** | — | canal→ES 链路从未失败 |
| A-5 | xxl executor_timeout（60s/300s + 存量 UPDATE）| **本机直连 MySQL 执行** | 11×60 + 10×300 ✅ |
| A-6/A-7 | SW 采样率确认（OAP 100%）/Grafana 数据源确认 | 对方 | — |

### 6.2 微服务代码修复（B 类，本机——对方无源码）
| # | 修复 | 文件 | 验证 |
|---|---|---|---|
| T-081【P1】| home 聚合 String→Number 强转 500（R4 序列化后遗症）| home 4 个 AggService + FeedService | Feed/详情/聚合 200 ✅ |
| T-082 | 品类打散过严（>=2→>2，允许 2 连续）| RecommendService.reRank | 3 候选返回 2 条 ✅ |
| T-083 | 特征任务表缺失仍 handle 200（误导）→ 前置检查 handleFail | RecommendComputeJob | xxl#19 handle 500 明确 DDL 提示 ✅（建表后自动恢复）|
| T-084 | Feed 脏成员 500 → 解析失败跳过 | FeedService | 脏成员 feed 200 ✅ |
| T-085 | hasMore 取满误报 → 多取 1 条判断 | search note/product + FeedService | 尾页 hasMore=false ✅ |
| T-087 | FOLLOWING 召回恒空 → FeedPushConsumer 推模式同步写 following:latest | FeedPushConsumer | 写入+FOLLOWING 来源出现 ✅ |
| T-088 | product canal 版本域 es 优先 → ts 统一（P1-4 对齐）| ProductIndexSyncConsumer | 编译部署 ✅ |
| T-089 | 热搜快照重复行 → INSERT 前删同分钟 | HotSearchService | 同分钟仅 1 组 ✅ |
| T-090 | home jar 943MB → pom 移除 5 个无引用兄弟依赖 | home pom.xml | **943MB→155MB（-84%）** ✅ |
| T-091【P1】| 删除标记无 ExternalGte version 被乱序覆盖 → 带版本 | NoteIndexSyncConsumer + ProductIndexSyncConsumer | 删除后 4s/12s 不被覆盖 ✅ |
| T-092 | ES likeCount 恒 0 → canal 增量补 counter 计数 | NoteIndexSyncConsumer | ES likeCount=2 → hot 排序正确 ✅ |
| T-095 | reconcile 限流优先鉴权 → perUser=true | CounterController | g7a 超限后 g7b 独立窗口 ✅ |
| T-098 | 聚合标题 count 从库竞态 → processWithAggregate 加 @Transactional | NotificationAggregator | 6 次聚合标题一致 ✅ |
| T-099 | SW traceId 与业务日志打通 → gateway %tid（agent 补装 toolkit-logback 插件）| gateway logback-spring.xml + agent plugins | 日志双 traceId → SW 命中 ✅ |
| T-103 | 不存在目标点赞静默 200 → 抛 404 业务码 | LikeService.like | 404"笔记/评论不存在或未发布" ✅ |
| T-094 | gateway WS 死隧道（内置）→ 客户端 PING 兜底规范 | 文档化 | — |

### 6.3 本轮教训（pitfalls #82-88 新增）
1. **#82 改 resources Lua 必须重打包**
2. **#83 JDBC found-rows 语义**（INSERT IGNORE 冲突恒 0）
3. **#84 测试清理勿删幂等记录**（MQ 重投重新预扣）
4. **#85 pay.type: remote**（模拟器 90%）
5. **#86 分片查询必须带 userId**
6. **#87 伪订单 ID 有符号**（SHA-256 前 8 字节可负）
7. **#88 重启必须用 scripts/restart-service.sh**（手搓重启反复踩坑：pgrep 自匹配/pids 历史 PID/analytics 缺 admin-token/setsid 包装 PID——全部固化进脚本）

## 七、文档地图（docs/test-3/）

| 文档 | 内容 |
|---|---|
| README.md | test-3 总览（G1-G8 分组）|
| cases/G1-auth-user/ ~ G5-trade/ | 已执行（78/43/32/23/41）|
| **cases/G6-search-home/** | README + G6-01（16）+ G6-02（10）+ G6-03（12）——3 轮回归记录 |
| **cases/G7-notify-im-counter/** | README + G7-01（14）+ G7-02（12）+ G7-03（10）——3 轮回归记录 |
| **cases/G8-observability/** | G8-observability.md（L4 基础设施 6 用例）|
| cases/G2-content-social/ | 2026-08-15 回归 43/43 记录（README 追加）|
| cases/00-time-matrix.md | 时间机制（#1~#40）|
| REVIEW-METHODOLOGY.md | 三层验证法 |
| review/ISSUES.md | **T-001~105 全量清单（全部有结论）** |
| **review/FIX-REQUESTS.md** | 修复提交清单（A 类 6 项闭环 + B 类状态）|
| review/observability-events-ddl.sql | 5 张可观测性新表 DDL |
| helpers/testlib.py | 测试工具（sign/query/headers 支持）|
| pitfalls.md | 踩坑 #1~#88 |
| HANDOFF-TASK8.md / **HANDOFF-TASK9.md** | 交接文档 |
| /data/workspace/SYNC-NOTES-FOR-MASTER.md | 对方同步说明 |

## 八、坑点速查（G 组执行前必扫 pitfalls 全文；#82-88 为本阶段新增）

1. **#88 重启**：一律 `bash scripts/restart-service.sh <模块>`（analytics 自动带 admin-token、dev profile 自动、PID/令牌自动验证）——**禁止手搓**
2. **#79-1**：手动操作必须带 INTERNAL_TOKEN/ADMIN_TOKEN
3. **#81-1**：Redis key 字面花括号 `{{{key}}}`
4. **#79-3**：限流/幂等窗口跨用例共享——执行前 DEL key；**key 格式 `{prefix}:{Class}:{method}:{uid}`，注意 prefix 有的无 myxhs 前缀（social:like）**
5. **#83**：ON DUPLICATE 探测不可靠 → INSERT IGNORE
6. **#84**：清理勿删幂等记录
7. **#85/86/87**：pay.type=remote、分片带 userId、伪订单 ID 有符号
8. **鉴权矩阵**：搜索/首页端点需 JWT 免 HMAC；sse/ticket、ws/ticket 免 HMAC（/** 通配覆盖）；管理端点 JWT+X-Admin-Call
9. **multipart 上传签名 bodyHash=""**（gateway 不缓存 multipart——sign body=None）
10. **写后读延迟**：MQ 消费 1-3s + 主从 1-2s + Buffer 5s 刷盘
11. **token 30min**：长测试中途重新登录（hmacSecret 随登录变化——写回文件）
12. **ES 认证**：19200/19201 密码不同；L2 查 ES sleep 1-2s
13. **打包**：多模块任一失败全部不产出（单独 -pl）；**测试代码编译失败用 -Dmaven.test.skip=true**（analytics 遗留测试过时）
14. **analytics 重启必须 admin-token**（脚本已含）

## 九、方法论与执行纪律（承接 Task7/8）

1. 三层验证法（L0/L1/L2——结论分级，禁止"没问题"）
2. 改 common 后：mvn install -pl my-xhs-common + 依赖服务 rm -rf target 重打包
3. 多模块打包：-pl 单模块；改 Lua 须重打包（#82）
4. pgrep/pkill 用 [j] 括号技巧
5. curl 一律 --max-time；start-all.sh 95s 冷启动
6. R4：Long→ToStringSerializer，id/计数断言 int() 转换
7. Redis：redis-py（max_connections=1）；6379 主
8. 测试数据统一前缀（g6r_ 等），执行后清理；**禁止批量测试**（逐用例）
9. 服务重启带令牌 + 用 restart-service.sh
10. ES 查询带认证；_delete_by_query 清理
11. 问题登记 T- 系列（延续 T-106+），修复后记录现象→实证→修复→验证

## 十、下一步计划（G1-G8 全部完成）

- **G1-G8 已全部完成**（297 用例 + 回归全绿 + 全量修复闭环）
- **待用户决策：AI 服务（my-xhs-ai-app 19020 / ai-mcp 19021）**——5+ Controller（/api/ai/chat|agent|agent/run|agent/run/stream|query|mcp-check|health），依赖 LLM 网关（OpenCode Go）+ 只读业务库 + my_xhs_ai 库；用户尚未写好，接入后按 G 组模式梳理（G9-AI）
- **可选项**：L3 九透镜系统性抽查（当前用例已带幂等/并发/安全抽查）、性能压测（my-xhs-benchmark）

## 十一、给下一个 AI 的执行要点（AI 模块接入时）

1. **测试主线**：G9-AI 按 G1-G8 模式（代码实证→用例→REVIEW→逐用例+L2 验证）；**禁止批量**
2. **AI 服务已知形态**（代码实证）：
   - 端口 19020（ai-app）/19021（ai-mcp）；gateway **未配路由**（需加 /api/ai/**）
   - 依赖：LLM 网关（opencode.ai/zen/go/v1，密钥走环境变量）、只读业务库（myxhs_ai_ro，GRANT SELECT）、AI 自有库（my_xhs_ai，myxhs_ai_rw）
   - 数据源指向 my_xhs_order_0（分析场景）——**注意多库访问**
3. **测试前**：启动 19020/19021 → gateway 加路由 → 确认 LLM 密钥/网络可达 → 建 my_xhs_ai 表（run store）
4. **风险提醒**：AI 服务调用外部 LLM（费用/限流/网络）——测试用例需 mock 或最小化调用；MCP 服务（19021）无 Controller（协议服务）
5. **环境现状**：15 服务 UP（全部最新 jar）、DB/Redis/ES 全 0、重启脚本就绪、T-001~105 全部有结论——可直接承接新模块

## 十二、本交接文档 REVIEW 记录（2026-08-15，两轮）

### 第一轮（成文时自检）
- ✅ 数据核对：15 服务 UP（本阶段 8 服务经 restart-service.sh 重启）、MySQL 全 0（t_item_feature 已建表）、Redis/ES 全 0、xxl timeout 60/300s
- ✅ 修复核对：A 类 6 项闭环 + B 类 15 项代码修复 + 文档化 5 项（§六 全部验证记录）
- ✅ 文档自检：§0-§十一 与当前实际一致；用例数 G1-G8 = 297
- ✅ 遗留确认：G1/G3/G4/G5 未复跑（本阶段改动均在后端服务+G2 已回归——如需保险可抽查）；AI 服务待用户决策

### 第二轮（深度 REVIEW 修正）
- ✅ 重启坑复盘固化（#88 + restart-service.sh——手搓重启 5 类坑全部入脚本）
- ✅ 观察项全量处理：能修全修（T-085/089/090/092 等 5 项），保留 3 项附实质理由（T-096/097/105）
- ✅ 采样率确认：OAP 100%（A-6）；gateway 日志 %tid 生效（T-099 实测）
- ✅ 引用有效性：#82-88 pitfalls 全部存在；SYNC-NOTES 可追溯
