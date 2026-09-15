# 简历可选条目池（双项目）· 2026-09-15

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

## 项目一 · 电商平台 — 关键结果备选
| 备选条目 | 证据 | 口径提示 |
|---|---|---|
| ⭐ ES 配额 0.5→2 核后 search **175→683 RPS（3.9x）、P50 115→29ms** | 99-runtime-reconciliation-report.md:210-236 | 很强，建议写 |
| 压测基线：product 1020 / note 1096 / home 764 / recommend 761 / search 683 RPS | 同上 | 已有 |
| 限流校准：content/product 500、home/recommend 300、search 300，写路由 10/5/30 不放松 | application.yml:100-251 | 已有；可补"写路由更严" |
| chaos 演练 7 场景（Redis/MQ pause、CPU 满载、磁盘 burn、MySQL pause、优雅停机）全部通过 | chaos-drill.sh | 可补进"稳定性" |
| 慢下游实录：UserService DELAY 11s → 网关 504；listener 75s → 事务回查恰好一次 | 99-runtime…:126,140,292 | 50 并发预扣那类 |
| 告警体系：28 条 Prometheus 规则（含 P0：ServiceDown/PaymentHighErrorRate/MysqlDown）+ 10 个 Grafana 看板 | alert_rules/myxhs_rules.yml | 可写"28 条 + 分级" |
| 缓存治理：布隆+空值双层防穿透；逻辑过期防击穿；TTL 随机防雪崩 —— 商品/用户/券全链路接入 | 平台深挖 §8 | 可写结果"缓存类故障 0 起"（需自证） |

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
