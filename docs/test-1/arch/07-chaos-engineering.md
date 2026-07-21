# 07 — 混沌工程

> **目标读者**：P7+ 工程师，需要理解故障注入框架设计、演练场景设计、与 ChaosBlade 的互补关系。
> **回答三个问题**：为什么自研注入框架？演练什么场景？怎么保证生产零开销？

---

## 一、三层故障注入架构

```
┌─────────────────────────────────────────────────────────────┐
│                    混沌工程三层架构                            │
├─────────────────────────────────────────────────────────────┤
│  第一层：应用级（自研 ChaosInterceptor AOP）                    │
│  • 方法延迟（Thread.sleep）                                   │
│  • 异常注入（反射构造异常并抛出）                               │
│  • 返回 null（跳过业务逻辑）                                   │
│  • Nacos 配置中心热更新，无需重启                               │
├─────────────────────────────────────────────────────────────┤
│  第二层：容器级（Docker pause/unpause）                       │
│  • Redis 容器暂停 → 模拟 Redis 不可用                          │
│  • MySQL 容器暂停 → 模拟数据库不可用                            │
│  • MQ Broker 容器暂停 → 模拟消息队列不可用                      │
├─────────────────────────────────────────────────────────────┤
│  第三层：基础设施级（ChaosBlade v1.7.4）                      │
│  • 网络延迟/丢包 → blade create network delay                 │
│  • CPU 满载 → blade create cpu fullload                      │
│  • 磁盘 IO 高负载 → blade create disk burn                    │
│  • 进程 kill → blade create process kill                     │
└─────────────────────────────────────────────────────────────┘
```

### 1.1 为什么需要应用级注入？

| 工具 | 能力 | 不足 |
|------|------|------|
| ChaosBlade | 网络/CPU/IO/进程级故障 | 无法注入特定方法返回 null |
| Docker pause | 整容器不可用 | 粒度太粗，无法验证降级逻辑 |
| **自研 AOP** | 精确到方法、支持概率 | 仅限 JVM 内，无法模拟真实网络故障 |

**三者互补**：ChaosBlade 模拟真实故障 → Docker 模拟中间件不可用 → 自研 AOP 验证代码级容错。

---

## 二、自研故障注入框架

### 2.1 核心设计

**ChaosProperties 配置模型**（`ChaosProperties.java`）：

```java
@ConfigurationProperties(prefix = "chaos")
@RefreshScope  // Nacos 热更新
public class ChaosProperties {
    private boolean enabled = false;  // 默认关闭
    private List<FaultConfig> faults = new ArrayList<>();

    @Data
    public static class FaultConfig {
        private FaultType type = FaultType.DELAY;
        private String target = "";        // 目标方法通配符
        private long delayMs = 1000;       // 延迟毫秒数
        private String exceptionClass;      // 异常类名
        private String exceptionMessage;    // 异常消息
        private int probability = 100;     // 触发概率 0-100
        private boolean active = true;     // 是否激活
    }
}
```

### 2.2 三种故障类型

| 类型 | 行为 | 用途 |
|------|------|------|
| `DELAY` | `Thread.sleep(delayMs)` 后正常执行 | 验证超时处理、熔断降级 |
| `EXCEPTION` | 反射构造指定异常并抛出 | 验证异常处理、事务回滚 |
| `RETURN_NULL` | 直接 `return null` | 验证空值处理、NPE 防护 |

### 2.3 通配符目标方法匹配

```
"RedisOperator.*"        → 匹配 RedisOperator 所有方法
"UserService.getUserById" → 精确匹配
"*.create*"              → 匹配所有类的 create 开头方法
"com.myxhs..service..*"  → 匹配 service 包下所有类的所有方法
```

**实现**（`ChaosInterceptor.java:111-118`）：
```java
String regex = pattern.replace(".", "\\.").replace("*", ".*");
return fullName.matches(regex);
```

### 2.4 概率触发机制

```java
int dice = ThreadLocalRandom.current().nextInt(0, 100);
if (dice < fault.getProbability()) {
    injectFault(fault);  // 命中，注入故障
}
```

**使用 `ThreadLocalRandom` 而非 `Random`**：避免多线程竞争 `Random` 的 seed。

### 2.5 Nacos 热更新示例

