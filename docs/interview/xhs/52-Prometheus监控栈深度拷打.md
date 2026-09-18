# 第52题 | 组件深度拷打：Prometheus / Grafana / Alertmanager

> 难度：★★★★☆｜频率：★★★★★｜区分度：高
> 关键词：拉模型、TSDB、PromQL、告警规则、Alertmanager 治理、通知黑洞

## 问题
监控体系怎么搭的？PromQL 怎么用？告警怎么保证不丢不炸？Prometheus 单点怎么办？

## 面试可讲版（五段式）

**① 原理层**
- **拉模型 + 服务发现**：Prometheus 按 `scrape_config` 周期抓取 targets（static/consul/k8s 发现），exporter 暴露 `/metrics`；对比 push（Pushgateway）适合短任务；
- **TSDB**：时间序列 = 指标名 + labels；样本压缩按 2h block 落盘（chunks + index），后台 compaction；retention 按时间/空间；
- **PromQL**：`rate()` 用计数器算 QPS（只配 counter）、`histogram_quantile` 算 P95/P99（直方图桶）、`increase/irate` 的区别、聚合 `by/without`；
- **告警链路**：Prometheus 规则（`for` 持续时长）→ pending → firing → Alertmanager → 路由/分组/抑制/静默 → receiver；
- **Alertmanager 治理**：`group_wait`（首次等待）/`group_interval`/`repeat_interval`；**短命告警会被 group_wait 吞掉**；`send_resolved` 决定恢复通知；
- **长期存储**：remote_write 到 VictoriaMetrics/Thanos 解决本地 retention 有限与单点。

**② 项目用法（运行态实测）**
- **Prometheus v2.48.1**（19090，`--web.enable-lifecycle` 已开——热加载可用）；**9 个 scrape job**：15 个服务 + 中间件（MySQL 主从/Redis/ES）+ xhs-ai 回环（127.0.0.1:19020）；
- **告警规则：9 个规则组 / 40 条规则**（分级 severity=critical/warning；应用 6 + 业务/黄金信号/DLQ/Meta 等）；
- **Alertmanager（19093）**：receiver=`default-webhook` → 本地告警落地器 **alert-sink（127.0.0.1:19099）**，`send_resolved: true`（恢复也通知），落盘 `/data2/logs/alerts.jsonl`；
- **exporter 矩阵**：node-exporter（主机）、mysql-exporter ×2（主/从）、redis-exporter、es-exporter；Grafana 看板（数据源指向 Prometheus）；
- **VictoriaMetrics** 容器作为 AI 诊断的指标查询后端（DIAG-10 场景的数据源）；
- **SLO 落地**：错误预算（30 天 ≈43m12s）+ Burn Ledger（38 题）。

**③ 坑与事故（全是真实治理）**
1. **通知黑洞**：早期 Alertmanager receiver 空/占位地址——告警 firing 了但没人收到；修复=接 alert-sink + **端到端演练验证**（人为触发→落盘→恢复通知）；
2. **短命告警被吞**：`group_wait` 窗口内 firing→resolved 的抖动告警可能只剩 resolved；用 `keep_firing_for` 让告警必须持续一定时间才发（38 题）；
3. **规则文件加载**：rule_files 挂载路径/语法错误会导致**整组规则静默失效**；本项目用 `promtool check rules` 思路校准 + 版本管理；
4. **单点**：Prometheus 单实例——挂了监控盲区；生产方案是双实例+Thanos/VM + 外部告警 Watchdog（38 题"监控的监控"）；
5. **高基数**：label 用 traceId/userId 会打爆 TSDB——本项目 label 控制在服务/接口/状态码维度。

**④ 兜底**
- Watchdog 规则（always firing）验证告警链路本身活着；
- alert-sink 落盘作为告警审计与丢失兜底；
- 指标兜底：VictoriaMetrics（远期存储）+ 应用侧 `/actuator/prometheus`。

**⑤ 拷打追问**
1. **"拉 vs 推？"** 拉模型让服务端控制节奏、天然做 target 健康（up 指标）；短生命周期任务用 Pushgateway；本项目全是长驻服务，拉更合适。
2. **"P99 怎么算？"** Histogram 桶 `histogram_quantile(0.99, rate(...))`；桶设计要与 SLO 量级匹配，否则插值误差大。
3. **"counter 和 gauge 用错会怎样？"** counter 重置/回绕要用 `rate()` 处理；gauge 直接看值；错误配 `rate(gauge)` 会出假峰。
4. **"告警风暴怎么防？"** 分组（group_by 服务/告警名）+ 抑制（inhibit：主机挂了抑制其上服务告警）+ 静默（维护窗口）+ severity 路由。
5. **"规则改了怎么生效？"** `--web.enable-lifecycle` 下 `POST /-/reload`；否则要重启；配置进 git。
6. **"Prometheus 怎么高可用？"** 双实例同配置（各自抓取，查询层去重）或 remote_write 到 VM/Thanos 做全局视图；告警由 Alertmanager 集群去重。

**⑥ 话术**
> "监控是 Prometheus v2.48 拉模型，9 个 job 覆盖 15 个服务和中间件，Prometheus 加 Alertmanager 加 exporter 一组容器，告警走 Alertmanager webhook 到我们自建的 alert-sink 落盘。最能讲的是治理过程：早期 receiver 是空的黑洞，告警发了没人收；后来接了 sink 还做了端到端演练。短命告警会被 group_wait 吞掉，我们加 keep_firing_for。规则 9 组 40 条、分级路由；另外用 always firing 的 Watchdog 告警监控告警链路本身。单点是已知短板，生产要双实例加 VictoriaMetrics/Thanos 长期存储。"

## 发散追问地图（横向）
- 存储：TSDB 原理、retention、remote write、Thanos/VM/Mimir。
- PromQL：rate/increase/irate、histogram/summary、recording rules。
- 告警：路由树、分组抑制静默、SLO 多窗口燃烧率。
- 生态：exporter 自研、Pushgateway、Grafana 看板即代码。
- 高可用：双实例、联邦、全局视图。

## 面试官评分点
**高级开发级**：能讲拉模型/TSDB/PromQL/告警链路。
**架构师加分**：通知黑洞与短命告警的完整治理；Watchdog 的"元监控"；错误预算落地；单点与长期存储方案。
**危险信号**：counter/gauge 混用；label 塞高基数；告警无分级无抑制；不知道 group_wait 语义。

## 本项目真实证据
- 运行态：Prometheus 9 组/40 条规则（API 实测）、v2.48.1 `--web.enable-lifecycle`、9 scrape job、Alertmanager → alert-sink 19099/send_resolved、exporter 矩阵；
- `config/prometheus/alert_rules/myxhs_rules.yml`、`config/alertmanager/alertmanager.yml`；
- 告警治理与 SLO：38 题（keep_firing_for、错误预算 43m12s、Burn Ledger）。

## 版本与来源
Prometheus/Alertmanager 官方文档；本项目配置与告警 e2e 报告。

## 真实性说明
规则数/端口/receiver/exporter 均为运行态与配置事实；单点无 HA、ILM/retention 等边界已标注。
