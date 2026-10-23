package com.myxhs.coupon.push;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 共享推送线程池（C-017 P3）
 * <p>
 * Semaphore 限制"同时在跑的批次数"，ThreadPoolExecutor 执行批任务；
 * 超过 permits 时 submit 会阻塞（背压），而不是把任务堆进无界队列。
 * </p>
 */
@Component
public class SharedThreadPool {

    private final Semaphore semaphore;
    private final ThreadPoolExecutor executor;

    public SharedThreadPool(@Value("${coupon.push.permits:20}") int permits) {
        int max = Math.max(permits * 2, permits + 1);
        int core = Math.min(max, Math.max(2, Runtime.getRuntime().availableProcessors()));
        this.semaphore = new Semaphore(permits);
        this.executor = new ThreadPoolExecutor(
                core,
                max,
                60, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new DaemonThreadFactory("coupon-push"));
    }

    /** 提交一个批次任务；信号量满时阻塞（背压） */
    public Future<?> submit(Runnable task) {
        semaphore.acquireUninterruptibly();
        try {
            return executor.submit(() -> {
                try {
                    task.run();
                } finally {
                    semaphore.release();
                }
            });
        } catch (RuntimeException e) {
            semaphore.release();
            throw e;
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
