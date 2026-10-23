package com.myxhs.coupon.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SharedThreadPoolTest {

    @Test
    @DisplayName("提交任务全部执行（Semaphore 释放正常）")
    void submitAll() throws Exception {
        SharedThreadPool pool = new SharedThreadPool(2);
        AtomicInteger count = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            futures.add(pool.submit(count::incrementAndGet));
        }
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        assertThat(count.get()).isEqualTo(6);
        pool.shutdown();
    }
}
