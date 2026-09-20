# 基本信息
姓名：【】　电话：【】　邮箱：【】　城市：【】　工作年限：【】
求职意向：后端开发（交易 / 稳定性方向）；AI 应用工程

# 教育背景
【学校 / 专业 / 学历 / 时间】

---

# 项目一 · 小红书电商平台（【时间】 | 核心开发）

## 项目介绍
xhs 是一个内容与交易并重的社交电商平台，共 15 个 Spring Cloud 微服务。交易侧包含商品、购物车、库存、优惠券、订单、通知，内容侧包含用户、笔记、Feed、搜索、推荐，支撑网关与认证。基础设施使用 RocketMQ、Redis Sentinel、Elasticsearch、ShardingSphere（订单 4 库 × 4 表）、Canal、XXL-Job，监控由 ELK、Prometheus/Grafana、SkyWalking 组成，整体 Docker 化部署。
我在项目中负责交易与内容核心模块的设计开发，并主导稳定性优化：落地库存三级扣减、订单分片与事务消息、优惠券/购物车一致性方案、Feed 推拉结合、IM 跨实例路由、网关安全链等，并完成容量校准与全链路可观测建设。

## 职责描述（Responsibilities）
- 锁与幂等边界：按"效率锁 + 正确性兜底"分层设计并发控制——Redisson 锁负责防重复执行（关键路径直连、显式租期；抢锁失败拒绝/跳过、Redis 故障 fail-closed）；正确性由唯一键、WHERE status 乐观锁、TCC fence 状态机、消息去重与对账任务承担，避免把分布式锁当一致性保证。
- 公共组件：限流/幂等切面按序执行（@Order 10/100）+ 消息幂等 helper 显式去重 + 关键路径 Redisson 直连锁，配套读写分离路由与 SQL 防护拦截器；MQ 透传 traceId/灰度/压测标记。
- 缓存一致性：商品用 Bloom（100 万容量/1% 误判）+ 逻辑过期异步刷新 + 空值缓存；写路径 afterCommit 删缓存并对 MQ 广播失效（CACHE_EVICT_TOPIC）兜底。
- 内容与社交：笔记发布/草稿/编辑/软删、评论与二级回复、DFA 敏感词热更新、话题绑定、图片上传（日期目录 + UUID + 魔数校验，可切 MinIO）；点赞、收藏、关注分别用 Set/ZSet/关系表落地（关注/取关按当前用户侧与目标用户侧拆分原子脚本），配合 Lua 原子写入、24 小时版本窗口防乱序、对账任务修复半成功。
- 购物车：Redis 为权威（items Hash + checked Set + 排序 ZSet），7 个 Lua 脚本收敛多 key 操作；CLEAR 屏障 + 同毫秒事件序号解决清空与并发写冲突；匿名合并、事件流水异步落库、每小时对账。
- 库存：Redis 分桶 + Lua 原子预扣 + DB 账本 + TCC fence 状态机的组合方案；热点 SKU 由滑动窗口检测自动扩容（默认 2→8 桶）；预扣幂等表、Outbox 唯一键（order+sku+action）防动作覆盖；超时回补、补偿与对账任务。
- 订单：ShardingSphere 4 库 × 4 表分片 + 订单号映射表支持非分片键反查；事务消息 + 本地消息表 + Broker 回查保证下单与预扣一致；延迟消息关单 + 状态机 + 事件溯源（序号唯一键 + 乐观锁收敛）；发货与确认收货走条件 UPDATE 状态流转，Mock 支付回调驱动状态收敛；支付表与订单号映射表各用独立数据源（HikariCP+JdbcTemplate）绕过分片路由，为支付域独立拆分预留。
- 优惠券：单 Lua 脚本完成库存校验、限领与扣减，msgId + 唯一键双层幂等；核销/退回、Outbox 补偿、过期与对账任务。
- 支付：发起支付与渠道策略（Mock/支付宝/微信）、回调验签与幂等（重复回调不改状态）、退款单与退款回调、支付/退款超时检测、通知补偿（5 分钟窗口、最多 10 次、批量 100）与每日对账；事件表记录支付状态变迁。
- 商品：分类树与 SPU/SKU 管理；SPU 详情多级缓存（布隆过滤器前置 + Redis 逻辑过期 + DB 回源，异步单飞刷新，空值占位防穿透），更新后事务提交再失效缓存；创建带幂等键与接口限流。
- 计数：点赞/收藏/关注等 10 类计数走 Redis，msgId 去重 + 5 秒攒批刷盘，DB 故障重试后回写缓冲、恢复自动补刷；计数对账以 Redis 为准修 DB，并支持从 analytics 权威 Set 重建。
- 对账与兜底：沉淀 8 个对账/修复任务（库存、券、购物车、未读、关注、本地消息补发、超时关单、订单映射补录），Redis 与 MySQL 差异可自动收敛。
- 搜索：Canal + MQ 双通道同步 ES，外部版本号拒绝陈旧写、tombstone 防旧消息复活；搜索建议（completion+前缀缓存）、热搜榜（实时+快照+置顶/屏蔽+反作弊）、搜索历史（去重截断+TTL）、search_after 深分页、增量补偿与全量重建（别名切换）。
- 推荐与 Feed：行为上报 → 特征、热池、ItemCF 三个离线任务预计算，线上 6 种召回策略（含 GeoHash 同城）；Feed 推拉结合，大 V 走发件箱拉模式，普通用户按粉丝批量写收件箱并支持 cursor 断点续推。
- IM 与通知：WebSocket 一致性哈希（150 虚拟节点）把会话固定到实例，跨实例走 Redis pub/sub，离线消息上线补发（上限 1000 条/7 天），已读/未读同步，多端登录踢旧连接，ticket 两步握手；通知按自然日窗口聚合（Redis SETNX+Lua，"等 N 人"），SSE 跨实例推送，未读对账限速防雪崩。
- 网关：8 个过滤器按序组链（BodyCache → 日志 → 鉴权 → 染色 → HMAC → 限流 → 灰度 → 版本）；JWT 注入用户身份并覆盖伪造头，HMAC（默认关闭、按需启用）以 method/path/query/时间戳/nonce/bodyHash 生成签名并用 Lua 防重放；维护 16 条服务路由；灰度按用户哈希 10% 打标（实例过滤未实现）、版本头默认 v1、未知版本降级。
- 公共组件（扩展）：号段 ID 生成器（DB 段号 + 双 Buffer 预加载）、读写分离路由（inventory 已启用；MyBatis 拦截器 + readOnly 事务路由）、多版本 API（@ApiVersion 替换 HandlerMapping）、审计独立事务模板（REQUIRES_NEW）、批量写入执行器（CPU×2/JDBC 5000/ID 分片）、造数框架（规模倍数）、HTTP ETag/304、Sentinel 客户端接入（网关兜底规则 + Dashboard，规则经控制台导入）。
- Zone 多活与容灾（专项）：同 zone 优先路由（**netem 跨区模拟实测吞吐 +34%、P99 -41%**）+ 健康检查摘除（实测切换 RTO 1.3~4.8s、回切 <5s）；数据面 zone 感知（MySQL 读本 zone/从库故障降级恢复；Redis 客户端读副本写主库）；Redis 双主仿真（DUMP/RESTORE + LWW + 周期对账，单侧故障不中断、恢复 ≤35s 追平）；支持运行时热切 zone（不重启）；发布链路加 PID 校验杜绝旧进程假成功。
- 部署与运维：27 个容器的 Docker 编排（全部 restart:always + healthcheck 覆盖 + 9 组 depends_on 时序），并核验 compose 配置与运行时零漂移；备份体系（MySQL 每日全量 + binlog 保留 30 天、Redis 每 6 小时 BGSAVE、ES 每日快照）；日志保留治理（ILM 30 天策略 + 环境清理脚本）；沉淀 22 条部署踩坑与开机自愈脚本。
- 覆盖审计与测试治理：在 117 项矩阵之外做文件级覆盖审计（购物车 34 个文件、内容 38/38、网关 11 个核心类），识别并修复"假修复/假测试"（注释冒充、非原子称原子、测试未真正执行等），补跑 common 97 + user 14 个单测全绿。
- 公共组件与稳定性：限流→业务幂等切面按序执行（@Order 10/100）；消息幂等由 helper 显式去重、关键路径 Redisson 直连锁；MQ 透传 traceId/灰度/压测标记；优雅停机（先摘流量再停、缓冲刷盘）；沉淀 20 个 @XxlJob 兜底任务与 17 个业务 topic（另有 2 个重试约定 topic）；搭建 117 项测试矩阵，用混沌注入框架（Nacos 动态开关）+ iptables 做故障注入，压测流量经影子表隔离（SQL 自动改写 _shadow 表），完成容量压测与限流校准、traceId 全链路与 DLQ 治理。
- 配置中心真实接入（修复"假生效"）：审计发现 15 个服务在 SCA 2023 下 `shared-configs` 为死配置（实际靠本地 yml 兜底），改为 `spring.config.import: optional:nacos:` 并为 14 个服务补齐 config 命名空间、网关补依赖；导入 3 个公共配置后全量重建发布，15/15 服务 `Load config success`、Nacos 侧请求 200；沉淀幂等导入脚本（nacos-import-configs.sh）与部署种子过期标注。
- 定时任务治理（XXL-Job）：审计 20 个任务/10 个执行器组；定位退款超时任务因非法 cron（`0/60` 秒字段增量越界）被调度器自动禁用、从未执行，修复后连续 200/200；3 个每日任务因环境非 7×24 + DO_NOTHING 静默丢窗口，改 `FIRE_ONCE_NOW`；拆解日报 633 条"失败"为调度失败/未上报/真实失败三类。
- APM 接入（SkyWalking）：修复"组件在跑但没数据"——agent 9.7.0 落地并沉淀幂等安装脚本；完成 Spring Boot 3 插件适配（springmvc 3/4/5 移出、6.x/webflux 6.x/gateway 4.x 移入、清理 macOS 元数据）；release/restart 启动脚本自动挂载（SW_AGENT_DIR/COLLECTOR/DISABLED/IGNORE_SUFFIX/SAMPLE 环境变量化）；`ignore_suffix` 降噪 + 采样可调。
- 日志与索引治理：修复漏网 `replicas=1` 日志索引导致的 ES yellow，新增索引副本巡检 cron；统一日志保留双口径（设计 ILM 30 天策略 + 环境清理脚本 7 天/本地文件 3 天）。
- 日志规范与滚动上限：15 服务统一 Logback JSON 结构化日志（单文件 100MB、保留 7 天、总量 2GB 上限）并批量重建发布生效；JSON 字段含 traceId/服务名/级别/stack_trace，支撑 ES 按 traceId 检索；采集链路 Filebeat filestream+ndjson → Logstash（grok 提取服务名/时间规范化）→ ES 按天索引（myxhs-logs-YYYY.MM.dd）→ Kibana。
- 成本与磁盘治理：清理 19 个残留 JVM（释放 16.4GB RSS，内存 45→28Gi）；日志目录 3.7G→2.6G + 每日 cron；发布包保留 3 版（8.1G→6.5G）；npm 缓存 3.6G→591M；apt 缓存清理并沉淀治理报告。
- MySQL 稳定性治理：定位"僵尸连接风暴"（318 条挂起查询、连接 459/500）并清理恢复（459→106）；comment 慢 SQL 由相关子查询改窗口函数 + 复合索引；补充连接/长查询告警规则与"压测中勿发布"纪律。
- 仓库与脚本卫生：pids 运行时文件取消 Git 跟踪并入 .gitignore；发布/重启脚本的 Agent 与降噪参数全部环境变量化，避免"改脚本才能调参"。
- 事实校准与知识沉淀：全库数字/口径按运行态校准（购物车 Lua 6→7、Sentinel 规则 15→16、common 单测 97/user 14、业务 topic 17、锁切面"未接入"纠偏、ILM 双口径等）；沉淀 76 篇深度问答（含 14 个组件深度拷打、12 条叙事链、逐链自测清单）与修复报告 4 篇。
- JVM 与线程池调优：G1 + MaxGCPauseMillis=200 + Metaspace 256m 参数体系；定位类加载锁热点（TraceContextHolder 每请求 Class.forName，92/99 Tomcat 线程 BLOCKED、product 仅 1,074 RPS）并改静态桥接修复；聚合服务内外线程池隔离 + MDC 包装线程池保证 traceId 跨池不断链。
- 可观测与告警体系：Prometheus 规则 31→40 条（9 组，severity 分级；含 MySQL 连接/复制 4 条新规则与 Runbook）；Grafana 看板 datasource provisioning 即代码；修复 Alertmanager 通知黑洞（receiver 空 → alert-sink 落地 /data2/logs/alerts.jsonl + send_resolved）；短命告警 keep_firing_for 防吞；Watchdog 元监控；SLO 错误预算（30 天 43m12s）与 Burn Ledger；完成告警端到端演练验证。
- CI/CD 落地（Gitea Actions）：Gitea 1.22 + act_runner v0.6.1（systemd、Docker 执行器、挂载 .m2 复用缓存）；workflow 覆盖编译/单测/规范扫描，ci-gate.sh 门禁实测拦截违规提交（printStackTrace → RED）；版本化发布与自动回滚链路配套。
- MySQL 故障转移演练：停主 10.2s、提升从库 0.087s、应用切换 22s，RTO≈32s（不含发现时间）；本次 RPO=0；输出 5 项短板（无自动切换/配置散落/异步复制 RPO 不保证/errant GTID/短命告警）与路线。
- Zone 多活延伸：ZoneLocator 自动发现（env/文件/网段 CIDR）+ X-Zone 跨服务传播（入/出站过滤 + 指标，实测 10/10）；网关反应式 zone LB（12/12 就近、切换 5.54s）；动态 JDBC/Spring 热切（内容服务读主从切换、购物车热切不重启）；发布链路 PID 校验杜绝旧进程假成功。
- SqlGuard v2 与限流加固：SQL 防护从"只判定"升级为可配置安全阻断（200ms 告警 + 5 次熔断 + 白名单阻断/豁免/冷却 + 3 个 Prometheus 指标 + 6 单测）；网关 Sentinel 规则 Nacos 化 + 30s 真空期兜底 + 路由 metadata 兜底双轨。
- 基础设施细节治理：RocketMQ topic 全量初始化（autoCreateTopicEnable=false 导致"M no route info"事故：namesrv 仅 1 个 topic）修复并脚本固化；Nacos gRPC 19848 启动噪音排查定性（非故障、不设告警）；Nacos 鉴权缺失与密码明文登记为安全债；compose 配置与运行时零漂移核验（27 容器全量对照）。 混沌演练 7 场景（Redis/MQ pause、CPU 满载、磁盘 burn、MySQL pause、优雅停机）全部通过；TIME_WAIT/临时端口耗尽排查口径（连接复用/keep-alive/池化）；部署包脚本（setup-firewall/setup-ip/重启脚本）配套。
- 发布"假成功"事故复盘与根治：发现批次重启后 11/15 服务实际仍跑旧 jar（脚本只看健康检查、端口占用未清、PID 指纹缺失）；发布链路加"监听 PID==本次启动 PID"校验 + ensure_port_free + setsid PID 回写 + 多实例用最新构建，并真重启 11 个服务。
- Zone LB 接线修正（5 坑）：供应商 Bean 必须进 LB 子 context、resolver 用标准 metadata 键、健康检查 liveness vs 聚合 health、同 zone 最小实例阈值单实例不适用、SIGTERM 造成"假恢复"必须 kill -9 验证；修正后同 zone 命中 240/240。
- Redis 双主同步修复：对账逻辑误删问题改"存在优先"、断线无重连改 3s 自动重连并补值相等防回环；验证 dbsize 1873=1873、恢复 ≤35s 追平。
- 服务间通信与负载均衡：24 个 Feign 客户端统一 HC5 连接池（200/50）+ 分级超时（核心链路 connect 500ms/read 2s、长任务 5s）+ 统一 Decoder/ErrorDecoder（R<T> 自动解包、BizException 还原）+ 内部令牌拦截器（缺失不携带，fail-closed）+ 52 处 FallbackFactory 降级；自定义最小连接负载均衡（活跃数/权重，需调用侧埋点否则退化为轮询；平局随机化）与同 zone 优先 Supplier；优雅停机"先摘注册 + 10s 实例列表传播等待"防发布抖动。
- 用户与认证：JWT 双 token（access 30 分钟 / refresh 7 天）+ 图形验证码（Redis）+ 刷新时旧 access 拉黑、登出失效；网关统一鉴权与身份注入（覆盖伪造头），直连服务路径鉴权 fail-closed。
- RPC 选型决策（Dubbo 试点）：完成 cart→product 双协议试点（开关+回退），A/B 实测 +4.9% RPS / P50 -19%，修复注册名冲突坑；最终按"运维复杂度与收益不匹配"决策不引入并全量回滚（选型数据留档为决策依据）。
- 分布式 ID 与缓存预热：订单 Snowflake 主键（worker-id 按本机 IP 推导防冲突）、号段 ID 双 Buffer 预加载、Bloom 异步分段预热（100 万容量/1% 误判）；ShardingSphere 绑定表 5 张逻辑表（同分片键防 JOIN 笛卡尔积）。
- 多 ORM 与动态数据源（专项 Demo）：MyBatis + MyBatis-Plus + Spring Data JPA 三框架同应用并存（独立 EMF/事务管理器，逻辑删除语义差异实测）；ShardingSphere 分片与 Zone 动态数据源同应用并存（映射表 master/slave 运行时切换，server_id 1↔2 验证、分片下单不受影响）。
- Zone 数据面细节：数据源 zone 来源统一为动态 ZoneContext（避免误杀第二实例）；从库宕机时 health 忽略路由目标、不阻塞发布（12 服务 ignore-routing 属性全量生效）；路由决策 9 分支指标（ZoneRouteMetrics）；最小连接平局随机化；RPO/冲突策略评估（单写场景无需 CRDT）；D4 演练复盘进程崩溃 1.31s/3.25s、分区 4.79s、自动回切。
- 语义与测试细节：区分业务失败与依赖失败（业务失败返回 404 而非 503）；陈旧测试按现行语义修正（退券 RV30 / 预扣异常 RV31）；订单快照表与事件表同分片保证单用户一致性；用户地址管理（默认地址切换 + 锁内数量校验）；发布脚本端口占用者精确清理（校验为本模块 app.jar 防误杀）。

