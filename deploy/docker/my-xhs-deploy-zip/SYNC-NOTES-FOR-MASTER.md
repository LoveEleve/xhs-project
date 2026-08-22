# 试验机修复同步清单(请对方 master 仓库同步, 2026-08-13)

对方 2026-08-13 17:13 上传的 zip 与试验机已验证修复存在 8 处差异,
本清单已覆盖回 zip(my-xhs-deploy-package.zip, 17:16, 335 文件), 请 master 同步:

## 看板(6 处)

1. **jvm-monitor.json**: application 变量
   `label_values(up{job="my-xhs-services"}, application)` → `label_values(jvm_memory_used_bytes, application)`
   原因: up 指标无 application 标签, 原写法导致"无法选择应用"(下拉为空)。

2. **redis-monitor.json**: 命中率用累计值 `hits/(hits+misses)` → rate(5m) 速率比值。
   原因: 累计值失真(运行越久越接近 1, 近期 miss 激增看不出来)。

3. **es-monitor.json**: 集群健康"1=green 2=yellow" → color 标签 0/1 三线(green/yellow/red)。
   原因: 指标实际是 elasticsearch_cluster_health_status{color="xxx"} 各一条 0/1,
   原标题映射错误且表达式不带 color 过滤。

4. **api-monitor.json**: 删除"慢请求(P95>500ms 数)"面板(le="0.5")。
   原因: 业务桶无 le="0.5"(指数桶), 面板永远空; P95/P99 已由 histogram_quantile 面板覆盖。

5. **node-monitor.json**: 补 19 个面板(上下文切换/中断/IOPS/吞吐/网络错误/丢包/TCP/文件描述符等), 14→33。

6. **tomcat-monitor.json**: 纯 Tomcat 5 面板(线程/使用率/连接/QPS/Servlet错误),
   删除 pool 变量(池名跨服务重名无意义), application 用 tomcat_threads_current_threads。
   注: 原版"JVM 线程替代 Tomcat"面板误导且重复, 已移除(mbeanregistry 已开, 用真指标)。

7. **hikaricp-monitor.json**(新增): HikariCP 独立看板 8 面板(活跃vs上限/使用率/空闲/等待/
   获取耗时/获取速率/超时/使用耗时), 保留池维度(legend {{application}}/{{pool}}),
   变量仅 application。耗时类用平均+峰值(无 histogram 桶)。

## 配置(2 处)

8. **prometheus.yml**: canal job 加 metric_relabel_configs drop destination="example"
   (canal 内置空实例延迟噪音 23.8h)。对方 zip 中缺失, 已补回。

9. **config/skywalking/log4j2.xml**: 加 <Logger name="io.netty.handler.logging" level="WARN">
   (1234 指标端口每连接刷 INFO, 348 条/6h 噪音)。对方 zip 中缺失, 已补回。

## 文档

- 对方已采纳 DEPLOY-NOTES.md / README-METRICS.md 放入 config/deploy-cloud ✓
- 本地版 DEPLOY-NOTES 29 条(对方版缺第 30 条 Tomcat 说明), 已用完整版覆盖
- QUESTIONS-FOR-REVIEW.md(12 项已答) / SKYWALKING-SERVICES.md 已放 zip 根目录
