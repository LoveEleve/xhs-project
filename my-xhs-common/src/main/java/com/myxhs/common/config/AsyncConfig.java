package com.myxhs.common.config;

import com.myxhs.common.trace.TraceContext;
import com.myxhs.common.trace.TraceContextHolder;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 异步线程池配置
 * <p>
 * 解决 @Async / CompletableFuture 场景下 MDC（TraceId）和 TraceContext（染色标记）丢失问题。
 * 通过 TaskDecorator 在提交任务时拷贝父线程的上下文到子线程。
 * </p>
 * <p>
 * 透传内容：
 * 1. MDC 上下文（traceId → 日志关联）
 * 2. TraceContext（6 个染色标记 → 压测隔离、灰度路由等）
 * </p>
 */
@Configuration
@EnableAsync
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AsyncConfig {

    /**
     * 自定义异步线程池（替代默认的 SimpleAsyncTaskExecutor）
     */
    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("async-");
        // 关键：设置 TaskDecorator 透传 MDC + TraceContext
        executor.setTaskDecorator(new TraceContextTaskDecorator());
        // 优雅停机：等待任务完成
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 全链路染色上下文透传装饰器
     * <p>
     * 在任务提交时拷贝父线程的 MDC + TraceContext，在任务执行时恢复，执行后清理。
     * </p>
     * <p>
     * 为什么必须深拷贝 TraceContext？
     * 如果直接传引用，父线程清理 ThreadLocal 后子线程拿到的 TraceContext 对象虽然还在，
     * 但父线程可能已经在处理下一个请求并修改了同一个对象。深拷贝保证子线程的上下文独立。
     * </p>
     */
    static class TraceContextTaskDecorator implements TaskDecorator {

        @Override
        public Runnable decorate(Runnable runnable) {
            // 1. 在主线程中获取上下文快照
            Map<String, String> mdcContextMap = MDC.getCopyOfContextMap();
            TraceContext traceContextSnapshot = TraceContextHolder.snapshot();

            return () -> {
                try {
                    // 2. 在子线程中恢复上下文
                    if (mdcContextMap != null) {
                        MDC.setContextMap(mdcContextMap);
                    }
                    if (traceContextSnapshot != null) {
                        TraceContextHolder.set(traceContextSnapshot);
                    }
                    // 3. 执行实际任务
                    runnable.run();
                } finally {
                    // 4. 清理子线程上下文，防止线程池复用时上下文串联
                    MDC.clear();
                    TraceContextHolder.clear();
                }
            };
        }
    }
}
