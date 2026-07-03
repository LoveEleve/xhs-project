# my-xhs P8 整改 — 97 项任务全阶段执行 Prompt

> 本文件是可直接喂给 AI agent 的完整执行指令。包含 4 个阶段 97 项任务的精确操作步骤。
> 权威参照：`docs/下一步行动计划-P8整改路线图.md`

---

## 项目上下文

- 项目根目录：`/data/workspace/my-xhs`
- 规模：16 个微服务模块，393 个 Java 源文件
- 当前评分：38/100（P8 深度审计 v3），目标：8 周后 ≥ 76/100
- 核心原则：**先止血 → 再加固 → 后超越**

**关键参考文档**（执行时按需查阅）：
- `docs/P8整改执行手册-详细版.md` — 任务 1~46 的完整代码示例、依赖关系图
- `docs/P8-COMPREHENSIVE-REVIEW.md` — P8 深度审计报告（18 项代码发现）
- `docs/P8-TECH-REVIEW-REPORT.md` — 技术选型评审（11 项 P0 + 22 项 P1）
- `docs/COMPREHENSIVE-REVIEW-REPORT.md` — 功能覆盖率报告
- `docs/P8-LEVEL-ROADMAP.md` — 限流四层设计方案
- `docs/source-index.md` — 16 模块文件索引

---

## 全局执行规则

1. **每改一个文件 → `search_content` 确认改动无遗漏 → 下一个**
2. **先 `read_file` 读懂目标代码上下文，再 `replace_in_file`，绝不盲改**
3. **同模块同类型的批量配置改动合并处理（如 11 个模块的 yml 追加），不要逐文件逐个回合**
4. **每完成一个任务输出**：`✅ 任务 N 完成 — [涉及文件] — [一句话摘要]`
5. **遇阻塞立即记录原因并跳过，不卡住**
6. **每阶段全部完成后，汇总输出该阶段的验收对照清单**

---

# 阶段一：止血（第 1~2 周，任务 1~18）

> 目标：P0 清零，核心链路可运行。预估 ~40h

## 管线连通组（第 1 天 — 独立并行）

### 任务 1 🔴P0 — Feed 流管线连通
- **问题**：`NoteService.publishNote()` 末尾 TODO 占位不发 MQ，Feed 流完全不可用
- **操作**：
  1. `read_file` 读取 `my-xhs-content/src/main/java/com/myxhs/content/service/NoteService.java`
  2. `search_content` 搜索 `TODO.*Feed` 定位 L94 附近的占位行
  3. `search_content` 搜索 `NotePublishEvent` 确认 DTO 类是否存在
  4. 若类中无 `RocketMQTemplate`，注入：`@Autowired private RocketMQTemplate rocketMQTemplate;`
  5. 在 `publishNote()` 方法 return 之前插入：
```java
NotePublishEvent event = new NotePublishEvent();
event.setNoteId(savedNote.getId());
event.setUserId(savedNote.getUserId());
event.setPublishTime(System.currentTimeMillis());
event.setNoteType(savedNote.getNoteType().name());
rocketMQTemplate.syncSend("FEED_TOPIC", event, 3000);
log.info("[NoteService] 笔记发布已推送Feed, noteId={}", savedNote.getId());
```
  6. 下游 `FeedPushConsumer`(my-xhs-home) 已完整实现，无需修改

### 任务 2 🔴P0 — Comment 通知管线连通
- **问题**：`CommentService.createComment()` L140 有 TODO，评论后笔记作者收不到通知
- **操作**：
  1. 读取 `my-xhs-content/.../service/CommentService.java`
  2. 搜索 `TODO.*通知` 定位占位
  3. 注入 `RocketMQTemplate`，搜索或新建 `CommentNotificationEvent` DTO
  4. return 前发送：
```java
CommentNotificationEvent event = new CommentNotificationEvent();
event.setCommentId(savedComment.getId());
event.setNoteId(noteId);
event.setCommentUserId(comment.getUserId());
event.setNoteAuthorUserId(note.getUserId());
event.setContent(savedComment.getContent().substring(0, Math.min(50, savedComment.getContent().length())));
event.setTimestamp(System.currentTimeMillis());
rocketMQTemplate.syncSend("NOTIFICATION_TOPIC", event, 3000);
```

