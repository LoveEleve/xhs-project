package com.myxhs.common.shutdown;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 应用内线程池关闭处理器（P1/2026-09-27）
 * <p>
 * 背景：原 {@link GracefulShutdownListener}（ContextClosedEvent）在 **web 排空之前**关闭线程池——
 * 发布窗口内依赖 ExecutorService bean 的请求（如聚合服务的 Feign 线程池）会
 * {@code RejectedExecutionException}，形成发布期 5xx。
 * </p>
 * <p>
 * 现移至 {@code destroyBeans} 阶段（lifecycle stop / web 排空之后）执行：
 * 关闭本应用内所有 {@code ExecutorService} bean，等待 5s，超时强制关闭。
 * </p>
 */
@Slf4j
@Component
public class ExecutorShutdownProcessor {

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * destroy 阶段执行：此时 web 已停止接收并排空在途请求，关闭线程池不再影响在途 HTTP 处理。
     */
    @PreDestroy
    public void shutdownExecutors() {
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
}
