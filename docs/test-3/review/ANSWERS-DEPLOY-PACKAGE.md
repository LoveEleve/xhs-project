# 部署包问题回复（QUESTIONS-FOR-REVIEW.md 逐条答复 · 最终版）

> 2026-08-13 | 依据：zip 解包核实 + 本地 master 配置比对 + 运行态验证 | 状态：A/B/C 全部落实

## A. SkyWalking 链路数据层

### A1. 旧端口虚拟节点 → 历史数据；26379 为现行端口（误判）
- **当前微服务配置无任何旧端口**：grep 13306/13307/13308/13309/13310/13311/13313/16379 于全部 15 服务配置 = **0 命中**
- 13306~13313、16379 虚拟节点 = **SkyWalking 历史 trace 推断数据**（VIRTUAL_DATABASE/CACHE 类型，按调用链推断生成，非当前连接）。当前实际：MySQL 3306/3307、Redis 主 6379/从 6380
- **26379 是现行 Sentinel 管理端口**（15 处配置引用 + start-all.sh 注释"恢复 Sentinel 模式"，主节点 6379）——**并非旧端口，请勿按旧端口处置**
- 处置：已随 A4 索引清理一并清除（旧虚拟节点数据源已删除）

### A2. localhost:-1 / Redis-local → 历史数据
- 该节点在 **2026-08-11 review 快照**（production-env-config/docker-container-review-20260811）已存在，非本轮新增
- 当前配置 grep `localhost`（datasource/Redis）= 0 命中——无服务写 localhost 地址
- 处置：已随 A4 清理

### A3. order/payment 裸名实例 → 非我方产生
- 我方 start-all.sh 统一 `SW_PLACEHOLDER → my-xhs-{module}`（15 服务全带前缀），配置中无任何裸名"order"/"payment"（grep 实证）
- **请对方试验机自查**：大概率旧 jar / 旧启动参数（缺 `-Dskywalking.agent.service_name=my-xhs-xxx`）的残留实例
- 处置：① 试验机清理旧实例；② 裸名服务数据已随 A4 索引清理删除（新拓扑不会再生）

### A4. 历史 sw_* 索引 → ✅ 已清理（2026-08-13 手动执行）
- 删除 SW-ES(19201) 全部 23 个 sw_* 索引（sw_metrics ~940 万条 / sw_segment ~100 万条 / sw_records / sw_log / sw_zipkin_span / sw_management）
- **验证**：索引按模板自动重建，新 trace 正常写入（15min 内 sw_segment 409 条、sw_metrics 2694 条，10% 采样率生效）；新拓扑仅含当前 15 个 my-xhs-* 服务
- 对方若另有 SW 实例，按同法清理即可

### A5. 动态桶 → 非异常，Micrometer percentile histogram 标准行为
- `ApiMetricsFilter` 明确 `.publishPercentileHistogram()`（P50/90/95/99）——动态桶（0.001048576=2^-20 系列指数桶）是 **publishPercentileHistogram 的标准行为**，非"错误公式"
- **按 le="0.5" 查询恒空是预期的**（动态桶无固定 0.5 边界）；P95/P99 必须用 `histogram_quantile(0.95, rate(bucket[1m]))`
- 我方 api-monitor 看板 P99/P95 面板已用 histogram_quantile；并发现 1 处遗漏（"慢请求(P95>500ms)"面板用 le="0.5"）→ **已修复为 histogram_quantile**
- **建议**：对方看板/告警统一用 histogram_quantile，勿按固定 le 查询

### A6. canal example 噪音 → ✅ 已同步 master
- zip 的 prometheus.yml `metric_relabel_configs`（destination="example" drop）**已同步**至本地 config/prometheus/prometheus.yml

## B. 部署配置

### B1. 修复清单同步状态（zip vs 本地 master 逐项核对）
| 项 | 状态 |
|---|---|
| 1) broker.conf 注释规范 | ✅ 已同步（T-025 已修，行内注释清零）|
| 2) compose 8 处（canal 依赖/镜像/端口/密码）| ✅ 内容 diff=0 完全一致 |
| 3) mysqld-exporter-slave(9105) | ✅ 27 容器含 |
| 4) node-exporter textfile 脚本 | ✅ rocketmq-metrics.sh + mysql-deadlock-metrics.sh 均在本仓库 config/deploy-cloud/ |
| 5) Grafana 看板 | ✅ 本轮补同步：node-monitor 14→33 面板、es/redis/tomcat-monitor 表达式优化、${DS_PROMETHEUS} 替换与双列布局；api-monitor 保留本地版（多"慢请求"面板，le 查询已修）|
| 6) OAP log4j2 netty 降噪 | ✅ 本轮补同步 |
| 7) innodb_print_all_deadlocks | ✅ compose mysql command `--innodb-print-all-deadlocks=ON`（zip 138 行实证）|

