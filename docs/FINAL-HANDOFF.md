# my-xhs 项目交接文档（2026-08-01 完整版）

> 交接人：AI 辅助梳理 | 日期：2026-08-01
> 范围：16 模块梳理 + 代码修复 + 可观测性验证 + SkyWalking 排查

---

## 1. 模块梳理完成状态（16/16）

| 模块 | 端口 | 架构文档 | curl 测试 | 深度文档 | 状态 |
|:--:|---|:--:|:--:|:--:|:--:|
| 01-user | 19001 | ✅ | ✅ | — | ✅ |
| 02-content | 19002 | ✅ | ✅ | 11 篇 | ✅ |
| 03-analytics | 19003 | ✅ | ✅ | 9 篇 | ✅ |
| 04-counter | 19004 | ✅ | ✅ | 5 篇 | ✅ |
| 05-product | 19006 | ✅ | ✅ | 5 篇 | ✅ |
| 06-cart | 19008 | ✅ | ✅ | 6 篇 | ✅ |
| 07-inventory | 19009 | ✅ | ✅ | 6 篇 | ✅ |
| 08-coupon | 19010 | ✅ | ✅ | 4 篇 | ✅ |
| 09-order | 19011 | ✅ | ✅ | 4 篇 | ✅ |
| 10-payment | 19012 | ✅ | ✅ | — | ✅ |
| 11-notification | 19013 | ✅ | ✅ | 3 篇 | ✅ |
| 12-im | 19014 | ✅ | ✅ | 4 篇 | ✅ |
| 13-home | 19015 | ✅ | ✅ | 3 篇 | ✅ |
| 14-search | 19016 | ✅ | ✅ | 4 篇 | ✅ |
| 15-common | — | ✅ | — | 5 篇 | ✅ |
| 16-gateway | 19000 | ✅ | ✅ | 3 篇 | ✅ |

文档位置：`docs/test-2/{NN}-{module}/`（01 架构文档 / 02 测试记录 / 03+ 深度文档；common 无测试记录，深度从 02 起）
注：深度文档数量以实际目录文件为准（2026-08-01 核对）。

---

## 2. 本周期代码修复汇总（2026-07-30 ~ 08-01）

### 2.1 新增修复

| 模块 | 修复 | 文件 |
|---|---|---|
| common | 读写分离 SQL 分析路由失效（isReadOperation 孤儿方法） | 新建 `ReadWriteRoutingInterceptor.java`（Executor 层拦截） |
| common | routingDataSource 无 @Primary（MyBatis 直连主库） | `ReadWriteRoutingDataSourceConfig.java` |
| search | ES `_id` 排序 → all shards failed | `NoteSearchService.java` / `ProductSearchService.java`（移除 _id tiebreaker） |
| search | Search After FieldValue 整体序列化 → 翻页空 | `AbstractSearchService.java`（新增 serializeSearchAfter） |
| gateway | recommend 路由缺失 | `application.yml`（新增路由） |
| gateway | Prometheus 0 行（缺 micrometer 依赖） | `pom.xml` |
| **15 模块** | LOGSTASH appender 缺 includeMdcKeyName → ES 无 traceId | 15 个 `logback-spring.xml` |

### 2.2 既有修复（上一周期）

见 HANDOFF.md「代码修复汇总」（inventory/coupon/order/payment/notification/cart/home 等）。

---

## 3. 可观测性验证结果（2026-08-01 全部闭环）

### 3.1 Prometheus ✅
- 16/16 目标 UP（修复：prometheus.yml 自监控 target → 127.0.0.1:19090）
- 各服务 `/actuator/prometheus` 正常暴露

### 3.2 Grafana ✅
- 4 个仪表盘（JVM / Tomcat&HikariCP / 业务指标 / 接口监控）
- Elasticsearch-Logs 数据源已补 ES 密码（原 basicAuth 无密码 → Authentication failed）
- 通过 Grafana 代理可查 ES 日志（含 traceId 精确检索）