- 多实例正确性专项（2026-09-20）：修复跨实例 SSE 两处真 Bug（本地 `isOnline` 短路跨实例推送、Jackson `TextNode` 解析失败导致消息全判格式异常），IM 跨实例投递与 WS 经网关路径实测通过；Snowflake 同机多实例 worker-id 加入端口维度防重号（354 vs 454）。
- 分片治理专项（2026-09-20）：定位分片倾斜根因（Snowflake 增量 `Δms<<22` 恒被 16 整除 → 低流量下 `user_id mod 16` 恒定，131 单落 2/16 片），改哈希取模并迁移存量 682 行（5 表/dry-run/停服约 2 分钟），配套分布·一致性·路由审计脚本。
- 消息乱序治理（2026-09-20）：Like/Favorite 计数消费端补 `actionTime` 版本门（旧事件跳过），并识别 comment/follow 事件缺时间戳的生产端改造项；缓存"更新→读"实测 24ms 一致（evict + 延迟二次删除 1s）。
- Redis 故障语义治理（2026-09-20）：网关 Redis 故障由"全站 401 Token 已被注销"改 503 可重试（保持 fail-closed）；自定义 Lettuce 工厂补 1s commandTimeout 使 CacheHelper 的 DB 回退生效；盘点 6380 主/6379 只读从/单 sentinel/容器命名颠倒等拓扑事实与 SPOF 风险。
- 运维执行面修复（2026-09-20）：MySQL 备份脚本（旧云模板：无效 host/端口 + 吞错无校验 → 长期 20B 空产物）重写为 docker exec + size/gunzip 双校验并接入 cron；ES ILM 落地（policy/模板/存量索引挂载）与每日清理恢复执行。
- 对抗与故障注入（2026-09-20）：订单状态机 8 场景并发对抗（含取消vs支付自动退款闭环、累计退款语义）；分布式事务故障注入（消费者宕机追平、MQ 暂停下单）；依赖变慢注入（netem 4s → 降级语义修复为 503）。

