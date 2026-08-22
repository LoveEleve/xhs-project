package com.myxhs.ai.app.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class TraceSampleLinker {

    private static final Path TRACE_SAMPLE_FILE = Path.of("/data/workspace/my-xhs/my-xhs-ai/knowledge/code-map/trace-map/real-trace-samples.yaml");
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public List<TraceSample> findRelated(String service, String methodHint, String role, List<String> responsibilities) {
        if ((service == null || service.isBlank()) && (methodHint == null || methodHint.isBlank()) && (role == null || role.isBlank())) {
            return List.of();
        }
        if (!Files.isRegularFile(TRACE_SAMPLE_FILE)) {
            return List.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = yaml.readValue(TRACE_SAMPLE_FILE.toFile(), Map.class);
            Object samples = root.get("samples");
            if (!(samples instanceof List<?> list)) {
                return List.of();
            }
            List<TraceSample> out = new java.util.ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String route = String.valueOf(m.get("route"));
                Object observed = m.get("observed_services");
                boolean serviceHit = observed instanceof List<?> services
                        && services.stream().map(String::valueOf).anyMatch(service::equals);
                boolean semanticHit = false;
                if ("inventory".equals(service) || "preDeduct".equals(methodHint)
                        || responsibilities != null && responsibilities.stream().anyMatch(s -> s.contains("预扣"))) {
                    semanticHit = route.contains("order -> payment") || route.contains("gateway -> order -> payment");
                }
                if (serviceHit || semanticHit) {
                    out.add(new TraceSample(
                            String.valueOf(m.get("id")),
                            String.valueOf(m.get("trace_id")),
                            route,
                            String.valueOf(m.get("note")),
                            String.valueOf(m.get("expected_verdict"))));
                }
            }
            java.util.LinkedHashMap<String, TraceSample> dedup = new java.util.LinkedHashMap<>();
            out.stream()
                    .sorted((a, b) -> Integer.compare(rank(b.expectedVerdict()), rank(a.expectedVerdict())))
                    .forEach(s -> dedup.putIfAbsent(s.traceId(), s));
            return dedup.values().stream().limit(3).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static int rank(String verdict) {
        if ("complete".equalsIgnoreCase(verdict)) {
            return 2;
        }
        if ("uncertain".equalsIgnoreCase(verdict)) {
            return 1;
        }
        return 0;
    }

    public record TraceSample(String id, String traceId, String route, String note, String expectedVerdict) {
    }
}
