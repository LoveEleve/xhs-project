# my-xhs P8 终极深度评审（下篇）：中间件配置 + 运维安全 + 修复路线图

> 接上篇 P8-FINAL-REVIEW-PART-A.md

---

## 六、Lua 脚本全量审计（13 个脚本）

| 脚本 | 模块 | 用途 | 原子性 | 问题 |
|------|------|------|:---:|------|
| `cart_add.lua` | cart | HEXISTS+HLEN+HINCRBY+SADD+ZADD | ✅ | 无问题 |
| `cart_remove.lua` | cart | HDEL+SREM+ZREM | ✅ | 无问题 |
| `cart_check_all.lua` | cart | HKEYS+DEL+SADD | ✅ | 无问题 |
| `claim_coupon.lua` | coupon | 库存检查+限领检查+DECR+INCR | ✅ | 无问题 |
| `return_coupon.lua` | coupon | INCR+DECR | ✅ | 无问题 |
| `follow.lua` | analytics | ZADD follower+ZADD following | ✅ | 无问题 |
| `unfollow.lua` | analytics | ZREM follower+ZREM following | ✅ | 无问题 |
| `like.lua` | analytics | SADD+SADD+计数操作 | ✅ | 无问题 |
| `unlike.lua` | analytics | SREM+SREM+计数操作 | ✅ | 无问题 |
| `note.lua` | home | ZRANGEBYSCORE 取 Feed TopN | ✅ | ⚠️ 关注 1000 人时需 N 次调用 |
| `hot_search_record.lua` | search | 屏蔽/限频检查+ZADD | ✅ | 无问题 |
| `decrement_safe.lua` | notification | GET+DECR 归零保护 | ✅ | 无问题 |
| `reset_unread.lua` | notification | HGET+DECRBY+HDEL 原子清零 | ✅ | 无问题 |

**结论**：所有 Lua 脚本**原子性正确，无竞态条件**。唯一性能关注点是 `note.lua` 在大关注量下的调用次数。

---

## 七、CounterBuffer 深度审计

**架构**：双 `ConcurrentHashMap` 交换 + `ReentrantLock.tryLock()` 防并发刷盘。

| 维度 | 评价 |
|------|------|
| 线程安全 | ✅ `volatile` buffer + `AtomicLong` delta + `ReentrantLock` 保护刷盘 |
| 刷盘触发 | ✅ 容量触发 (100 条) + 定时触发 (5s) 双重保障 |
| 防止死锁 | ✅ `tryLock()` 非阻塞 + 按 (targetType, targetId, countType) 排序防相交行锁 |
| 重试机制 | ✅ 3 次重试 + 指数退避 (100/200/300ms) |
| 优雅停机 | ✅ `@PreDestroy` 阻塞 `lock()` 最后刷出 |
| 监控缺失 | 🟡 无 Prometheus 指标（队列深度、刷盘耗时、失败次数） |
| 微优化 | 🟢 `@Scheduled(fixedRate=5000)` 可改为 `fixedDelay=5000` 更语义准确 |

**总体评价**：设计质量**达到 P8 标准**，仅次于 GM/Qwen 的 CounterBuffer 实现。只需加 Prometheus 指标暴露。

---

## 八、热搜排行榜审计

**算法**：滑动窗口（每分钟一个 Hash 桶）+ 指数衰减 + 反作弊。

| 维度 | 评价 |
|------|------|
| 衰减算法 | ✅ `Score = Σ(count × e^(-λ×Δt))`, λ=0.1，数学严谨 |
| 反作弊 | ✅ Lua 原子检查（屏蔽词+IP限频+用户限频+写入分钟桶） |
| 重算性能 | ✅ Redisson 分布式锁 + Pipeline 批量读 60 个桶 |
| 原子更新 | ✅ 先写临时 Key → RENAME 替换，无空窗期 |
| 人工干预 | ✅ 置顶 Set + 屏蔽 Set |
| 快照持久化 | ✅ MySQL `t_hot_search_snapshot` + 按日期查询 |
| 热度标签 | ✅ 爆(rank≤3+score>80%max)/热(rank≤10)/新(其余) |

**总体评价**：设计**超越华仔**。数学严谨、Lua 原子、运维友好。

---

## 九、Redis Key 命名规范审计

**总况**：`RedisKeyConstants` 定义了 40+ 个规范 Key 常量，但部分代码中仍有硬编码 Key。

| 硬编码 Key 位置 | Key pattern | 应使用常量 |
|------|------|------|
| `FeedPushConsumer.checkBigV()` | `"myxhs:user:bigv:" + authorId` | `RedisKeyConstants.USER_BIGV` |
| `CartReconcileJob` | `"myxhs:cart:items:"`, `"myxhs:cart:checked:"` | `RedisKeyConstants.CART_ITEMS`, `CART_CHECKED` |
| `IndexRebuildJob` | `"myxhs:search:index:rebuild:status"` | 需新增常量 |
| `InventoryCacheEvictConsumer` | `"inventory:bucket:"`, `"inventory:total:"` | 需新增常量 |

