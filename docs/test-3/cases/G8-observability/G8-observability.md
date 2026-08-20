# G8 可观测性验证（L4 层——基础设施完整性）

> 2026-08-14 | 服务：15 微服务 + 中间件 8 组件（对方机 21.130.247.89）+ 本机容器
> 依据：方法论 REVIEW-METHODOLOGY.md（三层验证法——本组全部 L2 实测）；README §五 控制台观察清单
> 目的：**基础设施完整性与链路打通验证**——不是业务功能，是"指标/日志/链路三支柱 + 运维面板"的可观测性闭环

## 基础设施全景（2026-08-14 探测实证）

### 中间件机 21.130.247.89（对方管理，无 docker 权限——探测+配置交付）
| 组件 | 端点 | 状态 | 数据验证 |
|---|---|---|---|
| SkyWalking UI/OAP | 8080（GraphQL）| ✅ 200 | ES 19201 存储：sw_segment-20260814=**411k**、sw_metrics=**2.1M** |
| Prometheus | 19090 | ✅ 302→UI | **23 targets 全 UP**、1757 指标名 |
| Grafana | 13000（admin/Xhs@2026#Admin）| ✅ 302 | **1 Prometheus 数据源 + 10 看板** |
| Kibana | 15601（elastic/Xhs@2026#Elastic）| ✅ 302 | myxhs-logs-2026.08.14=**114k** 日志 |
| RocketMQ Dashboard | 18081 | ✅ 200 | **登录 403（#14 教训）**——仅观测 |
| XXL-Job Admin | 18080/xxl-job-admin | ✅ 200（根 404 正常）| 21 任务 ON（运行库实证）|
| Sentinel | 8858 | ✅ 200 | gateway 限流规则加载 |
| Nacos | 18848 | ✅（API）| my-xhs namespace、15 服务注册 |
| Logstash | 15044（TCP input）| ✅ TCP OPEN | 日志推送链路正常 |
| Redis | 6379 主/6380 从/26379 哨兵 | ✅ | AOF+noeviction |
| MySQL | 3306 主/3307 从 | ✅ | 读写分离 |
| Canal | 11111（服务）/11112（metrics）| ✅（Prometheus up）| note/product/inventory 三实例 |
| ES | 19200 业务/19201 SW | ✅ | 索引齐全 |

### 微服务侧（本机 21.214.97.212，15 个）
- 全部 /actuator/health UP + /actuator/prometheus 暴露（mbeanregistry.enabled=true 实证）
- Prometheus job `my-xhs-services` 15 targets（19000-19016）全 UP
- 日志：/logs/my-xhs-{服务}.json（每行 JSON：@timestamp/APP_NAME/level/message/traceId/userId）→ Logstash TCP 15044 → ES myxhs-logs-*

## 业务指标矩阵（代码实证 2026-08-14，Prometheus 实测）
| 指标 | 来源 | 实测值 | 说明 |
|---|---|---|---|
| feed_push_total / feed_push_latency_seconds* | search/home（recordFeedPush）| **226** | Feed 推送/搜索计数 |
| orders_create_latency_seconds* | order | **48** | 下单延迟（percentiles-histogram 开桶）|
| mq_consume_total | common MqTraceHelper | **43** | MQ 消费计数 |
| inventory_prededuct_total / _latency_seconds* | inventory | **3** | 预扣延迟 |
| coupon_action_total | coupon | **0**（无近期活动）| 券动作 |
| inventory_action_total | inventory | 有 | 库存动作 |
| myxhs_mq_dlq_total / myxhs_local_message_dead_letter_count | common | **0** | DLQ/死信监控 |
| myxhs_http_request_duration_seconds* | common AccessLog | 有 | HTTP 延迟（gateway 用 Boot 自带 http_server_requests）|
| http_server_requests_seconds_count | 各服务 | 212 series | gateway 等（**gateway 无 myxhs_http——T-073 已开 bucket**）|
| jvm_memory_used_bytes / process_uptime_seconds | actuator | 120/15 | JVM 看板数据 |
| mysql_innodb_deadlock* | mysql exporter | 0 | 死锁监控 |

## 链路验证记录（2026-08-14 实测，全链路打通）

### L2-1 指标链路（请求→Prometheus）
- 基线：gateway http_server_requests count=4794
- 触发：GET /api/search/note（经 gateway）
- 结果：**4794→4796（+2）**——请求指标实时入 Prometheus ✅
- 抓取：15 服务 /actuator/prometheus 全被 my-xhs-services job 抓取（interval 15s 默认）

