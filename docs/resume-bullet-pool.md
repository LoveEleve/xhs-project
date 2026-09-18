# 简历可选条目池（双项目）· 2026-09-18（本轮按主题全量补充）

> 用法：从下表挑选替换/补充到 `docs/resume-final.md`；⭐=推荐直接写；⚠️=口径需注意（写法见"口径提示"列）
> 每条都可在 `docs/mining/*` 与 `docs/interview-defense-handbook.md` 找到 file:line / 报告出处

## 项目一 · 电商平台 — 职责备选
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ 库存：Redis 分桶 + Lua 原子预扣 + DB 账本 + TCC；**桶数默认 2、热点桶 8，热点检测 10s/100 次自动切换** | InventoryService.java:73,76；HotSkuDetector.java:32-38 | 直接可用，数字是源码默认值 |
| ⭐ 超时回收：预扣记录 ZADD 到过期索引（score=过期毫秒），任务提前 60s 扫描回收，替代全库 SCAN | prededuct.lua:62,75；PreDeductTimeoutJob.java:101 | 讲"O(N)→O(logN)" |
| ⭐ 幂等：MySQL 幂等占位表（走 master 无延迟）+ 失败删占位允许重试；伪订单号统一 SHA-256 前 8 字节（原 fold-hash 溢出碰撞致库存永久泄漏） | InventoryService.java:229-249；OrderService.java:557 等 | ⭐强故事，可展开讲根因 |
| 订单：4 库×4 表 + 映射表反查 + Snowflake workerId 三级优先级（环境变量→-D→IP 哈希） | ShardingSphereDataSourceConfig.java:92-112 | 可用 |
| 事务消息：半消息+本地消息表+回查；回查延迟 75s 注入只扣一次；本地消息 5 次指数退避 30/60/120/240/480s | 手册 §E；LocalMessageRetryJob.java:48-51 | 可用 |
| 状态收敛：事件溯源 `INSERT IGNORE` + `uk_order_event_seq`；状态迁移全部条件 UPDATE（取消仅 0/支付仅 0/发货仅 1/确认仅 2/退款仅{1,3}） | OrderEventService.java:72；OrderMapper.java:49 | ⭐展示"状态机=DB 约束" |
| 补偿：按 RELEASE_STOCK/RETURN_COUPON/CLOSE_ORDER 分派（不依赖订单状态），重试≥2 写 pending Set 每分钟重放 | OrderCompensationConsumer.java:98-120 | 可用 |
| 缓存：Cache-Aside + 带锁防击穿（tryLock 3/10s）+ 延迟双删 500ms + 空值 2min + TTL 随机偏移防雪崩 + 布隆 100 万/1% 防穿透 + L2 逻辑过期 30min/物理 120min | CacheHelper.java:57,219,290,420；SpuService.java:130,414 | ⭐缓存细节最完整，选 2-3 条 |
| 缓存失效通道：二次删失败发 CACHE_EVICT_TOPIC 兜底；库存缓存走 Canal 版本号 Lua 防乱序 | CacheHelper.java:498；InventoryCacheEvictConsumer.java:84-99 | 可用 |
| 购物车：事件序列号 CAS（旧 ADD 不覆盖新 DELETE/CHECK_ALL）+ CLEAR 屏障；优惠券单 Lua 库存+限领+扣减、`{templateId}` 同 slot | CartSyncConsumer.java:103；claim_coupon.lua:9 | 可用 |
| Feed：大V阈值 10 万、粉丝>5 万写发件箱；断点续推 cursor Hash（TTL 1h）；一次 Pipeline 同时写收件箱与 FOLLOWING 召回源 | FeedPushConsumer.java:53-60,130,194 | ⭐"修复召回恒空"是加分细节 |
| IM/SSE：150 虚拟节点一致性哈希 + 环指纹复用；路由 TTL=心跳×3；SSE 心跳 10s/30s、ticket 30s 一次性；`remove(key,value)` 防误删新连接 | ImConsistentHashLoadBalancer.java:43,126；SseEmitterManager.java:85 | 可用 |
| 网关 8 过滤器：BodyCache(1MB/413) → 日志(traceId+sw8) → 鉴权(黑名单 fail-closed) → 染色(6 头/AB/压测标记仅内网) → HMAC(±5min/nonce 300s/bodyHash) → 限流(分路由 5~500) → 灰度(10%) → 版本 | 平台深挖 §2；application.yml:100-251 | ⭐可画一张图讲 |
| 指标治理：预注册空标签防"业务系列被吞/恒 0"；URI 归一化（≥10 位 ID→{id}）防时间序列爆炸；DLQ Gauge 零 I/O 抓取 | BusinessMetrics.java:115-139；ApiMetricsFilter.java:12 | ⭐观测类问题很好用 |
| 安全：HMAC per-session 密钥 fail-closed；X-User-Id set 覆盖防伪造；压测标记仅 10.x 客户端可带（P0-7） | HmacSignatureFilter.java:174-209；TrafficColoringFilter.java:82 | 可用 |

