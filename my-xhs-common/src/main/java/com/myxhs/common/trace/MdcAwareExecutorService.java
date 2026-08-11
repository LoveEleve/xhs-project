package com.myxhs.common.trace;

import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MDC 感知的 ExecutorService 包装器（O2 修复）
 * <p>
 * 在每次 execute()/submit() 时捕获当前线程的 MDC 上下文（traceId/userId），
 * 任务执行前恢复、执行后清理——线程池复用线程时每个任务拿到正确的 MDC，
 * 避免异步任务（缓存刷新/延迟双删/布隆加载/补偿等）日志丢失 traceId 导致全链路断链。
 * </p>
 * <p>
 * 用法：<pre>ExecutorService pool = new MdcAwareExecutorService(new ThreadPoolExecutor(...));</pre>
 * </p>
 */
public class MdcAwareExecutorService extends AbstractExecutorService {

    private final ExecutorService delegate;

    public MdcAwareExecutorService(ExecutorService delegate) {
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
    public List<Runnable> shutdownNow() {
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
