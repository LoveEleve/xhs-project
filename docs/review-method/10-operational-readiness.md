# 10 运维就绪

> 复审维度 10 | 每个模块必查 | 9 透镜全覆盖，生产环境可运维性为本维度核心
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。每个检查项标注了透镜类型，覆盖
> 业务逻辑/工程正确性/分布式正确性/生产级/可扩展性/并发/微服务/性能/盲区 全部 9 个维度。

---


**执行本维度后，必须在审查报告中输出 `[10] 10 运维就绪：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [10]）。**
## 检查项

### 10.1 优雅关闭 | 透镜：生产级/工程/盲区

**必须检查**：服务关闭时是否执行了资源释放——关闭中线程池、清空 Buffer、完成在途请求。`@PreDestroy` 和 `GracefulShutdown` 是否正确注册。

**怎么查**：
```bash
grep -rn '@PreDestroy\|DisposableBean\|ApplicationListener.*ContextClosed\|GracefulShutdown\|SmartLifecycle\|shutdown' my-xhs-<module>/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 有 `GracefulShutdownListener` 但未注册 `@Component` | 写了关闭逻辑但 Spring 不知道→关闭时永远不会调用 |
| 线程池未关闭 | `ExecutorService` 无 `shutdown()`→K8s 强制 SIGKILL 时丢失在途任务 |
| 长任务无超时 | `awaitTermination(60s)` 但任务执行 120s→强制中断→数据不一致 |
| 计数器 Buffer 未 flush | 关闭前 Buffer 中积攒的计数未刷到 Redis→丢失最后一批计数 |

**案例**：04-counter 的 `GracefulShutdownListener` 未注册 `@Component`→优雅关闭形同虚设（修复加 `@Component`）。06-cart `merge` 用 `commonPool` 阻塞→关闭时 merge 任务被强制中断。

---

### 10.2 线程池与连接池管理 | 透镜：生产级/性能

**必须检查**：自定义线程池的核心/最大线程数、队列是否有界、拒绝策略是否合理；数据库连接池配置是否充分。

**怎么查**：
```bash
grep -rn 'ThreadPoolExecutor\|ExecutorService\|@Async\|connection-pool\|HikariCP\|maxPoolSize\|minimumIdle' my-xhs-<module>/src/main/
```

**判定**：

| 检查点 | 缺陷 | 修复 |
|--------|------|------|
| `Executors.newCachedThreadPool()` | 无上限创建线程→OOM | `new ThreadPoolExecutor(...)` 显式配置 |
| 队列无界 | `new LinkedBlockingQueue()` 无容量→任务堆积→OOM | `new LinkedBlockingQueue(capacity)` 有界 |
| 连接池太小 | `maximumPoolSize=5` 但 50 并发请求→排队超时 | 调大或加 circuit breaker |
| 连接池无验证 | `connectionTestQuery` 未配→拿到死连接→偶发 SQLException | 配 `connection-test-query=SELECT 1` |
| 长任务在 commonPool | `runAsync` 不带 Executor→阻塞 `forkJoinPool` 全局 | 专用有界线程池（见 02.5/02.10） |

**案例**：cart `merge` `CompletableFuture.runAsync` 共用 `commonPool`→长任务阻塞全 JVM 其他 `runAsync`（修复改专用单线程池）。

---

### 10.3 健康检查与就绪探针 | 透镜：生产级/微服务

**必须检查**：`/actuator/health` 是否包含了组件健康检查（DB/Redis/MQ 连接状态）；存活探针和就绪探针路径是否分开。

**怎么查**：
```bash
grep -rn 'health\|HealthIndicator\|@Endpoint\|Readiness\|Liveness\|healthcheck' my-xhs-<module>/src/main/
grep -rn 'management.endpoint\|management.health' my-xhs-<module>/src/main/resources/
```

**判定**：
- 只返回 `{"status": "UP"}` 无组件检查→DB 挂了但 health 仍然 UP→不触发重启
- `/actuator/health/liveness` 和 `/actuator/health/readiness` 同一路径→存活和就绪不可区分
- HealthIndicator bean 未注册→`/actuator/health` 默认空 UP→蒙骗 K8s

**案例**：（全特性面预置检查项——my-xhs 依赖 Spring Boot Actuator 默认 health endpoint，但自定义 HealthIndicator 覆盖度未知。）

---

### 10.4 Job/定时任务监控 | 透镜：生产级/工程

**必须检查**：所有 `@Scheduled` / `@XxlJob` / 对账 Job 是否有执行时间监控、失败告警、重复执行防护。

