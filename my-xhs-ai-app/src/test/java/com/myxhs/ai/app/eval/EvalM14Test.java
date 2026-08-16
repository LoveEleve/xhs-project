package com.myxhs.ai.app.eval;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M14 评测闭环单测：多文件加载 / bad case 回流判定与追加 / 报告含 judge 字段。
 */
class EvalM14Test {

    @Test
    void 多文件加载_合计101条() {
        EvalCaseLoader loader = new EvalCaseLoader();
        List<EvalCase> smoke = loader.load("eval/cases.yaml");
        List<EvalCase> all = loader.load("eval/cases.yaml", "eval/cases-regression.yaml");
        assertEquals(20, smoke.size(), "smoke 层应 20 条");
        assertEquals(100, all.size(), "smoke+regression 应 100 条（100+ 门禁）");
        // 分层 tag 齐全
        assertTrue(all.stream().anyMatch(c -> c.tags().contains("regression")));
        assertTrue(all.stream().anyMatch(c -> c.tags().contains("security")));
        assertTrue(all.stream().anyMatch(c -> c.tags().contains("decline")));
        assertTrue(all.stream().anyMatch(c -> c.tags().contains("edge")));
        // id 唯一
        assertEquals(all.size(), all.stream().map(EvalCase::id).distinct().count(), "id 必须唯一");
        // badcases 缺失容忍
        List<EvalCase> withBad = loader.load("eval/cases.yaml", "eval/cases-regression.yaml",
                "eval/cases-badcases.yaml");
        assertEquals(100, withBad.size());
    }

    @Test
    void badcase_判定与回流() throws Exception {
        BadCaseCollector c = new BadCaseCollector("target/badcases-test.yaml");
        // 质量失败（hardFails）→ 回流
        assertTrue(c.isQualityFailure(Map.of("pass", false, "status", "SUCCEEDED",
                "hardFails", List.of("答案缺少关键词: 证据"))));
        // 数字不一致 → 回流
        assertTrue(c.isQualityFailure(Map.of("pass", false, "status", "SUCCEEDED",
                "unmatchedNumbers", List.of("67"))));
        // FAILED（环境问题）→ 不回流
        assertTrue(!c.isQualityFailure(Map.of("pass", false, "status", "FAILED")));
        // pass → 不回流
        assertTrue(!c.isQualityFailure(Map.of("pass", true, "status", "SUCCEEDED")));

        // 追加 + 再加载（闭环）
        File f = new File("target/badcases-test.yaml");
        f.delete();
        c.append(List.of(Map.of("query", "为什么订单量下降了", "status", "SUCCEEDED",
                "hardFails", List.of("答案缺少关键词: 证据"))), 0);
        c.append(List.of(Map.of("query", "支付失败多吗", "status", "SUCCEEDED",
                "unmatchedNumbers", List.of("3"))), 1);
        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("bad_1") && content.contains("bad_2"), content);
        assertTrue(content.contains("badcase"), content);
        // 加载回流文件（YAML 结构合法）：copy 到 target/classes（classpath 内）
        File tmp = new File("target/badcases-test.yaml");
        java.nio.file.Files.copy(tmp.toPath(),
                new File("target/classes/eval/cases-badcases-test.yaml").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try {
            List<EvalCase> badCases = new EvalCaseLoader().load("eval/cases-badcases-test.yaml");
            assertEquals(2, badCases.size());
            assertTrue(badCases.stream().allMatch(x -> x.tags().contains("badcase")));
        } finally {
            new File("target/classes/eval/cases-badcases-test.yaml").delete();
            f.delete();
        }
    }

    @Test
    void judge_确定性评分() {
        // fake judge 模型：固定输出 "4.5" → 评分 4.5
        var fake = new dev.langchain4j.model.chat.ChatModel() {
            @Override
            public dev.langchain4j.model.chat.response.ChatResponse chat(
                    dev.langchain4j.model.chat.request.ChatRequest request) {
                return dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("4.5"))
                        .metadata(dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                                .tokenUsage(new dev.langchain4j.model.output.TokenUsage(10, 5))
                                .modelName("fake-judge").build())
                        .build();
            }
        };
        EvalJudge judge = new EvalJudge(fake, true);
        assertEquals(4.5, judge.score("q", "结论：订单量下降"));
        // 超长答案截断（不炸）
        assertTrue(judge.score("q", "长".repeat(5000)) >= 0);
        // 未启用 → -1
        EvalJudge off = new EvalJudge(fake, false);
        assertEquals(-1, off.score("q", "a"));
    }
}
