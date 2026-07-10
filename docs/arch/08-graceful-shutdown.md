# 08 — 优雅停机

> **目标读者**：P7+ 工程师，需要理解 Nacos 主动注销、ShutdownHook 回调、与 K8s 的配合。
> **回答三个问题**：为什么不直接 kill？注销和关闭的顺序是什么？数据怎么不丢？

---

## 一、为什么需要优雅停机？

| 方式 | 后果 |
|------|------|
| `kill -9`（强制） | 正在处理的请求中断、Counter Buffer 数据丢失、Nacos 心跳 TTL 内流量仍路由到已死实例 |
| `kill -15`（优雅） | 等待请求完成、Nacos 主动注销、Buffer 刷盘、连接池关闭 |

**关键差异**：`kill -9` 后 Nacos 心跳 TTL（默认 15s）内，其他服务仍然认为该实例存活，Feign 调用会失败。`kill -15` 主动注销后，其他服务在服务列表刷新周期内（默认 1s）就能感知到实例下线。

---

## 二、完整停机流程

```
┌─────────────────────────────────────────────────────────────────┐
│                        优雅停机全流程                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  K8s / Docker / kill -15 发送 SIGTERM                           │
│    │                                                            │
│    ▼                                                            │
│  ┌──────────────────────────────────────────────────┐          │
│  │ 1. preStop Hook (K8s)                            │          │
│  │    curl actuator/service-registry?status=DOWN     │          │
│  │    sleep 10s                                      │          │
│  └──────────────────────┬───────────────────────────┘          │
│                         │                                       │
│                         ▼                                       │
│  ┌──────────────────────────────────────────────────┐          │
│  │ 2. Spring Boot 停止接受新请求                      │          │
│  │    server.shutdown=graceful                       │          │
│  │    新请求返回 503                                  │          │
│  └──────────────────────┬───────────────────────────┘          │
│                         │                                       │
│                         ▼                                       │
│  ┌──────────────────────────────────────────────────┐          │
│  │ 3. 等待已有请求处理完成                             │          │
│  │    lifecycle.timeout-per-shutdown-phase=30s       │          │
│  │    超时后强制中断                                   │          │
│  └──────────────────────┬───────────────────────────┘          │
│                         │                                       │
│                         ▼                                       │
│  ┌──────────────────────────────────────────────────┐          │
│  │ 4. ContextClosedEvent → GracefulShutdownListener   │          │
│  │    ├─ Nacos 主动注销 (ServiceRegistry.deregister)  │          │
│  │    ├─ sleep 10s (等待服务列表传播)                  │          │
│  │    ├─ GracefulShutdownHook.onShutdown() 回调       │          │
│  │    └─ ExecutorService 优雅关闭                     │          │
│  └──────────────────────┬───────────────────────────┘          │
│                         │                                       │
│                         ▼                                       │
│  ┌──────────────────────────────────────────────────┐          │
│  │ 5. @PreDestroy 方法执行                           │          │
│  │    CounterBuffer.shutdown() → 强制刷盘             │          │
│  └──────────────────────┬───────────────────────────┘          │
│                         │                                       │
│                         ▼                                       │
│  ┌──────────────────────────────────────────────────┐          │
│  │ 6. 关闭基础设施连接                                 │          │
│  │    HikariCP / Redis / Redisson / RocketMQ         │          │
│  └──────────────────────┬───────────────────────────┘          │
│                         │                                       │
│                         ▼                                       │
│  ┌──────────────────────────────────────────────────┐          │
│  │ 7. JVM 退出                                       │          │
│  └──────────────────────────────────────────────────┘          │
│                                                                 │
│  K8s terminationGracePeriodSeconds=60 超时 → kill -9           │
└─────────────────────────────────────────────────────────────────┘
```

---

## 三、Nacos 主动注销

### 3.1 为什么需要主动注销？

```
被动等待心跳 TTL 过期（默认 15s）:
  T+0s:  实例 A 宕机
  T+15s: Nacos 感知心跳超时，标记实例为不健康
  T+15s: 其他服务刷新服务列表，移除实例 A
  → 15s 内流量仍路由到已死实例，请求失败

主动注销:
  T+0s:  实例 A 调用 ServiceRegistry.deregister()
  T+1s:  Nacos 立即标记实例为下线
  T+1s:  其他服务在下一个刷新周期感知到变化
  → 最多 1-2s 内流量不再路由到实例 A
```

### 3.2 实现方式

```java
// GracefulShutdownListener.java:83-98
private void deregisterFromNacos() {
    try {
        NacosRegistration registration = applicationContext.getBean(NacosRegistration.class);
        NacosServiceRegistry serviceRegistry = applicationContext.getBean(NacosServiceRegistry.class);
        serviceRegistry.deregister(registration);
        log.info("[优雅停机] Nacos 注销成功");
    } catch (Exception e) {
        log.error("[优雅停机] Nacos 注销失败，依赖 TTL 机制自动移除", e);
        // 失败不阻塞停机流程
    }
}
```

**注销失败怎么办？**
- Nacos 心跳 TTL 机制最终会自动移除实例（15s 后）
- 所以注销失败不阻塞停机，仅记录日志

### 3.3 等待服务列表传播

```java
// GracefulShutdownListener.java:58-60
long waitSeconds = environment.getProperty("myxhs.shutdown.deregister-wait-seconds", Long.class, 10L);
Thread.sleep(waitSeconds * 1000);
```