## 关键结果（Key Achievements）
- 并发控制分层后，锁只承担效率职责（Redis 故障时加锁 fail-closed 拒绝）：重复下单/重复回调/重复点赞分别被唯一键、状态机与幂等表拦截，故障注入下无幽灵数据。
- 缓存侧 Bloom + 逻辑过期将商品详情压测到 4,871 RPS / P99 50ms（类加载锁修复后、无本地缓存），缓存失效用 afterCommit + MQ 广播双保险。
- TCC fence 在空回滚、悬挂、超量三类异常下均能收敛（11 场景：幂等/空回滚/悬挂拒绝/超量拒绝/fence 状态机实测通过），等价于给资源端加了单调状态校验，不依赖时钟假设。
- 交易链路完整跑通：下单 → 库存预扣 → 确认 → 取消/关闭回补全流程验证通过；按 user_id 分片隔离用户维度数据（固定 4×4 暂无 rehash，扩容走双写迁移）。
- 库存一致性：20 并发预扣不超卖；超量请求整单拒绝；TCC /tcc/try|confirm|cancel 直接调用 11 场景实测通过（幂等/空回滚/悬挂拒绝/超量拒绝/fence 状态机 1→2/3）；注入 75 秒事务回查延迟后库存只扣一次。
- 幂等与对账：重复下单、重复回调不改状态；消息回放计数与优惠券不重复；关注半成功可由对账任务修复，故障注入下无幽灵关系。
- 优惠券与购物车：10 并发领券严格限领 2 张，单次领券约 2ms；购物车 10 并发加购无丢失，清空与并发写不串，匿名合并带容量保护。
- 内容与 Feed：笔记发布保持原子（内容+消息同事务）；Feed 批量写收件箱把 500 粉丝推送压缩到一次往返，大 V 拉模式避免写扩散，断点续推可恢复。
- 搜索：1,448 RPS / P99 61ms（ES 调优后；此前 508 RPS/P99 191ms）；索引同步带版本控制，乱序消息不会覆盖新数据；建议、热搜、历史、深分页功能完备。
- 推荐：冷启动闭环，6 用户 × 3 笔记行为产出特征 2 条、热池 3 条、ItemCF 相似对 6、feed 2 条、similar 相似度 0.913。
- IM 与通知：双实例下跨实例消息路由 + ACK + 落库、离线补发、双端 traceId 一致；通知聚合数 6→9，SSE 跨实例可达，未读操作并发不超减。
- 计数：覆盖 10 类事件计数，2 小时去重 + 5 秒攒批刷盘；DB 故障时重试后回写缓冲，恢复自动补刷，计数不丢。
- 商品与缓存：布隆过滤器（100 万容量/1% 误判）+ 空值缓存双层防穿透，逻辑过期 30min、物理 TTL 120min；SPU 详情缓存命中时 DB 零回源。
- 计数刷盘：1 万次点赞合并为约 2000 个计数 key、一次批量 SQL 落库；DB 故障注入后重试转回写缓冲，表恢复自动补刷，计数不丢。
- 网关与安全：HMAC 合法 200 / 缺签与篡改 403 / 重放 403；Redis 故障鉴权 fail-closed 并自愈；限流按实测容量校准（content/product 500、home/recommend 300），超限精确拒绝。
- 支付与退款：重复支付回调、重复退款回调均不改状态；退款回补库存与退券走幂等路径，通知补偿失败可自动补发；支付、退款、库存、券每日对账可收敛差异。
- 部署与备份：27 个容器 healthcheck 全覆盖、依赖时序就绪；备份任务上线（MySQL 每日全量 + binlog 30 天、Redis 每 6 小时、ES 每日快照），并有开机自愈脚本。
- 性能与容量：类加载锁修复后 product 4,871、comment 4,418、home 1,861、search 1,448 RPS 基线（P99 50/70/49.6/61ms；无本地缓存）；6 秒慢下游注入下首页仍返回 200、约 2 秒降级；ES 容器 CPU 2→6 核 + 客户端 IO 线程 4→16 后搜索 508→1,448 RPS（P99 191→61ms）；Redis 切主 2.3 秒会话无错乱。
- 稳定性与可观测：15 个服务健康、117 项测试收口、21 项运行态缺口修复并附回归；traceId 覆盖服务、MQ 与长连接三跳；DLQ 积压按 26 个消费组可见；三组历史死信清账（14 条归档：3 条演练毒丸 + 11 条混沌窗口合法事件）。
- 配置中心：修复死配置后 15/15 服务真实从 Nacos 加载配置（`optional` 依赖保证 Nacos 不可用不阻塞启动），完成全量重建发布与验证。
- 定时任务：20/20 任务可调度（1 个"从未运行"任务修复 + 3 个丢窗口任务改为补跑），日报失败口径可解释（633 条中真实业务失败 2 条）。
- APM：15/15 服务注册，真实链路 gateway→home 53 span（CROSS_PROCESS/CROSS_THREAD 完整，含 GatewayFilter）；`ignore_suffix` 对 SpringMVC 生效（gateway WebFlux 抓取噪音列入已知边界）。
- 日志/ES：yellow 索引修复 + 巡检 cron 上线；保留策略统一（ILM 30d/清理 7d/文件 3d）。
- 成本：内存 45→28Gi、发布包 8.1→6.5G、npm 3.6G→591M（附清理明细与治理报告）。
- 数据一致性：全库数字/口径完成一轮实测校准（65 测试=61 单测+4 契约/94 评测用例/8 红队/4 性能场景等），确保简历与运行态一致。
- JVM/性能：类加载锁修复后 product 1,074→4,871 RPS（4.5x，92/99 线程 BLOCKED 归零）；线程池隔离后聚合链路互不饥饿，MDC traceId 跨池保留。
- 告警体系：40 规则/9 组上线分级；通知黑洞修复并端到端验证（firing→落盘→resolved）；SLO 错误预算 43m12s/30 天 + Burn Ledger 落地。
- CI/CD：门禁实测拦截违规提交（RED 日志）；版本化发布 + 自动回滚上线；act_runner 复用 .m2 缓存加速流水线。
- MySQL 演练：RTO≈32s 分解（停主 10.2s/提升 0.087s/切换 22s）+ 5 短板清单 + RPO=0（本次）与回切验证。
- Zone 自动发现与传播：zone-a（网段）/zone-b（文件）自动识别，X-Zone 传播 10/10；动态数据源热切实测（+207→+206 / +40↔+40 不重启）。
- 服务间通信：HC5 池化 + 分级超时 + 52 处降级点上线；最小连接/同 zone 优先路由与优雅停机（摘注册+传播等待）配套，发布期间调用不抖动。
- 认证安全：双 token 刷新/旧 token 拉黑/登出失效闭环 + 验证码防刷；直连服务鉴权 fail-closed。
- 选型与成本判断：Dubbo 试点 A/B +4.9% RPS 后决策不引入并回滚，避免为性能盲区引入长期运维成本（决策数据留档）。

