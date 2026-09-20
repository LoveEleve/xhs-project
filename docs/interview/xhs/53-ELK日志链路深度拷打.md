# 第53题 | 组件深度拷打：ELK 日志链路（Filebeat / Logstash / Kibana）

> 难度：★★★★☆｜频率：★★★★☆｜区分度：高
> 关键词：filestream、ndjson、grok 提取、日索引、保留双口径、可检索 traceId

## 问题
日志链路怎么搭的？为什么不用 Logstash 直采？日志怎么保留？出过什么问题？

## 面试可讲版（五段式）

**① 原理层**
- **链路分工**：Filebeat（轻量采集，Go，低资源）→ 网络传输 → Logstash（重加工：grok/date/mutate，JVM，可横向扩）→ ES（存储检索）→ Kibana（可视化）；采集与加工分离，采端不阻塞业务；
- **采集模式**：`filestream`（新，文件指纹 + 游标，替代老 log input）；JSON 日志用 **ndjson 解析**（一行一条，天然免多行堆栈处理）；
- **处理链**：`input(json_lines)` → `filter(grok 提取服务名 + date 规范化时间戳 + mutate gsub 凭据脱敏)` → `output(按天索引)`；字段漂移靠 `overwrite_keys/add_error_key`；
- **保留与生命周期**：ILM（热/温/冷/删）是标准做法；没有 ILM 就要靠外部清理（cron 删索引）兜底；
- **背压**：ES 慢 → Logstash 队列 → Filebeat 重试；采端 `harvester` 限速防止打爆磁盘/网络。

**② 项目用法（实配）**
- **Filebeat**：`filestream` 采集 `/logs/*.json`，**ndjson 解析**（`overwrite_keys` + `add_error_key`），输出 `output.logstash: 127.0.0.1:15045`；
- **Logstash**：`input codec=json_lines`；filter 用 **grok 从 `[log][file][path]` 提取服务名**（`/logs/my-xhs-user.json → my-xhs-user`），date 规范化；`output index = myxhs-logs-%{+YYYY.MM.dd}`（**按天索引**，便于删除与查询裁剪）；**mutate gsub 对 `ticket=/token=/password=` 等凭据脱敏**（2026-09-19 review 补，网关侧亦同步脱敏 query）；
- **应用侧日志规范**：Logback JSON encoder（结构化字段：level/logger/traceId/stack_trace），单服务滚动上限 **100MB/文件、7 天、2GB**——**应用本地和 ES 是两级保留**，先保证不把磁盘写爆；
- **存储实况**：`myxhs-logs-*` 单日索引可达 **650 万条 / 1.8GB**（压测日），副本 0（单节点）；
- **保留双口径（诚实边界）**：设计上是 **ILM 30 天策略 + 模板**（`apply-ilm.sh`，P-D14——副本 0/1 分片/30d delete）；**本环境 ILM 策略未启用**，走 `log-cleanup.sh` cron（每日 4:00）删除 **7 天前**的索引 + 文件清理 3 天。

**③ 坑与修复**
1. **yellow 索引**：某日索引 `replicas=1` 在单节点无法分配 → 集群变 yellow、告警触发；已修正并新增 `es-log-index-check.sh` cron 巡检（每天 5:00，发现 replicas≠0 自动修正）——**"模板/ILM 没覆盖到的写入路径"是长期风险**；
2. **保留策略两套并存**：ILM 设计（30d）没启用、cron 实际（7d）——不统一就不知道"日志到底留多久"；回答时要讲清设计口径与运行口径；
3. **日志量与磁盘**：压测日单索引 1.8GB、6.5M 文档；文件侧靠 100MB×7 天×2GB cap + cron，ES 侧靠删索引；容量治理要**两头看**；
4. **时间戳/时区**：Logstash date filter 与 ES `@timestamp` 的时区处理不当会让"按天索引"错位；统一 UTC/Asia 口径；
5. **多行堆栈**：JSON 日志把 `stack_trace` 作为字段最省事；纯文本日志才需要 multiline 配置（容易采歪）。

**④ 兜底**
- 索引巡检 cron（副本修正）+ 清理 cron（保留控制）；
- `traceId` 是 JSON 字段 → ES 可直接按 traceId 检索全链路日志（16 题，含真实问题单处理）；
- 应用本地日志兜底 3 天：ES 链路故障时排查不中断。