### 3.3 日志（Elastic Stack：Filebeat + Logstash + Elasticsearch + Kibana）✅
- **完整链路**：服务写日志（logback）→ **Filebeat**（轻量采集）→ **Logstash**（grok 提取 traceId）→ **ES**（存储 19200）→ **Kibana**（查询+可视化）
- 版本：Elastic Stack 8.19.19（Kibana CVE-2024-4367 已修复，2026-08-02 重新部署）
- traceId 已作为独立字段（15 模块 logback 修复 + Logstash grok）
- ES 索引：`myxhs-logs-YYYY.MM.DD`（19200）
- 容器：my-xhs-kibana + my-xhs-filebeat + my-xhs-logstash（均 Up 42h+）

### 3.4 SkyWalking ✅（含完整排查记录）
- OAP 9.7.0（8080，gRPC 11800，**存储 ES 19201**）
- Agent 9.6.0（`/data/workspace/my-xhs/skywalking-agent-9.6.0/`）
- **验证实证**：跨服务 22 span 链路，refs（CROSS_PROCESS/CROSS_THREAD）完整
  ```
  traceId: 3696c8ed7b034096be6f2a345387e8cc.240.17855678610420037
  home SpringMVC → Feign Exit ×3 → user/counter/content 各自 Entry + DB/Redis
  ```

### 3.5 SkyWalking agent 配置要点（重要）

```
启动参数：
  -Dskywalking.agent.service_name={模块名}
  -Dskywalking.logging.dir=/tmp/sw-logs/{模块名}
  -javaagent:/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar
环境变量：
  SW_MOUNT_FOLDERS=plugins,activations,bootstrap-plugins  ← jdk-http 兜底必须

agent 插件改动：
  plugins 移出：apm-springmvc-annotation-3/4/5.x（与 v6 共存冲突）
  plugins 移入：apm-springmvc-annotation-6.x、apm-spring-webflux-6.x（+webclient）
  plugins 移入：apm-spring-cloud-gateway-4.x（2026-08-01 补，从 optional-plugins 移入）
  备份：/tmp/springmvc-bak/
```

**启动脚本**：`/data/workspace/my-xhs/scripts/restart-all-skywalking.sh`（15 服务一键重启，含 SkyWalking agent 参数，日志统一写到 `logs/` 目录）

### 3.6 SkyWalking agent 插件目录结构（新机器部署必读）

SkyWalking agent 9.6.0 的插件分 4 个目录，**加载行为不同**：

| 目录 | 加载行为 | 说明 |
|---|---|---|
| `plugins/` | ✅ **自动加载** | agent 启动时扫描并加载，130+ 个常用插件默认在此 |
| `optional-plugins/` | ❌ **不自动加载** | 需手动 `cp` 到 `plugins/` 才加载。**这是最容易踩的坑**——链路缺失时第一步查这里 |
| `bootstrap-plugins/` | ⚠️ 需 `SW_MOUNT_FOLDERS=bootstrap-plugins` | JDK 底层插件（jdk-httpclient/jdk-threading 等），通过环境变量挂载 |
| `activations/` | ✅ 自动加载 | toolkit 激活类（如 webflux activation），通常无需手动操作 |

**本项目需要从 `optional-plugins/` 移到 `plugins/` 的插件清单**：

| 插件 | 用途 | 状态 | 验证方法 |
|---|---|---|---|
| `apm-spring-cloud-gateway-4.x-plugin-9.6.0.jar` | Gateway 转发链路追踪（含 `v412x` 子包支持 SCG 4.1.2） | ✅ 已移+已加载 | gateway↔user segment 同 trace_id |
| `apm-sentinel-1.x-plugin-9.6.0.jar` | Sentinel 限流/熔断 span | ✅ 已移+已加载 | agent 日志 `loading plugin class ...SentinelCtEntryInstrumentation` 等 3 类 |
| `apm-nacos-client-2.x-plugin-9.6.0.jar` | Nacos 服务发现/配置拉取 span | ✅ 已移+已加载 | SkyWalking segment 出现 `Nacos/serverCheck`、`Nacos/registerInstance` endpoint |
| `apm-mybatis-3.x-plugin-9.6.0.jar` | MyBatis-Plus SQL span（增强 JDBC span） | ✅ 已移+已加载 | agent 日志 `loading plugin class ...MyBatisInstrumentation` 等 2 类 |
| `apm-spring-tx-plugin-9.6.0.jar` | @Transactional 事务 span | ✅ 已移+已加载 | agent 日志 `apm-spring-tx-plugin-9.6.0.jar loaded` |