```yaml
# Nacos 配置中心
chaos:
  enabled: true
  faults:
    - type: DELAY
      target: "InventoryService.preDeduct"
      delayMs: 3000
      probability: 50
      active: true
    - type: EXCEPTION
      target: "CouponService.useCoupon"
      exceptionClass: "java.lang.RuntimeException"
      exceptionMessage: "优惠券服务故障"
      probability: 30
      active: true
```

修改后 Nacos 推送 → `@RefreshScope` 刷新 → `ChaosProperties` 字段更新 → 下一次请求生效。

---

## 三、生产零开销保障

双层保障确保生产环境不受影响：

### 3.1 编译/加载层面

```java
@Bean
@ConditionalOnProperty(prefix = "chaos", name = "enabled", havingValue = "true")
public ChaosInterceptor chaosInterceptor(ChaosProperties properties) {
    log.warn("[混沌工程] 故障注入已启用！");
    return new ChaosInterceptor(properties);
}
```

`chaos.enabled` 不为 `true` 时，`ChaosInterceptor` Bean **根本不会被注册**。AOP 切面不存在，零代理开销。

### 3.2 运行层面

```java
@Around("execution(* com.myxhs..service..*(..)) || ...")
public Object intercept(ProceedingJoinPoint pjp) throws Throwable {
    if (!chaosProperties.isEnabled()) {
        return pjp.proceed();  // 直接放行
    }
    // ... 故障注入逻辑
}
```

即使 Bean 被注册（如测试环境配置错误），运行时检查也确保不注入故障。

### 3.3 切面范围限制

```java
@Around("execution(* com.myxhs..service..*(..)) || " +
        "execution(* com.myxhs..controller..*(..)) || " +
        "execution(* com.myxhs..mapper..*(..))")
```

**只拦截业务层**，不拦截 config/aspect/health 等基础设施类。避免影响 Spring 容器自身的正常运行。

---

## 四、chaos-drill.sh：7 个演练场景

### 4.1 场景清单

| # | 场景 | 注入方式 | 验证指标 | 等待 |
|---|------|---------|---------|------|
| 1 | **Redis 不可用** | `docker pause my-xhs-redis` | `/actuator/health/liveness` 仍 200 | 5s |
| 2 | **网络延迟 3s** | `blade create network delay --time 3000 --local-port 16379` | `/actuator/health` 可响应 | 5s |
| 3 | **MQ Broker 不可用** | `docker pause my-xhs-mq-broker` | `/actuator/health/liveness` 仍 200 | 5s |
| 4 | **CPU 满载** | `blade create cpu fullload --cpu-count 2 --timeout 15` | `/actuator/health` 可响应 | 10s |
| 5 | **磁盘 IO 高负载** | `blade create disk burn --read --write --size 100` | `/actuator/health` 可响应 | 10s |
| 6 | **MySQL 不可用** | `docker pause my-xhs-mysql` | `/actuator/health/liveness` 仍 200 | 10s |
| 7 | **优雅停机验证** | `kill -15 <pid>` | 进程退出，无数据丢失 | 8s |

### 4.2 统一演练流程

```
run_drill() {
    1. 注入故障 (eval $inject_cmd)
    2. 等待故障生效 (sleep Ns)
    3. 验证结果 (eval $verify_cmd, 记录退出码)
    4. 恢复故障 (eval $recover_cmd)
    5. 等待恢复 (sleep 5s)
    6. 记录报告
}
```

### 4.3 场景设计依据

| 场景 | 验证目标 | 预期行为 |
|------|---------|---------|
| Redis 不可用 | 降级放行策略 | @RateLimit/@DistributedLock/@Idempotent 全部降级放行，liveness 仍为 UP |
| 网络延迟 3s | 超时处理 | Feign 超时 500ms 触发熔断，health 可响应（非阻塞） |
| MQ Broker 不可用 | 异步可靠性 | 本地消息表兜底，订单不丢失，liveness 仍 UP |
| CPU 满载 | 限流有效性 | Sentinel 限流生效，health 可响应 |
| 磁盘 IO 高负载 | 日志写入 | 异步日志不阻塞业务，health 可响应 |
| MySQL 不可用 | 缓存可用性 | 多级缓存兜底，读请求仍可服务，liveness UP |
| 优雅停机 | 数据完整性 | Counter Buffer 刷盘完成，Nacos 注销，无请求丢失 |

---

## 五、与 ChaosBlade / Litmus 的对比

