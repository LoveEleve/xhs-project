package com.myxhs.ai.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评测集加载（M6）：YAML 用例 → EvalCase 列表。
 * 文件：classpath: eval/cases.yaml（smoke 层，PR 用；regression 层后续扩展）。
 */
public class EvalCaseLoader {

    private final ObjectMapper yaml;

    public EvalCaseLoader() {
        this.yaml = new ObjectMapper(new YAMLFactory());
    }

    public List<EvalCase> load(String classpath) {
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            Map<?, ?> root = yaml.readValue(in, Map.class);
            List<EvalCase> cases = new ArrayList<>();
            Object raw = root.get("cases");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> sm = (Map<String, Object>) m;
                        cases.add(EvalCase.fromYaml(sm));
                    }
                }
            }
            return cases;
        } catch (Exception e) {
            throw new IllegalStateException("评测集加载失败: " + classpath + " - " + e.getMessage(), e);
        }
    }
}
