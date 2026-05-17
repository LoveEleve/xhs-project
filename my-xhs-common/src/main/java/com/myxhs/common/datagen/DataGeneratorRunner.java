package com.myxhs.common.datagen;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据生成启动器
 * <p>
 * 通过 Spring Boot 配置激活：
 * <pre>
 * myxhs.datagen.enabled=true          # 开启数据生成
 * myxhs.datagen.generators=user,product  # 指定要执行的生成器（逗号分隔，all=全部）
 * myxhs.datagen.scale=0.01            # 数据规模倍数（0.01=1%，用于快速测试）
 * </pre>
 * </p>
 * <p>
 * 安全保障：
 * 1. 默认关闭（enabled=false），必须显式开启
 * 2. 只在应用启动时执行一次（CommandLineRunner）
 * 3. 支持 scale 参数控制数据量（0.01 = 1%，避免误操作生成海量数据）
 * 4. 生产环境禁止开启（通过 Spring Profile 控制）
 * </p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "myxhs.datagen.enabled", havingValue = "true", matchIfMissing = false)
public class DataGeneratorRunner implements CommandLineRunner {

    private final List<DataGenerator> generators;
    private final DataSource dataSource;
    private final DataGenProperties properties;

    @Override
    public void run(String... args) throws Exception {
        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║          my-xhs 数据生成工具 v1.0               ║");
        log.info("╠══════════════════════════════════════════════════╣");
        log.info("║  生成器: {}                                     ", properties.getGenerators());
        log.info("║  规模倍数: {}                                   ", properties.getScale());
        log.info("╚══════════════════════════════════════════════════╝");

        // 解析要执行的生成器
        Set<String> targetGenerators = parseGenerators(properties.getGenerators());

        // 按 order 排序后依次执行
        List<DataGenerator> sortedGenerators = generators.stream()
                .filter(g -> targetGenerators.contains("all") || targetGenerators.contains(g.name()))
                .sorted(Comparator.comparingInt(DataGenerator::order))
                .toList();

        if (sortedGenerators.isEmpty()) {
            log.warn("[数据生成] 没有匹配的生成器，可用: {}",
                    generators.stream().map(DataGenerator::name).collect(Collectors.joining(", ")));
            return;
        }

        long totalStart = System.currentTimeMillis();

        for (DataGenerator generator : sortedGenerators) {
            log.info("[数据生成] 开始执行: {} (order={})", generator.name(), generator.order());
            long start = System.currentTimeMillis();
            try {
                generator.generate(dataSource, properties.getScale());
                log.info("[数据生成] {} 完成, 耗时: {}s",
                        generator.name(), (System.currentTimeMillis() - start) / 1000);
            } catch (Exception e) {
                log.error("[数据生成] {} 失败", generator.name(), e);
            }
        }

        long totalElapsed = (System.currentTimeMillis() - totalStart) / 1000;
        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║  数据生成全部完成！总耗时: {}s                   ", totalElapsed);
        log.info("╚══════════════════════════════════════════════════╝");
    }

    private Set<String> parseGenerators(String generators) {
        if (generators == null || generators.isBlank()) {
            return Set.of("all");
        }
        return Set.of(generators.split(","));
    }
}