### 任务 3 🔴P0 — ORDER_COMPENSATION_TOPIC 消费者
- **问题**：关单失败发送补偿消息但 0 消费者，订单状态不一致无兜底
- **操作**：
  1. 新建 `my-xhs-order/src/main/java/com/myxhs/order/consumer/OrderCompensationConsumer.java`
  2. `@RocketMQMessageListener(topic="ORDER_COMPENSATION_TOPIC", consumerGroup="order-compensation-consumer-group", maxReconsumeTimes=3)`
  3. 消费逻辑：Redis SETNX 幂等 → `orderService.closeTimeoutOrder(orderId)` → 异常 throw 触发 RocketMQ 重试
  4. 同步修改 `OrderService.java` 中 `closeTimeoutOrder()` 的 catch 块，发送 `CompensationMessage` 到 `ORDER_COMPENSATION_TOPIC`
  5. 新建 `my-xhs-common/.../entity/CompensationMessage.java`（字段：orderId, failReason, timestamp）
  6. **参考**：`docs/P8整改执行手册-详细版.md` 任务 3 的完整代码

---

## Fallback 复活组（第 2 天 — 依赖任务 1 验证）

### 任务 4 🔴P0 — home 模块 Sentinel 生效
- **问题**：home BFF 有 11 个 FallbackFactory 但 pom 缺 Sentinel 依赖，全部静默失效
- **操作**：
  1. `read_file` 读取 `my-xhs-home/pom.xml`，搜索 `sentinel` 确认是否存在
  2. 若不存在，在 `<dependencies>` 中添加：
```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
```
  3. `read_file` 读取 `my-xhs-home/src/main/resources/application.yml`，追加：
```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: 21.91.124.110:18082
        port: 8725
      eager: true
      feign:
        sentinel:
          enabled: true
```
  4. `search_content` 搜索 home 模块 `feign/` 目录下 `*FeignClient` 的 `fallbackFactory` 属性，确认已配置

### 任务 5 🔴P0 — cart 模块 Sentinel 生效
- 同任务 4，涉及文件：`my-xhs-cart/pom.xml` + `application.yml`，port 用 8729

---

## 安全漏洞组（第 2 天 — 独立）

### 任务 6 🔴P0 — Jackson RCE 修复
- **问题**：`RedisConfig.java` L78-82 用了 `LaissezFaireSubTypeValidator`（允许反序列化任意 Java 类型）
- **操作**：
  1. 读取 `my-xhs-common/.../config/RedisConfig.java`
  2. 定位 `LaissezFaireSubTypeValidator.instance`
  3. 替换为：
```java
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;

PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
    .allowIfBaseType("com.myxhs.")
    .allowIfBaseType("java.util.")
    .allowIfBaseType("java.lang.")
    .allowIfBaseType("java.time.")
    .build();
objectMapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
```

### 任务 7 🔴P0 — @Valid 补齐（3 处 Controller）
- **问题**：SpuUpdateRequest、CounterRequest、搜索 GET POJO 均无参数校验
- **操作**：
  1. `search_content` 搜索 `public R.*updateSpu` → 定位 `ProductController.java`
  2. `@RequestBody SpuUpdateRequest request` → `@RequestBody @Valid SpuUpdateRequest request`
  3. 同法处理 `CounterController.java` 和 `SearchController.java` L36,L54

---

## Feign 配置组（第 3~4 天 — 任务 9 依赖任务 8）

### 任务 8 🔴P0 — 11 模块 Feign 超时配置
- **前置确认**：`search_content` 搜索 `connect-timeout` 确认**已配模块**（预计 cart/order/payment/home 有）
- **操作**：对**确认缺失的模块**追加配置（不要对已有配置的模块重复添加）：
```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connect-timeout: 500
            read-timeout: 2000
            logger-level: BASIC
      compression:
        request:
          enabled: true
          mime-types: application/json
        response:
          enabled: true
```
  - inventory/order 的 read-timeout 设为 3000ms（若 order 已有则不改）
  - search 的 read-timeout 设为 5000ms

### 任务 9 🔴P0 — Feign HttpClient 5 连接池
  1. `read_file` 读取父 `pom.xml`，在 `<dependencyManagement>` 搜索 `feign-hc5`，若无则添加：
```xml
<dependency>
    <groupId>io.github.openfeign</groupId>
    <artifactId>feign-hc5</artifactId>
    <version>13.1</version>
</dependency>
```
  2. 所有有 FeignClient 的模块 yml 追加：
```yaml
spring:
  cloud:
    openfeign:
      httpclient:
        hc5:
          enabled: true
          max-connections: 200
          max-connections-per-route: 50
```