**当前实际生效情况（2026-08-01 验证）**：5 个插件全部已移到 `plugins/` 并已加载。15 个服务已用 `scripts/restart-all-skywalking.sh` 一键重启，3 条核心链路抽测通过（gateway→user / home→user / payment→order 30/30）。新 span 类型 `Nacos/serverCheck`、`Nacos/registerInstance` 已在 SkyWalking segment 出现。

**已知插件冲突（勿移回 plugins）**：
- `apm-springmvc-annotation-3.x/4.x/5.x` 与 `6.x` **不能共存**（v6 不注册）。3/4/5 已备份到 `/tmp/springmvc-bak/`，勿移回。

**部署到新机器的标准流程**：
```bash
# 1. 解压 skywalking-agent-9.6.0 到目标位置
# 2. 移走冲突插件
mv plugins/apm-springmvc-annotation-3.x-plugin-9.6.0.jar /tmp/springmvc-bak/ 2>/dev/null
mv plugins/apm-springmvc-annotation-4.x-plugin-9.6.0.jar /tmp/springmvc-bak/ 2>/dev/null
mv plugins/apm-springmvc-annotation-5.x-plugin-9.6.0.jar /tmp/springmvc-bak/ 2>/dev/null
# 3. 从 optional-plugins 移入项目需要的插件
cp optional-plugins/apm-spring-cloud-gateway-4.x-plugin-9.6.0.jar plugins/        # Gateway 转发链路（必须）
cp optional-plugins/apm-sentinel-1.x-plugin-9.6.0.jar plugins/                     # Sentinel span
cp optional-plugins/apm-nacos-client-2.x-plugin-9.6.0.jar plugins/                 # Nacos span
cp optional-plugins/apm-mybatis-3.x-plugin-9.6.0.jar plugins/                      # MyBatis span
cp optional-plugins/apm-spring-tx-plugin-9.6.0.jar plugins/                        # @Transactional span
# 4. 验证插件加载（启动服务后看 agent 日志）
grep "loading plugin class" /tmp/sw-logs/{模块名}/skywalking-api.log | grep -E "gateway|sentinel|nacos|mybatis|spring-tx"
```

**验证链路追踪完整性**（见 4.1 修复 #6 的方法）：
1. curl gateway 转发请求
2. 查 SkyWalking ES segment（`sw_segment-YYYYMMDD`，ES 19201，凭据 `elastic:Xhs@2026#ElasticSW`）
3. 用 `service_id`（base64 编码）过滤 gateway segment，取 `trace_id` hex 部分
4. 用 wildcard `trace_id: hex*` 查下游服务 segment
5. 有匹配 → sw8 传播成功；0 条 → 链路断，查 optional-plugins 是否有对应插件没移

**关键教训**：observability-issues.md 旧描述"SCG 4.1.2 witness 盲区"是**错误的**。真正根因是插件在 optional-plugins 没移到 plugins。**不要相信"witness 盲区"描述，先验证插件是否在 plugins 目录**。

---

## 4. 遗留问题

| 问题 | 状态 | 处理 |
|---|---|---|
| Gateway 转发（route 级）链路 | ✅ **已解决（2026-08-01）**：根因不是"SCG 4.1.2 witness 盲区"（observability-issues.md 旧描述错误），而是 `apm-spring-cloud-gateway-4.x-plugin-9.6.0.jar` 在 `optional-plugins` 目录没移到 `plugins`。插件 `skywalking-plugin.def` 里有 `v412x` 子包专门支持 SCG 4.1.2。移插件+重启 gateway 后验证：gateway `/api/user/1/info` segment trace_id=`42508a52...`，user `GET:/api/user/{userId}/info` segment 同 trace_id，sw8 传播成功 | 见 4.1 修复 #6 |
| 时区不统一 | ⚠️ **配置选择，非 BUG（2026-08-01 重新评估）**：业务机 CST / OAP UTC。改 OAP 时区会导致历史 time_bucket 数据混桶（8 小时错位）；UI 显示时间是浏览器转换 epoch 不受 OAP 时区影响。当前 UTC 查询窗口 workaround 是正确实践，保留 | 保留 UTC workaround；文档标注为正确实践 |
| payment→order Feign | ✅ **已修复（2026-08-01）**：30/30 调通（payment `[补偿任务] 通知成功` × 30 ↔ order `[订单回调] 收到支付成功通知` × 30） | 见 4.1 修复 #1-3 |
| order→payment Feign | ✅ **已修复（2026-08-01）**：curl `/api/order/pay/status/{orderId}` HTTP 200 + 真实 paymentNo（pay.type=remote 模式抽测） | 见 4.1 修复 #5 |