注销后 sleep 10s，确保其他服务的 Nacos 客户端刷新了服务列表。10s 是一个保守值：
- Nacos 客户端默认拉取间隔：1s
- Spring Cloud LoadBalancer 缓存刷新间隔：默认 35s（已优化）
- 10s 确保至少 10 次拉取周期

---

## 四、GracefulShutdownHook 接口

### 4.1 接口定义

```java
public interface GracefulShutdownHook {
    /**
     * 约束：
     * 1. 尽快执行完成（建议 5s 内）
     * 2. 不抛出异常（异常被捕获，不阻塞其他 Hook）
     * 3. Spring 容器仍然可用
     */
    void onShutdown();
}
```

### 4.2 执行机制

```java
// GracefulShutdownListener.java:103-118
private void executeShutdownHooks() {
    Map<String, GracefulShutdownHook> hooks = applicationContext.getBeansOfType(GracefulShutdownHook.class);
    for (Map.Entry<String, GracefulShutdownHook> entry : hooks.entrySet()) {
        try {
            entry.getValue().onShutdown();
            log.info("[优雅停机] Hook 执行完成: {}", entry.getKey());
        } catch (Exception e) {
            log.error("[优雅停机] Hook 执行失败: {}", entry.getKey(), e);
            // 异常不阻塞其他 Hook
        }
    }
}
```

### 4.3 典型案例：CounterBuffer 刷盘

```java
@Component
public class CounterBuffer implements GracefulShutdownHook {

    private final ReentrantLock flushLock = new ReentrantLock();

    @Override
    public void onShutdown() {
        flushLock.lock();  // 阻塞等待，确保刷盘执行
        try {
            doFlush();      // 将内存 Buffer 批量写入 DB
        } finally {
            flushLock.unlock();
        }
    }

    @PreDestroy
    public void shutdown() {
        onShutdown();  // @PreDestroy 也调用，双重保障
    }
}
```

**与运行时刷盘的区别**：

| 维度 | 运行时 | 停机时 |
|------|--------|--------|
| 锁策略 | `flushLock.tryLock()` 非阻塞 | `flushLock.lock()` 阻塞等待 |
| 行为 | 刷盘正在进行则跳过 | 必须等待并执行刷盘 |
| 理由 | 避免线程堆积 | 数据不能丢失 |

---

## 五、线程池优雅关闭

```java
// GracefulShutdownListener.java:123-137
private void shutdownExecutors() {
    Map<String, ExecutorService> executors = applicationContext.getBeansOfType(ExecutorService.class);
    for (Map.Entry<String, ExecutorService> entry : executors.entrySet()) {
        ExecutorService executor = entry.getValue();
        executor.shutdown();  // 不再接受新任务
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();  // 5s 未完成则强制中断
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```

**关闭策略**：`shutdown()` → 等待 5s → 未完成则 `shutdownNow()`。

---

## 六、K8s 配合

### 6.1 完整 K8s 配置

```yaml
apiVersion: v1
kind: Pod
spec:
  terminationGracePeriodSeconds: 60  # K8s 等待最多 60s
  containers:
    - name: my-xhs-user
      lifecycle:
        preStop:
          exec:
            command:
              - /bin/sh
              - -c
              - |
                curl -X PUT http://localhost:8080/actuator/service-registry?status=DOWN
                sleep 10
```

### 6.2 时间线分析

```
T+0s:   K8s 发送 SIGTERM
T+0s:   preStop 执行 → actuator/service-registry?status=DOWN → sleep 10s
T+10s:  Spring Boot graceful shutdown 开始（timeout-per-shutdown-phase=30s）
T+40s:  Spring 关闭完成，JVM 退出
T+60s:  K8s terminationGracePeriod 到期 → 强制 kill -9（兜底）

60s > 10s + 30s + 余量，安全
```

### 6.3 为什么是 60s？

| 阶段 | 耗时 | 说明 |
|------|------|------|
| preStop | 10s | Nacos 注销 + 服务列表传播 |
| graceful shutdown | 30s | 等待请求完成 + Hook 执行 + 连接池关闭 |
| 余量 | 20s | GC 暂停 / 网络抖动 / 异常情况 |

---

## 七、Spring Boot 配置

```yaml
server:
  shutdown: graceful  # 启用优雅停机

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s  # 等待已有请求完成的超时

myxhs:
  shutdown:
    deregister-wait-seconds: 10  # Nacos 注销后等待服务列表传播的秒数
```

---

## 八、代价与局限

| 局限 | 影响 | 改进方向 |
|------|------|------|
| **ShutdownHook 无超时控制** | 某个 Hook 执行过久会阻塞后续 Hook | 添加 Future.get(timeout) |
| **Nacos 注销使用反射** | API 升级可能不兼容 | 使用 Nacos API 直接调用 |
| **线程池关闭固定 5s 超时** | 大任务可能被中断 | 按线程池差异化配置 |
| **preStop 依赖 curl** | 容器需要 curl 命令 | 使用 busybox 或程序内注册 |
| **GracefulShutdownHook 回调时机** | ContextClosedEvent 之后，@PreDestroy 之前 | 此时 Spring 容器仍可用，是合理的时机 |

---

> **下一篇**：`09-sql-guard-interceptor.md` — SQL 熔断：MyBatis Interceptor 慢 SQL 检测、熔断恢复机制、阈值选择依据