### 新增（2026-09-18）· 按主题
**A. 配置中心与调度**
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ 配置中心真实接入：shared-configs 在 SCA 2023 失效（死配置）→ `spring.config.import: optional:nacos:` + 14 服务补 config 命名空间 + 网关补依赖；15/15 Load config success | nacos-config-externalization-20260918.md；release-cart 日志 | ⭐"写了没生效"强故事；optional 保启动韧性 |
| ⭐ XXL-Job 治理：`0/60` 秒增量越界 → 调度器自动禁用退款任务（从未运行）→ 修复；3 每日任务 DO_NOTHING→FIRE_ONCE_NOW；20 任务/10 执行器组 | xxl-job-schedule-audit-20260918.md | ⭐排查链完整；日报 633=调度失败+未上报+真失败 2 |
| 基建治理：RocketMQ topic 全量初始化（autoCreateTopicEnable=false 事故）；Nacos gRPC 19848 噪音定性；27 容器零漂移核验 | init-rocketmq-topics.sh；nacos-grpc-noise-20260917.md | 可用 |

**B. APM 与可观测**
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ SkyWalking 接入：修复"组件在跑但没数据"；agent 9.7 + 幂等安装脚本 + SB3 插件适配 + release/restart 自动挂载 + ignore_suffix 降噪 + 采样可调 | skywalking-agent-enable-20260918.md；install-skywalking-agent.sh | ⭐gateway WebFlux 噪音为已知边界 |
| ⭐ 告警体系：31→40 规则/9 组；通知黑洞修复（noop→alert-sink 落盘）；keep_firing_for；Watchdog；SLO 43m12s + Burn Ledger | alerting-e2e-20260917.md | ⭐通知黑洞/短命告警是经典 |
| ELK 链路：Filebeat filestream+ndjson → Logstash grok → ES 日索引；Logback JSON 上限（100MB/7天/2GB）；yellow 修复+巡检 cron；ILM 双口径 | filebeat.yml；logstash.conf；es-log-index-check.sh；log-cleanup.sh | 保留双口径主动讲 |
| ⭐ APM 开销实测：product 同法两轮对比（agent ON/OFF），吞吐开销 **≈5.4%**（7,439 vs 7,863 RPS）、P50 +0.41ms；明确"基线无 agent"口径 | skywalking-overhead-20260918.md | ⭐性能数字可复现的前提 |
| Grafana：看板 datasource provisioning 即代码 | config/grafana/provisioning | 可用 |

**C. 通信与负载均衡**
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ Feign：24 客户端 HC5（200/50）+ 分级超时（核心 500ms/2s）+ Decoder/ErrorDecoder 统一 + 内部令牌 fail-closed + 52 处 fallback | FeignUnifiedConfig.java；FeignInternalCallInterceptor.java | ⭐"超时怎么定/为什么不重试" |
| ⭐ 负载均衡：最小连接（活跃/权重；需埋点+平局随机）+ 同 zone 优先 + 优雅停机（摘注册+10s 传播等待） | LeastConnectionsLoadBalancer.java；GracefulShutdownListener.java | 未埋点退化轮询是坑 |
| Dubbo 决策：双协议试点 A/B +4.9% RPS / P50 -19% → 决策不引入并回滚（数据留档） | docs/design/rpc-upgrade.md | ⭐"评估后不做"加分 |