### 4.1 2026-08-01 第二周期补修（6 个 BUG）

| # | BUG | 修复 | 验证 |
|---|---|---|---|
| 1 | payment 4 个 XXL-Job 未在 Admin 注册（paymentNotifyCompensateJob/paymentTimeoutCheckJob/refundNotifyCompensateJob/refundTimeoutCheckJob） | XXL-Job Admin 创建执行器 `my-xhs-payment`(id=6) + 4 个 job(id=6,7,8,9) | ✅ handleCode=200 |
| 2 | `OrderFeignClient`/`PaymentFeignClient` `contextId` + Sentinel Feign NPE（`Cannot invoke "Object.hashCode()" because "key" is null`） | 删除 contextId 属性 | ✅ NPE 消失 |
| 3 | `LeastConnectionsLoadBalancerConfig` 单例 Bean serviceId="default" | 加 `@LoadBalancerClients(defaultConfiguration=...)` + `@ConditionalOnProperty(loadbalancer.client.name)` | ✅ payment→order 30/30 |
| 4 | home `spring.cloud.sentinel.feign.sentinel.enabled` typo（多一层 sentinel）+ `restart-all-skywalking.sh` 日志路径 `/tmp/` | home 改 `feign.sentinel.enabled`；脚本日志改 `logs/` | ✅ home Feign 调通 |
| 5 | `OrderController` 强依赖 `MockPayService`（`@ConditionalOnProperty(pay.type=mock)`）导致 `pay.type=remote` 启动失败 | `MockPayService` 改 `ObjectProvider<MockPayService>` 可选注入 | ✅ remote 模式启动 + order→payment Feign 调通 |
| 6 | **Gateway 转发链路追踪缺失**（observability-issues.md 旧描述"SCG 4.1.2 witness 盲区"是错误的，真正原因是插件没启用） | `cp optional-plugins/apm-spring-cloud-gateway-4.x-plugin-9.6.0.jar plugins/` + 重启 gateway | ✅ gateway segment trace_id `42508a52...` ↔ user segment 同 trace_id，sw8 传播成功 |

### 4.2 撤回的误判（2026-08-01）

| 误判 | 实际情况 |
|---|---|
| `my_xhs_order` 数据库缺 `t_order` 表 | ShardingSphere 分库分表，实际表 `t_order_0/1/2/3` 分布在 `my_xhs_order_0/1/2/3` 4 个分库，全部存在。`my_xhs_order` 库只有 `t_order_no_mapping`（路由映射表）是正常设计 |
| OAP 时区改 CST 可修复时区问题 | 撤回：改 OAP 时区会导致历史 time_bucket 混桶；UI 显示不受影响；当前 UTC workaround 是正确实践 |

**SkyWalking 查询技巧**（踩坑记录）：
1. queryBasicTraces 返回 segmentId，**traceId 在 traceIds 字段**
2. Redisson 噪音占满分页 → 缩小时间窗口（1 分钟级）+ pageSize 2000+
3. 查询窗口用 **UTC**（OAP 按 UTC 存 time_bucket）—— 这是**正确实践**，不是 workaround
4. segment 索引 `sw_segment-YYYYMMDD` 在 ES 19201（凭据 `elastic:Xhs@2026#ElasticSW`），`service_id`/`endpoint_id` 是 base64 编码
5. 业务 traceId（X-Trace-Id）和 SkyWalking traceId 是两套独立系统：业务 traceId 透传正常不代表 SkyWalking sw8 传播正常
4. agent 日志在 `/tmp/sw-logs/{模块名}/skywalking-api.log`（启动参数 -Dskywalking.logging.dir）

---

## 5. 环境信息

### 5.1 服务机 21.214.97.212
- 15 个微服务（19000-19016）
- SkyWalking agent 9.6.0