### B2. 监控缺口 → 决策已定
1. RocketMQ textfile 方案：**认可**（官方镜像无 metrics 模块 + exporter 客户端不兼容 5.1.4 均已实证），进云主机部署
2. 告警通知渠道（webhook）：**业务确认暂不启用**，保持占位
3. 备份 cron（02:00/保留 7 天）：**业务确认维持默认**

### B3. Tomcat 线程池监控 → ✅ 已全量开启并验证
- **配置**：14 个 Servlet 服务 yml 固化 `server.tomcat.mbeanregistry.enabled: true`（gateway 为 WebFlux 无 Tomcat，故 14 个）+ 启动脚本三处追加 `-Dserver.tomcat.mbeanregistry.enabled=true`（本机即时生效）
- **验证**：15/15 服务 UP；**14/14 服务 /actuator/prometheus 均暴露 tomcat_threads 指标**（busy/max/current 等 9 个）
- tomcat-monitor 看板线程池面板可直接使用；另含连接池面板（hikaricp）

## C. 环境遗留

### C1. broker-slave.conf → 请对方从部署包删除
- 本仓库 master **已删除**（无任何容器引用，避免"有主从"误导）；**对方 zip 中仍存在**，请从部署包移除
- 旧孤儿卷：列入运维清理计划（无害，占磁盘）

### C2. filebeat/容器数 → 一致
- filebeat 已移除（微服务日志经 TCP 15044 直推 Logstash）；27 容器 = 25 基线 + node-exporter + mysqld-exporter-slave，与本地一致

## 对方侧待办（请对方执行）
1. **zip 删除 config/rocketmq/broker-slave.conf**
2. **试验机自查** order/payment 裸名实例（旧启动参数残留），清理后新拓扑自动收敛
3. 如需同步最新 master 配置：本轮新增/变更文件清单——
   - config/prometheus/prometheus.yml（canal example drop）
   - config/skywalking/log4j2.xml（netty 降噪）
   - config/grafana/provisioning/dashboards/json/{node,es,redis,tomcat,api}-monitor.json（33 面板/表达式优化/le 修复）
   - 微服务：14 服务 application.yml（mbeanregistry.enabled: true）+ start-all.sh（JAVA_OPTS_BASE/HEAVY/ANALYTICS 追加 -Dserver.tomcat.mbeanregistry.enabled=true）

## 补充修复（2026-08-13：慢查询日志阈值）
- **核实修正**：compose mysql 主/从**均已开 `--slow-query-log=1`**（此前 grep 用下划线漏匹配连字符格式）——但**主库未设 long-query-time（默认 10s）**，文档"≥0.5s 已开"不成立（只开了开关）
- **修复**：主库补 `--long-query-time=0.5`；从库原有 `--long-query-time=1` 保留（只读复制流量，1s 合理）
- **zip 已同步更新**（docker-compose.yml 34246B，解包验证 2 处阈值）
- **对方生效**：试验机/云主机 `docker compose up -d mysql mysql-slave`（或 restart）后生效；生效验证：`SHOW VARIABLES LIKE 'slow_query_log'/'long_query_time'` = ON/0.500000

## 技术待办处理（2026-08-13，用户指示全修+小心原则）
1. **slow log 路径确定化（✅ 已修）**：主/从 `--slow-query-log-file=/var/lib/mysql/slow.log`（datadir 卷内，零权限风险）；阈值主 0.5s/从 1s（上轮）
2. **DLQ 死信监控（✅ 已修）**：rocketmq-metrics.sh 新增 `rocketmq_dlq_topics`/`rocketmq_retry_topics`（topicList 一次输出复用，原仅总数）；bash -n 校验通过
3. **MySQL error log（✅ 保持现状，不采集）**：默认 stderr→docker logs 可见；显式文件会破坏 docker logs 可见性——保守不动
4. **slow log 进 ES 管道（⏳ 待办，云主机部署时接入）**：涉及 logstash 重启+multiline+挂载权限，试验机不做（避免对方环境引入问题）；云主机部署时有环境可 L2 验证
5. **zip 重建（✅）**：修复重复条目与 `../` 坏路径（重建 331 文件，无重复/无坏路径，关键文件校验通过）
6. **行为上报端点路径**：实测为 `/api/recommend/behavior`（非 /api/search/behavior）
- **t_user_behavior P1 修复**（前置完成）：表建错库（analytics vs search 写 content）→ content 建表+删空表+init-all.sql 修正+运行态验证落库 ✓；行为链路（API→MQ→落库）恢复；RecommendComputeJob 不再降级跳过
- 生效说明（对方）：docker-compose.yml/rocketmq-metrics.sh/init-all.sql 三文件更新（zip 已含），试验机需替换后重启 mysql 容器（slow log 路径）+ 重挂 textfile cron（脚本）

