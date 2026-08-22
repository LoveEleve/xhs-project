package com.myxhs.ai.app.service.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class TraceServiceContextLoader {

    private static final Path SERVICE_MAP_ROOT = Path.of("/data/workspace/my-xhs/my-xhs-ai/knowledge/code-map/service-map");
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public ServiceContext load(String serviceName) {
        String simple = normalize(serviceName);
        Path path = SERVICE_MAP_ROOT.resolve(simple + ".yaml");
        if (!Files.isRegularFile(path)) {
            return ServiceContext.empty(serviceName);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = yaml.readValue(path.toFile(), Map.class);
            return new ServiceContext(
                    serviceName,
                    str(m.get("role")),
                    list(m.get("key_controllers")),
                    list(m.get("key_services")),
                    list(m.get("key_consumers")),
                    list(m.get("sources")));
        } catch (Exception e) {
            return ServiceContext.empty(serviceName);
        }
    }

    private static String normalize(String serviceName) {
        String s = serviceName == null ? "" : serviceName.trim();
        if (s.startsWith("my-xhs-")) {
            s = s.substring("my-xhs-".length());
        }
        return s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    public record ServiceContext(
            String service,
            String role,
            List<String> controllers,
            List<String> services,
            List<String> consumers,
            List<String> sources) {
        public String primarySource() {
            if (sources.isEmpty()) {
                return null;
            }
            String source = sources.get(0);
            String prefix = "/data/workspace/my-xhs/";
            return source.startsWith(prefix) ? source.substring(prefix.length()) : source;
        }

        public List<String> recentCommits() {
            String primary = primarySource();
            if (primary == null || primary.isBlank()) {
                return List.of();
            }
            try {
                Process p = new ProcessBuilder("git", "log", "--oneline", "-3", "--", primary)
                        .directory(new java.io.File("/data/workspace/my-xhs"))
                        .redirectErrorStream(true)
                        .start();
                String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (p.waitFor() != 0 || out.isBlank()) {
                    return List.of();
                }
                return out.lines().map(String::trim).filter(s -> !s.isBlank()).toList();
            } catch (Exception e) {
                return List.of();
            }
        }

        public TraceDiagnosisResult.Owner owner() {
            String primary = primarySource();
            if (primary == null || primary.isBlank()) {
                return null;
            }
            try {
                Process p = new ProcessBuilder("git", "log", "-1", "--pretty=format:%an|%ae|%h|%s", "--", primary)
                        .directory(new java.io.File("/data/workspace/my-xhs"))
                        .redirectErrorStream(true)
                        .start();
                String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (p.waitFor() != 0 || out.isBlank()) {
                    return null;
                }
                String[] parts = out.split("\\|", 4);
                if (parts.length < 4) {
                    return null;
                }
                return new TraceDiagnosisResult.Owner(parts[0], parts[1], parts[2], parts[3]);
            } catch (Exception e) {
                return null;
            }
        }
        static ServiceContext empty(String service) {
            return new ServiceContext(service, null, List.of(), List.of(), List.of(), List.of());
        }
    }
}