**D. 认证与安全**
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| 用户认证：JWT 双 token（access 30min/refresh 7d）+ 验证码（Redis）+ 刷新拉黑旧 token + 登出失效；用户地址管理（默认地址切换+锁内校验） | TokenService.java；AuthController.java；UserAddressService.java | 可用 |
| SqlGuard v2：200ms 告警+5 次熔断+白名单/豁免/冷却+3 指标+6 单测；Sentinel 规则 Nacos 化 + 30s 真空期兜底 | sqlguard-sentinel-hardening-20260917.md | 默认关闭要说明 |
| 安全边界：直连服务鉴权 fail-closed、管理/内部令牌空即拒绝、HMAC 重放防护 | 手册 §Q；TrafficColoringFilter | 与 17 题组合讲 |

**E. 数据一致性与多活**
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ Zone 数据面：动态 ZoneContext 源统一；12 服务 ignore-routing；9 分支路由指标；RPO/冲突策略评估；D4 演练 1.31s/3.25s/4.79s | d4-zone-drills-20260918.md；ZoneRouteMetrics.java | ⭐数字背全 |
| MySQL 故障转移：停主 10.2s/提升 0.087s/切换 22s/RTO≈32s + 5 短板 | mysql-failover-drill-20260917.md | ⭐"不含发现时间" |
| ⭐ 同区优先收益实测（netem 跨区模拟）：吞吐 **+34%**、P99 **-41%**、平均延迟 **-49%**（网关 7,077 vs 5,278 RPS）；失效时 P99 743ms~1.03s 且静默 | zone-same-zone-benefit-20260918.md | ⭐对标"降低 10-30%"的实测证据 |
| ⭐ 同区优先收益实测（netem 跨区模拟 25ms/向）：网关优先 ON vs OFF——吞吐 **+34%**（7,077 vs 5,278）、P99 **-33~47%**、均值延迟 -44~54%；暴露"网关 zone 取值源不统一（invalid_zone）"问题 | zone-cross-region-latency-20260918.md | ⭐对标 10-30% 的实测数据 |
| Redis 双主修复："存在优先"防误删 + 3s 重连 + 防回环；dbsize 1873=1873、≤35s 追平 | redis-server-multi-active-20260918.md | 已有 |
| 动态 JDBC/Spring：content +207→+206、cart +40↔+40 不重启 | dynamic-zone-jdbc-spring-20260918.md | 已有 |
| 多活选型对比：与 Microsphere 多活框架逐项对比（路由/数据面/容灾/运维成本），结论"整体不如成熟框架、差异化在落地与实测"留档 | docs/design/multi-active-vs-microsphere.md | ⭐选型视野 |

**F. JVM 与性能**
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ 类加载锁：每请求 Class.forName → 92/99 线程 BLOCKED → 静态桥接 1,074→4,871（4.5x） | a2-jvm-tuning-20260917.md | ⭐必背 |
| JVM/线程池：G1+200ms+Metaspace 256m；聚合内外池隔离；MDC 包装线程池 | 同上；MdcAwareExecutorService.java | 可用 |
| ID 与预热：订单 Snowflake（IP 推导 worker-id）；号段双 Buffer；Bloom 预热 100 万/1% | ShardingSphereDataSourceConfig.java；CacheHelper | 可用 |

**G. 稳定性与发布**
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ 发布假成功：11/15 服务跑旧 jar → "监听 PID==启动 PID" + ensure_port_free + setsid PID 回写 + 多实例最新构建 | release-service.sh；review 记录 | ⭐根治故事 |
| 慢 SQL/连接：僵尸连接 318 条 → 459/500 → 清理 106；窗口函数+复合索引 | comment-sql-tuning-20260917.md | ⭐故事完整 |
| 死信清账：14 条归档（3 毒丸+11 合法）；DLQ 26 组指标恒 0 治理 | dlq-cleanup-*.md | 已有 |

