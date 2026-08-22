package com.myxhs.ai.app.service.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class TraceCallChainLoader {

    private static final Path CODE_MAP_ROOT = Path.of("/data/workspace/my-xhs/my-xhs-ai/knowledge/code-map");
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public List<String> summariesForService(String serviceName) {
        String simple = normalize(serviceName);
        List<String> out = new ArrayList<>();
        collect(out, CODE_MAP_ROOT.resolve("feign-map"), simple);
        collect(out, CODE_MAP_ROOT, simple);
        return out.stream().distinct().limit(3).toList();
    }

    private void collect(List<String> out, Path dir, String simple) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir, 1)) {
            stream.filter(p -> p.toString().endsWith(".yaml")).forEach(p -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = yaml.readValue(p.toFile(), Map.class);
                    String text = p.getFileName().toString() + " " + m.toString();
                    if (text.contains(simple) || text.contains("my-xhs-" + simple)) {
                        String role = str(m.get("semantic_role"));
                        String desc = str(m.get("description"));
                        String q = str(m.get("question"));
                        String id = str(m.get("id"));
                        String edge = str(m.get("edge_id"));
                        String summary = firstNonBlank(role, desc, q, edge, id);
                        if (summary != null) {
                            out.add(summary);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static String normalize(String serviceName) {
        String s = serviceName == null ? "" : serviceName;
        return s.startsWith("my-xhs-") ? s.substring("my-xhs-".length()) : s;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