## Micrometer Timer bucket 规律核查与处置（2026-08-13）
- **规律确认（运行态实证）**：默认 Timer 只有 _count/_sum/_max（无 _bucket）；运行态唯一带 _bucket 的 Timer 族 = myxhs_http_request_duration_seconds（业务已开 publishPercentileHistogram）——`histogram_quantile` 对无 bucket 指标恒空
- **踩坑修复**：tomcat-monitor.json 2 处 hikaricp histogram_quantile（对方 zip 版带进来）→ 改 `_max` 近似（面板标注"P99 待开 percentileHistogram"）
- **配套开启**：14 个 Servlet 服务 yml 固化 `management.metrics.distribution.percentiles-histogram.hikaricp.connections.{acquire,usage,creation}: true`（云主机构建生效；content 本机已重启验证 **138 个 bucket 出现**，histogram_quantile 可用）
- **告警规则核查**：2 处 histogram_quantile 均用 myxhs_http_request_duration_seconds_bucket（有 bucket，无坑）
- zip 已更新（tomcat-monitor.json）

## JVM/Tomcat/Hikari 看板完善（2026-08-13，用户审查反馈）
- **jvm-monitor 32→33**："内存池明细(分代)" 查询修正（`sum by (id)` 按 Eden/Survivor/Old 展开，原只按 area 合计=假分代）+ 新增 Non-Heap 池明细（CodeCache/Metaspace/CompressedClass）
- **tomcat-monitor 8→9**：
  - 新增 **Tomcat 工作线程面板**（tomcat_threads busy/current/max——mbeanregistry 已开的指标终于配套，B3 闭环）
  - hikaricp 耗时面板改**自适应**：`histogram_quantile(...) or max(...)`——有 bucket（percentileHistogram 已开）显示 P99/P95，无 bucket 回退 max（不再恒空/恒近似）
  - **Prometheus 实测**：content 连接获取 P99 = 0.99ms 出数 ✓
- zip 已更新（jvm/tomcat 两看板）

## 全量看板深度 REVIEW（2026-08-13 第三轮，147 表达式逐一 Prometheus 实测）
- **验证方法**：提取全部 9 看板 147 个面板表达式 → 指标名逐一 Prometheus 实测存在性 + target 健康检查（22 target 全 up）
- **结果**：全部指标存在（此前"缺失"为提取正则假阴性，逐一复核确认：mysql/node/es/rocketmq textfile 均正常出数）
- **抓到 2 个真 bug（均修复）**：
  1. Tomcat 线程面板指标名：`tomcat_threads_busy`（不存在）→ `tomcat_threads_busy_threads`；`max_threads`（不存在）→ `tomcat_threads_config_max_threads`（Micrometer 实际指标族实证：busy_threads/current_threads/config_max_threads）
  2. hikaricp usage 面板 P95 与 acquire P99 不一致 → 统一 P99
- **hikaricp 补齐（tomcat-monitor 8→11 面板）**：连接创建耗时 P99（histogram_quantile or max）、连接池使用率+min、统一 P99
- **配套确认**：Prometheus 42 个 tomcat_threads 序列（14 服务全有——start-all.sh -D 全量生效）；rocketmq_dlq_backlog 322 序列（#52 修复生效）；myxhs_mq_dlq_total 14 序列

## 看板深度 REVIEW 第二轮（2026-08-13，PromQL 全量执行 + 源码级实证）
- **PromQL 全量执行**：147 表达式逐一替换变量后真实查询——0 语法错误、137 有数据；api-monitor 8 个 EMPTY 定性为**测试环境 5 分钟窗口无业务流量**（非 bug，gateway 有流量即有数据）
- **源码级实证（不再猜）**：Boot 3.5.13 无 ProcessMetricsAutoConfiguration/JvmDeadlockMetrics；micrometer-core 1.12.5 无 ProcessMetrics/JvmDeadlockMetrics → **3 个指标在本版本不可开启**（enable 配置无效）：
  - `jvm_threads_deadlocked`（死锁线程数）、`process_resident_memory_bytes`（RSS）、`process_cpu_seconds_total`（累计 CPU）→ **jvm-monitor 删除 3 个不可用面板**（30→30，原 33 面板：+Non-Heap -3 +缓冲修正）
  - **回滚 14 服务 yml 的 enable 配置**（无效配置移除，避免误导）
