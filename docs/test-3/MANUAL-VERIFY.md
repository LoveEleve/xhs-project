# G1 人工验证操作手册（用户执行）

> 面向用户的可操作清单：**打开 → 输入 → 看到什么（含预期数据）**。所有控制台在中间件机（试验）21.130.247.89 或云主机 IP。

---

## 1. SkyWalking 全链路（http://21.130.247.89:8080）

**验证内容：注册/登录/关注请求的跨服务 trace + 异步段**

| 步骤 | 操作 | 预期看到 |
|---|---|---|
| 打开 | http://21.130.247.89:8080（首页"General Service"标签）| 服务列表含 my-xhs-gateway/user/analytics/counter 等 15 个 |
| 看 trace | 左侧列表点最新 trace（或搜索 traceId）| 端到端 span 链：gateway→user→Redis/MySQL |
| 验证异步段（P-T1）| 找一条"登录"或"关注"trace，展开看 span | 主线程 span 下应有**异步子 span**（线程池段不悬空）——P-T1 插件生效的标志 |
| 最近 1 小时 | 顶部时间选"最近 15 分钟" | 有持续的新 trace（请求在产生） |

> 参考数据：G1 测试跑了 200+ 请求，SkyWalking 应有对应数量的 trace（gateway 每请求 1 条主 trace）。

## 2. Kibana 日志（http://21.130.247.89:15601，账号 elastic / Xhs@2026#Elastic）

**验证内容：日志链路（TCP 15044 直推）实时可用**

| 步骤 | 操作 | 预期看到 |
|---|---|---|
| 打开 | http://21.130.247.89:15601 → Discover | 无 security_exception（P-D7 已修）|
| 看索引 | 索引模式 myxhs-logs-* | 最近索引 = 今天（myxhs-logs-2026.08.12）|
| 查日志 | 搜索 `logger_name:"com.myxhs.user.service.CaptchaService"`，时间最近 1 小时 | 多条"验证码 生成成功/校验成功"日志 |
| 查认证 | 搜索 `message:"用户注册成功" OR message:"登录"` | G1 测试期间的用户注册/登录日志 |
| 查安全 | 搜索 `message:"剥离未认证"`（最近 1 小时）| 有（直连端口伪造头被剥离的记录）|
| 关联 traceId | 任意日志展开看字段 | 含 traceId/spanId/userId（O1/O2 MDC 修复生效）|

> 参考数据：G1 测试注册了约 40 个用户（g1r*/g1e* 前缀），每个注册应有"用户注册成功, userId=..."日志。

## 3. Grafana 大盘（http://21.130.247.89:13000，admin / Xhs@2026#Admin）

**验证内容：指标采集与业务指标**

| 步骤 | 操作 | 预期看到 |
|---|---|---|
| 打开 | 登录 → Dashboards | 4 个看板（my-xhs 相关）|
| 看服务指标 | 选"服务概览"类看板 | 15 服务 up（绿点）、QPS 非零（有请求才有）|
| 看业务指标 | 自定义查询或看板面板 | orders_created_total / payment_callback_total 等**系列存在**（P-D42 预注册，即使无事件也有 series）|
| Prometheus 直查（备用）| http://21.130.247.89:19090 → Graph | 输入 `up` 回车：15 个 1；输入 `count(orders_created_total)`：≥1 |

> 参考数据：Prometheus 当前 21 targets（15 微服务+prometheus+redis/es/mysql exporter+canal+OAP）。

## 4. RocketMQ（http://21.130.247.89:18081）

**验证内容：关注链路的 MQ 消息**

| 步骤 | 操作 | 预期看到 |
|---|---|---|
| 打开 | http://21.130.247.89:18081 → Topic | 列表含 SOCIAL_TOPIC / ORDER_CLOSE_TOPIC / FEED_TOPIC 等 |
| 看消息 | 点 SOCIAL_TOPIC → 消息（按时间）| G1-07 关注测试产生的 FOLLOW/UNFOLLOW 消息（Tag=FOLLOW/UNFOLLOW）|
| 看消费 | 消费组页 | counter 服务消费组无积压（消费进度追上）|

> 参考数据：G1-07 执行了约 20 次关注/取关，SOCIAL_TOPIC 应有对应消息。

## 5. XXL-Job（http://21.130.247.89:18080，admin / 123456）

**验证内容：定时任务执行**

| 步骤 | 操作 | 预期看到 |
|---|---|---|
| 打开 | 登录 → 调度管理 | 19 个启用任务（orderCloseJob/cartReconcileJob 等）|
| 看执行日志 | 点 followCounterRepairJob（或任意任务）→ 调度日志 | 最近日志 handle_code=200（G1-07-08 手动触发过）|
| 看执行器 | 执行器管理 | 10 个执行器在线（21.214.97.212:999x）|

## 6. Nacos（http://21.130.247.89:18848，nacos/nacos）

| 步骤 | 操作 | 预期看到 |
|---|---|---|
| 服务列表 | 服务管理 → 服务列表 | 15 个服务各 1 实例（21.214.97.212:19000-19016）|
| 配置列表 | 配置管理 → my-xhs 命名空间 | 3 个配置（my-xhs-common/gateway/redis.yaml）|

## 7. Sentinel（http://21.130.247.89:8858，sentinel/sentinel）

| 步骤 | 操作 | 预期看到 |
|---|---|---|
| 规则 | 左侧各服务 → 流控/降级规则 | 已导入的规则（16 服务 json）|
| 命中 | 实时监控 | G1-07-12 关注限流期间应有拒绝记录（可选）|

---

## 时间窗口建议
- 现在就能看：SkyWalking trace、Kibana 日志、Grafana 指标、RocketMQ 消息、XXL 日志（G1 测试数据都在最近 1 小时内）
- 如果重启过服务，trace/指标从重启后重新累计（日志保留）

---

## 8. SkyWalking 采样率修复后的验证（P-T4/T-024，对方改 OAP 后）

**背景**：原部署包 `SW_TRACE_SAMPLE_RATE: 10`（=0.1%，万分比单位）→ OAP 几乎不采样 → 16:02 后 SW 无新 trace。已改 1000（10%）。

**对方操作**（中间件机）：
```bash
cd /data/workspace/my-xhs-deploy-zip   # 或云主机部署目录
docker compose up -d skywalking-oap    # 用新部署包重建 OAP
docker compose ps | grep skywalking    # 确认 Up (healthy)
```

**生效后你的验证**（本机操作）：
1. 跑一次登录（任意方式，或直接用测试工具）
2. 打开 SkyWalking (8080) → 时间选最近 15 分钟 → 点 my-xhs-gateway → **应有新的 trace 列表**
3. 找 operationName 含 `/api/user/auth/login` 的 trace → 点开 → 瀑布图：
   - gateway 段 → user 段**连成一条**（跨服务）
   - user 段下 Redis/MySQL span 完整
   - 耗时分布合理（BCrypt ~100ms、Redis ~3ms、MySQL ~5ms）
4. 关注链路（可选）：跑一次关注 → SW 里 my-xhs-analytics 服务有新 trace → 瀑布图含 Redis + MQ 消费段

**数据预期**：
- 修复后每次登录/注册/关注都会产生 1 条 trace（10% 采样，10 次请求约 1 条）
- SW 存储每天增长：10% 采样 ≈ 3 万-18 万 segment/天（原 100% 是 30 万-180 万）
