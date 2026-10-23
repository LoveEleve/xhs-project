package com.myxhs.coupon.push;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** 守护线程工厂（推送线程不阻止 JVM 退出） */
public class DaemonThreadFactory implements ThreadFactory {

    private final String name;
    private final AtomicInteger seq = new AtomicInteger();

    public DaemonThreadFactory(String name) {
        this.name = name;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, name + "-" + seq.incrementAndGet());
        t.setDaemon(true);
        return t;
    }
}