- **真 bug 修复**：缓冲区面板指标名 `jvm_buffer_pool_used_bytes`（不存在）→ `jvm_buffer_memory_used_bytes`（1.12.5 实际指标名）
- **如需这 3 个指标**：需升级 micrometer（≥1.13 有 ProcessMetrics，但 Boot 3.5 也不自动注册死锁）或自定义 Binder——标注待办，不升级（依赖风险）
- zip 已更新（jvm-monitor.json）

## JVM 死锁监控补齐（2026-08-13，用户确认）
- **方案**：micrometer 1.12.5 无内置 JvmDeadlockMetrics → common 新增自定义 `JvmDeadlockMetricsBinder`（MeterBinder + ThreadMXBean.findDeadlockedThreads，异常兜底 0 + 竞争监测不支持告警一次）
- **构建**：common install 后 **14 服务 rm -rf target 全量重打包**（#80 规范；第一轮 install 静默失败导致 jar 内旧 common——复查 BUILD SUCCESS 后重打包）
- **重启**：start-all.sh 全量重启（14/15 起，analytics 老问题手动启动恢复）——**验证 14/14 服务 jvm_threads_deadlocked 指标生效，Prometheus 16 序列值=0（无死锁）**
- **看板**：jvm-monitor 恢复"死锁线程数"面板（31 面板）
- **已知问题（记录）**：start-all.sh 经 `setsid bash -c` 启动时 analytics 的 `-Dmanagement.admin-token=${ADMIN_TOKEN}` 偶发展开失败（Could not resolve placeholder）——手动启动可靠；根治待后续（start-all.sh 内 eval 求值）
- **验证方法教训**：外层 unzip -l 看不到 BOOT-INF/lib 嵌套 jar 内部类——验证 common 变更需解压嵌套 jar（#80 补充）

## Tomcat/HikariCP 看板拆分（2026-08-13，采纳边界清晰方案）
- **背景**：tomcat-monitor 为混合看板（hikaricp 连接池 + Tomcat 线程），pool 下拉对 Tomcat 面板无意义（对方方案主张拆分）
- **拆分结果**：
  - **tomcat-monitor（4 面板）**：Tomcat 线程（busy/current/config_max）+ HTTP QPS + 5xx + 延迟 P95/P99；**删 pool 变量**；application 变量改用 `label_values(tomcat_threads_current_threads, application)`（原 hikaricp——Tomcat 域语义修正）
  - **hikaricp-monitor.json（新建，10 面板）**：active/max/idle/pending/acquire P99/usage P99/creation P99/获取速率/超时速率/使用率+min；**仅 application 变量**（pool 下拉移除），面板 legend 显示 `{{application}}/{{pool}}`（选服务可见 HikariPool-1/2 两条线）
- **验证**：两看板全部 PromQL 执行 success（content 实测）
- 看板总数 9→10（provisioning 自动加载）；zip 已更新

## 看板深度 REVIEW 第三轮（2026-08-13，全量执行+label 核验）——抓到一个 P1
- **审查方法**：10 看板全面板：PromQL 真实执行 + **application label 存在性核验** + 幽灵变量 + 布局/重复 + counter 语义
- **P1：gateway 指标无 application 标签**——根因：gateway **不依赖 common 模块**（独立实现）→ MetricsAutoConfiguration 未加载 → 全部指标无 application label → **所有 $application 面板看不到网关流量**（入口流量监控缺失）
  - 修复：gateway 模块内新增 `GatewayMetricsConfig`（MeterRegistryCustomizer 注入 application=my-xhs-gateway）
  - 验证：gateway 指标带 application 标签 ✓；api QPS 6 序列出数 ✓
- **P1 延伸：gateway 无 myxhs_http 指标（common 的 ApiMetricsFilter 无）→ 延迟面板对 gateway 空**
  - 修复：gateway yml 开 `management.metrics.distribution.percentiles-histogram["http.server.requests"]: true`（138 bucket）；api/tomcat 看板延迟面板指标改正则 `{__name__=~"myxhs_http_request_duration_seconds_bucket|http_server_requests_seconds_bucket"}`（14 服务+gateway 统一覆盖）
  - 验证：gateway P99 = 0.044s 出数 ✓
