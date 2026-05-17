package com.myxhs.home.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BFF 聚合线程池配置
 * <p>
 * 为什么不用 ForkJoinPool.commonPool()？
 * 1. commonPool 是全局共享的，下游服务超时会阻塞其他业务
 * 2. commonPool 的线程数 = CPU 核心数 - 1，对 IO 密集型的 Feign 调用不够
 * 3. 独立线程池可以精确控制核心/最大线程数、队列容量、拒绝策略
 * </p>
 * <p>
 * MDC 透传：通过 MdcAwareExecutorService 包装器，在每次任务执行时
 * 捕获提交线程的 MDC 上下文（TraceId），在子线程中恢复，执行后清理。
 * <p>
 * 为什么不用 ThreadFactory 级别的 MDC 透传？
 * ThreadFactory.newThread() 在线程创建时捕获 MDC，但线程池会复用线程，
 * 第一次创建线程时的 MDC 会被后续复用时一直使用，导致 TraceId 错误。
 * 正确做法是在每次 execute() 时包装 Runnable，在任务执行前设置 MDC。
 * </p>
 */
@Configuration
public class AggregatorThreadPoolConfig {

    @Value("${home.aggregator.core-pool-size:20}")
    private int corePoolSize;

    @Value("${home.aggregator.max-pool-size:50}")
    private int maxPoolSize;

    @Value("${home.aggregator.queue-capacity:200}")
    private int queueCapacity;

    @Value("${home.aggregator.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    @Value("${home.aggregator.thread-name-prefix:home-aggregator-}")
    private String threadNamePrefix;

    @Bean("aggregatorPool")
    public ExecutorService aggregatorPool() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveSeconds, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new NamedThreadFactory(threadNamePrefix),
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时由调用线程执行（降级而非丢弃）
        );
        return new MdcAwareExecutorService(executor);
    }

    /**
     * 批量 Feign 调用线程池（独立于 aggregatorPool）
     * <p>
     * 解决嵌套 CompletableFuture 线程池饥饿问题：
     * aggregatorPool 中的任务（如 batchGetNoteDetails）内部需要并行调用多个 Feign 接口，
     * 如果子任务也提交到 aggregatorPool，高并发下外层任务占满线程池后，
     * 内层子任务无法获得线程执行，形成死锁/饥饿。
     * 使用独立线程池彻底隔离外层编排和内层批量调用。
     * </p>
     */
    @Bean("batchFeignPool")
    public ExecutorService batchFeignPool() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                30,   // 核心线程数（IO 密集型，20 条笔记并行调用）
                80,   // 最大线程数
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                new NamedThreadFactory("home-batch-feign-"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return new MdcAwareExecutorService(executor);
    }

    /**
     * 命名线程工厂（仅负责线程命名和守护线程设置，不处理 MDC）
     */
    static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger count = new AtomicInteger(0);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + count.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * MDC 感知的 ExecutorService 包装器
     * <p>
     * 核心修复：在每次 execute()/submit() 时捕获当前线程的 MDC 上下文，
     * 而非在线程创建时捕获。这样线程池复用线程时，每个任务都能获取到
     * 正确的 MDC 上下文（TraceId）。
     * <p>
     * 实现原理：
     * 1. execute()/submit() 调用时，捕获当前线程的 MDC 上下文
     * 2. 将原始 Runnable/Callable 包装为 MdcAwareRunnable/MdcAwareCallable
     * 3. 包装器在任务执行前设置 MDC，执行后清理 MDC
     * 4. 委托给底层 ExecutorService 执行
     */
    static class MdcAwareExecutorService extends AbstractExecutorService {

        private final ExecutorService delegate;

        MdcAwareExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            delegate.execute(() -> {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                try {
                    command.run();
                } finally {
                    MDC.clear();
                }
            });
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
