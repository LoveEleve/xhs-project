package com.myxhs.search.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 推荐系统线程池配置
 * <p>
 * 召回层 5 路并行执行，需要独立线程池隔离，
 * 避免与 Tomcat 线程池竞争导致搜索接口受影响。
 * </p>
 * <p>
 * MDC 透传：通过 MdcAwareExecutorService 包装器，在每次任务执行时
 * 捕获提交线程的 MDC 上下文（TraceId），在子线程中恢复，执行后清理。
 * 避免因线程池复用导致 TraceId 错乱或丢失。
 * </p>
 */
@Configuration
public class RecommendThreadPoolConfig {

    /**
     * 召回专用线程池（也用于搜索 Controller 异步化）
     * <p>
     * 核心线程 10，最大 20，队列 100。
     * 召回任务是 IO 密集型（Redis 查询），线程数可以适当多一些。
     * 拒绝策略：CallerRunsPolicy，降级为调用线程执行（不丢弃）。
     * </p>
     */
    @Bean("recallExecutor")
    public ExecutorService recallExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                10, 20,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger(0);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "recall-pool-" + count.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return new MdcAwareExecutorService(executor);
    }

    /**
     * MDC 感知的 ExecutorService 包装器
     * <p>
     * 在每次 execute() 时捕获当前线程的 MDC 上下文，
     * 在子线程执行前恢复、执行后清理。线程池复用线程时，
     * 每个任务都能获取到正确的 MDC 上下文（TraceId）。
     * </p>
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