---

## 基础设施组（第 4 天 — 独立）

### 任务 10 — 15 模块优雅停机
- `codebase_search` 找出所有 15 个业务模块的 `application.yml`
- 每个 yml 追加：
```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

### 任务 11 — 4 模块 Actuator 补齐
- 对 search/counter/content/analytics 的 yml 追加：
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

---

## Redis 安全组（第 5~7 天 — 任务 13 依赖任务 12）

### 任务 12 — Redis 拆分方案文档
- 输出 `docs/redis-split-plan.md`，含：
  - 双实例架构：Cache(16380, 128MB, allkeys-lru) + Business(16381, 256MB, noeviction)
  - 8 类核心数据 Key 映射表：cart/follow/like/favorite/coupon:stock/lock/inventory:id/token:blacklist/id:sequence/counter buffer → Business；cache/hotsearch/suggest/ratelimit → Cache
  - 6 天逐模块灰度迁移步骤

### 任务 13 🔴P0 — 8 类核心数据迁移
  1. `read_file` 读取 `docker-compose.yml`，追加 redis-cache 和 redis-business 两个容器
  2. 在 `my-xhs-common` 新建 `config/RedisMultiSourceConfig.java` 实现双数据源（`@Primary` business，`@Qualifier("cache")` cache）
  3. 逐模块切换 `@Qualifier` 注解：
     - Day 2: cart → `@Qualifier("businessStringRedisTemplate")`
     - Day 3: inventory + coupon
     - Day 4: analytics + home + counter
     - Day 5: user + product + content
     - Day 6: order + payment
  4. 每步切换后运行验证脚本（参考 `docs/P8整改执行手册-详细版.md` §12.6）

---

## 代码质量速修组（第 7~8 天 — 独立，可与 Redis 并行）

### 任务 14 — CouponController / ImController VO 化
  1. `read_file` 读取 `CouponController.java` L34,L51，搜索 `R<CouponTemplate>`
  2. 新建 `CouponTemplateVO`（字段不含 `deleted`/`createdAt`），修改 Controller 返回类型
  3. `read_file` 读取 `ImController.java` L67-89，搜索 `Map<String, Object>`
  4. 新建 `ImMessageVO`，修改返回类型

### 任务 15 — CacheHelper 延迟双删 MQ 兜底
  1. `codebase_search` 搜索 `delayDoubleDelete` 定位 `CacheHelper.java`
  2. 在 catch 块中增加 `rocketMQTemplate.syncSend("CACHE_EVICT_TOPIC", key, 3000)`
  3. 如果类中无 `RocketMQTemplate`，注入之

### 任务 16 — 清理未使用依赖
  1. `read_file` 读取父 `pom.xml`
  2. `search_content` 搜索 `fastjson2` → 删除整个 `<dependency>` 块
  3. `search_content` 搜索 `hotkey.version` → 删除 properties 中的声明（dependencyManagement 无对应条目）
  4. testcontainers 声明保留，加注释 `<!-- 保留供后续测试阶段使用 -->`

### 任务 17 — OrderService.cancelOrder() Feign 并行化
  1. `read_file` 读取 `OrderService.java` 的 `cancelOrder()` 方法
  2. 识别 3 个顺次 Feign 调用（释放库存、退优惠券、更新状态）
  3. 将无依赖的调用改为 `CompletableFuture.allOf(future1, future2).join()`

### 任务 18 — CartService 批量 SKU 接口
  1. `read_file` 读取 `CartService.java`，搜索 `for.*skuId` 定位循环单查
  2. `read_file` 读取 `my-xhs-product/.../feign/ProductFeignClient.java`
  3. 新增方法 `@GetMapping("/api/product/sku/batch") R<List<SkuVO>> batchGetBySkuIds(@RequestParam List<Long> skuIds)`
  4. CartService 中一次调用替代 N 次循环

---

## 阶段一验收清单

| # | 验收项 | 验证方式 |
|:---:|------|------|
| 1 | 发布笔记 → 关注者 Feed 流可见 | 功能测试 |
| 2 | 发表评论 → 笔记作者收到通知 | 功能测试 |
| 3 | 关单失败 → 补偿消费者日志可见 | 日志检查 |
| 4 | 停 product 服务 → home 返回降级数据 | `docker stop` + curl |
| 5 | 停 product 服务 → cart 提示降级 | `docker stop` + curl |
| 6 | Jackson `LaissezFaireSubTypeValidator` 已替换 | Code Review |
| 7 | 3 个 Controller `@RequestBody @Valid` | Code Review |
| 8 | 11 模块 yml 含 Feign 超时配置 | `search_content "connect-timeout"` |
| 9 | pom 含 feign-hc5 | `search_content "feign-hc5"` |
| 10 | 15 模块 `server.shutdown: graceful` | `search_content "shutdown: graceful"` |
| 11 | search/counter/content/analytics 暴露 prometheus | curl `/actuator/prometheus` |
| 12 | Redis 拆分方案文档存在 | `docs/redis-split-plan.md` |
| 13 | Redis 双实例 Key 分布正确 | 验证脚本通过 |
| 14 | CouponTemplateVO / ImMessageVO 存在 | Code Review |
| 15 | CacheHelper catch 块有 MQ 兜底 | Code Review |
| 16 | FastJSON2 依赖已删除 | `search_content "fastjson2"` 结果为空 |
| 17 | cancelOrder 使用 CompletableFuture | Code Review |
| 18 | ProductFeignClient 有 batchGetBySkuIds | Code Review |

---

# 阶段二：加固（第 3~4 周，任务 19~46）

> 目标：Sentinel 100% 覆盖、备份建立、Nacos 配置中心启用。预估 ~56h

## Sentinel 全覆盖组（第 1~3 天）

### 任务 19 — 12 模块 Sentinel 依赖+配置
- `search_content` 搜索 `spring-cloud-starter-alibaba-sentinel` 确认已覆盖模块
- 对 inventory/content/search/analytics/user/product/coupon/counter/im/notification 的 pom 添加 sentinel 依赖
- 每个模块 yml 追加 sentinel transport 配置，端口号见 `docs/P8整改执行手册-详细版.md` §14.2

### 任务 20 — Sentinel Dashboard 部署
- `read_file` 读取 `docker-compose.yml`，追加 sentinel-dashboard 容器（bladex/sentinel-dashboard:1.8.8, 端口 18082）

### 任务 21 — Sentinel 规则迁移 Nacos
- Gateway + 全服务 flow+degrade 规则写入 Nacos

### 任务 22 — Gateway 真空期修复
- `read_file` 读取 `my-xhs-gateway/.../filter/RateLimitFilter.java`
- `onApplicationReady()`：Nacos 规则 30s 未到达 → `CompletableFuture.delayedExecutor(30, SECONDS)` 加载本地兜底 `initFlowRules()`

### 任务 23 — Feign Sentinel + max-rt 验证
- inventory/search 启用 `feign.sentinel.enabled: true`
- **关键**：`search_content` 搜索 `slowRatioThreshold` 和 `max-rt`，验证其值 ≥ Feign read-timeout（防阈值倒挂误熔断）

---

## 容器化组（第 3 天 — 独立）

### 任务 24 — HEALTHCHECK
- `read_file` 读取 `docker-compose.yml`，为 MySQL/Redis/ES/RocketMQ/Canal 各容器加 `healthcheck`

### 任务 25 — Docker 日志轮转
- 全容器加 `logging: driver: json-file, options: {max-size: "10m", max-file: "3"}`

### 任务 26 — 密码迁移 .env
- docker-compose 中硬编码密码改为 `${MYSQL_ROOT_PASSWORD}` 变量引用
- 新建 `.env` 文件，`.gitignore` 加 `.env`

---

## 监控告警组（第 4~5 天 — 独立）

### 任务 27 — 告警规则 10→24 条
- `read_file` 读取 `config/prometheus/alert_rules/myxhs_rules.yml`
- 按订单/支付/库存/搜索/用户/网关 6 核心 × 4 黄金信号(Error/Latency/Traffic/Saturation) 补齐

### 任务 28 — 中间件级告警
- 追加 Redis 内存/连接、ES 集群健康、Canal 同步延迟、RocketMQ 积压 4 条规则

### 任务 29 — Grafana 面板
- 导入 JVM Micrometer Dashboard（ID 4701）
- 新建业务大盘：下单成功率/支付转化率/QPS热力图/P99延迟

### 任务 30 — SkyWalking Agent 挂载
- 14 个微服务在 IDE Run Configuration 的 VM options 中追加：
  `-javaagent:/opt/skywalking/agent/skywalking-agent.jar -Dskywalking.agent.service_name=my-xhs-{module} -Dskywalking.collector.backend_service=21.91.124.110:11800`
- 后续 Dockerfile 中（任务 76）已含此配置

---

## Nacos 配置中心组（第 5~6 天）

### 任务 31 — 15 服务启用 Nacos Config
- `search_content` 搜索 `nacos.config.enabled`，全部改为 `true`

### 任务 32 — 配置分层设计
- 在 Nacos 中创建：`my-xhs-common.yml` + 15 个 `my-xhs-{service}.yml` + `my-xhs-degrade-switches.yml`

### 任务 33 — 降级开关 @RefreshScope
- 新建 `my-xhs-common/.../config/DegradeSwitchManager.java`（@RefreshScope + @ConfigurationProperties）
- 字段：recommendDegrade/feedDegrade/notificationDegrade/searchDegrade/hotFallback

---

## 备份组（第 6~7 天）

### 任务 34 🔴P0 — MySQL 备份
- 编写 `/opt/scripts/mysql-backup.sh`（mysqldump 4 实例 + gzip + 7 天过期清理）
- docker-compose MySQL 容器 `command` 追加 `--binlog-expire-logs-seconds=604800`

### 任务 35 — Redis 备份
- 编写 `/opt/scripts/redis-backup.sh`（BGSAVE + cp dump.rdb）

### 任务 36 — ES 备份
- curl 注册 snapshot 仓库 + crontab 定时 snapshot 脚本

---

## ShardingSphere 组（第 7 天）

### 任务 37 🔴P0 — 一致性 Hash 迁移方案
- 输出方案文档到 `docs/sharding-consistent-hash-plan.md`
- 含：MOD→一致性 Hash 代码改动清单、回滚步骤、双写过渡方案、全量验证脚本
- **不执行切换**，切换在第 5~6 周任务 73

### 任务 38 — JDBC URL 补充 rewriteBatchedStatements
- `read_file` 读取 `my-xhs-order/.../sharding-config.yaml`
- 4 个 ds0~ds3 的 jdbcUrl 追加 `&rewriteBatchedStatements=true`

---

## 遗漏 P1 加固组（第 8~10 天 — 独立并行）

### 任务 39 — RocketMQ Topic 手动创建 + 关 autoCreate
- `search_content` 搜索所有 `syncSend\|asyncSend\|@RocketMQMessageListener` 列出 Topic
- 为每个 Topic 执行创建命令，broker.conf 关 autoCreateTopicEnable

### 任务 40 — Search After _id tiebreaker
- `read_file` 读取 `SearchService.java`，搜索 `searchAfter` 或 `search_after`
- 排序字段追加 `"_id": "asc"` 作为 tiebreaker

### 任务 41 — 订单关单三线切换
- `read_file` 读取 `OrderService.java` 关单逻辑
- L1 延时消息(30min) → L2 新建 XXL-Job Handler 每分钟扫表 → L3 补偿消费者(任务 3)

### 任务 42 — Canal 多分区
- `read_file` 读取 `config/canal/conf/` 下 note_instance 和 product_instance 的配置
- 将 `partition=0` 改为 `partitionByTable=true` 或 `partitionHash`

### 任务 43 — Prometheus 抓取间隔 15s→5s
- `read_file` 读取 `config/prometheus/prometheus.yml`，`scrape_interval: 5s`

### 任务 44 — CORS + 压测 IP 收紧
- `read_file` 读取 Gateway `CorsConfig` 或 `GatewayConfig.java`
- `allowedOriginPatterns("*")` → 具体域名白名单
- 压测标记 IP `10.0.0.0/8` → 精确压测平台 IP

### 任务 45 — CounterService N+1 修复
- `read_file` 读取 `CounterService.java` 的 `batchGetCounts()` 方法
- Pipeline 未命中时的逐条 MySQL 回退 → 批量 `WHERE (target_type, target_id) IN (...)` 一次查询

### 任务 46 — Jackson ES 8.12 兼容性验证
- `search_content` 搜索 `ObjectMapper` 和 `@class` 确认序列化配置
- 全模块编译测试，确认无 `IncompatibleClassChangeError`

---

## 阶段二验收清单

| # | 验收项 | 验证方式 |
|:---:|------|------|
| 19 | Sentinel Dashboard 可见全部 12 个新服务 | Dashboard 查看 |
| 20 | sentinel-dashboard 容器运行 | `docker ps` |
| 21 | Nacos 中可见 flow+degrade 规则 | Nacos 后台查看 |
| 22 | Nacos 断连 30s 后 Gateway 加载本地规则 | 日志验证 |
| 23 | Sentinel max-rt ≥ Feign read-timeout | 配置对比审查 |
| 24 | `docker inspect` MySQL/Redis/ES 可见 Healthcheck | `docker inspect` |
| 25 | 容器日志 `/var/lib/docker/containers/` 可见轮转 | 检查日志文件 |
| 26 | `.gitignore` 含 `.env` | `cat .gitignore` |
| 27 | `config/prometheus/alert_rules/` 下 24 条规则 | 计数确认 |
| 28 | Redis/ES/Canal/RocketMQ 告警存在 | 同上 |
| 29 | Grafana `:13000` 可见业务大盘 | 浏览器访问 |
| 30 | SkyWalking UI 可查询 Trace | UI 查看 |
| 31 | `search_content "nacos.config.enabled: true"` 15 处 | grep 计数 |
| 32 | Nacos 中可见 17 个配置项 | Nacos 后台 |
| 33 | Nacos 改 degrade 开关 → 日志确认刷新 | 操作验证 |
| 34 | 备份脚本执行成功，文件 > 0 字节 | 手动执行 |
| 35 | Redis RDB 备份文件存在 | 文件检查 |
| 36 | ES snapshot 创建成功 | `GET _snapshot/my_backup/_all` |
| 37 | `sharding-consistent-hash-plan.md` 存在 | 文件检查 |
| 38 | sharding-config.yaml 含 `rewriteBatchedStatements=true` | grep |
| 39 | autoCreateTopicEnable=false + 所有 Topic 已手动创建 | 配置检查 |
| 40 | SearchService 排序含 `_id` | Code Review |
| 41 | XXL-Job 新增关单扫表 Handler | Admin 查看 |
| 42 | Canal instance 配置含 partition 策略 | 配置文件检查 |
| 43 | prometheus.yml scrape_interval=5s | grep |
| 44 | CORS + 压测 IP 白名单已收紧 | Code Review |
| 45 | CounterService 批量回退 | Code Review |
| 46 | 全模块编译测试通过 | `mvn compile` |

---

# 阶段三：提升（第 5~6 周，任务 47~73）

> ⚠️ **强制 2 人并行**：A 线路负责限流体系(47-60)，B 线路负责推荐引擎+ShardingSphere(61-73)
> 如仅 1 人：按 47→61→70 顺序串行，57-60 延后到第 7 周

## 线路 A：四层限流体系（任务 47-60，~49h）

### L1-Gateway 层

**任务 47** — Sentinel 集群限流 Token Server：独立 JVM 进程部署，Gateway 配 cluster-mode

**任务 48** — 多维 KeyResolver：UserKeyResolver/IPKeyResolver/ApiKeyResolver/CompositeKeyResolver

**任务 49** — GatewayParamFlowRule：按用户+API 参数级流控规则

**任务 50** — 阈值协调文档化：输出 Gateway 阈值 ≥ 业务 @RateLimit 阈值之和的矩阵文档

### L2-Server 层

**任务 51** — TomcatWebServerFactoryCustomizer：`my-xhs-common` 实现 Connector/Protocol/MBean 通用定制器

**任务 52** — 差异化 Tomcat 配置：Order→300, IM→400, search→400, notification→500, home→200

**任务 53** — Tomcat JMX MBean + Prometheus 指标

### L3-Framework 层

**任务 54** — Spring MVC RateLimitInterceptor：Redisson RRateLimiter，Controller/URL Pattern/全局三级

**任务 55** — @RateLimit 注解升级：SpEL Key + 令牌桶/漏桶/预热可配 + blockWait 排队模式

**任务 56** — 核心模块业务层 @RateLimit：订单/支付/库存加注解（当前仅 6 个方法有）

### L4-Component 层

**任务 57** — SqlGuardInterceptor：MyBatis Interceptor，慢 SQL(>200ms)检测 + 连续 5 次熔断

**任务 58** — Lettuce 连接池定制 + 热点 Key 保护：commandTimeout 500ms + pool MaxTotal=32 + 耗尽降级

**任务 59** — RocketMQ 消费端差异化：consumeThreadMax/pullBatchSize/maxReconsumeTimes 按 Consumer 配置

**任务 60** — 全量 FeignClient Sentinel fallback + FallbackFactory

---

## 线路 B：可观测性 + 分布式加固 + 推荐引擎真实化 + ShardingSphere 切换（任务 61-73，~45h）

### 可观测性（61-64）

**任务 61** — 结构化日志：LogstashEncoder JSON 格式

**任务 62** — ELK/EFK 部署（如资源允许）

**任务 63** — Grafana Funnel 面板：下单漏斗 + 支付转化 + QPS 热力图

**任务 64** — Prometheus 每层限流指标 + 一键告警静默

### 分布式加固（65-69）

**任务 65** — 死信队列消费者 + DLQ 配置

**任务 66** — RocketMQ 顺序消息（评论排序按 `noteId` 路由到同一队列）

**任务 67** — 优惠券延时消息改用 RocketMQ

**任务 68** — 缓存预热（ApplicationRunner 启动时预加载热搜/Top100/分类树）

**任务 69** — 数据库索引审查：补充 `idx_user_status_created`、`idx_status_like` + 慢查询治理

### 推荐引擎真实化（70-72）🔴P0-15

**任务 70** — 精排补全（4 维）：
- `read_file` 读取 `RecommendService.java` 的 `fineRank()` 方法
- 1 维透传 → 4 维加权融合：来源权重(已有) + 用户偏好(从 ES user_behavior 读取) + 内容质量(从 ES note_index 读取) + 时效衰减(基于发布时间指数衰减)
- 删除 "预留 ML 接口" 注释

**任务 71** — Geo 召回真实化：
- `read_file` 读取 `RecallService.java` 的 geoRecall 方法
- 时间排序假实现 → GeoHash 编码 + ES `geo_distance` 查询
- 若用户位置未知，降级为热门内容

**任务 72** — 特征提取去 Random：
- `read_file` 读取特征提取相关代码，搜索 `Random`
- 从 ES note_index 读取真实标签(tags 字段)、分类(categoryId)、质量分(like_count/comment_count)
- 删除所有 Random 随机生成逻辑

### ShardingSphere 切换（73）

**任务 73** 🔴P0-8 — 一致性 Hash 切换：
- 严格基于任务 37 的方案文档执行
- 步骤：双写 MOD+一致性 Hash → 历史数据迁移 → 全量验证 → 切只读一致性 Hash → 清理旧路由
- 每步有回滚脚本

---

## 阶段三验收清单

| # | 验收项 | 验证方式 |
|:---:|------|------|
| 47 | Token Server 独立进程运行 | `jps` |
| 48 | 4 种 KeyResolver 实现 | Code Review |
| 49 | GatewayParamFlowRule 配置生效 | Sentinel Dashboard |
| 50 | 阈值协调文档存在 | 文件检查 |
| 51-53 | Tomcat Customizer + JMX 指标 | Prometheus 指标查询 |
| 54-55 | RateLimitInterceptor + 注解升级 | Code Review |
| 56 | 订单/支付/库存有 @RateLimit | `search_content "@RateLimit"` |
| 57 | SqlGuardInterceptor 编译通过 | Code Review |
| 58-60 | Lettuce/RocketMQ/Feign 配置差异化 | 配置文件审查 |
| 61 | 日志 JSON 格式 | `docker logs` 查看 |
| 62 | ELK/EFK 日志收集（如资源允许） | Kibana 可查询日志 |
| 63 | Grafana Funnel 面板 | 浏览器访问 |
| 64 | Prometheus 每层限流指标可查询 | Prometheus 查询 |
| 65 | 死信队列消费者存在 | Code Review |
| 66 | 评论排序用 ORDERLY 模式 | Code Review |
| 67 | 优惠券延时消息改为 RocketMQ | 功能验证 |
| 68 | ApplicationRunner 存在 | Code Review |
| 69 | 复合索引已创建 | `SHOW INDEX FROM t_order` |
| 70 | 精排 4 维计算非透传 | 推荐结果与基线对比 |
| 71 | Geo 召回含 GeoHash | 附近内容召回可返回结果 |
| 72 | 特征提取无 Random | `search_content "new Random"` |
| 73 | 一致性 Hash 切换验证通过 | 扩容模拟测试 |

---

# 阶段四：超越（第 7~8 周，任务 74~97）

> ⚠️ 本阶段 ~88h，单人无法完成。**中间件 HA(90-93) 标注"资源允许时"，可延后到第 9~10 周独立阶段**

## CI/CD 管道组

**任务 74** — Jenkins 部署（docker-compose 追加，端口 18083）

**任务 75** — Jenkins Pipeline 编写：Checkout→Compile→Test→Package→Docker Build→Deploy

**任务 76** — 16 个微服务 Dockerfile（含 SkyWalking Agent 挂载到 `/app/skywalking-agent`）

**任务 77** — Docker Compose 部署脚本（含环境变量 + depends_on 启动顺序 + 健康检查等待）

## 测试体系组

**任务 78** — OrderServiceTest（8 用例）：下单成功/库存不足/幂等/事务消息回滚/关单/补偿/批量/异常

**任务 79** — PaymentServiceTest（6 用例）：支付成功/重复支付/回调幂等/策略路由/退款/异常

**任务 80** — InventoryServiceTest（6 用例）：分桶预扣/超卖防护/释放/桶间均衡/并发/异常

**任务 81** — Gateway 测试：GatewayAuthFilterTest(5) + HmacSignatureFilterTest(3)

**任务 82** — CouponServiceTest(Lua, 4 用例)：领券/退券/校验链/异常

**任务 83** — CartServiceTest(Lua, 4 用例)：加购/删除/全选/超限

> 每个测试类：先 `read_file` 读懂业务代码 → 用 Mockito mock 外部依赖 → `write_to_file` 创建测试类 → `mvn test -pl {module}` 验证

## 灰度发布组

**任务 84** — Nacos metadata.version 全服务配置

**任务 85** — GrayRouteFilter 完善：基于 userId Hash 的流量比例分配

**任务 86** — 金丝雀指标对比面板（Grafana）

## 压测验证组

**任务 87** — JMeter 脚本 4 场景：下单/支付/Feed/搜索

**任务 88** — 全链路压测 + 瓶颈定位 + 调优（含备份恢复验证）

**任务 89** — 限流触发验证：各层限流实际生效测试

## 中间件 HA 组（可延后）

**任务 90** — Redis Sentinel HA（1 主 + 1 从 + 3 哨兵）

**任务 91** — ES 3 节点集群：先 snapshot→建新集群→restore→切换 alias

**任务 92** — RocketMQ Dledger（3 节点 Broker）

**任务 93** — Canal HA（Server + ZooKeeper 选主）

## 遗留 P1 收尾组

**任务 94** — Refresh Token Rotation：使用 refresh token 后发新 token + 旧 token 失效

**任务 95** — DFA 词库扩充至 5000+（生产级），当前仅 `sensitive-words.txt` 10 个词

**任务 96** — 自定义 LoadBalancer：IM 一致性 Hash（TreeMap 虚拟节点 + userId Hash）

**任务 97** — Controller @Valid 全面扫描 + 数据归档策略文档：4 表（order/behavior/local_message/notification）冷热分离方案

---

## 阶段四验收清单

| # | 验收项 | 验证方式 |
|:---:|------|------|
| 74 | Jenkins :18083 可访问 | 浏览器访问 |
| 75 | 推送代码 → Pipeline 自动触发 | 实操验证 |
| 76 | 16 个 Dockerfile 存在 | `ls my-xhs-*/Dockerfile` |
| 77 | `docker-compose up` 所有服务正常启动 | 实操 |
| 78-83 | `mvn test` 33 用例全部通过 | `mvn test` |
| 84 | Nacos 元数据含 version 字段 | Nacos 查看 |
| 85 | GrayRouteFilter 按 userId hash 分配 | 日志验证 |
| 87 | JMeter 4 场景脚本可执行 | 手动运行 |
| 88 | 压测报告含瓶颈分析 | 文档输出 |
| 89 | 各层限流触发均有日志+指标 | 日志+Grafana |
| 90-93 | 中间件 HA 状态检查 | `redis-cli INFO replication` 等 |
| 94 | 旧 refresh token 使用后失效 | 安全测试 |
| 95 | sensitive-words.txt ≥ 5000 行 | `wc -l` |
| 96 | IM WebSocket 同用户连同一实例 | 功能测试 |
| 97 | 归档策略文档存在 + 所有 Controller @Valid | 文件检查 + `search_content` |

---

> **执行完毕后的终验**：
> 1. `search_content "TODO|FIXME"` — 修复型 TODO 已清零（任务 1、2 的占位已替换）；非修复型 TODO（如 ChaosInterceptor 预留注入点）需逐条标注原因
> 2. `mvn compile` 全模块通过
> 3. 对照 `docs/下一步行动计划-P8整改路线图.md` §九 的 P0/P1 表逐一勾销（15 P0 + 22 P1）
> 4. 最终评分目标：≥ 76/100