### 5.2 中间件机 21.130.247.89
| 组件 | 地址 | 凭据 |
|---|---|---|
| MySQL | 13306(user) / 13307(content) / 13308(order+payment) / 13309(inventory) | root / Xhs@2026#MySQL |
| Redis | 16379(master)/16380/16381 | Xhs@2026#Redis |
| ES 业务 | 19200 | elastic / Xhs@2026#Elastic |
| ES SkyWalking | **19201** | 凭据在 OAP 环境变量 SW_STORAGE_ES_USERNAME/PASSWORD |
| Nacos | 18848 (namespace=my-xhs) | |
| RocketMQ | 9876;9877 | |
| XXL-Job | 18080 | admin/123456 |
| Sentinel | 8858 | sentinel/sentinel |
| Prometheus | 19090 | |
| Grafana | 13000 | admin/Xhs@2026#Admin |
| SkyWalking OAP | 8080 (gRPC 11800) | |
| Canal | 默认 11111（TCP server） | canal / Canal@2026#Sync |

**SSH 到中间件机**：`ssh -p 36000 21.130.247.89`（服务机 21.214.97.212 已加白名单）

### 5.3 Canal 状态（2026-07-31 修复）
- 根因：Canal 1.1.7 + JDK 17 不兼容 → binlog decoder 静默罢工
- 修复：切 Kona JDK 8，note/product/inventory 实例对齐 binlog 位点
- 验证：MySQL UPDATE → 10s 内 ES 同步 ✅

---

## 6. 测试补测记录（2026-07-31 ~ 08-03）

| 测试 | 结果 |
|---|---|
| Canal 增量同步（UPDATE/软删） | ✅ |
| TCC 五场景（Try/Confirm/幂等/空回滚/悬挂） | ✅ |
| @Idempotent / @RateLimit | ✅ 40201 / 40202 |
| 流量染色 + traceId 跨服务透传 | ✅ |
| Gateway 限流 429（令牌桶 100/50） | ✅ |
| IP 防刷（分钟桶恰 10 词） | ✅ |
| Search After 翻页（3 页无重复） | ✅ |
| 读写分离（general log 实证 SELECT→slave） | ✅ |
| SkyWalking 跨服务 22 span | ✅ |
| **Gateway + HMAC 重测 07-inventory 11/11（2026-08-03）** | ✅ |

### 6.1 通过 Gateway 19000 + JWT + HMAC per-session 重测进度

| 模块 | 通过 | 备注 |
|---|:---:|---|
| 01-user | 19/19 | 含登录/注册/HMAC 异常 |
| 02-content | 16/16 | 笔记+评论 DFA+本地消息表 |
| 03-analytics | 20/20 | 关注+点赞 Lua 原子 |
| 04-counter | 7/7 | 缓冲触发+对账 |
| 05-product | 12/12 | 布隆+多级缓存 |
| 06-cart | 10/10 | Lua 三结构 |
| **07-inventory** | **11/11** | **L1/L2 + TCC 双路径**（2026-08-03） |
| **08-coupon** | **11/11** | **Lua 防超发 + 责任链 + MQ 持久化**（2026-08-03） |
| **09-order** | **14/14** | **Feign 编排(3 client) + 状态机 + 回调**（2026-08-03） |
| **10-payment** | **5/5** | **Mock策略模式 + 回调 String 响应 + MQ**（2026-08-03） |
| **11-notification** | **9/9** | **SSE 两步法 + 无请求体 DTO + dev test/send**（2026-08-03） |
| **12-im** | **6/6** | **WebSocket + 无请求体 DTO + Redis Pub/Sub 路由**（2026-08-03） |
| **13-home** | **7/7** | **BFF 聚合层 + 9 Feign 客户端 + CompletableFuture 异步**（2026-08-03） |
| **14-search** | **18/18** | **ES 搜索 + 推荐引擎(5路召回) + ES 直连**（2026-08-03） |
| 15-common | N/A | 工具类 |
| 16-gateway | N/A | 路由+filter |
| **总计** | **147/147** | 100% ✅ |

---

## 7. 关键文档索引

