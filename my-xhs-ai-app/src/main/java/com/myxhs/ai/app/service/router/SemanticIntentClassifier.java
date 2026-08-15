package com.myxhs.ai.app.service.router;

import com.myxhs.ai.app.service.embedding.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图语义分类（M8-4 语义路由，取代词表穷举）：
 * 每个意图给 few-shot 种子示例（诊断侧封闭集已在 L0 规则层拦截，此处主要服务
 * GREETING/OUT_OF_SCOPE/AGENT 的语义区分），对输入 embedding 做余弦相似度分类。
 * 设计要点：
 *  - 零训练：种子即标注，冷启动快；新语言变体加种子即可，不维护词表
 *  - 低延迟低成本：单条消息一次 embedding 调用（doubao-embedding，与 RAG 共享基础设施）
 *  - 可降级：embedding 不可用/调用失败 → 返回 null，调用方走 LLM 兜底或默认意图
 *  - 种子向量懒加载 + 缓存（首次调用批量 embedAll）
 */
@Component
public class SemanticIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(SemanticIntentClassifier.class);

    /** 意图种子（few-shot）：数量克制、覆盖常见变体；命中阈值由相似度分数判定 */
    private static final Map<Intent, List<String>> SEEDS = new LinkedHashMap<>();

    static {
        SEEDS.put(Intent.GREETING, List.of(
                "你好", "您好", "在吗", "哈哈", "随便聊聊", "今天心情不错", "谢谢",
                "我爱你", "很高兴认识你", "hello", "你能帮我吗", "你是谁", "吃饭了吗"));
        SEEDS.put(Intent.OUT_OF_SCOPE, List.of(
                "今天天气怎么样", "讲个笑话", "帮我写段代码", "最近有什么新闻", "什么是Nacos",
                "给我推荐一部电影", "今天吃什么", "股票行情怎么样", "翻译一句话", "写首诗",
                "给我讲个故事", "周末去哪玩"));
        SEEDS.put(Intent.METRIC_ORDER_VOLUME, List.of(
                "订单量是多少", "查一下订单", "今天有多少订单", "订单总量", "看看订单数据"));
        SEEDS.put(Intent.METRIC_PAYMENT_RATE, List.of(
                "支付成功率是多少", "查一下支付数据", "付款成功率", "支付情况怎么样"));
        SEEDS.put(Intent.METRIC_CONTENT_INTERACTION, List.of(
                "内容互动量是多少", "点赞数多少", "内容互动数据", "评论数有多少"));
        SEEDS.put(Intent.AGENT, List.of(
                "为什么订单量下降了", "订单量为什么异常", "支付成功率为什么降低", "服务为什么有5xx",
                "MQ积压的原因", "帮我分析一下订单下降的原因", "为什么内容互动骤降", "系统为什么这么慢",
                "帮我排查一下系统问题", "死锁的原因是什么", "为什么有服务报错",
                "帮我查一下服务的日志", "查一下报错日志", "能帮我查日志吗",
                "帮我查一下这个请求的日志", "根据traceId查一下日志", "查一下这个单号的记录"));
    }

    private final EmbeddingClient embeddingClient;
    private final double threshold;

    /** 种子向量缓存（首次调用懒加载） */
    private volatile List<Intent> seedIntents;
    private volatile List<float[]> seedVectors;

    public SemanticIntentClassifier(EmbeddingClient embeddingClient,
                                    @Value("${myxhs.ai.router.semantic-threshold:0.42}") double threshold) {
        this.embeddingClient = embeddingClient;
        this.threshold = threshold;
    }

    /** 查询向量 LRU 缓存（限流防护：重复文本不重复调 embedding API） */
    private final java.util.Map<String, float[]> queryCache =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, float[]> eldest) {
                    return size() > 128;
                }
            });

    /** 语义分类：返回最相似意图（>阈值）或 null（模糊/embedding 不可用，调用方降级） */
    public Intent classify(String text) {
        if (text == null || text.isBlank() || !embeddingClient.available()) {
            return null;
        }
        try {
            List<float[]> seeds = seeds();
            float[] q = queryCache.get(text);
            if (q == null) {
                q = embeddingClient.embed(text);
                queryCache.put(text, q);
            }
            Intent best = null;
            double bestScore = -1;
            for (int i = 0; i < seeds.size(); i++) {
                double sim = cosine(q, seeds.get(i));
                if (sim > bestScore) {
                    bestScore = sim;
                    best = seedIntents.get(i);
                }
            }
            if (best != null && bestScore < threshold) {
                log.debug("[router] 语义模糊 score={} text={}", bestScore, text);
                return null;
            }
            return best;
        } catch (Exception e) {
            log.warn("[router] 语义分类失败，降级: {}", e.getMessage());
            return null;
        }
    }

    private List<float[]> seeds() {
        if (seedVectors != null) {
            return seedVectors;
        }
        synchronized (this) {
            if (seedVectors != null) {
                return seedVectors;
            }
            List<String> all = new ArrayList<>();
            List<Intent> intents = new ArrayList<>();
            for (Map.Entry<Intent, List<String>> e : SEEDS.entrySet()) {
                for (String s : e.getValue()) {
                    all.add(s);
                    intents.add(e.getKey());
                }
            }
            seedIntents = intents;
            seedVectors = embeddingClient.embedAll(all);
            log.info("[router] 语义种子就绪: {} 条示例", all.size());
            return seedVectors;
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