| 维度 | 自研 AOP | ChaosBlade | LitmusChaos |
|------|---------|-----------|-------------|
| 语言 | Java | Go | Go |
| K8s 集成 | 无 | 部分支持 | 原生 CRD |
| 故障类型 | 方法延迟/异常/null | 网络/CPU/IO/进程 | 网络/CPU/IO/Pod |
| 学习成本 | 低（配置即用） | 低（命令行） | 中（K8s Operator） |
| 热更新 | Nacos 动态配置 | 需重新注入 | CRD 变更 |
| 精确度 | 方法级 | 进程/网络级 | Pod/网络级 |
| 选型理由 | 验证代码容错 | 模拟真实故障 | K8s 原生方案 |

**MyXHS 的策略**：不造轮子，在 ChaosBlade 基础上补充应用级注入能力。ChaosBlade 负责"硬件层"故障，自研 AOP 负责"软件层"故障。

---

## 六、优雅停机

> 混沌工程的场景 7 是优雅停机验证，以下是对应的架构设计。

### 6.1 完整流程

```
收到 SIGTERM (kill -15)
  │
  ├─ 1. Spring Boot 停止接受新请求
  │     (server.shutdown=graceful)
  │
  ├─ 2. 等待已有请求处理完成
  │     (lifecycle.timeout-per-shutdown-phase=30s)
  │
  ├─ 3. ContextClosedEvent 触发
  │     ├─ Nacos 主动注销（反射调用 ServiceRegistry.deregister）
  │     ├─ sleep 10s（等待服务列表传播）
  │     └─ 执行所有 GracefulShutdownHook.onShutdown()
  │           ├─ CounterBuffer.flush() → 刷盘内存 Buffer
  │           ├─ IM WebSocket → 关闭连接通知客户端
  │           └─ SSE → 关闭 SseEmitter
  │
  ├─ 4. @PreDestroy 方法执行
  │
  ├─ 5. 关闭连接池 / Redis / MQ Consumer
  │
  └─ 6. JVM 退出
```

### 6.2 GracefulShutdownHook 接口

```java
public interface GracefulShutdownHook {
    /**
     * 约束：
     * 1. 应尽快执行完成（建议 5s 内）
     * 2. 不应抛出异常（异常被捕获，不阻塞其他 Hook）
     * 3. 此时 Spring 容器仍然可用
     */
    void onShutdown();
}
```

### 6.3 典型案例：CounterBuffer 刷盘

```java
@Component
public class CounterBuffer implements GracefulShutdownHook {

    @PreDestroy
    public void shutdown() {
        flushLock.lock();  // 阻塞等待，确保刷盘执行
        try {
            doFlush();      // 将内存 Buffer 写入 DB
        } finally {
            flushLock.unlock();
        }
    }

    @Override
    public void onShutdown() {
        shutdown();  // 双重保障
    }
}
```

**与运行时的区别**：
- 运行时：`flushLock.tryLock()` 非阻塞，刷盘正在进行则跳过
- 停机时：`flushLock.lock()` 阻塞等待，必须执行刷盘

### 6.4 K8s 配合

```yaml
terminationGracePeriodSeconds: 60   # K8s 等待 60s
lifecycle:
  preStop:
    exec:
      command:
        - curl
        - -X
        - PUT
        - actuator/service-registry?status=DOWN
        - &&
        - sleep
        - "10"
```

```
时间线：
T+0s:  K8s 发送 SIGTERM
T+0s:  preStop 执行 → Nacos 注销 + sleep 10s
T+10s: Spring graceful shutdown 开始（30s 超时）
T+40s: Spring 关闭完成，JVM 退出
T+60s: K8s terminationGracePeriod 到期，强制 kill -9

60 > 10 + 30，给足缓冲时间
```

---

## 七、代价与局限

| 局限 | 影响 | 改进方向 |
|------|------|------|
| **AOP 注入仅限 JVM 内** | 无法模拟网络层面的真实故障 | 与 ChaosBlade 互补 |
| **演练脚本依赖 Docker** | 非 Docker 环境无法使用 | 适配 K8s 场景 |
| **Nacos 注销使用反射** | Nacos API 升级可能不兼容 | 直接使用 Nacos API |
| **ShutdownHook 执行超时无保护** | 某个 Hook 执行过久会阻塞后续 Hook | 添加超时机制 |
| **chaos-drill.sh 无 K8s 支持** | K8s 部署时无法使用 | 使用 LitmusChaos CRD |

---

> **下一篇**：`08-graceful-shutdown.md` — 优雅停机：Nacos 主动注销、ShutdownHook 回调、与 K8s terminationGracePeriodSeconds 的配合
