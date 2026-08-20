package com.myxhs.ai.app.service.memory;

import java.util.List;
import java.util.Optional;

/**
 * 研发用户级长期记忆存储接口。
 * 
 * 记忆是给研发/运维用的，不是给电商终端用户的。
 * 存储内容：诊断结论、排查路径、常见问题、指标趋势——帮助 Agent 记住"这个研发上次查了什么"。
 * 
 * 向量语义检索：每条记忆附带 embedding（豆包 doubao-embedding-vision-large 2048维），
 * 查询时用余弦相似度匹配最相关的记忆，而非关键词精确匹配。
 */
public interface MemoryStore {

    /** 保存/更新一条记忆（upsert by userId+memoryKey，含向量） */
    void save(String userId, String memoryKey, String valueJson, String embeddingJson, String category);

    /** 兼容旧调用：无 embedding 的保存 */
    default void save(String userId, String memoryKey, String valueJson, String category) {
        save(userId, memoryKey, valueJson, null, category);
    }

    /** 查询单条记忆 */
    Optional<MemoryEntry> load(String userId, String memoryKey);

    /** 查询用户某分类的所有记忆 */
    List<MemoryEntry> loadByCategory(String userId, String category, int limit);

    /** 查询用户所有记忆（按更新时间倒序） */
    List<MemoryEntry> loadAll(String userId, int limit);

    /** 向量语义检索：返回用户记忆中与 queryEmbedding 余弦相似度最高的 topK 条 */
    List<MemoryWithScore> searchBySimilarity(String userId, float[] queryEmbedding, int topK);

    /** 删除一条记忆 */
    void delete(String userId, String memoryKey);

    /** 统计用户记忆数 */
    int count(String userId);

    /** 带相似度分数的记忆 */
    record MemoryWithScore(MemoryEntry entry, double score) {}
}
