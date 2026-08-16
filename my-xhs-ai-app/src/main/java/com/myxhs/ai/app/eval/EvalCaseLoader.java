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
        return load(new String[]{classpath});
    }

    /** M14：多文件合并加载（smoke + regression + badcases；缺失文件容忍=跳过） */
    public List<EvalCase> load(String... classpaths) {
        List<EvalCase> all = new ArrayList<>();
        for (String cp : classpaths) {
            try (InputStream in = new ClassPathResource(cp).getInputStream()) {
                all.addAll(parse(in));
            } catch (Exception e) {
                // badcases 可能尚未生成：缺失容忍（其他文件缺失则抛出）
                if (cp.contains("badcases")) {
                    continue;
                }
                throw new IllegalStateException("评测集加载失败: " + cp + " - " + e.getMessage(), e);
            }
        }
        return all;
    }

    private List<EvalCase> parse(InputStream in) throws Exception {
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
    }
}
