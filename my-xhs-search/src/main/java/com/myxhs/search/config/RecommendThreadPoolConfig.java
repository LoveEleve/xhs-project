package com.myxhs.search.config;

import com.myxhs.common.trace.MdcAwareExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 推荐系统线程池配置
 * <p>
 * 召回层 5 路并行执行，需要独立线程池隔离，
 * 避免与 Tomcat 线程池竞争导致搜索接口受影响。
 * </p>
 * <p>
 * MDC 透传：通过 common 模块的 MdcAwareExecutorService 包装器，在每次任务执行时
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
    @org.springframework.beans.factory.annotation.Value("${search.recall.core-pool-size:32}")
    private int recallCore;

    @org.springframework.beans.factory.annotation.Value("${search.recall.max-pool-size:64}")
    private int recallMax;

    @org.springframework.beans.factory.annotation.Value("${search.recall.queue-capacity:200}")
    private int recallQueue;

    @Bean("recallExecutor")
    public ExecutorService recallExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                recallCore, recallMax,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(recallQueue),
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
}
