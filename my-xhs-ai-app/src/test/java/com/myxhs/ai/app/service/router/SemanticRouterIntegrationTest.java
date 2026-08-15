package com.myxhs.ai.app.service.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.embedding.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 语义路由真实集成（ARK embedding）：验证种子 few-shot 在真实中文语义下的分类质量。
 * 无 ARK_PLAN_API_KEY 时跳过（CI 不阻塞）；本地跑用于校准 myxhs.ai.router.semantic-threshold。
 * 语义层价值点：不在任何规则词表中的表达（如"明天去哪玩"）也能靠语义相似正确分类。
 */
@EnabledIfEnvironmentVariable(named = "ARK_PLAN_API_KEY", matches = ".+")
class SemanticRouterIntegrationTest {

    private final IntentRouter router = new IntentRouter(new SemanticIntentClassifier(
            new EmbeddingClient(new ObjectMapper(),
                    System.getenv().getOrDefault("ARK_PLAN_BASE_URL", "https://ark.cn-beijing.volces.com/api/plan/v3"),
                    System.getenv("ARK_PLAN_API_KEY"),
                    System.getenv().getOrDefault("ARK_EMBEDDING_MODEL", "doubao-embedding-vision-large")),
            0.0), null);

    @Test
    void 真实语义_问候与超范围分类() {
        assertEquals(Intent.GREETING, router.classify("我爱你"));
        assertEquals(Intent.GREETING, router.classify("随便聊聊"));
        assertEquals(Intent.GREETING, router.classify("你能帮我吗"));
        assertEquals(Intent.OUT_OF_SCOPE, router.classify("讲个笑话"));
        assertEquals(Intent.OUT_OF_SCOPE, router.classify("今天会下雨吗"));
        assertEquals(Intent.OUT_OF_SCOPE, router.classify("周末去哪玩"));
        assertEquals(Intent.OUT_OF_SCOPE, router.classify("什么是Nacos"));
    }

    @Test
    void 真实语义_种子外变体_非词表匹配() {
        // 不在任何规则词表中的变体：语义相似度识别（词表穷举做不到的）
        assertEquals(Intent.GREETING, router.classify("在忙啥呢"));
        assertEquals(Intent.GREETING, router.classify("最近过得咋样"));
        assertEquals(Intent.OUT_OF_SCOPE, router.classify("推荐个好看的剧"));
        assertEquals(Intent.OUT_OF_SCOPE, router.classify("明天出门要带伞吗"));
    }

    @Test
    void 真实语义_诊断不误伤() {
        // L0 规则优先：诊断信号仍走正确路径（语义层不抢）
        assertEquals(Intent.AGENT, router.classify("为什么订单量下降了"));
        assertEquals(Intent.METRIC_ORDER_VOLUME, router.classify("帮我查下订单量"));
        assertEquals(Intent.AGENT, router.classify("帮我排查下系统问题"));
        assertNotNull(router.classify("内容互动"));
    }
}
