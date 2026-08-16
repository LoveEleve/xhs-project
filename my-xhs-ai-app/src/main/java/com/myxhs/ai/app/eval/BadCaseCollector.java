package com.myxhs.ai.app.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * bad case 回流（M14）：评测失败（质量失败）的 case 自动追加到 eval/cases-badcases.yaml，
 * 下轮评测加载后持续回归——闭环。人工确认可删除误报行。
 * 回流判定边界：仅"质量失败"（hardFails 非空 / 数字不一致）——排除 FAILED（环境问题）
 * 与 statusIn 允许的 PARTIAL（预期 partial）。
 */
public class BadCaseCollector {

    private static final Logger log = LoggerFactory.getLogger(BadCaseCollector.class);

    private final String outputPath;

    public BadCaseCollector() {
        this("src/main/resources/eval/cases-badcases.yaml");
    }

    public BadCaseCollector(String outputPath) {
        this.outputPath = outputPath;
    }

    /** 判定是否质量失败（回流候选）：pass=false 且非 FAILED（环境问题）→ 质量失败 */
    public boolean isQualityFailure(Map<String, Object> result) {
        if (Boolean.TRUE.equals(result.get("pass"))) {
            return false;
        }
        if ("FAILED".equals(result.get("status"))) {
            return false; // 环境问题（限流等），非质量
        }
        return true;
    }

    /** 追加失败 case 到回流文件（YAML 追加；文件不存在则创建带头） */
    public synchronized void append(List<Map<String, Object>> failedResults, int startIndex) {
        if (failedResults.isEmpty()) {
            return;
        }
        try {
            File f = new File(outputPath);
            StringBuilder sb = new StringBuilder();
            if (!f.exists()) {
                f.getParentFile().mkdirs();
                sb.append("# bad case 回流（M14 自动生成；人工确认后保留，误报删除）\n")
                        .append("cases:\n");
            }
            int n = startIndex;
            for (Map<String, Object> r : failedResults) {
                n++;
                String reason = reasonTag(r);
                sb.append("  - id: bad_").append(n)
                        .append("\n    query: \"").append(esc((String) r.get("query"))).append("\"")
                        .append("\n    tags: [badcase, ").append(reason).append("]")
                        .append("\n    statusIn: [SUCCEEDED, PARTIAL]")
                        .append("\n    minEvidence: 1")
                        .append("\n    contains: [\"证据\"]")
                        .append("\n    numbersConsistent: true\n");
            }
            Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            log.info("[badcase] 回流 {} 条 → {}", failedResults.size(), outputPath);
        } catch (Exception e) {
            log.warn("[badcase] 回流写入失败: {}", e.getMessage());
        }
    }

    private static String reasonTag(Map<String, Object> r) {
        List<?> hard = (List<?>) r.get("hardFails");
        List<?> unmatched = (List<?>) r.get("unmatchedNumbers");
        if (unmatched != null && !unmatched.isEmpty()) {
            return "hallucination";
        }
        if (hard != null && !hard.isEmpty()) {
            String h = String.valueOf(hard.get(0));
            if (h.contains("关键词")) {
                return "missing-keyword";
            }
            if (h.contains("证据")) {
                return "low-evidence";
            }
        }
        return "hard-fail";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }
}
