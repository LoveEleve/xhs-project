package com.myxhs.ai.code;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 代码定位（v1 轻量：文件扫描 + 符号/关键字匹配；jdtls 为 v1.1 评测触发项）
 * <p>返回 文件:行号 + 片段，供 Agent 引用；只读、限流、限结果数。</p>
 */
@Slf4j
@Service
public class CodeLocateService {

    private static final int MAX_FILES = 8000;
    private static final int DEFAULT_LIMIT = 8;

    @Value("${ai.code.root:/data/workspace/xhs-project}")
    private String codeRoot;

    private volatile java.util.Set<String> fileNameCache;

    /** 引用存在性校验（供答案级评测）：支持仓库相对路径与短文件名 */
    public boolean exists(String ref) {
        if (ref == null || ref.isBlank() || ref.contains("...")) {
            return false;
        }
        String norm = ref.startsWith("/") ? ref.substring(1) : ref;
        if (norm.contains("..")) {
            return false;
        }
        try {
            if (norm.contains("/")) {
                Path resolved = Path.of(codeRoot).resolve(norm).normalize();
                return resolved.startsWith(Path.of(codeRoot).normalize()) && Files.exists(resolved);
            }
            return fileNames().contains(norm);
        } catch (Exception e) {
            return false;
        }
    }

    private java.util.Set<String> fileNames() {
        java.util.Set<String> cached = fileNameCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (fileNameCache == null) {
                java.util.Set<String> names = new java.util.HashSet<>();
                try (Stream<Path> files = Files.walk(Path.of(codeRoot))) {
                    files.filter(p -> p.toString().endsWith(".java"))
                            .filter(p -> !p.toString().contains("/target/"))
                            .forEach(p -> names.add(p.getFileName().toString()));
                } catch (Exception e) {
                    log.warn("[代码定位] 文件名索引构建失败: {}", e.getMessage());
                }
                if (names.isEmpty()) {
                    log.warn("[代码定位] 文件名索引为空（扫描异常？），本次不缓存");
                    return names;
                }
                fileNameCache = names;
            }
            return fileNameCache;
        }
    }

    public Map<String, Object> locate(String query, Integer limit) {
        if (query == null || query.isBlank()) {
            return Map.of("error", "query 不能为空");
        }
        String needle = query.trim();
        if (needle.length() > 200) {
            needle = needle.substring(0, 200);
        }
        int max = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), 20);
        String classPart = null;
        String methodPart = null;
        if (needle.contains(".")) {
            int idx = needle.lastIndexOf('.');
            classPart = needle.substring(0, idx);
            methodPart = needle.substring(idx + 1);
        }

        List<Map<String, Object>> matches = new ArrayList<>();
        int scanned = 0;
        Path root = Path.of(codeRoot);
        try (Stream<Path> files = Files.walk(root)) {
            var it = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !Files.isSymbolicLink(p))
                    .filter(Files::isRegularFile)
                    .iterator();
            while (it.hasNext() && scanned < MAX_FILES && matches.size() < max) {
                Path file = it.next();
                scanned++;
                matches.addAll(searchInFile(file, needle, classPart, methodPart, max - matches.size()));
            }
        } catch (Exception e) {
            log.warn("[代码定位] 扫描失败: {}", e.getMessage());
            return Map.of("error", "代码扫描失败: " + e.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", needle);
        result.put("scannedFiles", scanned);
        result.put("truncated", scanned >= MAX_FILES || matches.size() >= max);
        result.put("matches", matches);
        result.put("hint", "返回 文件:行号；回答引用具体位置，必要时用 grep 语义继续缩小。");
        return result;
    }

    private List<Map<String, Object>> searchInFile(Path file, String needle,
                                                   String classPart, String methodPart, int remaining) {
        List<Map<String, Object>> found = new ArrayList<>();
        boolean classFileMatch = classPart == null
                || file.getFileName().toString().startsWith(fileNameOf(classPart));
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && found.size() < remaining; i++) {
                String line = lines.get(i);
                boolean hit = line.contains(needle)
                        || (methodPart != null && classFileMatch && line.contains(methodPart));
                if (!hit) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("file", "/" + Path.of(codeRoot).relativize(file));
                item.put("line", i + 1);
                item.put("snippet", line.trim().length() > 160 ? line.trim().substring(0, 160) : line.trim());
                found.add(item);
            }
        } catch (Exception ignored) {
        }
        return found;
    }

    private String fileNameOf(String classPart) {
        String simple = classPart.contains(".") ? classPart.substring(classPart.lastIndexOf('.') + 1) : classPart;
        return simple.split("\\$")[0];
    }
}