- 多实例一致性（2026-09-20）：跨实例 SSE 修复后实测推送成功；IM 跨实例与网关 WS（PING→PONG）通过；同机双实例 Snowflake worker-id 唯一（354 vs 454）。
- 分片治理（2026-09-20）：哈希分片后 16/16 片全用（最大 31%，热点用户单片聚集属预期）、订单↔映射 132=132、路由抽查命中；E2E 30/30、test-09/10/11 全绿。
- 乱序防护（2026-09-20）：旧 UNLIKE/UNFAVORITE 注入被版本门拒绝（计数保持、日志留痕），新事件正常生效，API 正常路径回归通过。
- Redis 故障语义（2026-09-20）：主库暂停下 product 200（DB 回退，首跳 11.2s/后续 0.01s）、counter 0.26s、cart 0.64s、notification 0.58s；网关 503 可重试、从库暂停无感；1s 超时已配置化（Nacos 公共配置）。
- 备份与生命周期（2026-09-20）：MySQL 实体备份 3.0MB（双校验）并每日 3:30 自动执行；ES 清理老索引 2 个（1.8G）+ 日志文件 45 个，ILM `managed=True`，每日 4:00 清理恢复。
- 对抗与隔离（2026-09-20）：订单 8 对抗场景全过；消费者宕机 LAG 1→0（约 11s）且计数恰好一次；MQ 暂停下单失败无半成品、同 bizIdentifier 重试成功且仅 1 单；依赖 4s 变慢聚合 3.0s 准时降级。

