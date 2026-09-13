package com.myxhs.ai.knowledge;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 知识卡仓库（本地卡片加载 + catalog；D12 v1：BM25 为主，不做分块/向量）
 */
@Slf4j
@Repository
public class KnowledgeRepository {

    @Value("${ai.knowledge.dir:/data/workspace/xhs-project/xhs-ai/knowledge}")
    private String knowledgeDir;

    private final Map<String, KnowledgeCard> cards = new LinkedHashMap<>();

    @PostConstruct
    public void load() {
        Path root = Path.of(knowledgeDir);
        if (!Files.isDirectory(root)) {
            log.warn("[知识] 目录不存在，跳过加载: {}", knowledgeDir);
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .forEach(this::loadCard);
        } catch (Exception e) {
            log.error("[知识] 加载失败: {}", e.getMessage(), e);
        }
        log.info("[知识] 卡片加载完成: {} 张, dir={}", cards.size(), knowledgeDir);
    }

    @SuppressWarnings("unchecked")
    private void loadCard(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String relative = Path.of(knowledgeDir).relativize(file).toString().replace('\\', '/');
            String layer = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : "unknown";
            String fileName = file.getFileName().toString().replaceFirst("\\.ya?ml$", "");

            Map<String, Object> yaml = null;
            try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
                Object parsed = new Yaml().load(reader);
                if (parsed instanceof Map) {
                    yaml = (Map<String, Object>) parsed;
                }
            } catch (Exception parseError) {
                log.warn("[知识] YAML 解析失败（按纯文本索引）: {} - {}", relative, parseError.getMessage());
            }

            String id = yaml == null ? fileName : String.valueOf(yaml.getOrDefault("id", fileName));
            String question = yaml == null ? fileName
                    : String.valueOf(yaml.getOrDefault("question", yaml.getOrDefault("title", fileName)));
            String answer = yaml == null ? "" : stringify(yaml.get("answer"));
            String category = yaml == null ? "" : String.valueOf(yaml.getOrDefault("category", ""));
            String priority = yaml == null ? "" : String.valueOf(yaml.getOrDefault("priority", ""));
            List<String> keywords = new ArrayList<>();
            if (yaml != null) {
                collectStrings(yaml.get("trigger_keywords"), keywords);
                collectStrings(yaml.get("best_for_questions"), keywords);
                collectStrings(yaml.get("related_topics"), keywords);
            }
            cards.put(id, new KnowledgeCard(id, layer, relative, category, priority,
                    question, answer, keywords, content));
        } catch (Exception e) {
            log.warn("[知识] 卡片加载失败: {} - {}", file, e.getMessage());
        }
    }

    public Optional<KnowledgeCard> byId(String idOrPath) {
        if (idOrPath == null || idOrPath.isBlank()) {
            return Optional.empty();
        }
        KnowledgeCard card = cards.get(idOrPath);
        if (card != null) {
            return Optional.of(card);
        }
        return cards.values().stream()
                .filter(c -> c.path().equals(idOrPath) || c.path().endsWith("/" + idOrPath))
                .findFirst();
    }

    public List<KnowledgeCard> all() {
        return new ArrayList<>(cards.values());
    }

    /** 目录视图（渐进披露入口，紧凑输出）：layer → {count, top10} */
    public Map<String, Object> catalog(String layer) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        cards.values().stream()
                .filter(c -> layer == null || layer.isBlank() || layer.equals(c.layer()))
                .sorted(Comparator.comparing(KnowledgeCard::layer).thenComparing(KnowledgeCard::id))
                .forEach(c -> {
                    Map<String, Object> group = grouped.computeIfAbsent(c.layer(), k -> {
                        Map<String, Object> g = new LinkedHashMap<>();
                        g.put("count", 0);
                        g.put("top", new ArrayList<Map<String, Object>>());
                        return g;
                    });
                    group.put("count", ((Number) group.get("count")).intValue() + 1);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> top = (List<Map<String, Object>>) group.get("top");
                    if (top.size() < 10) {
                        top.add(Map.of("id", c.id(), "title", truncate(c.question(), 80)));
                    }
                });
        return Map.of("total", cards.size(), "layers", grouped,
                "hint", "目录只列每层 Top10；请用 knowledge_search 精确检索，再 card_read 读整卡。");
    }

    private void collectStrings(Object value, List<String> target) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    target.add(String.valueOf(item));
                }
            }
        } else if (value != null) {
            target.add(String.valueOf(value));
        }
    }

    private String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
