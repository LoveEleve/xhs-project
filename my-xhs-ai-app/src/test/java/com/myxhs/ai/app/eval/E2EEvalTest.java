package com.myxhs.ai.app.eval;

import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 E2E 评测（依赖外部环境：中间件机 21.130.247.89 + 微服务机 21.214.97.212）。
 * 运行条件：
 *  - RocketMQ Dashboard 21.130.247.89:18081 可达
 *  - MySQL 21.130.247.89:3306 可达
 *  - 日志文件 /data/workspace/my-xhs/logs/ 存在
 *  - my-xhs-ai-app:19020 运行中（含 MYXHS_LLM_API_KEY）
 *
 * 不在 CI 中运行（依赖外部环境），手动触发验证。
 */
class E2EEvalTest {

    private static final Logger log = LoggerFactory.getLogger(E2EEvalTest.class);

    @Test
    void e2eCases() {
        // 需要真实 Harness（真模型 + 真外部服务），跳过条件：无 API key
        String apiKey = System.getenv("MYXHS_LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[e2e] MYXHS_LLM_API_KEY 未设置，跳过真实 E2E 评测");
            return;
        }

        // 此处需要真实 Harness 实例（由 Spring 容器管理）。
        // 本测试验证的是评测集加载 + 断言逻辑，真实运行需通过 EvalRunner 或 nightly。
        EvalCaseLoader loader = new EvalCaseLoader();
        List<EvalCase> cases = loader.load("eval/cases-e2e.yaml");
        assertFalse(cases.isEmpty(), "E2E 评测集不应为空");
        log.info("[e2e] 加载 {} 个 E2E 评测 case", cases.size());

        // 验证每个 case 的字段完整性
        for (EvalCase c : cases) {
            assertNotNull(c.id(), "case id 不应为 null: " + c);
            assertNotNull(c.query(), "case query 不应为 null: " + c.id());
            assertFalse(c.statusIn().isEmpty(), "case statusIn 不应为空: " + c.id());
            log.info("[e2e] case={} query={} tags={}", c.id(), c.query(), c.tags());
        }
    }
}
