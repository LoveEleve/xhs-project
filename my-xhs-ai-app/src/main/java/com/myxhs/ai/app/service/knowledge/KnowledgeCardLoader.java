package com.myxhs.ai.app.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 `/data/workspace/my-xhs/my-xhs-ai/knowledge/` 读取第一版 cards/maps。
 */
@Component
public class KnowledgeCardLoader {

    private static final Path KNOWLEDGE_ROOT = Path.of("/data/workspace/my-xhs/my-xhs-ai/knowledge");
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public List<KnowledgeCard> loadCards(String subdir) {
        try {
            Path dir = KNOWLEDGE_ROOT.resolve(subdir);
            if (!Files.exists(dir)) return List.of();
            List<KnowledgeCard> out = new ArrayList<>();
            try (var stream = Files.walk(dir, 2)) {
                stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                        .forEach(p -> {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String,Object> m = yaml.readValue(p.toFile(), Map.class);
                                if (m.containsKey("id") && m.containsKey("answer")) {
                                    out.add(toCard(m));
                                }
                            } catch (Exception ignored) {
                            }
                        });
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private KnowledgeCard toCard(Map<String, Object> m) {
        return new KnowledgeCard(
                str(m.get("id")),
                str(m.get("category")),
                str(m.get("priority")),
                str(m.get("question")),
                str(m.get("answer_shape")),
                str(m.get("serve_mode")),
                str(m.get("scope")),
                str(m.get("answer")),
                map(m.get("structured_points")),
                str(m.get("why_it_matters")),
                list(m.get("anti_confusion")),
                list(m.get("best_for_questions")),
                list(m.get("trigger_keywords")),
                listMap(m.get("sources")),
                list(m.get("evidence_level")),
                str(m.get("confidence")),
                list(m.get("followup_docs")),
                list(m.get("related_cards"))
        );
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
    @SuppressWarnings("unchecked")
    private Map<String,String> map(Object o) { return o instanceof Map<?,?> mm ? mm.entrySet().stream().collect(java.util.stream.Collectors.toMap(e->String.valueOf(e.getKey()), e->String.valueOf(e.getValue()))) : Map.of(); }
    @SuppressWarnings("unchecked")
    private List<String> list(Object o) { return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(); }
    @SuppressWarnings("unchecked")
    private List<Map<String,String>> listMap(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<Map<String,String>> out = new ArrayList<>();
        for (Object item : l) out.add(map(item));
        return out;
    }
}
