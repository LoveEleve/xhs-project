package com.myxhs.ai.eval;

import com.myxhs.ai.knowledge.KnowledgeIndexer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KB 检索评测（M3 出口门禁：hit@1 / hit@3；基线=BM25+结构化，向量实验需相对基线显著提升）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbEvalService {

    private final KnowledgeIndexer knowledgeIndexer;

    @Value("${ai.knowledge.eval-cases:/data/workspace/xhs-project/xhs-ai/eval/kb-cases.yaml}")
    private String casesPath;

    @SuppressWarnings("unchecked")
    public Map<String, Object> run() {
        List<Map<String, Object>> cases;
        try (Reader reader = new InputStreamReader(Files.newInputStream(Path.of(casesPath)), StandardCharsets.UTF_8)) {
            cases = new Yaml().load(reader);
        } catch (Exception e) {
            return Map.of("error", "评测集加载失败: " + e.getMessage());
        }
        int total = 0;
        int hit1 = 0;
        int hit3 = 0;
        List<Map<String, Object>> misses = new ArrayList<>();
        Map<String, int[]> byLayer = new LinkedHashMap<>();
        for (Map<String, Object> c : cases) {
            String question = String.valueOf(c.get("question"));
            String expected = String.valueOf(c.get("expectedCardId"));
            String layer = String.valueOf(c.getOrDefault("layer", "unknown"));
            total++;
            int[] stat = byLayer.computeIfAbsent(layer, k -> new int[3]);
            stat[0]++;
            try {
                List<Map<String, Object>> hits = knowledgeIndexer.search(question, null, 3);
                List<String> ids = hits.stream().map(h -> String.valueOf(h.get("id"))).toList();
                boolean first = !ids.isEmpty() && expected.equals(ids.get(0));
                boolean top3 = ids.contains(expected);
                if (first) {
                    hit1++;
                    stat[1]++;
                }
                if (top3) {
                    hit3++;
                    stat[2]++;
                } else {
                    Map<String, Object> miss = new LinkedHashMap<>();
                    miss.put("question", question);
                    miss.put("expected", expected);
                    miss.put("layer", layer);
                    miss.put("top3", ids);
                    misses.add(miss);
                }
            } catch (Exception e) {
                Map<String, Object> miss = new LinkedHashMap<>();
                miss.put("question", question);
                miss.put("expected", expected);
                miss.put("error", e.getMessage());
                misses.add(miss);
            }
        }
        Map<String, Object> layerStats = new LinkedHashMap<>();
        byLayer.forEach((k, v) -> layerStats.put(k, Map.of(
                "total", v[0],
                "hit1", v[1],
                "hit3", v[2])));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("hit1", hit1);
        result.put("hit3", hit3);
        result.put("hit1Rate", total == 0 ? 0 : Math.round(hit1 * 1000.0 / total) / 10.0);
        result.put("hit3Rate", total == 0 ? 0 : Math.round(hit3 * 1000.0 / total) / 10.0);
        result.put("gatePass", total > 0 && hit1 * 100.0 / total >= 90.0);
        result.put("byLayer", layerStats);
        result.put("misses", misses);
        log.info("[KB评测] total={}, hit@1={} ({}%), hit@3={} ({}%), 门禁(≥90% hit@1)={}",
                total, hit1, result.get("hit1Rate"), hit3, result.get("hit3Rate"), result.get("gatePass"));
        return result;
    }
}
