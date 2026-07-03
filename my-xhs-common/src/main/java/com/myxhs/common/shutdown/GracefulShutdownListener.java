package com.myxhs.common.shutdown;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 优雅停机事件监听器
 * <p>
 * 监听 Spring 容器关闭事件（ContextClosedEvent），在 JVM 退出前执行资源清理。
 * </p>
 * <p>
 * 【修复M5】完整优雅停机流程（收到 SIGTERM / kill -15 后）：
 * 1. Spring Boot 停止接受新的 HTTP 请求（server.shutdown=graceful）
 * 2. 等待已有请求处理完成（最长 lifecycle.timeout-per-shutdown-phase=30s）
 * 3. 触发 ContextClosedEvent → 本监听器执行：
 *    a. 通过 Nacos API 将实例标记为下线（其他服务 30s 内刷新服务列表）
 *    b. sleep 10s 等待服务列表传播（确保不再有新请求路由过来）
 *    c. 触发各模块注册的 ShutdownHook 回调（如 Counter Buffer 刷盘）
 * 4. 各 Bean 的 @PreDestroy 方法执行
 * 5. 关闭数据库连接池 / Redis 连接 / MQ Consumer
 * 6. JVM 退出
 * </p>
 * <p>
 * K8s 部署时的配合：
 * - terminationGracePeriodSeconds=60（K8s 等待 60 秒）
 * - preStop: curl -X PUT actuator/service-registry?status=DOWN && sleep 10
 * - lifecycle.timeout-per-shutdown-phase=30s（Spring 等待 30 秒）
 * - 60 > 10 + 30，给足缓冲时间
 * </p>
 */
@Slf4j
public class GracefulShutdownListener implements ApplicationListener<ContextClosedEvent> {

    @Value("${spring.application.name:unknown}")
    private String applicationName;

    @Value("${myxhs.shutdown.deregister-wait-seconds:10}")
    private int deregisterWaitSeconds;

    @Autowired
    private ApplicationContext applicationContext;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("[优雅停机] {} 开始关闭，执行优雅停机流程...", applicationName);

        // 打印关键信息
        Runtime runtime = Runtime.getRuntime();
        log.info("[优雅停机] 当前活跃线程数: {}, 可用内存: {}MB",
                Thread.activeCount(),
                runtime.freeMemory() / 1024 / 1024);

        // Step 1: 通过 Nacos API 标记实例为下线状态
        deregisterFromNacos();

        // Step 2: 等待服务列表传播（其他服务感知本实例已下线）
        log.info("[优雅停机] 等待 {}s 让服务列表传播...", deregisterWaitSeconds);
        sleep(deregisterWaitSeconds);

        // Step 3: 触发自定义的 ShutdownHook 回调（如 Counter Buffer 刷盘等）
        executeShutdownHooks();

        // Step 4: 关闭应用内线程池（graceful shutdown）
        shutdownExecutors();

        log.info("[优雅停机] {} 优雅停机流程完成，即将退出", applicationName);
    }

    /**
     * 通过 Nacos 将服务实例标记为下线
     * <p>
     * 使用 Spring Cloud 的 ServiceRegistry 接口，兼容 Nacos/Eureka 等。
     * 下线后其他服务的 Ribbon/LoadBalancer 缓存会在下次刷新时移除本实例。
     * </p>
     */
    private void deregisterFromNacos() {
        try {
            // 尝试通过 Spring Cloud 的 Registration 注销（如果存在）
            Object registration = applicationContext.getBean("nacosRegistration");
            Object serviceRegistry = applicationContext.getBean("nacosServiceRegistry");
            if (registration != null && serviceRegistry != null) {
                serviceRegistry.getClass()
                        .getMethod("deregister", registration.getClass().getInterfaces()[0])
                        .invoke(serviceRegistry, registration);
                log.info("[优雅停机] Nacos 实例注销成功");
            }
        } catch (Exception e) {
            // Nacos 注销失败不阻塞停机流程（TTL 机制会自动移除）
            log.warn("[优雅停机] Nacos 实例注销失败(TTL会自动移除): {}", e.getMessage());
        }
    }

    /**
     * 执行各模块注册的 ShutdownHook
     */
    private void executeShutdownHooks() {
        try {
            // 获取所有实现了 GracefulShutdownHook 接口的 Bean
            var hooks = applicationContext.getBeansOfType(GracefulShutdownHook.class);
            for (var entry : hooks.entrySet()) {
                try {
                    log.info("[优雅停机] 执行 ShutdownHook: {}", entry.getKey());
                    entry.getValue().onShutdown();
                } catch (Exception e) {
                    log.error("[优雅停机] ShutdownHook 执行失败: {}", entry.getKey(), e);
                }
            }
        } catch (Exception e) {
            log.warn("[优雅停机] 获取 ShutdownHook 失败: {}", e.getMessage());
        }
    }

    /**
     * 关闭应用内的自定义线程池
     */
    private void shutdownExecutors() {
        try {
            var executors = applicationContext.getBeansOfType(ExecutorService.class);
            for (var entry : executors.entrySet()) {
                ExecutorService executor = entry.getValue();
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("[优雅停机] 线程池 {} 未在5s内完成，强制关闭", entry.getKey());
                    executor.shutdownNow();
                }
            }
        } catch (Exception e) {
            log.warn("[优雅停机] 关闭线程池失败: {}", e.getMessage());
        }
    }

    private void sleep(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[优雅停机] 等待被中断");
        }
    }
}
