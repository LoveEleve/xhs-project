package com.myxhs.ai.app.service.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 指标字典入库：解析 knowledge/metric-dictionary.md，按指标行分块。
 * 可靠性（深度 review 修复）：
 *  - 识别表头行确定"指标名列"（A 面表第一列 / B 面表第二列），不再硬编码第一列
 *  - 支持 ## 与 ### 两级标题（B 面在 "## 2. 能力面 B" 下）
 *  - 过滤表头/分隔行（含 :--: / # / 纯数字）噪音
 */
@Component
public class MetricDictionaryIngester {

    private static final Logger log = LoggerFactory.getLogger(MetricDictionaryIngester.class);

    public static final String SOURCE = "business-analysis/d0/metric-dictionary.md";
    private static final Pattern NOISE = Pattern.compile("[\\d#:|-]+");

    public List<Map<String, String>> parseFromClasspath() {
        try {
            String md = new String(new ClassPathResource("knowledge/metric-dictionary.md").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return parse(md);
        } catch (Exception e) {
            throw new IllegalStateException("读取 metric-dictionary.md 失败", e);
        }
    }

    /**
     * 解析 markdown 表格为知识块。
     * 表格语义：表头行（含"指标"或"场景"列名）确定"指标名列"位置；
     * 数据行从指标名列取值，其余列拼为口径描述。
     */
    static List<Map<String, String>> parse(String md) {
        List<Map<String, String>> docs = new ArrayList<>();
        String currentSection = "";
        int metricIdx = -1;    // 当前表格"指标列"位置（由表头行确定）
        int sectionIdx = -1;   // 当前表格"场景列"位置（B 面表有）

        for (String line : md.split("\n")) {
            String t = line.trim();
            if (t.startsWith("### ") || t.startsWith("## ")) {
                currentSection = t.replaceFirst("^#{2,3} ", "").trim();
                metricIdx = -1;
                sectionIdx = -1;
                continue;
            }
            if (!t.startsWith("|")) {
                metricIdx = -1;
                sectionIdx = -1;
                continue;
            }
            String[] cells = t.split("\\|");
            List<String> cols = new ArrayList<>();
            for (String c : cells) {
                String clean = c.trim().replace("**", "").replace("`", "");
                if (!clean.isEmpty()) {
                    cols.add(clean);
                }
            }
            if (cols.isEmpty()) {
                continue;
            }
            String joined = String.join(" ", cols);
            // 表头行：确定"指标列"位置（场景表=场景列后一列；普通表=含"指标"列）
            if (joined.contains("指标") || joined.contains("场景")) {
                for (int i = 0; i < cols.size(); i++) {
                    if (cols.get(i).contains("场景")) {
                        sectionIdx = i;
                        metricIdx = i + 1;
                        break;
                    }
                }
                if (metricIdx < 0) {
                    for (int i = 0; i < cols.size(); i++) {
                        if (cols.get(i).contains("指标")) {
                            metricIdx = i;
                            break;
                        }
                    }
                }
                continue;
            }
            // 分隔行 / 噪音（去空格后全噪音字符即过滤）
            if (metricIdx < 0 || NOISE.matcher(joined.replace(" ", "")).matches() || joined.length() <= 2) {
                continue;
            }
            if (metricIdx >= cols.size()) {
                continue;
            }
            String metric = cols.get(metricIdx);
            if (metric.matches("\\d+") || metric.isEmpty()) {
                continue;
            }
            StringBuilder def = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                if (i == metricIdx) {
                    continue;
                }
                def.append("；").append(cols.get(i));
            }
            String section = sectionIdx >= 0 && sectionIdx < cols.size() ? cols.get(sectionIdx) : currentSection;
            docs.add(Map.of(
                    "title", metric,
                    "content", "指标：" + metric + "；口径" + def,
                    "source", SOURCE + "#" + section,
                    "section", section));
        }
        return docs;
    }
}
