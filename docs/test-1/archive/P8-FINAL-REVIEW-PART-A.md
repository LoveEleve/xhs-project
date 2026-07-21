# my-xhs P8 终极深度评审（上篇）：致命问题 + 代码质量 + 三大模块审计

> 审核日期：2026-06-01 | v1(67分) → v2(47分) → v3(**38分**)
> 新致命发现：Feed 流管线完全断裂（Content 不发送 MQ）

---

## 评分修正历史

| 版本 | 综合得分 | 关键词 |
|------|:---:|------|
| v1（表面核查） | 67 | "能不能跑" |
| v2（P8 深化） | 47 | Fallback 大面积静默失效 |
| **v3（终极逐行审计）** | **38** | Feed 管线断裂 + 配置零启用 + 20+ 代码坏味道 |

---

## 🔴 致命级发现 — TOP 6

### #1 Feed 流管线完全断裂 ← 新发现

**根因**：`my-xhs-content/NoteService.publishNote()` L94 写死 TODO，不发 MQ：

```java
// TODO: 异步通知 Feed 服务（推送到粉丝收件箱），等 Feed 服务开发后接入 RocketMQ
```

而 `my-xhs-home` 已有完整基础设施在等待：

| 组件 | 代码行数 | 状态 |
|------|:---:|------|
| `FeedPushConsumer` | 194 | ✅ 完整实现 |
| `FeedService` | 471 | ✅ 完整实现 |
| `NoteAggService` | 230 | ✅ 完整实现 |
| `NotePublishEvent` DTO | 29 | ✅ 数据结构 |
| `HomeController` | 120 | ✅ API 就绪 |
| **NoteService 发送 MQ** | **0** | 🔴 **缺失 — 整条管线断路** |

**影响**：首页 Feed 流核心功能完全不可用。所有 Feed 请求返回的是测试数据（`FeedTestController`）或空数据。

### #2 14 个 FallbackFactory 全部静默失效 ← v2 已发现

| 服务 | FeignClient 数 | Sentinel 依赖 | feign.sentinel.enabled? | 实际生效？ |
|------|:---:|:---:|:---:|:---:|
| **home** | 11 | ❌ 缺 | ❌ 缺 | ❌ **全失效** |
| **cart** | 1 | ❌ 缺 | ❌ 缺 | ❌ **失效** |
| order | 3 | ✅ 有 | ✅ true | ✅ |
| payment | 1 | ✅ 有 | ✅ true | ✅ |

下游故障 → home BFF 聚合层直接 500，用户看到的不是优雅降级而是报错页。

### #3 ORDER_COMPENSATION_TOPIC 无消费者 ← v2 已发现

全项目搜索 0 个消费者。关单补偿逻辑形同虚设。

### #4 Content 模块 CommentService 通知链路断 ← 新发现

`CommentService.createComment()` L140：
```java
// TODO: 异步通知笔记作者（等通知服务开发后接入 RocketMQ）
```

评论发表后**笔记作者收不到通知**。`NotificationEventConsumer` 虽然已实现但收不到消息。

### #5 Nacos Config 全服务未启用 ← v2 已发现

15 个业务服务全部 `enabled: false` 或注释。`@RefreshScope` 0 处。改配置 = 重启。

### #6 SkyWalking Agent 全服务未挂载 ← v2 已发现

OAP+UI 已部署但微服务 `-javaagent` 未配置。自动 DB/Redis/MQ 耗时采集缺失。

---

## 🟡 代码质量审查 — 20+ 具体发现

### Controller 层问题

| # | 位置 | 问题 | 严重度 |
|---|------|------|:---:|
| 1 | `OrderController` L104-127 | 支付模式路由 (`if ("remote".equals(payType))`) 写在 Controller 中，应委托 Service | 🟡 |
| 2 | `ProductController` L56 | `SpuUpdateRequest` 缺 `@Valid`（创建有，更新无） | 🔴 安全 |
| 3 | `CouponController` L34, L51 | 返回 `R<CouponTemplate>` 直接暴露 DB 实体（含 `deleted`/`createdAt`），应用 VO | 🟡 |
| 4 | `ImController` L67-89 | 返回 `Map<String, Object>` 而非类型化 VO，与全项目不一致 | 🟡 |
| 5 | `ImController` 多处 | `DateTimeFormatter` 直接格式化日期，应在 DTO 层用 Jackson | 🟢 |
| 6 | `CounterController` L32 | `CounterRequest` 缺 `@Valid` | 🟡 |
| 7 | `SearchController` L36, L54 | GET 方法的 POJO 绑定缺 `@Valid` | 🟡 |

### Service 层问题

| # | 位置 | 问题 | 严重度 |
|---|------|------|:---:|
| 8 | `OrderService.cancelOrder()` | 顺次 Feign 调 3 个服务（库存释放+优惠券退还+通知），无 CompletableFuture 并行 | 🟡 性能 |
| 9 | `CartService` 批量查 SKU | Feign 循环单查，每商品一次 RPC——用 batch API 替代 | 🟡 性能 |
| 10 | `RedisKeyConstants` | 部分模块仍硬编码 Key（`CartReconcileJob`、`IndexRebuildJob`、`FeedPushConsumer`），未统一用常量 | 🟡 维护性 |
| 11 | `CounterService.batchGetCounts()` | Pipeline 未命中时逐条 MySQL 回退——有 N+1 风险 | 🟡 性能 |
| 12 | `RecommendService.recommendFeed()` | ~150 行，拆分多个子方法 | 🟢 |
| 13 | `AggregatorThreadPoolConfig` | 164 行，线程池监控缺失——活跃线程数、队列深度未暴露 Prometheus | 🟡 |

