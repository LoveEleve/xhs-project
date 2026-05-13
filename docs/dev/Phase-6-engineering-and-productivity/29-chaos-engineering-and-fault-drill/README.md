# 混沌工程与故障演练

> 所属维度：生产验证 | 开发阶段：Phase-6 | 核心工具：ChaosBlade

---

## 🎯 一、为什么需要混沌工程

> 📖 **知识来源**：《持续演进的Cloud Native》第7章 — 混沌工程实践
> - "架构×研发流程×团队文化=Cloud Native，混沌工程是验证这三者是否真的能扛故障的关键手段"
> - "不能只在文档里写'降级走DB'，要真的断开Redis验证"

| 问题 | 说明 |
|------|------|
| 故障预案都是理论 | 文档里写了"Redis不可用时降级走DB"，但没有验证过 |
| 上线前必须验证 | 生产环境一定会出故障，不提前演练就是在用户身上演练 |
| 发现隐藏问题 | 熔断配置不对、超时时间太短、降级逻辑有bug——只有真正注入故障才能发现 |

---

## 🏗️ 二、ChaosBlade 简介

ChaosBlade 是阿里巴巴开源的混沌工程实验工具（CNCF Sandbox项目），支持丰富的故障注入场景。

### 2.1 安装与使用

```bash
# 下载 ChaosBlade（Linux）
wget https://github.com/chaosblade-io/chaosblade/releases/download/v1.7.2/chaosblade-1.7.2-linux-amd64.tar.gz
tar -zxvf chaosblade-1.7.2-linux-amd64.tar.gz
cd chaosblade-1.7.2

# 基本命令格式
./blade create [target] [action] [flags]
./blade destroy [uid]    # 恢复故障
./blade status [uid]     # 查看状态
```

### 2.2 Java Agent 集成（JVM 故障注入）

```bash
# 挂载 Java Agent（可注入方法延迟、异常、返回值篡改等）
./blade prepare jvm --process my-xhs-order --port 9669

# 注入方法延迟：OrderService.createOrder 延迟 3 秒
./blade create jvm delay --time 3000 \
  --classname com.myxhs.order.service.impl.OrderServiceImpl \
  --methodname createOrder \
  --process my-xhs-order

# 注入方法异常：InventoryFeignClient.deduct 抛出 RuntimeException
./blade create jvm throwCustomException \
  --exception java.lang.RuntimeException \
  --exception-message "模拟库存服务异常" \
  --classname com.myxhs.order.feign.InventoryFeignClient \
  --methodname deduct \
  --process my-xhs-order

# 卸载 Java Agent
./blade revoke [prepare-uid]
```

### 2.3 自动化演练脚本

```bash
#!/bin/bash
# deploy/scripts/chaos-drill.sh
# 自动化混沌演练脚本 — 一键执行所有场景并生成报告

BLADE_HOME="/opt/chaosblade"
REPORT_DIR="/data/chaos-reports/$(date +%Y%m%d)"
mkdir -p "$REPORT_DIR"

# 颜色输出
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

# 演练函数：注入故障 → 等待 → 验证 → 恢复
run_drill() {
    local name="$1"
    local inject_cmd="$2"
    local verify_cmd="$3"
    local wait_seconds="${4:-30}"

    echo "========== 演练场景: $name =========="

    # 1. 注入故障
    echo "[注入] $inject_cmd"
    uid=$($BLADE_HOME/blade $inject_cmd | grep -oP '"uid":"\K[^"]+')
    echo "故障UID: $uid"

    # 2. 等待故障生效
    sleep "$wait_seconds"

    # 3. 验证结果
    echo "[验证] $verify_cmd"
    result=$(eval "$verify_cmd" 2>&1)
    echo "$result"

    # 4. 恢复故障
    echo "[恢复] blade destroy $uid"
    $BLADE_HOME/blade destroy "$uid"

    # 5. 记录报告
    echo "场景: $name" >> "$REPORT_DIR/report.txt"
    echo "注入: $inject_cmd" >> "$REPORT_DIR/report.txt"
    echo "结果: $result" >> "$REPORT_DIR/report.txt"
    echo "---" >> "$REPORT_DIR/report.txt"
}

# 场景1: Redis 不可用 → 验证降级走 DB
run_drill "Redis不可用" \
    "create network drop --port 6379" \
    "curl -s -o /dev/null -w '%{http_code}' http://localhost:9001/api/user/profile -H 'Authorization: Bearer test'" \
    10

# 场景2: 库存服务网络隔离 → 验证 Sentinel 熔断
run_drill "库存服务网络隔离" \
    "create network drop --remote-port 9009" \
    "curl -s http://localhost:9011/api/order/create -X POST -H 'Content-Type: application/json' -d '{\"skuItems\":[{\"skuId\":1,\"quantity\":1}]}'" \
    15

# 场景3: CPU 满载 → 验证限流
run_drill "CPU满载" \
    "create cpu fullload --cpu-count 2" \
    "curl -s http://localhost:9000/actuator/health" \
    20

echo "演练完成，报告已生成: $REPORT_DIR/report.txt"
```

### 2.4 Spring Boot 集成（演练结果自动上报）

