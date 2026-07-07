package com.myxhs.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * 动态配置刷新器 — 监听 Nacos 配置变更，实时更新降级开关和阈值
 * 
 * 增强点：
 * 1. oldValue/newValue 记录 — 配置变更可审计
 * 2. 合法性校验 — 防止非法配置值导致运行时异常
 * 3. 异步处理 — 不阻塞 Nacos 事件推送线程
 * 4. onConfigChanged 钩子 — 配置变更通知（钉钉/企微）
 */
@Slf4j
@Component
public class DynamicConfigRefresher {

    private static final String DEGRADE_PREFIX = "myxhs.dynamic.degrade.";
    private static final String THRESHOLD_PREFIX = "myxhs.dynamic.threshold.";

    /** 布尔型降级开关 key 集合 */
    private static final Set<String> BOOLEAN_KEYS = Set.of(
        "myxhs.dynamic.degrade.cache",
        "myxhs.dynamic.degrade.mq",
        "myxhs.dynamic.degrade.feign",
        "myxhs.dynamic.degrade.db",
        "myxhs.dynamic.degrade.search"
    );

    private final Environment environment;
    private final Map<String, String> lastKnownValues = new ConcurrentHashMap<>();
    
    private final ExecutorService configRefreshExecutor = new ThreadPoolExecutor(
        1, 2, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(100),
        r -> {
            Thread t = new Thread(r, "config-refresh");
            t.setDaemon(true);
            return t;
        },
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    public DynamicConfigRefresher(Environment environment) {
        this.environment = environment;
    }

    /**
     * 异步处理配置变更事件
     */
    @EventListener
    public void onEnvironmentChangeEvent(EnvironmentChangeEvent event) {
        Set<String> keys = event.getKeys();
        if (keys == null || keys.isEmpty()) return;

        configRefreshExecutor.submit(() -> {
            List<String> changedKeys = new ArrayList<>();
            for (String key : keys) {
                if (!key.startsWith("myxhs.dynamic.")) continue;
                
                String oldValue = lastKnownValues.get(key);
                String newValue = environment.getProperty(key);
                
                // 配置值未变化，跳过
                if (Objects.equals(oldValue, newValue)) continue;
                
                // 合法性校验
                if (!validate(key, newValue)) {
                    log.error("[Config] 配置校验失败，忽略变更: key={}, value={}", key, newValue);
                    continue;
                }
                
                log.info("[Config] 配置变更 | key={} | oldValue={} | newValue={}", key, oldValue, newValue);
                lastKnownValues.put(key, newValue);
                changedKeys.add(key);
            }
            
            if (!changedKeys.isEmpty()) {
                onConfigChanged(changedKeys);
            }
        });
    }

    /**
     * 校验配置值合法性
     */
    private boolean validate(String key, String value) {
        if (value == null) return false;
        
        // 布尔型降级开关校验
        if (BOOLEAN_KEYS.contains(key)) {
            boolean valid = "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
            if (!valid) {
                log.warn("[Config] 布尔值校验失败: key={}, value={}, expected=true/false", key, value);
            }
            return valid;
        }
        
        // 阈值类型校验（正整数，范围 1-60000）
        if (key.startsWith(THRESHOLD_PREFIX)) {
            try {
                int v = Integer.parseInt(value);
                boolean valid = v > 0 && v <= 60000;
                if (!valid) {
                    log.warn("[Config] 阈值校验失败: key={}, value={}, expected=1~60000", key, value);
                }
                return valid;
            } catch (NumberFormatException e) {
                log.warn("[Config] 阈值格式错误: key={}, value={}, expected=integer", key, value);
                return false;
            }
        }
        
        return true;
    }

    /**
     * 配置变更通知钩子 — 发送钉钉/企微通知
     * 子类可重写实现具体通知逻辑
     */
    protected void onConfigChanged(List<String> changedKeys) {
        String appName = environment.getProperty("spring.application.name", "unknown");
        log.info("[Config] 配置变更通知 | app={} | keys={} | time={}", 
            appName, changedKeys, java.time.LocalDateTime.now());
        // 在此接入钉钉/企微通知
    }

    /** 获取降级开关状态 */
    public boolean isDegraded(String key) {
        String value = environment.getProperty(DEGRADE_PREFIX + key, "false");
        return "true".equalsIgnoreCase(value);
    }

    /** 获取阈值配置 */
    public int getThreshold(String key, int defaultValue) {
        String value = environment.getProperty(THRESHOLD_PREFIX + key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("[Config] 阈值解析失败: key={}, value={}, fallback={}", key, value, defaultValue);
            return defaultValue;
        }
    }
}
