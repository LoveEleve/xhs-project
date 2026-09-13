package com.myxhs.ai.approval;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.JedisSentineled;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 审批决策事件总线（跨实例，D02 §10）
 * <p>任一实例审批决策后 publish，全部实例订阅用于跨实例感知（会话状态/审计一致性）。</p>
 */
@Slf4j
@Component
public class ApprovalEventBus {

    public static final String CHANNEL = "ai:approval:decided";

    @Value("${REDIS_SENTINEL_MASTER:mymaster}")
    private String sentinelMaster;

    @Value("${REDIS_SENTINEL_NODES:192.168.0.142:26379}")
    private String sentinelNodes;

    @Value("${REDIS_PASSWORD:}")
    private String redisPassword;

    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private JedisSentineled pubJedis;
    private JedisSentineled subJedis;
    private Thread subscriberThread;

    @PostConstruct
    public void init() {
        Set<HostAndPort> sentinels = Arrays.stream(sentinelNodes.split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .map(HostAndPort::from).collect(Collectors.toSet());
        JedisClientConfig masterConfig = DefaultJedisClientConfig.builder()
                .password(redisPassword == null || redisPassword.isEmpty() ? null : redisPassword)
                .build();
        JedisClientConfig sentinelConfig = DefaultJedisClientConfig.builder().build();
        pubJedis = new JedisSentineled(sentinelMaster, masterConfig, sentinels, sentinelConfig);
        subJedis = new JedisSentineled(sentinelMaster, masterConfig, sentinels, sentinelConfig);
        running.set(true);
        subscriberThread = new Thread(this::subscribeLoop, "approval-event-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        log.info("[审批事件] 订阅已启动 channel={}", CHANNEL);
    }

    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void publish(String payload) {
        try {
            pubJedis.publish(CHANNEL, payload);
            log.info("[审批事件] 已发布: {}", payload);
        } catch (Exception e) {
            log.warn("[审批事件] 发布失败（不影响本地流程）: {}", e.getMessage());
        }
    }

    private void subscribeLoop() {
        while (running.get()) {
            try {
                subJedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        log.info("[审批事件] 收到跨实例决策: {}", message);
                        listeners.forEach(l -> {
                            try {
                                l.accept(message);
                            } catch (Exception e) {
                                log.warn("[审批事件] 监听器异常: {}", e.getMessage());
                            }
                        });
                    }
                }, CHANNEL);
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("[审批事件] 订阅中断，5s 后重连: {}", e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    @PreDestroy
    public void destroy() {
        running.set(false);
        try {
            subJedis.close();
        } catch (Exception ignored) {
        }
        try {
            pubJedis.close();
        } catch (Exception ignored) {
        }
    }
}