```java
/**
 * 混沌演练结果收集器
 * 在演练期间自动收集关键指标，演练结束后生成报告
 */
@Component
public class ChaosMetricsCollector {

    private final MeterRegistry meterRegistry;
    private final List<ChaosMetricSnapshot> snapshots = new CopyOnWriteArrayList<>();
    private volatile ScheduledExecutorService scheduler; // 保存引用，用于停止采集

    /**
     * 开始收集（演练开始时调用）
     */
    public void startCollecting(String drillName) {
        snapshots.clear();
        // 每秒采集一次关键指标
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            ChaosMetricSnapshot snapshot = new ChaosMetricSnapshot();
            snapshot.setTimestamp(LocalDateTime.now());
            snapshot.setDrillName(drillName);
            snapshot.setHttpErrorRate(getGaugeValue("http.server.requests.error.rate"));
            snapshot.setAvgResponseTime(getGaugeValue("http.server.requests.avg.rt"));
            snapshot.setActiveThreads(getGaugeValue("jvm.threads.live"));
            snapshot.setCircuitBreakerOpen(getGaugeValue("sentinel.circuit.breaker.open"));
            snapshots.add(snapshot);
        }, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * 停止收集（演练结束时调用）
     */
    public void stopCollecting() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    /**
     * 生成演练报告（自动停止采集）
     */
    public ChaosDrillReport generateReport() {
        stopCollecting(); // 停止采集后再生成报告
        ChaosDrillReport report = new ChaosDrillReport();
        report.setMaxErrorRate(snapshots.stream()
                .mapToDouble(ChaosMetricSnapshot::getHttpErrorRate).max().orElse(0));
        report.setMaxResponseTime(snapshots.stream()
                .mapToDouble(ChaosMetricSnapshot::getAvgResponseTime).max().orElse(0));
        report.setCircuitBreakerTriggered(snapshots.stream()
                .anyMatch(s -> s.getCircuitBreakerOpen() > 0));
        report.setSnapshots(snapshots);
        return report;
    }
}
```

---

## 📋 三、my-xhs 混沌演练计划（7大场景）

| # | 演练场景 | ChaosBlade命令 | 验证目标 | 预期结果 |
|---|----------|---------------|----------|----------|
| 1 | Redis不可用 | `blade create network drop --port 6379` | 缓存降级走DB+限流 | 服务不挂，RT升高但可接受 |
| 2 | MySQL主库宕机 | `blade create process kill --process mysqld` | 主从切换 | 30秒内切换完成，写入恢复 |
| 3 | 库存服务网络隔离 | `blade create network drop --remote-port 9009` | Sentinel熔断降级 | 订单服务熔断，返回降级提示 |
| 4 | 下单接口延迟 | `blade create network delay --time 3000 --interface com...OrderService` | Feign超时+重试 | 3秒超时触发降级，不无限等待 |
| 5 | MQ Broker不可用 | `blade create process kill --process java --cmd-keyword broker` | 本地消息表补偿 | 消息暂存本地表，MQ恢复后补发 |
| 6 | CPU飙高 | `blade create cpu fullload` | 限流是否生效 | Sentinel限流，非核心接口被限 |
| 7 | Pod被杀 | `blade create process kill --process java --cmd-keyword my-xhs` | K8s自动重启+优雅停机 | 新Pod启动，请求不丢失 |

---

## 🔄 四、演练流程

```
1. 制定演练计划（哪些场景、预期结果、回滚方案）
2. 在测试环境执行ChaosBlade注入
3. 观察监控面板（Grafana+SkyWalking）
4. 记录实际结果 vs 预期结果
5. 不符合预期的 → 修复代码/配置 → 重新演练
6. 恢复故障（blade destroy / blade revoke）
7. 输出演练报告
```

> **注意**：混沌演练在测试环境执行，不在生产环境直接演练。生产环境可先在低流量时段小范围验证。

---

## ⚖️ 五、方案对比

| 维度 | ChaosBlade | Chaos Monkey (Netflix) | LitmusChaos |
|------|-----------|----------------------|-------------|
| 语言 | Go | Java | Go |
| K8s支持 | ✅ | ⚠️ 需额外集成 | ✅ 原生CRD |
| 故障类型 | 丰富(网络/进程/CPU/IO) | 主要杀实例 | 丰富 |
| 阿里场景积累 | ✅ 双十一验证 | ❌ | ❌ |
| 学习成本 | 低(命令行) | 中 | 中(K8s Operator) |

**最终选择**：ChaosBlade — 阿里双十一验证过，命令行简单，故障类型最全面。

---

## 📊 六、演练报告模板

```
演练报告
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. 演练时间：{日期}
2. 演练场景：{场景描述}
3. 注入命令：{ChaosBlade命令}
4. 预期结果：{描述}
5. 实际结果：{描述}
6. 是否符合预期：✅/❌
7. 发现问题：{如有}
8. 修复方案：{如有}
9. 监控截图：{Grafana/SkyWalking截图}
```

---

## 🐛 七、踩坑记录

### 7.1 {待开发时填写}

- **现象**：
- **原因**：
- **解决**：
- **教训**：

---

## 🎤 八、面试考察点

### Q1: 你们做过混沌工程吗？怎么做的？

**推荐回答思路**：

> 1. "用ChaosBlade在测试环境做了7个故障演练场景"
> 2. "Redis断开验证降级走DB是否生效、MySQL宕机验证主从切换30秒内完成、MQ挂了验证本地消息表兜底"
> 3. "演练中发现了几个问题：比如Feign超时配置太长、熔断阈值不合理，修复后重新验证通过"

### Q2: 混沌工程和压测有什么区别？

**推荐回答思路**：

> 1. "压测验证的是系统在**正常条件下的极限**（能扛多少QPS）"
> 2. "混沌工程验证的是系统在**异常条件下的韧性**（组件挂了会怎样）"
> 3. "两者互补：压测找性能瓶颈，混沌工程找容错短板"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📖 《持续演进的Cloud Native》 | 第7章 | 混沌工程原则与实践 |
| 📄 04-基础设施与部署.md | §10 | ChaosBlade 7场景设计 |
| 📄 03-分布式解决方案.md | §8 | 各组件高可用方案（演练验证对象） |