### L2-2 链路追踪（请求→SkyWalking）
- 基线：sw_segment-20260814=411415
- 触发：同上搜索请求
- 结果：**411415→411503（+88 segment）**——gateway/search 及内部调用全进 SW ✅
- SW 服务聚合：**15 服务全有**（order 138k 最高/home 6.6k 最低）
- **⚠️ T-099 观察项**：SW trace_id（UUID.段号.span 格式）与业务日志 traceId（gateway X-Trace-Id UUID）**是两套体系**——Kibana 日志 traceId 无法直接跳到 SW trace（未打通传播）

### L2-3 日志链路（请求→Logstash→ES）
- 触发：GET /api/search/note（traceId=a836004bccca4d85acfd9fd96e9fa6ad）
- 结果：ES `q=traceId:"a836..."` **命中 4 条**：gateway 鉴权通过×2 + search ACCESS×1 + gateway 完成×1——**跨服务同一 traceId 日志关联** ✅
- 本地：/logs/my-xhs-search.json 每行 JSON（traceId/userId 实证）
- 延迟：Logstash 推送秒级（sleep 3-6s 可查）

### L2-4 看板数据（Grafana）
- 10 看板：Elasticsearch/HikariCP/JVM/MySQL/Redis/RocketMQ/Tomcat/业务指标/主机/接口
- 数据源：Prometheus（127.0.0.1:19090——容器内地址，看板可查）
- 验证方式：Grafana API 查看板 JSON（dashboard slug）+ Prometheus 指标源核对

## 用例清单（G8-observability，验证型——非业务断言）

### G8-01 Prometheus 抓取完整性
- **L2**：23 targets 全 UP（canal/elasticsearch/15 服务/mysql×2/node/prometheus/redis/skywalking-oap）
- **L2**：每服务抓取指标含 jvm_/process_/http_server_requests/myxhs_http（15 服务抽查 3 个）

### G8-02 业务指标出数
- **L2**：feed_push_total≥0、orders_create_latency_seconds_bucket 存在（histogram 桶实证）、mq_consume_total≥0、DLQ=0
- **联动**：触发一次下单/搜索 → 对应指标 +1（15s 抓取窗口）

### G8-03 SkyWalking 链路完整性
- **L2**：15 服务 segment 全有（service_id base64 解码=服务名）；实时请求产生 segment（基线对比）
- **L2**：segment 含 latency/is_error/start_time 字段（可查）

### G8-04 日志聚合链路
- **L2**：新请求 traceId → ES myxhs-logs-* 命中 ≥2 条（gateway+下游）——跨服务关联
- **L2**：日志字段（APP_NAME/message/level/traceId/userId）完整
- **L2**：Kibana 索引 pattern 存在（myxhs-logs-*）

### G8-05 Grafana 看板
- **L2**：10 看板 + Prometheus 数据源；抽样看板数据可查（API 或指标源核对）

### G8-06 基础设施健康
- **L2**：8 组件端点可达；xxl 21 任务 ON；Sentinel gateway 规则加载；Nacos 15 服务注册；RocketMQ topic 存在

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G8-01 Prometheus 抓取 | 20:10 | ✅ | 23 targets 全 UP（canal 11112 metrics 实证——11111 不可达为端口认知错误）；1757 指标 |
| G8-02 业务指标联动 | 20:15 | ✅ | 触发搜索 → feed_push 227→228、search http 1338→1341（15s 抓取窗口）|
| G8-03 SkyWalking | 20:12 | ✅ | 15 服务 segment 全有（order 138k~home 6.6k）；is_error 分布 409656 正常/2545 错误（order 1117/coupon 777/gateway 300——历史测试负面用例痕迹）；实时请求 +88 segment |
| G8-04 日志链路 | 20:06 | ✅ | traceId 跨服务关联（gateway×3+search×1 命中）；字段完整；本地 JSON→Logstash 15044→ES 秒级 |
| G8-05 Grafana | 20:18 | ✅ | 10 看板 slug 全确认（含业务指标看板）+ Prometheus 数据源 |
| G8-06 基础设施健康 | 20:00 | ✅ | 8 组件端点可达；xxl 21 任务 ON；Nacos 15 服务；Sentinel gateway 规则 |

## 已知观察项（T- 系列）
- **T-099【观察】SW traceId 与业务日志 traceId 两套体系未打通**：日志按 traceId 可跨服务关联（gateway 传播），但无法关联到 SkyWalking trace（SW 自生成 ID）——建议：SW agent 配置业务 traceId 传播（sw8 header 或日志 traceId 插件）或接受现状（两套独立排障）
- RocketMQ Dashboard 登录 403（环境事实 #14）——投递类操作不可用，观测类可用
- gateway 无 myxhs_http 指标（用 Boot http_server_requests + bucket 开）——Grafana api-monitor 已正则覆盖