| 文档 | 内容 |
|---|---|
| `docs/HANDOFF.md` | 模块梳理交接（16/16） |
| `docs/observability-issues.md` | 可观测性验证 + SkyWalking 排查全记录 |
| `docs/traceid-es-issue.md` | ES traceId 缺失修复（logback includeMdcKeyName） |
| `docs/canal-issue.md` | Canal JDK 兼容修复 |
| `docs/test-2/test-guide.md` | **测试操作手册 + 深度 Review 交接入口（§9）**：进度 147/147(100%) + 01-user✅02-content✅03-analytics⚠️ |
| `docs/test-2/test-pitfalls.md` | 测试踩坑汇总（20 个坑 + 登录基建模板） |
| `docs/test-2/*` | 16 模块架构/测试/深度文档 |

---

---

## 9. 架构欠债 + 生产标准修复（2026-08-03）

### P0 — 已修复 ✅

| 修复项 | 文件 | 改动 |
|--------|------|------|
| SC 升级 + Feign Nacos 发现 | pom.xml → SC 2023.0.3 / SCA 2023.0.1.2 | 修复 LoadBalancer hashCode NPE，13 处 Feign URL 硬编码全部删除，恢复 Nacos 服务发现 |
| Nacos 配置中心迁移 | my-xhs-common.yaml, my-xhs-gateway.yaml 写入 Nacos；15 服务 shared-configs | 密码/密钥从 yml 明文改 Nacos 管理，Spring Cloud Config 动态加载 |
| order 回调端点鉴权 | OrderController.java + InternalCallFeignConfig.java | 4 回调接口加 X-Internal-Call 校验，Payment Feign 拦截器自动注入 |
| search 管理端点鉴权 | SearchController.java | 5 管理接口加 X-Admin-Call 校验 |
| user 暴力破解防护 | AuthController.java | captcha/register/login 加 @RateLimit |
| Gateway 限流双轨 | RateLimitFilter.java → GatewayProperties | 硬编码删，从 yml metadata.rate-limit-qps 读；user-service 删 Redis 双重限流 |
| JVM -Xmx 默认值 | start-all.sh | 11/15 服务缺 -Xmx → ${3:-$JAVA_OPTS_BASE} |
| cart Feign Sentinel | cart/application.yml | 顶层补 feign.sentinel.enabled:true |
| 10 服务死配置 | 批量删 | spring.cloud.sentinel.feign.sentinel.enabled 无效配置 |

### 深度 Review 修复（2026-08-03）

**01-user**:
| 修复 | 影响 |
|------|------|
| CACHE_EVICT_TOPIC 双断裂 | Consumer 去 tag + 兼容 String/JSON，全站缓存 L3 兜底恢复 |
| updateUserInfo 空 body → 500 | DTO hasNoFields() 校验，返回 PARAM_INVALID |
| 注册手机号并发 500 | catch DuplicateKeyException → PHONE_EXISTS |
| Block key 无 TTL | expire(365 days) |
| 不存在用户名锁定 DoS | login 非存在用户不 incrementLoginFail |

**02-content**:
| 修复 | 影响 |
|------|------|
| FeedPushConsumer WRONGTYPE | 统一 hash 读/写进度 key |
| compensateIncompletePush 无限循环 | 查 Redis 进度置 push_status=2 |
| publishDraft localMsgId | 对齐 publishNote，insert 后 setLocalMsgId |
| publishNote body 时序 | localMsgId 为 null 的 body 说明 |
| 文件魔数校验 | verifyMagicBytes() 防 Content-Type 伪造 |
| DFA 词库 | 📋 暂不扩展（合规风险） |

### P0 — 生产上线前必改

| 欠债 | 位置 |
|------|------|
| 支付全链路 Mock | order application.yml:135 pay.type=mock + payment 3 个 Mock Strategy |

---

## 10. 交接注意事项

1. **agent 重启必须带** `SW_MOUNT_FOLDERS=plugins,activations,bootstrap-plugins`（否则 Feign JDK 兜底失效）
2. **springmvc-3/4/5 插件不能与 6.x 共存**（v6 不注册）——已在备份目录，勿移回 plugins
3. **SkyWalking 查询用 UTC 时间窗口**，traceId 在 traceIds 字段
4. Kibana 日志查询（2026-08-02 重新部署，完整 ELK）
5. **Canal 用 Kona JDK 8**，勿换回 JDK 17
