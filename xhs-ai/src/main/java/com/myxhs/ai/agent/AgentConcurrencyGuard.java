package com.myxhs.ai.agent;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 并发护栏（生产化）：单用户 ≤N、全局 ≤M，超出快速拒绝（429）。
 * 预算管总量，本护栏管瞬时并发/洪峰。
 */
@Slf4j
@Component
public class AgentConcurrencyGuard {

    @Value("${myxhs.agent.max-inflight-per-user:2}")
    private int maxPerUser;

    @Value("${myxhs.agent.max-inflight-global:8}")
    private int maxGlobal;

    private final AtomicInteger global = new AtomicInteger();
    private final Map<Long, AtomicInteger> perUser = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("[并发护栏] 启用 单用户≤{}，全局≤{}", maxPerUser, maxGlobal);
    }

    public void acquire(long userId) {
        AtomicInteger userCount = perUser.computeIfAbsent(userId, k -> new AtomicInteger());
        synchronized (userCount) {
            if (userCount.get() >= maxPerUser) {
                throw new TooManyRequestsException("当前并发请求过多（单用户上限 " + maxPerUser + "），请稍后重试");
            }
            if (global.get() >= maxGlobal) {
                throw new TooManyRequestsException("服务繁忙（全局上限 " + maxGlobal + "），请稍后重试");
            }
            userCount.incrementAndGet();
            global.incrementAndGet();
        }
    }

    public void release(long userId) {
        AtomicInteger userCount = perUser.get(userId);
        if (userCount != null) {
            synchronized (userCount) {
                if (userCount.get() > 0) {
                    userCount.decrementAndGet();
                    global.decrementAndGet();
                }
            }
            if (userCount.get() == 0) {
                perUser.remove(userId, userCount);
            }
        }
    }

    int globalCount() {
        return global.get();
    }

    int userCount(long userId) {
        AtomicInteger c = perUser.get(userId);
        return c == null ? 0 : c.get();
    }

    public static class TooManyRequestsException extends RuntimeException {
        public TooManyRequestsException(String message) {
            super(message);
        }
    }
}
