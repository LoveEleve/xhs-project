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
public class TraceNavigationLoader {

    private static final Path CODE_MAP_ROOT = Path.of("/data/workspace/my-xhs/my-xhs-ai/knowledge/code-map");
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public Navigation load(String serviceName) {
        String simple = normalize(serviceName);
        String primaryController = null;
        String primaryControllerPath = null;
        String primaryService = null;
        String primaryServicePath = null;
        List<String> nextHops = new ArrayList<>();
        for (Path path : List.of(CODE_MAP_ROOT.resolve("service-map").resolve(simple + ".yaml"),
                CODE_MAP_ROOT.resolve("order-create-mainline.yaml"),
                CODE_MAP_ROOT.resolve("payment-success-mainline.yaml"),
                CODE_MAP_ROOT.resolve("inventory-pre-deduct-mainline.yaml"))) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = yaml.readValue(path.toFile(), Map.class);
                if (primaryController == null) {
                    primaryController = first(list(m.get("key_controllers")), path, "controller");
                    primaryControllerPath = findSourceFor(primaryController, m);
                }
                if (primaryService == null) {
                    primaryService = first(list(m.get("key_services")), path, "service");
                    primaryServicePath = findSourceFor(primaryService, m);
                }
                collectNextHops(nextHops, m, simple);
            } catch (Exception ignored) {
            }
        }
        String primaryControllerSnippet = readSnippet(primaryControllerPath);
        String primaryServiceSnippet = readSnippet(primaryServicePath);
        List<TraceDiagnosisResult.ClassSource> classSources = loadClassSources(simple);
        return new Navigation(primaryController, primaryControllerPath, primaryControllerSnippet,
                primaryService, primaryServicePath, primaryServiceSnippet,
                nextHops.stream().distinct().limit(5).toList(), classSources);
    }

    @SuppressWarnings("unchecked")
    private void collectNextHops(List<String> out, Map<String, Object> m, String simple) {
        Object phases = m.get("phases");
        if (phases instanceof List<?> list) {
            for (Object phase : list) {
                if (phase instanceof Map<?, ?> p) {
                    Object comps = p.get("components");
                    if (comps instanceof List<?> cl) {
                        for (Object c : cl) {
                            String s = String.valueOf(c);
                            if (!s.toLowerCase().contains(simple.toLowerCase())) {
                                out.add(s);
                            }
                        }
                    }
                }
            }
        }
        String callee = str(m.get("callee_service"));
        if (callee != null && !callee.equals(simple)) {
            out.add(callee);
        }
    }

    @SuppressWarnings("unchecked")
    private String findSourceFor(String className, Map<String, Object> map) {
        if (className == null || className.isBlank()) {
            return null;
        }
        Object sources = map.get("sources");
        if (sources instanceof List<?> list) {
            for (Object item : list) {
                String source = String.valueOf(item);
                if (source.endsWith(className + ".java") || source.contains("/" + className + ".java")) {
                    String prefix = "/data/workspace/my-xhs/";
                    return source.startsWith(prefix) ? source.substring(prefix.length()) : source;
                }
            }
        }
        return null;
    }

    private List<TraceDiagnosisResult.ClassSource> loadClassSources(String simple) {
        Path srcRoot = Path.of("/data/workspace/my-xhs/my-xhs-" + simple + "/src/main/java");
        if (!Files.isDirectory(srcRoot)) {
            return List.of();
        }
        List<TraceDiagnosisResult.ClassSource> out = new ArrayList<>();
        java.util.LinkedHashSet<String> classNames = new java.util.LinkedHashSet<>();
        classNames.addAll(List.of(simple + "Service", simple + "Controller", simple + "FeignClient",
                simple + "Consumer", simple + "Listener", simple + "Job"));
        Path serviceMapPath = CODE_MAP_ROOT.resolve("service-map").resolve(simple + ".yaml");
        if (Files.isRegularFile(serviceMapPath)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = yaml.readValue(serviceMapPath.toFile(), Map.class);
                for (String key : List.of("key_services", "key_controllers", "key_feign_clients",
                        "key_consumers", "key_jobs", "key_filters")) {
                    Object val = m.get(key);
                    if (val instanceof List<?> list) {
                        for (Object item : list) {
                            String name = String.valueOf(item);
                            if (name.matches("[A-Z][A-Za-z0-9]+")) {
                                classNames.add(name);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        for (String className : classNames) {
            String path = findJavaFile(srcRoot, className);
            if (path != null) {
                String snippet = readSnippet(path);
                out.add(new TraceDiagnosisResult.ClassSource(className, path, snippet));
            }
        }
        return out;
    }

    private String findJavaFile(Path root, String className) {
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(className + ".java"))
                    .findFirst()
                    .map(p -> {
                        String abs = p.toAbsolutePath().toString();
                        String prefix = "/data/workspace/my-xhs/";
                        return abs.startsWith(prefix) ? abs.substring(prefix.length()) : abs;
                    })
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String readSnippet(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        try {
            Path absolute = Path.of("/data/workspace/my-xhs").resolve(relativePath);
            if (!Files.isRegularFile(absolute)) {
                return null;
            }
            List<String> lines = Files.readAllLines(absolute);
            int total = lines.size();
            int maxLines = 500;
            int from = 0;
            int to = Math.min(maxLines, total);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                sb.append(String.format("%4d  %s%n", i + 1, lines.get(i)));
            }
            if (to < total) {
                sb.append("... (文件共 ").append(total).append(" 行，已展示前 ").append(to).append(" 行)");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String first(List<String> values, Path path, String key) {
        if (!values.isEmpty()) {
            return values.get(0);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = yaml.readValue(path.toFile(), Map.class);
            Object entry = m.get("entry");
            if (entry instanceof Map<?, ?> e) {
                Object v = e.get(key);
                return v == null ? null : String.valueOf(v);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String normalize(String serviceName) {
        String s = serviceName == null ? "" : serviceName;
        return s.startsWith("my-xhs-") ? s.substring("my-xhs-".length()) : s;
    }

    public record Navigation(String primaryController, String primaryControllerPath, String primaryControllerSnippet,
                             String primaryService, String primaryServicePath, String primaryServiceSnippet,
                             List<String> nextHops, List<TraceDiagnosisResult.ClassSource> classSources) {
    }
}