---

# 项目二 · 小红书 AI 运维诊断与知识问答 Agent（【时间】 | 独立开发）

## 项目介绍
xhs-ai 是面向上述 15 微服务交易系统的 AIOps 诊断与知识问答 Agent，从零设计并实现。以自然语言为入口，自动编排 DLQ / 日志 / 指标 / 知识 / 代码 / 业务六类共 20 个自研工具（运维 16 + 业务 4），输出可回链的定位结论；对死信重投等危险变更实施 HITL 审批与可校验审计；配套检索/答案/Agent 三层评测体系与按用户 token 预算治理。技术栈 AgentScope 2.0 Java（Harness / HITL / Redis 状态存储）、Spring Boot 3.2.5、JDK 17；模型走双通道——聊天 deepseek-v4-pro、Agent 工具循环 qwen3.8-flash、降级 deepseek-v4-flash；接入 RocketMQ Admin / Elasticsearch / Prometheus（VictoriaMetrics）/ MySQL / Redis，单机 systemd 托管，接入 ELK 与 Prometheus 告警。

## 职责描述（Responsibilities）
- 需求与架构：主导需求工程（34 个业务场景，REQ→AC→TC 全链路追溯，含 NFR/STRIDE 威胁模型）；确定分层架构与 23 条 ADR（AgentScope 选型、Redis 状态存储、BM25 先行+向量止损、模型网关自研、策略引擎、扩展框架两代 SPI→Sidecar、技能仓库 GitSkill、沙箱 v1 不启用等）；划清框架边界（Flyway 只管 ai_*，不碰 agentscope_*）。
- 测试与工程规范：设计六层测试矩阵——单测/契约（实测 65：61 单测 + 4 条 LLM fixture 契约）/ 集成（IT 冒烟 6 项）/ E2E（自动化 12 项，含 SSE 与权限负向） / 评测（50 条答案 + 14 条轨迹）/ 红队 8 项 + 性能 4 场景；按 E1~E7 工程规范执行（依赖 BOM+Enforcer、出网收敛到两个域名、受控只读 SQL 三层、审计只追加、灰度与回滚流程）。
- 工具与编排：设计 20 个自研工具（运维 16：DLQ 诊断/重投、日志检索/Top 服务、指标 Top/趋势/PromQL 兜底、标签探索、消费积压、ES 索引/DSL 兜底、代码定位、知识卡目录/检索/读取；业务 4：order_trace 订单全链（跨订单/支付/退款/库存/通知，分片路由与订单服务同哈希）、order_stats 经营指标（16 分片聚合）、inventory_query 库存、coupon_query 优惠券，业务库仅 SELECT 授权）；ES DSL 与 PromQL 全部在服务端拼装，模型只填业务参数，并对参数做 clamp、服务名/索引/PromQL 白名单校验；DLQ 详情可按 originMsgId→msgId→keys 逐级检索日志取首错（匹配 message/MSG_ID/UNIQ_KEY/keys 字段），把死信与首条失败日志自动关联。统一错误返回与只读标记。20 个工具：dlq_topic_list / dlq_message_detail / dlq_redeliver / consumer_lag_top / log_search / log_top_services / es_search / es_index_list / metric_top / metric_trend / metric_query / metric_labels / knowledge_catalog / knowledge_search / card_read / code_locate / order_trace / order_stats / inventory_query / coupon_query。
- 提示词与行为约束：系统提示 13 条硬约束（同一工具最多 1 次、参数报错禁止重调、总工具调用 ≤4、系统本体问题必须走知识检索、锚点事实两关键词各查一次并读卡、引用只允许卡片 id），配合 ReAct 循环（maxIters=12、温度 0.2）控制行为边界。
- HITL 审批闭环：设计"诊断→提案→审批→执行→核验→审计"状态机；审批超时 fail-closed、同会话同指纹 pending 复用、原文指纹执行前复核；审批决策跨实例 pub/sub 事件通知（业务续跑订阅未接线）；重投后按消息所在队列的消费位点核验是否真被消费，可区分 reentered_dlq / verified_consumed / 无位点证据；always 授权写会话授权表（先撤销旧授权，后续同工具直执）、reject 级联拒绝同会话其余待审；审批执行崩溃自动补执行、卡在 executing 超时回收为失败交人工重试。
- 评测体系：检索级（30 条 hit@1）、答案级（50 条关键词 + 引用存在性硬校验，SEC 拒答用例）、Agent 级（工具选择 12 题、轨迹部分分、4 例 × 3 次稳定性）；KB 门禁 hit@1≥90%、答案门禁通过率≥90% 且引用必须全部有效；评测与单测、审计一致性、审计哈希链组成门禁脚本（本地一键），评测集 14 条轨迹用例 + 50 条答案用例 + 8 项红队断言。
- 模型网关与成本：网关实现传输重试（仅连接超时/重置/流中断，已出流不降级重放）、熔断半开（冷却后放行探测、成功清零）、备用与轻量模型降级、调用/耗时/Token 指标；按用户 token 预算三段（真实 usage 计量 → 软限 80% 切轻量模型 → 硬限 429），工具 schema token 预算指标（软 12k），单用户/全局并发护栏，请求幂等（X-Request-Id，重复提交回放、处理中 409）。
- 可靠性与恢复：Agent 状态存 Redis（进程重启可续聊）；状态丢失时从 ai_message 重建最近对话 + 滚动摘要注入；会话摘要任务（保留最近 20 条、超 50 条触发、轻量模型压缩 ≤300 字）；数据保留清理（消息 90 天 / 审计 365 天 / 状态空闲 30 天后过期）；MCP 子进程自愈（探测 + 三连击 + 限流 + systemd 拉起，默认仅告警）；去 MCP 化后 20 个工具全部自研，kill 两个 MCP 进程仍可完成 ES/Prom 诊断。
- 接口与部署：对外提供 7 组 REST 接口（对话、Agent、审批、知识、MCP 直连、会话、评测），三级鉴权（内部令牌 / 平台 JWT / 管理令牌 ADMIN），未授权一律 401；systemd 托管，JSON 日志 100MB 滚动保留 7 天，Prometheus 指标仅本机回环抓取。
- 安全与权限：RBAC 消费平台 JWT 角色（ADMIN/OPERATOR/VIEWER），管理端点（重索引/评测/诊断/MCP 直连）收敛 ADMIN；审计只追加 + 哈希链防篡改（SHA-256 前向链 + 链头行锁）+ 参数脱敏与密钥形态打码；敏感数据出网默认脱敏；红队 8 项回归（未授权/注入/密钥诱导/越权审批/危险工具/洪水/方法混淆/直调）；MCP 白名单与工具名缓存（TTL 10min，未知工具快速失败），管理端点全部收敛 ADMIN。
- 可观测与运营：17 个自定义指标（工具数/schema token/工具调用与失败率/运行时长/模型调用与 Token/熔断/预算决策/审批事件与恢复/MCP 健康/清理量）；7 条 Prometheus 告警（实例可用/5xx/熔断/预算拒绝/MCP 健康/P95/schema 预算）；traceId 贯穿 HTTP、工具调用与审计；会话按 (userId, sessionId) 归属校验防越权读取；ADMIN 运维端点（消费位点诊断、会话摘要、知识重索引）。
- 知识层：55 张结构化知识卡（架构 11 / 业务 7 / 代码导航 36 / 失败模式 1）+ ES BM25 检索 + 卡片目录/检索/整卡读取工具 + code_locate 文件行号定位；不建默认 RAG：以 BM25 为基线（hit@1=100%），向量方案按"触发 + 止损"规则不启动（触发：hit@1<90% 且失败以词汇不匹配为主；止损：无 ≥5% 提升即删除向量路径）。
- 容量与降级：资源估算单机约 10 并发诊断会话（第一瓶颈为 LLM 配额）；降级链为"重试 → 备用 provider → 轻量模型 → 显式报错"（只读检索模式为设计项）；FMEA 设计覆盖 18 种失败模式（Redis 全丢重建已实现；Bulkhead 隔离为设计项）。
- 框架权限取舍（AgentScope）：实测框架 PermissionEngine 默认 ASK 会把无只读注解工具卡在 asking（答复为空）、DONT_ASK 仍被默认规则拒绝，改为 BYPASS + 应用层工具白名单/HITL/审计补偿（明确"框架不再兜底"风险）；工具不支持热替换 → 去 MCP 化（16 工具自研）。