**怎么查**：
```bash
grep -rn '@Scheduled\|@XxlJob\|SchedulingConfigurer\|cron =' my-xhs-<module>/src/main/java/
grep -rn 'failCount\|executionTime\|lock\|setIfAbsent' my-xhs-<module>/src/main/java/com/myxhs/*/job/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| Job 无分布式锁 | 多实例同时跑同一个 Job→重复对账/重复发通知 |
| 无执行时间监控 | Job 一次跑了 30 分钟后依赖人发现→应该加 metric |
| 无失败告警 | Job 抛异常只 log→失败后遗症持续积累→无人知道 |
| cron 时间 > 执行时间 | `@Scheduled(fixedRate=5000)` 但执行需 10 秒→任务堆积 |

**案例**：`FeedMessageRetryJob` 加锁但超时 25s 不足→改为 55s + 锁超时 > 业务时间 × 1.5。

---

### 10.5 日志与错误信息 | 透镜：生产级/工程

**必须检查**：错误日志是否包含足够的上下文（userId/orderNo/异常堆栈）；是否有区分级别的日志（info/warn/error）；敏感信息是否被记录。

**怎么查**：
```bash
grep -rn 'log.error\|log.warn\|log.info' my-xhs-<module>/src/main/java/com/myxhs/*/service/ my-xhs-<module>/src/main/java/com/myxhs/*/consumer/ | grep -v 'log.info.*成功\|log.info.*完成'
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 无上下文 | `log.error("操作失败")`→没有 userId/orderNo→无法定位问题 |
| 吞异常 | `catch(Exception e) { log.error("xxx") }` 无 `, e`→堆栈丢失 |
| 记录敏感信息 | `log.info("用户登录：password={}")`→密码泄露 |
| 所有异常都是 error | 业务异常（用户输入错误）用 `log.error`→告警噪音淹没问题 |

**案例**：多个 Consumer 的 catch 块 `log.error("fail", e)` 但没记录消息内容→排查 MQ 问题时需额外手动 grep。

---

### 10.6 内存与 JVM 参数 | 透镜：生产级/性能

**必须检查**：服务是否配置了 `-Xmx`/`-Xms`；GC 算法选择是否合理；元空间大小是否配置。

**怎么查**：
```bash
grep -rn 'JAVA_OPTS\|-Xmx\|-Xms\|MaxMetaspaceSize\|UseG1GC' my-xhs-<module>/src/main/ Dockerfile* docker-compose* start*.sh 2>/dev/null
```

**判定**：
- 无 `-Xmx`→JVM 默认物理内存的 1/4→容器限制 512M→实际 `-Xmx=128M`→可能太小
- 有多个服务无 `-Xmx` 各占 1/4→16GB 主机 15 个服务 = OOM
- GC 日志未开启→OOM 后无法分析

**案例**：my-xhs 15 个服务全部无 `-Xmx`→每个占 25% RAM→总和 375%→必然 OOM（已修复加 dev-lower-limits）。

---

### 10.7 监控与日志运维栈 | 透镜：生产级/工程

**必须检查**：模块的日志输出是否被集中采集（Filebeat/Logstash→Elasticsearch→Kibana）；是否有业务监控仪表盘（Prometheus→Grafana）；traceId 是否正确传播。

**怎么查**：
```bash
grep -rn 'traceId\|X-Trace-Id\|MDC\|skywalking\|Sleuth\|Micrometer' my-xhs-<module>/src/main/
grep -rn 'Prometheus\|prometheus\|management.metrics' my-xhs-<module>/src/main/resources/
grep -rn 'logstash\|filebeat\|logback-spring\|log4j2' my-xhs-<module>/src/main/resources/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| MDC traceId 丢失 | Gateway 设置了 traceId→Feign 调用时未传播→Consumer 日志无 traceId→查链路断裂 |
| 日志仅本地文件 | `/var/log/app.log` 无 Filebeat 采集→Kibana 搜不到 |
| 无业务 metric | 只有 JVM metric→无订单数/支付数/库存变化等业务指标→Grafana 业务仪表盘空白 |
| SkyWalking 插件未加载 | optional-plugins 未移到 plugins/→Gateway 链路无 trace 传播 |

**案例**：my-xhs Gateway traceId 通过 MDC 透传但 Feign 调用断连→Consumer 日志 traceId 缺失（修复 Gateway tail context filter + Feign interceptor）；SkyWalking gateway-4.x-plugin 在 optional-plugins 未移→链路追踪全断。

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| 线程池配置（大小/队列/拒绝策略） | 02.10 | 并发维度下的线程池陷阱 |
| 共享可变状态 Buffer 清理 | 02.5 | 双 Buffer 交换/CounterBuffer flush |
| 锁超时设计 | 02.4 | 分布式锁超时 < 业务执行时间 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl <module> -am
mvn test -pl <module>

# 优雅关闭
grep -rn '@PreDestroy\|DisposableBean\|ApplicationListener.*ContextClosed\|GracefulShutdown\|SmartLifecycle' my-xhs-<module>/src/main/java/

# 线程池配置
grep -rn 'ThreadPoolExecutor\|ExecutorService\|@Async' my-xhs-<module>/src/main/

# 健康检查
grep -rn 'HealthIndicator\|@Endpoint\|Readiness\|healthcheck\|health-endpoint' my-xhs-<module>/src/main/

# 定时任务
grep -rn '@Scheduled\|@XxlJob\|fixedRate\|cron =' my-xhs-<module>/src/main/java/

# 日志上下文
grep -rn 'log.error\|log.warn' my-xhs-<module>/src/main/java/ | grep -v 'userId\|orderNo\|traceId\|spuId\|skuId'

# JVM 参数
grep -rn 'JAVA_OPTS\|-Xmx\|-Xms\|MaxMetaspaceSize' my-xhs-<module>/ Dockerfile* start*.sh 2>/dev/null
```