**建议**：全项目 `grep` 扫描 `myxhs:` 硬编码，统一迁到 `RedisKeyConstants`。

---

## 十、全量 application.yml 配置审计

### 端口分配（无冲突 ✅）

| 服务 | 端口 |
|------|:---:|
| gateway | 19000 |
| user | 19001 |
| content | 19002 |
| analytics | 19003 |
| counter | 19004 |
| product | 19005 |
| cart | 19006 |
| order | 19007 |
| inventory | 19008 |
| coupon | 19009 |
| payment | 19010 |
| notification | 19011 |
| home | 19012 |
| im | 19013 |
| search | 19014 |
| common | (library) |

### 缺失配置项

| 配置项 | 缺失服务数 | 影响 |
|------|:---:|------|
| `server.shutdown: graceful` | 15 | 无优雅停机，kill 信号直接中断处理中的请求 |
| `management.endpoints.web.exposure.include: health,info,prometheus` | 4 | search/counter/content/analytics 未暴露 actuator 端点 |
| `logging.level.com.myxhs: DEBUG` | 全设为 INFO | 排查问题需改配置 |
| `spring.lifecycle.timeout-per-shutdown-phase: 30s` | 15 | 优雅停机超时未设 |

### 安全风险

| 风险 | 位置 | 修复 |
|------|------|------|
| Actuator 无认证 | 所有暴露 `/actuator/*` 的服务 | 加 `management.endpoint.health.show-details: when-authorized` |
| CORS `*` | gateway `CorsConfiguration` | 生产改为具体域名白名单 |
| 压测标记仅 `10.0.0.0/8` | `TrafficColoringFilter` | 改为精确压测平台 IP 白名单 |

---

## 十一、Canal + Chaos + Gateway 配置审计

### Canal

| 项目 | 状态 |
|------|:---:|
| docker-compose 部署 | ✅ Canal 1.1.7 |
| instance 配置（3 个） | ✅ note-instance / product-instance / order-instance |
| MySQL 源配置 | ✅ 三个 MySQL 实例 Binlog 监听 |
| RocketMQ Topic 映射 | ✅ `NOTE_INDEX_TOPIC` / `PRODUCT_INDEX_TOPIC` / `ORDER_INDEX_TOPIC` |
| Consumer 端消费 | ✅ NoteIndexSyncConsumer / ProductIndexSyncConsumer |
| 版本防乱序 | ✅ `ExternalGte` version type + Canal `es` sequence |
| 数据过滤 | ✅ DELETE 不真删，改 status=-1（防乱序旧 INSERT 复活） |
| 计数字段跳过 | ✅ likeCount/collectCount/commentCount 不覆盖（计数服务维护） |

**评价**：Canal 配置**设计精致**，是项目亮点之一。

### Chaos Engineering

| 场景 | chaos-drill.sh | 说明 |
|------|:---:|------|
| Redis 不可用 | ✅ | iptables DROP Redis 端口 |
| Redis 延迟 | ✅ | tc netem delay |
| MQ 不可用 | ✅ | iptables DROP RocketMQ 端口 |
| CPU 满载 | ✅ | stress 命令 |
| 磁盘 IO | ✅ | dd 写入 |
| MySQL 不可用 | ✅ | iptables DROP |
| 优雅停机验证 | ✅ | kill -15 检查 @PreDestroy |

**评价**：7 个场景覆盖主要故障模式，**超越华仔**。

### Gateway 过滤链

| Order | 过滤器 | 职责 | 降级策略 |
|:---:|------|------|------|
| 100 | RequestLogFilter | TraceId + 日志 | 无需降级 |
| 1000 | GatewayAuthFilter | JWT 鉴权 + Token 黑名单 | Fail-Closed |
| 1200 | TrafficColoringFilter | 6 个染色标记 | 无需降级 |
| 1500 | HmacSignatureFilter | HMAC 签名 + 防重放 | Fail-Open |
| 2500 | RateLimitFilter | Sentinel 服务 QPS | Sentinel 内置熔断 |
| 3000 | GrayRouteFilter | 灰度路由 | 未匹配时全部实例 |
| 3100 | ApiVersionFilter | API 版本路由 | 默认 v1 |

**评价**：**设计优秀，是项目最大亮点**。注意 HMAC Fail-Open 和 Auth Fail-Closed 体现了成熟的安全意识。

---

## 十二、修复优先级路线图

### 本周必做（P0 — ~8 工时）

