# my-xhs 项目交接文档（2026-08-01 完整版）

> 交接人：AI 辅助梳理 | 日期：2026-08-01
> 范围：16 模块梳理 + 代码修复 + 可观测性验证 + SkyWalking 排查

---

## 1. 模块梳理完成状态（16/16）

| 模块 | 端口 | 架构文档 | curl 测试 | 深度文档 | 状态 |
|:--:|---|:--:|:--:|:--:|:--:|
| 01-user | 19001 | ✅ | ✅ | 13 篇 | ✅ |
| 02-content | 19002 | ✅ | ✅ | 13 篇 | ✅ |
| 03-analytics | 19003 | ✅ | ✅ | 8 篇 | ✅ |
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

文档位置：`docs/test-2/{NN}-{module}/`（01 架构文档 / 02 测试记录 / 03+ 深度文档）

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

### 3.3 日志（Logstash→ES）✅
- traceId 已作为独立字段（15 模块 logback 修复）
- ES 索引：`myxhs-logs-YYYY.MM.DD`（19200）
- **Kibana 未部署**——用 Grafana 替代日志查询

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
  备份：/tmp/springmvc-bak/
```

**启动脚本**：`/tmp/restart-all-v3.sh`（15 服务一键重启）

---

## 4. 遗留问题

| 问题 | 状态 | 处理 |
|---|---|---|
| Gateway 转发（route 级）链路 | ❌ SCG 4.1.2 无 `responseCacheSizeWeigher`（4.2+ 才有），gateway-4.x 插件 witness 盲区 | 对方代报 apache/skywalking-java issue；本地编译插件待定 |
| 时区不统一 | ⚠️ 业务机 CST / OAP UTC（time_bucket 按 UTC） | 查询/告警注意时区换算；建议统一 |
| payment→order Feign | ⚠️ 待验证 | HANDOFF.md 既有项 |

**SkyWalking 查询技巧**（踩坑记录）：
1. queryBasicTraces 返回 segmentId，**traceId 在 traceIds 字段**
2. Redisson 噪音占满分页 → 缩小时间窗口（1 分钟级）+ pageSize 2000+
3. 查询窗口用 **UTC**（OAP 按 UTC 存 time_bucket）

---

## 5. 环境信息

### 5.1 服务机 21.214.97.212
- 15 个微服务（19000-19016）
- SkyWalking agent 9.6.0

### 5.2 中间件机 21.130.247.89
| 组件 | 地址 | 凭据 |
|---|---|---|
| MySQL | 13306/13307/13308/13309 | root / Xhs@2026#MySQL |
| Redis | 16379(master)/16380/16381 | Xhs@2026#Redis |
| ES 业务 | 19200 | elastic / Xhs@2026#Elastic |
| ES SkyWalking | **19201** | 见 OAP 环境变量 |
| Nacos | 18848 (namespace=my-xhs) | |
| RocketMQ | 9876;9877 | |
| XXL-Job | 18080 | admin/123456 |
| Sentinel | 8858 | sentinel/sentinel |
| Prometheus | 19090 | |
| Grafana | 13000 | admin/Xhs@2026#Admin |
| SkyWalking OAP | 8080 (gRPC 11800) | |
| Canal | 8080（Kona JDK 8 运行） | canal / Canal@2026#Sync |

### 5.3 Canal 状态（2026-07-31 修复）
- 根因：Canal 1.1.7 + JDK 17 不兼容 → binlog decoder 静默罢工
- 修复：切 Kona JDK 8，note/product/inventory 实例对齐 binlog 位点
- 验证：MySQL UPDATE → 10s 内 ES 同步 ✅

---

## 6. 测试补测记录（2026-07-31 ~ 08-01）

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

---

## 7. 关键文档索引

| 文档 | 内容 |
|---|---|
| `docs/HANDOFF.md` | 模块梳理交接（16/16） |
| `docs/observability-issues.md` | 可观测性验证 + SkyWalking 排查全记录 |
| `docs/traceid-es-issue.md` | ES traceId 缺失修复（logback includeMdcKeyName） |
| `docs/canal-issue.md` | Canal JDK 兼容修复 |
| `docs/test-2/*` | 16 模块架构/测试/深度文档 |

---

## 8. 交接注意事项

1. **agent 重启必须带** `SW_MOUNT_FOLDERS=plugins,activations,bootstrap-plugins`（否则 Feign JDK 兜底失效）
2. **springmvc-3/4/5 插件不能与 6.x 共存**（v6 不注册）——已在备份目录，勿移回 plugins
3. **SkyWalking 查询用 UTC 时间窗口**，traceId 在 traceIds 字段
4. **Grafana 日志查询**替代 Kibana（ES 数据源已配密码）
5. **Canal 用 Kona JDK 8**，勿换回 JDK 17
