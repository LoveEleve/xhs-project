package com.myxhs.common.config;

import com.myxhs.common.cache.CacheWarmer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 缓存预热启动器
 * <p>
 * 应用启动完成后执行本服务注册的全部 {@link CacheWarmer}：
 * 1. 总开关：{@code myxhs.cache-warmup.enabled}（默认 true）
 * 2. 任务选择：{@code myxhs.cache-warmup.targets}（逗号分隔任务名；为空 = 本服务全部任务）
 * </p>
 * <p>
 * 任务按注册顺序串行执行：单个任务失败只记日志（降级为按需加载）不阻塞启动，
 * 结束后统一打印任务数/条目数/失败数与耗时，便于观察冷启动成本。
 * </p>
 */
@Slf4j
@Component
public class CacheWarmupRunner implements ApplicationRunner {

    private final List<CacheWarmer> warmers;

    @Value("${myxhs.cache-warmup.enabled:true}")
    private boolean enabled;

    @Value("${myxhs.cache-warmup.targets:}")
    private String targets;

    public CacheWarmupRunner(ObjectProvider<CacheWarmer> warmers) {
        // ObjectProvider：本服务没有注册任何预热任务时注入空集合，不报错
        this.warmers = warmers.orderedStream().collect(Collectors.toList());
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[缓存预热] 已关闭(myxhs.cache-warmup.enabled=false)");
            return;
        }

        Set<String> wanted = parseTargets(targets);
        List<CacheWarmer> todo = warmers.stream()
                .filter(CacheWarmer::enabled)
                .filter(w -> wanted.isEmpty() || wanted.contains(w.name()))
                .collect(Collectors.toList());
        if (todo.isEmpty()) {
            log.info("[缓存预热] 本服务无预热任务(targets='{}')", targets);
            return;
        }

        long start = System.currentTimeMillis();
        int total = 0;
        int failed = 0;
        for (CacheWarmer warmer : todo) {
            try {
                int warmed = warmer.warm();
                total += warmed;
                log.info("[缓存预热] {}: {} 项", warmer.name(), warmed);
            } catch (Exception e) {
                failed++;
                log.warn("[缓存预热] {} 失败(降级为按需加载): {}", warmer.name(), e.getMessage());
            }
        }
        log.info("[缓存预热] 完成: 任务={}, 条目={}, 失败={}, cost={}ms",
                todo.size(), total, failed, System.currentTimeMillis() - start);
    }

    private Set<String> parseTargets(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