**H. 成本与日志治理**
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ 成本：19 残留 JVM（16.4GB）；内存 45→28Gi；日志 3.7→2.6G；releases 8.1→6.5G；npm 3.6G→591M；apt | cost-log-governance-20260918.md | ⭐数字都可复现 |
| 日志清理 cron（文件 3 天/ES 7 天）+ 版本保留 3 | log-cleanup.sh；crontab | 已有 |
| 备份体系：MySQL 每日全量+binlog 30 天（mysql-backup.sh）、Redis 每 6 小时 BGSAVE（redis-backup.sh）、ES 每日快照（es-backup.sh）；开机自愈 systemd（boot-selfheal：磁盘扩容+依赖等待+15 服务拉起+重试补偿） | deploy/scripts/*.sh；my-xhs-selfheal.service | 备份+自愈是一组讲 |

**I. 工程与知识治理**
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| 仓库卫生：pids 取消跟踪+gitignore；SW_AGENT_* 环境变量化 | .gitignore；release-service.sh | 小点不占位 |
| 事实校准：Lua 6→7、Sentinel 15→16、单测 53→97、topic 18→17、锁切面纠偏 | 各题文件 | ⭐"简历对得上代码" |
| 知识沉淀：76 题库/14 组件拷打/12 链/逐链自测/防御手册 10 事故/4 报告 | docs/interview/*；docs/reports/* | 面试准备资产 |

**J. 公共组件与数据面扩展**
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| 号段 ID 生成器：DB 段号 + 双 Buffer 预加载；批量写入执行器（CPU×2/JDBC 5000/ID 分片） | SegmentIdGenerator.java；BatchInsertExecutor.java | 与 Snowflake 区分场景 |
| 多版本 API：@ApiVersion 替换 HandlerMapping；未知版本降级不拒绝（v2/v9/无版本均 200） | ApiVersionFilter.java；定制 HandlerMapping | 默认 v1，降级要说明 |
| ⭐ 多 ORM 隔离并存（实测）：MyBatis + MyBatis-Plus + **Spring Data JPA** 同一应用/同一数据源共存——独立 EMF 与事务管理器、逻辑删除语义差异实测（36 vs 33 行） | multi-orm-coexistence-20260918.md；ContentJpaConfig.java | ⭐对标"JDBC 框架隔离并存" |
| 审计独立事务：REQUIRES_NEW 模板（不被主事务回滚拖累）；HTTP ETag/304 | AuditTransactionTemplate.java；ETag 过滤器 | 两个小点可合并 |
| 读写分离：ReadWriteRoutingInterceptor（SLAVE 只读路由/事务中不切）；12 服务 ignore-routing 不阻塞发布 | ReadWriteRoutingDataSource；application.yml | ⭐与 21 题互相印证 |
| 造数框架：seed-data.py（规模倍数造数）；压测脚本 test-07~14 全链路 | scripts/seed-data.py；scripts/test-*.py | 数据准备能力 |
| ShardingSphere 绑定表：5 张逻辑表同 user_id 分片，JOIN 无笛卡尔积；Snowflake worker-id 三级优先级（env→-D→IP 哈希） | sharding-config.yaml；ShardingSphereDataSourceConfig.java:92-112 | 与 29/49 题一致 |
| 压测隔离：影子表 ShadowTableInterceptor（X-Pressure-Test + 开关，表名+_shadow）；混沌演练脚本（Redis/MQ pause、CPU 满载、磁盘 burn、MySQL pause、优雅停机） | ShadowTableInterceptor.java；chaos-drill.sh | ⭐"压测中勿发布"来源 |
| 端口与连接治理：TIME_WAIT/临时端口排查口径（连接复用/keep-alive/池化），Grafana node 面板指标 | 平台深挖；node-exporter 面板 | 无事故，讲原理+口径 |
| 部署脚本：setup-firewall.sh（安全组/端口）、setup-ip.sh（部署包 IP 置换）、restart-all-skywalking.sh、开机自愈 boot-selfheal.sh | deploy/docker/my-xhs-deploy-zip/*.sh；deploy/scripts/boot-selfheal.sh | 可用 |

## 项目一 · 电商平台 — 关键结果备选
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ ES 容器 CPU 2→6 核 + 客户端 IO 4→16 后 search **508→1,448 RPS（2.9x）、P99 191→61ms** | batch-release-baseline-20260917.md:19,29 | 很强，建议写（旧 175→683 口径已作废） |
| 压测基线（**新口径**）：product 4,871 / comment 4,418 / home 1,861 / search 1,448 RPS（类加载锁修复后，无本地缓存） | batch-release-baseline-20260917.md:13-19 | 建议替换旧数字 |
| 限流校准：content/product 500、home/recommend 300、search 300，写路由 10/5/30 不放松 | application.yml:100-251 | 已有；可补"写路由更严" |
| chaos 演练 7 场景（Redis/MQ pause、CPU 满载、磁盘 burn、MySQL pause、优雅停机）全部通过 | chaos-drill.sh | 可补进"稳定性" |
| 慢下游实录：UserService DELAY 11s → 网关 504；listener 75s → 事务回查恰好一次 | 99-runtime…:126,140,292 | 50 并发预扣那类 |
| 告警体系：**40 条规则/9 组**（含 P0：ServiceDown/PaymentHighErrorRate/MysqlDown）+ Grafana 看板（provisioning 即代码） | alerting-e2e-20260917.md；alert_rules/myxhs_rules.yml | 可写"40 条/9 组 + 分级 + SLO"（旧 28 条口径作废） |
| 缓存治理：布隆+空值双层防穿透；逻辑过期防击穿；TTL 随机防雪崩 —— 商品/用户/券全链路接入 | 平台深挖 §8 | 可写结果"缓存类故障 0 起"（需自证） |

### 新增（2026-09-18）· 按主题
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ 配置中心：15/15 真实加载（修复前为死配置）；optional 保 Nacos 故障不阻塞启动 | nacos-config-externalization | 可用 |
| ⭐ 定时任务：20/20 可调度（1 从未运行+3 丢窗口修复）；日报 633 条=真实业务失败 2 条 | xxl-job-schedule-audit | 可用 |
| ⭐ APM：15/15 注册 + gateway→home 53 span（CROSS_PROCESS/CROSS_THREAD）；ignore_suffix 对 SpringMVC 生效 | skywalking-agent-enable | 边界：gateway 噪音 |
| ⭐ 告警：40 规则/9 组 + 通知黑洞修复 e2e + SLO 43m12s/30 天 | alerting-e2e | 可用 |
| ⭐ CI/CD：Gitea Actions + act_runner 门禁实测拦截 RED + 版本化发布/自动回滚 + .m2 缓存 | cicd-gitea-20260917.md；ci-gate-red | 可用 |
| ⭐ JVM：1,074→4,871（4.5x，92/99 BLOCKED 归零）+ 线程池隔离/MDC 跨池 | a2-jvm-tuning | ⭐必背 |
| MySQL 演练：RTO≈32s 分解 + RPO=0（本次）+ 5 短板 | mysql-failover-drill | 可用 |
| 成本：内存 45→28Gi / releases 8.1→6.5G / npm 3.6G→591M | cost-log-governance | 必背 |
| Feign/LB：HC5+分级超时+52 降级+最小连接+优雅停机 | 代码 | 可用 |
| 认证：双 token 刷新/拉黑/登出闭环 + 验证码防刷 | TokenService | 可用 |
| Dubbo：A/B +4.9% RPS 后决策不引入并回滚 | rpc-upgrade | ⭐决策力 |
| Zone：自动发现/传播 10/10；动态热切 +207→+206 / +40↔+40 | zone 系列报告 | 已有 |

## 项目二 · xhs-ai — 职责备选
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ 提示词工程：13 条硬约束（同一工具≤1 次、参数报错禁重调、总工具调用≤4、系统本体必须 knowledge_search、锚点事实必须两关键词+读卡） | AgentService.java:71-106 | ⭐体现"约束模型" |
| ⭐ 工具参数 clamp 全表：日志 minutes 1..1440/size 1..100；指标 5..1440；ES 索引白名单前缀；PromQL ≤600 字+指标白名单；label 正则 | LogQueryBuilder.java:14-31；MetricQueryBuilder.java:116-154 | 展示"防注入/防误用" |
| 模型网关：仅传输类错误重试（连接/超时/流中断），**已出流不降级重放**（PartialStreamException）；半开探测 1 个请求 | ModelGateway.java:151,172-190 | ⭐高级细节 |
| 审计链：`SHA256(prev|traceId|actor|action|target|canonical(params)|result)` + 链头行锁；篡改可检出 | AuditChain.java；production-gaps 报告 | 已有 |
| 审批：同会话同指纹 pending 复用刷新时间；always 授权 revoke 旧的 grant 再插 pattern；reject 级联拒绝同会话 | ApprovalService.java:44-59,139-153 | 可用 |
| 预算三段 + 并发护栏 + 幂等状态机 + 摘要参数（50 万/80%、2/8、600s、20 条/12000 字） | TokenBudgetService 等 | 已有 |
| 去 MCP 化架构决策 + 工具 schema token 预算（3428/12000） | 素材 §4 | 已有 |
| 评测：三层 + 部分分 + 稳定性；评测隔离（专用用户+额度清零） | trajectory-eval 报告 | 已有 |

### 新增（2026-09-18）· 按主题
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ 需求与架构：34 场景（DIAG/KB/OPS/PLAT）+ 量化 NFR/SLO + STRIDE + REQ→AC→TC 门禁 + 23 ADR + Flyway 只管网表 | requirements/*.md；02-architecture.md:107-133 | ⭐强 |
| ⭐ 测试与工程：六层设计（落地 UT61+**CT4（LLM fixture 契约：200/429/500/连接中断重试）**/EVAL94/RED8/PERF4；IT/E2E 为 TC 清单）+ Enforcer/CVE/依赖冲突 4 项与豁免退出条件 | 03-test-design.md；04-engineering.md；ModelContractTest.java | 设计 vs 落地分开讲 |
| 部署运维：systemd（Restart=always/RestartSec=10/Stop 45s）+ readiness=MySQL/Redis + 日志 100MB/7天/2GB + 指标 loopback + 10 运维脚本 | xhs-ai.service；prometheus.yml:91 | 可用 |
| ⭐ AgentScope 框架：BYPASS 补偿（白名单+HITL+审计）；热替换缺失→去 MCP；RuntimeContext 多租户坑；源码级验证 6 项 | AgentService.java:233；research/02 | ⭐强 |
| 知识卡工程：55 卡（11/7/36/1）+ BM25 + 引用硬校验 + 验证问题集 + 启停重索引 | KnowledgeIndexer.java；ES 聚合 | 可用 |
| ⭐ 扩展框架两代设计：v1 Java SPI（声明式零信任）+ v2 出进程 Sidecar（借鉴协议）；禁止 in-process full-trust | ADR-21；design/08-extension-framework | ⭐设计感强 |
| 技能仓库：GitSkillRepository（技能变更走 PR 治理）+ 四层合成（全局<Marketplace<workspace<user）；沙箱 v1 不启用（无不可信代码执行） | ADR-7/6；design/12 | 治理视角 |
| 受控只读 SQL：AST 校验 + READ ONLY 事务 + 只读账号 + 行数/超时限制 + 脱敏审计（替代"禁止 SQL"） | ADR-23；deploy/scripts/ai-readonly-grant.sql | ⭐安全设计 |
| 工程规范 E1-E7：依赖精确版本/CVE 扫描/出网收敛两域名/审计只追加/灰度回滚 | 04-engineering.md | 与测试体系互补 |
| 缺口登记（诚实边界）：OTel/Langfuse 0 命中、Prompt 版本化缺失、DLP 仅密钥、多租户未做、Bulkhead 设计未落地 | 18-生产化缺口.md；observability-truth | ⭐主动披露加分 |

