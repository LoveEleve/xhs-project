package com.myxhs.ai.app.service.router;

import com.myxhs.ai.app.service.embedding.EmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 语义意图分类单测（Fake embedding）：验证种子 few-shot + 余弦相似度 + 阈值 + 降级。
 * 向量空间设计：每个意图一个主题方向（3 维），查询按其预期意图落入对应方向。
 */
class SemanticIntentClassifierTest {

    /** Fake embedding：文本→预设向量；模拟意图方向 */
    private static final class FakeEmbeddingClient extends EmbeddingClient {
        private final Function<String, float[]> vectorizer;
        private boolean fail = false;
        final Map<String, float[]> cache = new ConcurrentHashMap<>();

        FakeEmbeddingClient(Function<String, float[]> vectorizer) {
            super(null, "http://fake", "key", "fake-model");
            this.vectorizer = vectorizer;
        }

        @Override
        public boolean available() {
            return !fail;
        }

        @Override
        public float[] embed(String text) {
            if (fail) {
                throw new IllegalStateException("embedding 失败（模拟）");
            }
            return vectorizer.apply(text);
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            if (fail) {
                throw new IllegalStateException("embedding 失败（模拟）");
            }
            List<float[]> out = new ArrayList<>(texts.size());
            for (String t : texts) {
                out.add(vectorizer.apply(t));
            }
            return out;
        }
    }

    private static float[] vec(double x, double y, double z) {
        return new float[]{(float) x, (float) y, (float) z};
    }

    /** 3 维意图方向：GREETING=[1,0,0] OUT_OF_SCOPE=[0,1,0] AGENT=[0,0,1] */
    private static float[] intentVector(Intent intent) {
        return switch (intent) {
            case GREETING -> vec(1, 0, 0);
            case OUT_OF_SCOPE -> vec(0, 1, 0);
            case AGENT -> vec(0, 0, 1);
            default -> vec(0, 0, 0);
        };
    }

    private static SemanticIntentClassifier classifier(FakeEmbeddingClient client) {
        return new SemanticIntentClassifier(client, 0.5);
    }

    @Test
    void 语义匹配_按意图方向分类() {
        // 查询文本按其预期意图映射到方向向量；种子向量相同方向 → cosine=1
        FakeEmbeddingClient client = new FakeEmbeddingClient(text -> {
            if (text.contains("天气") || text.contains("笑话") || text.contains("代码")) {
                return intentVector(Intent.OUT_OF_SCOPE);
            }
            if (text.contains("为什么") || text.contains("下降") || text.contains("排查")) {
                return intentVector(Intent.AGENT);
            }
            return intentVector(Intent.GREETING); // 种子/默认
        });
        SemanticIntentClassifier c = classifier(client);

        assertEquals(Intent.GREETING, c.classify("我爱你"));
        assertEquals(Intent.GREETING, c.classify("随便聊聊"));
        assertEquals(Intent.OUT_OF_SCOPE, c.classify("今天天气怎么样"));
        assertEquals(Intent.OUT_OF_SCOPE, c.classify("讲个笑话"));
        assertEquals(Intent.AGENT, c.classify("为什么订单量下降了"));
    }

    @Test
    void 低相似度_返回null降级() {
        // 查询向量与所有种子方向正交（零向量 cosine=0 < 阈值）→ null，调用方降级
        FakeEmbeddingClient zero = new FakeEmbeddingClient(text -> vec(0, 0, 0));
        SemanticIntentClassifier c = classifier(zero);
        assertNull(c.classify("随便什么"));
    }

    @Test
    void embedding不可用或失败_返回null() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(text -> intentVector(Intent.GREETING));
        client.fail = true;
        SemanticIntentClassifier c = classifier(client);
        assertNull(c.classify("我爱你"), "embedding 失败应降级为 null（调用方走规则/默认）");
    }

    @Test
    void 空白输入_返回null() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(text -> intentVector(Intent.GREETING));
        SemanticIntentClassifier c = classifier(client);
        assertNull(c.classify(""));
        assertNull(c.classify("  "));
        assertNull(c.classify(null));
    }
}