## 关键结果（Key Achievements）
- 故障定位效率：10 个真实故障案例 10/10 给出完整证据，Agent 平均 1.63 分钟；人工口径保守重构 20.6 分钟，降幅约 92.1%。
- 知识问答质量：55 张知识卡 BM25 检索 30 条 hit@1=100%；答案级首轮 47/50、引用有效性 97.8%，定向修复后 50/50；2026-09-20 三批复跑 kb29/30+diag15/15+sec5/5（blocked=0，1 例卡片 id 幻觉被硬校验拦截）。
- Agent 评测：工具选择 12/12；轨迹评测支持部分分，4 例 × 3 次稳定性 0.917；服务端拼装 DSL 后，同一道 P95 排查题从 98 秒答偏变为 50 秒答对。
- 变更闭环：审批重投全链路实测（pending→approved→重投→核验），毒丸消息二次入死信被准确判为 reentered_dlq，无误报成功；审计 SQL 验证"未审批高危执行 0"。
- 成本治理：单次诊断 18,292 / 1,433 tokens ≈ ¥0.012–0.048（估算，费率待网关确认；74% 为语料推算非同期 A/B）；预算三态实测（软限切轻量、硬限 429、单次问答计量 5,577 tokens）。
- 可靠性：批准后崩溃 60s 内自动补执行；executing 卡死回收为 failed 待人工；重复请求 0s 返回首次结果、并发同 id 一 200 一 409；删除 Redis 状态后仍能答出历史关键事实（消息表 + 摘要重建）。
- 需求到验证闭环：34 个场景全部有 REQ→AC→TC 追溯；评测门禁为通过率 ≥90%、回退 ≥2% 阻断、引用有效性 100%。
- 依赖治理：Enforcer 三规则全过（Java 17/重复类/依赖收敛），处置 4 项版本冲突（okhttp 降级、jedis 漂移、org.json 重复、MCP json 包豁免并附退出条件）。
- 故障演练：FMEA 设计覆盖 18 种失败模式，实做 9 个场景——停 ES 受控降级（stats 返回 indexed=-1，恢复后 55 卡自动重建）、Redis Sentinel 切主 2.3s（458 个状态键无损、会话续跑）、滚动重启停机 13s（重启后同会话继续）、kill MCP 进程级自愈，另有 5 个真实流量场景（DLQ 根因/日志检索/指标/审批重投/全链路下单）。
- 压测：N=100 会话，C=5 全部成功（P50 13.5s / P95 39.0s / P99 46.5s）；C=20 时 61% 成功、39 个请求客户端 120s 中止，服务端熔断降级、不崩不重启。
- 去 MCP 化（2026-09-20 复核）：Agent 20 个工具全部自研、`mcp-tools-enabled=false`，MCP 不参与关键路径；MCP 直连死进程从永久挂起修复为 **15.1s 快速失败 + 1.2s 按需重建**；旧 kill-MCP 19s/39s 自愈为去 MCP 化之前口径，不再引用。
- 安全验证：红队 8 项全拦截；审计链篡改可检出（篡改后校验失败）；RBAC 实测 OPERATOR→403 / ADMIN→200。
- 可观测与门禁：17 个 ai_* 指标 + 7 条告警上线；门禁一键通过（65 测试含契约 + 审计一致性 + 哈希链校验，可选 LLM 评测），CI 工作流当前仅跑单测。
- 全量评测首跑：mimo-v2.5-pro 下 100 条 nightly 全量（smoke 20 + regression 80）取得 100% 通过率 / 96% 完成率 / 0% 幻觉率，评测体系从"关键门禁可跑"进入"完整体系成立"阶段。
- 在线 QA 验证：chat 轻量问答（重启后 3.2s 返回 200）与 Agent 通道复杂诊断（145.6s 完整回答）双通道实测；AI 给出的日志结论经 ES 复核属实（19848 噪音 6319 条 / 19008 端口占用 8305 条）。
- 评测隔离与资产：评测使用专用用户并在运行前清零额度（此前预算硬限曾拦评测，默认 20 万→50 万）；55 张知识卡、14 条轨迹用例、50 条答案用例、10 个运维脚本，20 篇评审（RV01~RV20）+ RV21~RV29 报告与 10 篇专项设计。