## 项目二 · xhs-ai — 关键结果备选
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ 17 个 ai_* 指标全覆盖（工具失败率/运行时长/预算决策/MCP 健康/保留清理量） | 素材 §5 | 可写成"可观测：17 个自定义指标 + 7 告警" |
| ⭐ 评测隔离与门禁：门禁一键 = 61 单测 + 审计一致性 + 哈希链（+可选 LLM 评测）；CI 工作流 | gate.sh；.github/workflows | 已有 |
| 成本实测三态：软切、硬限 429、计量 5577 tokens | live-drill §4.4 | 已有 |
| 崩溃恢复：批准后 60s 补执行 + executing 600s 回收 | production-gaps | 已有 |
| 去 MCP 化 kill 演练 19s/39s | live-drill §4.6 | 已有 |
| 会话摘要演练：47 条→27 条摘要，删状态仍答 ZEBRA-42/87 | production-gaps | 已有 |
| 诚实边界（可写进"边界"）：跨实例事件只发不收（除指标）、capture_mode/ai_feedback 未接线、DLQ 尾部扫描 200 条上限、/chat 不计预算 | 素材 §6 | ⚠️ 主动写边界反而加分 |

### 新增（2026-09-18）· 按主题
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ 100 条 nightly：100% 通过率 / 96% 完成率 / 0% 幻觉率（smoke 20+regression 80） | nightly-100-report | ⭐强 |
| 在线 QA：chat 3.2s / Agent 145.6s；日志结论经 ES 复核属实（6319/8305 条） | ai-live-qa | 可用 |
| 需求追溯：34 场景 100% REQ→AC→TC；门禁通过率≥90% + 引用有效性 100% | 03-test-design | 可用 |
| 运维脚本资产：audit-gate/audit-verify（审计链）、cost-week（周成本）、tool-eval（工具评测）、order-flow（全链路演练）、traffic-gen（造流量）、load-test、red-team | xhs-ai/scripts/ | 10 个脚本=可执行 runbook |

