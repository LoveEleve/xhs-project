package com.myxhs.ai.app.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 研发用户级长期记忆服务（M10 Memory）。
 *
 * 谁在用：研发/运维人员（通过 AI 诊断台排查电商系统问题），不是电商终端用户。
 * 记什么：诊断结论、排查路径、常见问题、指标趋势——帮助 Agent 记住"这个研发上次查了什么"。
 * 怎么查：豆包 embedding 向量化 + 余弦相似度语义检索（非关键词匹配）。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 注入记忆条数上限 */
    private static final int INJECT_LIMIT = 5;
    /** 语义检索 topK */
    private static final int SEARCH_TOP_K = 3;
    /** 单条记忆注入最大长度 */
    private static final int SINGLE_MAX_LEN = 300;
    /** 同一分类最多保留记忆数 */
    private static final int MAX_PER_CATEGORY = 5;

    private final MemoryStore store;
    private final com.myxhs.ai.app.service.embedding.EmbeddingClient embeddingClient;
    private final ObjectMapper om;

    public MemoryService(MemoryStore store,
                         com.myxhs.ai.app.service.embedding.EmbeddingClient embeddingClient,
                         ObjectMapper om) {
        this.store = store;
        this.embeddingClient = embeddingClient;
        this.om = om;
    }

    /** 保存一条记忆（自动向量化） */
    public void save(String userId, String memoryKey, String valueJson, String category) {
        String embeddingJson = null;
        if (embeddingClient != null && embeddingClient.available()) {
            try {
                float[] vec = embeddingClient.embed(memoryKey + ": " + valueJson);
                embeddingJson = om.writeValueAsString(toDoubleList(vec));
            } catch (Exception e) {
                log.warn("[memory] 向量化失败，降级存储: {}", e.getMessage());
            }
        }
        store.save(userId, memoryKey, valueJson, embeddingJson, category);
        log.info("[memory] 保存: user={} key={} category={} hasEmbedding={}",
                userId, memoryKey, category, embeddingJson != null);
    }

    /** 查询用户所有记忆 */
    public List<MemoryEntry> list(String userId, int limit) {
        return store.loadAll(userId, limit);
    }

    /** 删除一条记忆 */
    public void delete(String userId, String memoryKey) {
        store.delete(userId, memoryKey);
    }

    /**
     * 组装记忆上下文：用当前 query 做语义检索，找到最相关的记忆注入。
     * 比"全量注入"更精准——只注入和当前问题相关的记忆。
     */
    public List<ChatMessage> buildMemoryContext(String userId, String currentQuery) {
        List<ChatMessage> msgs = new ArrayList<>();
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            return msgs;
        }

        List<MemoryEntry> memories;
        if (embeddingClient != null && embeddingClient.available() && currentQuery != null) {
            // 语义检索：用当前 query 的 embedding 找最相关的记忆
            try {
                float[] queryVec = embeddingClient.embed(currentQuery);
                List<MemoryStore.MemoryWithScore> results = store.searchBySimilarity(userId, queryVec, SEARCH_TOP_K);
                memories = results.stream()
                        .filter(r -> r.score() > 0.3) // 相似度阈值
                        .map(MemoryStore.MemoryWithScore::entry)
                        .toList();
                if (!memories.isEmpty()) {
                    log.info("[memory] 语义检索命中 {} 条: user={} query={}",
                            memories.size(), userId, currentQuery.substring(0, Math.min(50, currentQuery.length())));
                }
            } catch (Exception e) {
                log.warn("[memory] 语义检索失败，降级为最近记忆: {}", e.getMessage());
                memories = store.loadAll(userId, INJECT_LIMIT);
            }
        } else {
            // 降级：无 embedding 时取最近 N 条
            memories = store.loadAll(userId, INJECT_LIMIT);
        }

        if (memories.isEmpty()) {
            return msgs;
        }

        StringBuilder sb = new StringBuilder("你的历史诊断记忆（仅供参考，数据以当前工具查询为准）：\n");
        for (MemoryEntry m : memories) {
            String val = m.valueJson();
            if (val.length() > SINGLE_MAX_LEN) {
                val = val.substring(0, SINGLE_MAX_LEN) + "…";
            }
            sb.append("- [").append(m.category()).append("] ").append(val).append("\n");
        }
        msgs.add(SystemMessage.from(sb.toString()));
        return msgs;
    }

    /** 兼容旧调用（无 query 时降级为最近记忆） */
    public List<ChatMessage> buildMemoryContext(String userId) {
        return buildMemoryContext(userId, null);
    }

    /**
     * 从诊断结论中自动提取研发记忆（V1 规则抽取 + 向量化）。
     * 按诊断领域分类，非覆盖式——多次诊断累积，同一分类最多 MAX_PER_CATEGORY 条。
     */
    public void extractFromConclusion(String userId, String runId, String finalAnswer) {
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            return;
        }
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return;
        }

        // 提取结论段（"证据链："之前）
        String conclusion = finalAnswer;
        int idx = conclusion.indexOf("证据链：");
        if (idx > 0) {
            conclusion = conclusion.substring(0, idx).strip();
        }
        if (conclusion.length() > 500) {
            conclusion = conclusion.substring(0, 500) + "…";
        }

        // 分类：按关键词判断诊断领域
        String category = classifyConclusion(conclusion);
        String memoryKey = "diagnosis:" + category + ":" + System.currentTimeMillis();

        // 同一分类超出上限时删除最旧的
        List<MemoryEntry> existing = store.loadByCategory(userId, "diagnosis:" + category, MAX_PER_CATEGORY + 1);
        if (existing.size() >= MAX_PER_CATEGORY) {
            MemoryEntry oldest = existing.get(existing.size() - 1);
            store.delete(userId, oldest.memoryKey());
        }

        save(userId, memoryKey, "\"" + conclusion + "\"", "diagnosis:" + category);
        log.info("[memory] 自动提取: user={} runId={} category={}", userId, runId, category);
    }

    /** 按结论关键词分类（研发场景：诊断领域） */
    private static String classifyConclusion(String conclusion) {
        String lower = conclusion.toLowerCase();
        if (lower.contains("订单") || lower.contains("下单") || lower.contains("漏斗")) return "order";
        if (lower.contains("支付") || lower.contains("付款")) return "payment";
        if (lower.contains("内容") || lower.contains("笔记") || lower.contains("互动")) return "content";
        if (lower.contains("死信") || lower.contains("dlq") || lower.contains("mq")) return "mq";
        if (lower.contains("5xx") || lower.contains("错误") || lower.contains("延迟")) return "service";
        if (lower.contains("库存") || lower.contains("预扣")) return "inventory";
        return "general";
    }

    private static List<Double> toDoubleList(float[] arr) {
        List<Double> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add((double) f);
        return list;
    }
}