**⑤ 拷打追问**
1. **"为什么 Filebeat + Logstash 两层？"** 采集轻量（业务机/共享盘低开销）+ 加工可扩展（grok/date/改字段）；也可以 Filebeat 直出 ES（processor 处理），但复杂加工放 Logstash 更稳。
2. **"为什么不用 Loki？"** Loki 索引 label 省存储，但本项目已按 ES 技能栈建设（且 ES 同时承载搜索业务）；Loki 适合"只查不聚合"的场景，选型上不是不能换。
3. **"日志怎么保证不丢？"** Filebeat 游标 + at-least-once（可能重复，靠 id 去重即可）；Logstash 队列/重试；应用本地文件是最终兜底。
4. **"ES 索引为什么按天？"** 删除/rollover 简单、查询能裁剪到具体日期；缺点是跨天查询要 index pattern。
5. **"高流量日志怎么优化？"** 采端过滤（只采需要级别/字段）、采样（debug 不落 ES）、Logstash 横向扩容、ES 冷热分层。
6. **"日志格式怎么定的？"** JSON 结构化 + 必带 traceId/服务名/级别/时间戳；禁止打印敏感信息（脱敏在应用侧完成）。

**⑥ 话术**
> "日志链路是 Filebeat 采、Logstash 加工、ES 存、Kibana 查：Filebeat 用 filestream 采 JSON 文件、ndjson 解析，Logstash 从文件路径 grok 出服务名然后按天写索引。应用侧日志是结构化 JSON，traceId 直接可检索，本地还留 3 到 7 天做兜底。保留这块我会如实说双口径：设计是 ILM 30 天，当前环境实际是清理脚本 7 天，今天还发现一个 replicas=1 的漏网索引把集群搞 yellow，修了之后加了巡检 cron。压测日单索引 650 万条 1.8G，日志治理必须应用本地和 ES 两头卡。"

## 发散追问地图（横向）
- 采集：Filebeat/Fluent Bit/Vector、filestream、backpressure。
- 加工：grok/date/mutate、ECS 字段规范、脱敏。
- 存储：索引设计、ILM、rollover、冷热分层、快照。
- 查询：KQL/Lucene、traceId 关联、看板（Kibana/Grafana）。
- 替代：Loki/ClickHouse/SLS 对比。

## 面试官评分点
**高级开发级**：能讲链路分工、采集/加工/索引/保留。
**架构师加分**：保留双口径的诚实表达；"模板/ILM 未覆盖写入路径"的巡检机制；日志两级保留与容量治理；JSON 结构化与 traceId 可检索的联动。
**危险信号**：采集直写 ES 无背压；日志无保留上限；把 ILM 当已启用；堆栈多行采集全靠 multiline 硬调。

## 本项目真实证据
- `config/filebeat/filebeat.yml`（filestream/ndjson/output.logstash 15045）；
- `config/logstash/logstash.conf`（json_lines/grok 服务名/date/按天索引）；
- 应用 logback JSON（100MB/7 天/2GB cap）；`apply-ilm.sh`（设计 ILM 30d）与 `scripts/log-cleanup.sh`（环境 7d）、`scripts/es-log-index-check.sh`（副本巡检）；
- ES 运行态：`myxhs-logs-*` 单日 6.5M docs/1.8GB；yellow 索引修复记录（42 题）。

## 版本与来源
Filebeat/Logstash/ES 官方文档；本项目 ELK 配置与清理脚本。

## 真实性说明
配置/保留天数/巡检与清理脚本/索引体量均为仓库与运行态事实；ILM 未启用、双口径保留主动披露。

## 本轮补充（2026-09-20 管道与生命周期修复）
- 管道漂移修复：运行时原为 compose 内联 `-e` 管道（**无 filter**）→ 改为挂载仓库 `logstash.conf`（grok/date 真正生效）+ `mutate gsub` 凭据脱敏（ticket/token/password），ES 实测脱敏、明文 0 命中。
- 网关访问日志 query 脱敏（SSE ticket → `ticket=***`）。
- 生命周期：ES ILM 此前**从未 apply**（`managed=False`）→ 已建 policy/模板并挂存量索引；每日 4:00 脚本清理因 cron 进程重启窗口未执行 → 手动清 2 个老索引(1.8G)+45 文件，此后自动。