- 模型网关容灾演练（2026-09-20）：注入无效主模型（404）→ 重试→自动切备用 deepseek-v4-flash，5/5 会话不中断（熔断后降级 4.5s）；连续 3 败熔断 60s + 冷却半开探测；"已出流不降级重放"真实命中；指标 primary error/retry/breaker_open、fallback ok 可查。
- 预算绕过修复（2026-09-20）：演练抓出 `/api/ai/chat` 未注入用户上下文导致预算整段绕过（不拒绝、不计量）→ contextWrite + 429 映射修复；agent/chat 双路径硬拒 0.2–0.3s。
- 会话摘要与恢复（2026-09-20）：修两处真缺陷（maxTokens 800 致 reasoning 空响应、60s block 过紧）后单次摘要 56.6s 压缩 25 条消息；清 Redis 状态后真对话 18.1s 三事实全中，session.rebuild 审计落库。
- 业务工具双场景（2026-09-20）：新增 order_trace（跨订单/支付/退款/库存/通知五域，分片路由与订单服务同哈希）、order_stats（16 分片聚合：24h 55 单/GMV ¥4,771/退款 ¥3,801）、inventory_query、coupon_query 四个只读工具（业务库仅 SELECT）；实测抓修 2 个真 Bug（LocalDateTime 序列化、无 id 列 SQL）；E2E 10/10。
- 评测闭环（2026-09-20）：检索 30/30=100%；答案级 kb 29/30=96.7% + diag 15/15（修正过时用例）+ sec 5/5=100%，blocked=0；引用校验拦截卡片 id 幻觉；门禁脚本 + CI 可选接入；三批结果归档。

# 技能
Java 17、Spring Boot / Spring Cloud、MySQL、Redis、RocketMQ、Elasticsearch、ShardingSphere、Canal、XXL-Job；分布式事务、缓存、消息、限流降级；AgentScope、MCP / Function Call、Agent 评测与成本控制。
