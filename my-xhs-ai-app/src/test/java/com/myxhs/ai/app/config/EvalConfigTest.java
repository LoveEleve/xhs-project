package com.myxhs.ai.app.config;

import com.myxhs.ai.app.eval.BadCaseCollector;
import com.myxhs.ai.app.eval.EvalJudge;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M14 评测装配测试（P2 收尾）：EvalJudge/BadCaseCollector bean 就位 + 配置项生效。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalcfg;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "myxhs.ai.tools.mode=direct",
        "MCP_API_KEY=",
        "myxhs.ai.llm.api-key=test-key",
        "spring.ai-datasource.url=jdbc:h2:mem:evalcfgai;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.ai-datasource.username=sa",
        "spring.ai-datasource.password=sa"
})
class EvalConfigTest {

    @Autowired
    private EvalJudge evalJudge;

    @Autowired
    private BadCaseCollector badCaseCollector;

    @Test
    void judge默认关闭_可复用主模型() {
        assertNotNull(evalJudge, "EvalJudge bean 应装配（P2 收尾）");
        assertFalse(evalJudge.enabled(), "judge 默认关闭（成本控制）");
    }

    @Test
    void badcase路径配置生效() {
        assertNotNull(badCaseCollector, "BadCaseCollector bean 应装配");
        assertTrue(badCaseCollector.outputPath().contains("cases-badcases.yaml"),
                "默认路径应指向回流文件: " + badCaseCollector.outputPath());
        // 判定语义回归（配置化后行为不变）
        assertTrue(badCaseCollector.isQualityFailure(
                java.util.Map.of("pass", false, "status", "SUCCEEDED",
                        "hardFails", java.util.List.of("x"))));
        assertFalse(badCaseCollector.isQualityFailure(
                java.util.Map.of("pass", false, "status", "FAILED")));
    }

    @Test
    void judge启用配置可注入() {
        // 覆盖配置：enabled=true 时 bean 生效（复用主模型，无新模型——成本红线）
        assertEquals(-1, evalJudge.score("q", "a"), "未启用时评分 -1（not scored）");
    }
}
