package com.myxhs.common.shutdown;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 优雅停机事件监听器
 * <p>
 * 监听 Spring 容器关闭事件（ContextClosedEvent），在 JVM 退出前执行资源清理。
 * </p>
 * <p>
 * 【修复M5 + P1 时序修正（2026-09-27，Spring 6.1 doClose 字节码核实）】实际停机顺序：
 * 1. SIGTERM → 容器 close() → **先发布 ContextClosedEvent**（本监听器，此时 web 仍在接收请求）：
 *    a. 通过 Nacos API 将实例标记为下线（其他服务刷新服务列表）
 *    b. sleep 10s 等待服务列表传播（期间仍可正常服务，避免"摘除未传播"窗口的失败请求）
 *    c. 触发各模块注册的 ShutdownHook 回调（如 Counter Buffer 刷盘）
 * 2. lifecycleProcessor.onClose() → web 停止接收新请求 + 排空在途（≤ lifecycle.timeout-per-shutdown-phase=30s）
 * 3. destroyBeans → @PreDestroy（号段/订阅等；**线程池由 {@link ExecutorShutdownProcessor} 在此阶段关闭**）
 * 4. 关闭数据库连接池 / Redis 连接 / MQ Consumer → JVM 退出
 * </p>
 * <p>
 * 【P1 修复】线程池关闭**不在本监听器执行**（原先早于第 2 步的 web 排空，排空期依赖
 * ExecutorService bean 的请求会 RejectedExecutionException）；已移至 destroy 阶段执行。
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
@Component
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

        // 线程池关闭已移至 ExecutorShutdownProcessor 的 @PreDestroy（destroy 阶段 = web 排空之后执行）
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
     * 安全 sleep
     */
    private void sleep(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[优雅停机] 等待被中断");
        }
    }
}