## 备选"一句话加分细节"（面试自然带出）
- 伪订单号 fold-hash 溢出碰撞 → SHA-256 统一（库存永久泄漏修复）
- ZSet 过期索引替代全库 SCAN；MySQL 幂等占位（master 无延迟）
- 一次 Pipeline 双写修复 FOLLOWING 召回恒空
- 指标预注册空标签防"系列被吞"；URI 归一化防时间序列爆炸
- Sentinel×WebFlux BlockHandler 不兼容致限流悬挂 30s（T-106）
- 网关 max-life-time 必须 < 下游 keep-alive（T-048 PrematureClose）
- 提示词硬约束：工具≤4 次、参数报错禁重调
- 模型网关"已出流不降级重放"（防重复输出）
- 审计规范化 Java/Python 双端等价（TreeMap vs sort_keys）
- 工具 schema tokens=JSON 字符/4（实测 3428/12000）
- 配置中心"写了没生效"：SCA 2023 下 shared-configs 死配置 → optional:nacos
- XXL-Job"非法 cron 静默禁用"：0/60 秒增量越界（CronExpression.checkIncrementRange）
- SkyWalking"组件在跑但没数据"：release 未挂 -javaagent → 自动挂载修复
- 告警"通知黑洞"：receiver 为 noop 静默丢弃 → alert-sink + e2e 演练
- 发布"假成功"：11/15 跑旧 jar，健康检查过 → 监听 PID 指纹校验
- gateway WebFlux 不吃 ignore_suffix（agent 9.7 边界）
- Redis 双主对账"存在优先"防误删；断线 3s 重连
- AgentScope 工具"注册≠可用"：权限 ASK 卡 asking（空答复）
- 模型网关"已出流不降级重放"（防重复输出）