| # | 任务 | 文件位置 | 估计工时 |
|---|------|------|:---:|
| 1 | **Feed 管线连通**：NoteService 发布笔记后发 `FEED_TOPIC` | `my-xhs-content/NoteService.java` L94 删 TODO | 1h |
| 2 | **Comment 通知连通**：CommentService 发评论后推送通知 | `my-xhs-content/CommentService.java` L140 删 TODO | 1h |
| 3 | **home 服务加 Sentinel**：pom.xml 加依赖 + yml 启 sentinel | `my-xhs-home/pom.xml` + `application.yml` | 1h |
| 4 | **cart 服务加 Sentinel**：同上 | `my-xhs-cart/` | 0.5h |
| 5 | **ORDER_COMPENSATION_TOPIC 消费者**：新建 `OrderCompensationConsumer` | `my-xhs-order/consumer/` | 2h |
| 6 | **ProductController SpuUpdateRequest 加 @Valid** | `my-xhs-product/SpuUpdateRequest.java` | 0.5h |
| 7 | **优雅停机配置**：所有服务加 `server.shutdown: graceful` | 15 个 `application.yml` | 1h |

### 两周内（P1 — ~24 工时）

| # | 任务 | 工时 |
|---|------|:---:|
| 8 | **Nacos Config 全服务启用** | 4h |
| 9 | **SkyWalking Agent 全量挂载** | 2h |
| 10 | **死信队列消费者** + RocketMQ DLQ 配置 | 3h |
| 11 | **Redis Key 硬编码统一迁移** `RedisKeyConstants` | 2h |
| 12 | **CouponController 返回 VO 而非 Entity** | 1h |
| 13 | **ImController 返回类型化 VO** | 2h |
| 14 | **CacheHelper 延迟双删修复**（二次失败发 MQ 兜底） | 1h |
| 15 | **Grafana Dashboard 导入** (JVM + 业务 + 中间件) | 4h |
| 16 | **核心链路单元测试** (Order/Payment/Inventory 各 ≥6 用例) | 5h |

### 月度（P2 — ~32 工时）

| # | 任务 | 工时 |
|---|------|:---:|
| 17 | Sentinel Dashboard 部署 + 熔断规则配置 | 4h |
| 18 | 多实例部署 + Nacos metadata.version 灰度标签 | 4h |
| 19 | ProxySQL 读写分离 + ShardingSphere 读写分离 | 4h |
| 20 | 慢查询治理 (MyBatis SqlGuardInterceptor) | 6h |
| 21 | 缓存预热 + BigKey 检测脚本 | 3h |
| 22 | JD-hotkey 集成 | 4h |
| 23 | Jenkins CI/CD 流水线 + Dockerfile | 7h |

---

## 十三、最终评分明细

| 维度 | v1 | v2 | **v3** | 降幅原因 |
|------|:---:|:---:|:---:|------|
| 架构设计 | 85 | 80 | **80** | — |
| 代码质量 | 78 | 60 | **55** | 18 项具体问题 + Feed/Comment 管线断 |
| 安全性 | 90 | 90 | **90** | — |
| 分布式能力 | 82 | 70 | **60** | 补偿链路断裂 (v2) + Feed 管线断裂 (v3 新) |
| 业务完整性 | 75 | 75 | **55** | Feed 流不可用 (v3 新) |
| 可观测性 | 72 | 40 | **35** | — (v2 已完整评估) |
| 测试覆盖 | 10 | 10 | **5** | v3 确认：Testcontainers 依赖也写了但没用 |
| 工程化成熟度 | 35 | 10 | **5** | 配置中心全服务未启用 (v3 新确认) |
| 华仔覆盖率 | 76 | 76 | **76** | 功能层面不变 |
| **综合** | **67** | **47** | **38** | Feed/Comment 管线断裂是最致命的扣分项 |

---

## 结论

my-xhs 项目在**安全性**、**Lua 原子操作**、**CounterBuffer 设计**、**热搜算法**、**Gateway 过滤链**、**Canal 配置**这六个维度达到了真正的 P8 水平。

但 v3 深挖暴露了三个**结构性问题**：

1. **管线断裂**：Feed 和 Comment 两个关键管线，Producer→MQ→Consumer 中 Producer 端缺失。这是代码审查中最致命的问题——写了完整的 Consumer 但忘了对接 Producer。
2. **配置断层**：Nacos Config 全服务未启用、SkyWalking Agent 全服务未挂载、Sentinel 仅 3 个服务启用。这些不是"还没做"而是"部署好了基础设施但没人接"。
3. **防御失效**：FallbackFactory 大面积静默失效（pom 缺依赖）、优雅停机未配置、死信无处理。看起来有防御但实际上没生效。

**修复策略**：8 工时修复 7 个 P0 问题 → 管线连通 + 防御生效；然后 2 周补齐 P1 工程化基础。P0 问题修复后综合得分可从 38 回升到 **65+**。