- **确认无误**：application label 14 服务+gateway 全有；幽灵变量无；counter rate 语义全部正确（前两轮误报为脚本提取函数名 bug，修正后复验）
- 审查脚本教训：指标提取须排除函数名（rate/sum/by/id 等），否则假阳性
- zip 已更新（api/tomcat 看板）

## 业务指标看板重建（2026-08-13，用户质疑"全是替代xxx"）
- **原状**：biz-metrics 5 面板全是"替代未埋点xxx"的补救标题 + 1 处重复（MQ 消费速率×2）+ 1 处与 api-monitor 重复（接口 QPS）
- **重建（5→11 面板，全部真实业务埋点）**：
  - 订单创建/支付/超时关单、支付回调、券动作(by action)、库存动作/预扣、Feed 推送、MQ 消费(by topic)、MQ 消费结果(by result)、DLQ 积压
  - 业务延迟 P99×3（订单创建/Feed 推送/预扣）——`histogram_quantile or max` 自适应
- **配套**：14 服务 yml 为 3 个业务延迟 Timer 开 percentileHistogram（feed.push.latency/orders.create.latency/inventory.prededuct.latency）——content 重打包验证 **69 bucket/指标** ✓；其余服务下次部署生效（max 兜底）
- 指标名实证：BusinessMetrics 全量（coupon.action.total/mq.consume.total/orders.created.total/orders.paid.total/orders.timeout.closed/payment.callback.total/inventory.action.total/inventory.prededuct.total/feed.push.total/myxhs.mq.dlq.total + 3 latency Timer）
- zip 已更新（biz-metrics.json）

## start-all.sh 启动慢/卡死彻底修复（2026-08-13，根因链闭环）
- **现象**：每次全量启动"几十分钟"，期间服务其实 2 分钟内就 UP 了
- **根因链（逐个定位修复）**：
  1. **JAVA_OPTS_ANALYTICS 引号坏**（历史 python 修复 line[:-1] 吞掉引号）→ `VAR="..." -Dxxx` 语法错误 → 变量空 → analytics 无 admin-token 启动失败 → 60s 空等 + 每次手动补救（10-20 分钟主要来源）→ **修复引号闭合**
  2. **JAVA_OPTS_HEAVY 引号外残留**（mbeanregistry 重复在引号外）→ command not found 噪音 → **修复**
  3. **末尾 `wait` 卡住**（gateway 前台调用后的多余 wait 永不返回）→ 脚本"永不完成"假象 → **删除**
  4. **curl 健康检查/状态循环无 --max-time**（服务 UP 但响应慢 → 挂起）→ **两处加 --max-time 2**
  5. **操作层自匹配**：pgrep/pkill -f 匹配自身命令行杀 shell → 用 [b]/[s] 括号技巧
- **验证**：冷启动 **95 秒** 全部 15 服务 UP + 脚本正常退出（✅15 / ⚠️0）
- **启动耗时结论**：正常 ~95s（15 JVM 分组并行 10-16s/服务）；此前"几十分钟"= 失败空等 + 脚本不退出 + 手动补救

## "需对方配合修改业务埋点"说明——已核实，无需对方配合（2026-08-13）
- **对方转述的必须项**：mq_consume_total/myxhs_mq_dlq_total 缺 topic/result/consumerGroup 标签 → 需对方补埋点
- **核实结论：已由我方解决（根因在预注册冲突，非业务代码缺标签）**：
  - 埋点代码本就带完整标签：recordMqConsume(topic, consumerGroup, success)/recordDlqMessage(consumerGroup, topic)（FeedPushConsumer/DlqMessageHandler 实证）
  - 之前 Prometheus 无标签 = 我方 common 预注册无标签 vs 业务带标签同名冲突（业务指标恒 0 的同根因）→ 已修（预注册带业务标签空值）+ **13 服务重打包 v2 common + 全量重启**
  - **运行态验证**：mq_consume_total{topic="FEED_TOPIC", consumerGroup="feed-push-consumer-group", result="success"} = 1 ✓（业务触发即出）；feed_push_total{mode="publish"} = 1 ✓
- **可选项同理无需对方**：orders_created_total（status 标签）/coupon_action_total（action/result）代码已带，业务触发即出；延迟桶为 publishPercentileHistogram 指数桶，P99 可算（非必须）
- **结论**：无需对方改任何业务代码；对方只需同步 master 9 条（§SYNC-NOTES）即可