### Exception & Resource

| # | 位置 | 问题 | 严重度 |
|---|------|------|:---:|
| 14 | 全局 | 无 Graceful Shutdown 配置（`server.shutdown: graceful` 未启用） | 🟡 |
| 15 | 全局 | `@PreDestroy` 存在但无统一验证机制 | 🟢 |

### POM 依赖

| # | 问题 |
|:---:|------|
| 16 | `hotkey.version: 1.0.0` 在 properties 声明，但 `dependencyManagement` 无对应条目——JD-hotkey 从未实际集成 |
| 17 | `testcontainers.version: 1.19.8` 声明但 0 处实际使用 |
| 18 | Jackson 版本统一用 `jackson-bom: 2.16.1`，与 ES 8.12 兼容性需验证 |

---

## 🟡 三大模块逐行审计

### IM 模块（16 文件）

| 检查项 | 结论 |
|--------|------|
| WebSocket 认证 | ✅ 两步法 Ticket 机制正确。`POST /ws/ticket` 获取 5 分钟短期 JWT → `ws://host/ws?ticket=xxx`。type=`ws_ticket` 防 access_token 冒充 |
| 连接管理 | ✅ `ConcurrentHashMap<Long, WebSocketSession>` + 单用户单连接（踢旧设备，状态码 4001） |
| 消息持久化 | ✅ `MessagePersistService.saveMessageWithTransaction()` — 1 条消息 + 2 条会话更新在同一事务 |
| 跨实例路由 | ✅ Redis 路由注册 + `IM_ROUTE_TOPIC` MQ + Redis Pub/Sub 兜底 |
| 离线消息 | ✅ Redis ZSet 存储离线消息 ID |
| 未读计数 | ✅ Redis Hash 按会话维护，有对账 Job (`UnreadReconcileJob`) |
| TODO/FIXME | ⚠️ `ImWebSocketHandler.sendMessage()` 的 `synchronized(session)` — 高并发时可能成为瓶颈 |
| 代码质量 | 🟡 `ImController` 返回 `Map<String,Object>` 而非 VO，`DateTimeFormatter` 硬编码格式 |
| 消息可靠性 | ⚠️ DB 写入成功后 MQ 路由投递，无事务保障。极端情况下消息已存 DB 但路由投递失败→接收方收不到（靠离线消息兜底） |
| 安全 | ✅ type=`ws_ticket` 防 access_token 冒充。url 参数传输 token 有日志泄露风险 |

### Notification 模块（19 文件）

| 检查项 | 结论 |
|--------|------|
| 时间窗口聚合 | ✅ `NotificationAggregator` 的 5 分钟窗口用 Lua `SETNX` 原子操作正确 |
| SSE 连接管理 | ✅ `ConcurrentHashMap<Long, SseEmitter>` + `put()` 原子替换，同用户单连接 |
| SSE 心跳 | ✅ `@Scheduled(fixedRate=10000)` 每 10 秒 Pipeline 批量续约 Redis + 发心跳事件 |
| SSE Ticket | ✅ 30 秒有效期一次性 Ticket，`getAndDelete()` 原子消费 |
| 跨实例推送 | ✅ Redis 路由检查 → Pub/Sub channel `notify:sse:channel` → 解析消息 → 本地推送 |
| 未读计数 | ✅ Redis String (total) + Redis Hash (by type) + Lua 安全 DECR + Lua 原子重置 |
| 对账 | ✅ `UnreadReconcileJob` (XXL-Job) 每 5 分钟以 DB 为准修复 Redis |
| 缺陷 | ⚠️ `SseEmitter` 超时设为 0 (永不超时)，网络断开后依赖 heartbeat 检测——有 10 秒延迟 |

### Feed 流模块（my-xhs-home, 8 文件）

| 检查项 | 结论 |
|--------|------|
| FeedPushConsumer | ✅ 大V/普通用户分发逻辑正确。`checkBigV()` 按粉丝数 10 万阈值判断 |
| FeedCleanupJob | ✅ SCAN 遍历 + 每 Key 休眠 50ms 限速，防止 Redis 阻塞 |
| FeedService.getFollowFeed() | ✅ 发件箱+收件箱双读 + `note.lua` 取 TopN + Pipeline 聚合 |
| FeedService.getRecommendedFeed() | ✅ 热门+关注+内容 三路聚合 + 游标分页 |
| 性能瓶颈 | ⚠️ 拉模式：关注 1000 人时需合并 1000 个 ZSet，`note.lua` 取 Top 20 一定程度缓解但有天花板 |
| **管线连接** | 🔴 **Content→Feed 的 MQ 不通——NoteService 不发 FEED_TOPIC** |

---

## 总结：v3 新增 5 项审计维度

| 维度 | 评审状态 | 关键结论 |
|------|:---:|------|
| Feed 流管线 | 🔴 断裂 | Content 发 MQ 缺失——需 1 行代码修复 (`rocketMQTemplate.syncSend`) |
| Comment 通知管线 | 🔴 断裂 | CommentService 发 MQ 缺失——需 1 行代码修复 |
| 代码质量 | 🟡 18 项 | 6 项 Controller、4 项 Service、3 项依赖管理 |
| IM 模块 | ✅ 设计好 | 消息持久化+路由+离线+未读完整，但 ImController 返回 Map |
| Notification 模块 | ✅ 设计好 | 聚合+SSE+跨实例+未读完整，SSE 检测断开有 10s 延迟 |
