package com.myxhs.ai.app.service.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * JDBC 实现：my_xhs_ai.ai_memory 表。
 * 向量语义检索：从 DB 加载用户所有记忆，在 Java 侧计算余弦相似度（MySQL 无原生向量支持）。
 * 用户量小（研发团队几十人），每人记忆几十条，全量加载 + Java 计算完全够用。
 */
public class JdbcMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcMemoryStore.class);
    private static final ObjectMapper om = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public JdbcMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String userId, String memoryKey, String valueJson, String embeddingJson, String category) {
        try {
            jdbc.update("""
                    INSERT INTO ai_memory (user_id, memory_key, value_json, embedding_json, category, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE value_json = VALUES(value_json), embedding_json = VALUES(embedding_json),
                        category = VALUES(category), updated_at = VALUES(updated_at)
                    """,
                    userId, memoryKey, valueJson, embeddingJson, category,
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        } catch (Exception e) {
            log.error("[memory] 保存失败: user={} key={}", userId, memoryKey, e);
        }
    }

    @Override
    public Optional<MemoryEntry> load(String userId, String memoryKey) {
        List<MemoryEntry> rows = jdbc.query(
                "SELECT * FROM ai_memory WHERE user_id=? AND memory_key=?",
                (rs, i) -> toEntry(rs), userId, memoryKey);
        return rows.stream().findFirst();
    }

    @Override
    public List<MemoryEntry> loadByCategory(String userId, String category, int limit) {
        return jdbc.query(
                "SELECT * FROM ai_memory WHERE user_id=? AND category=? ORDER BY updated_at DESC LIMIT ?",
                (rs, i) -> toEntry(rs), userId, category, limit);
    }

    @Override
    public List<MemoryEntry> loadAll(String userId, int limit) {
        return jdbc.query(
                "SELECT * FROM ai_memory WHERE user_id=? ORDER BY updated_at DESC LIMIT ?",
                (rs, i) -> toEntry(rs), userId, limit);
    }

    @Override
    public List<MemoryWithScore> searchBySimilarity(String userId, float[] queryEmbedding, int topK) {
        // 加载用户所有记忆（含 embedding）
        List<MemoryEntry> all = jdbc.query(
                "SELECT * FROM ai_memory WHERE user_id=? AND embedding_json IS NOT NULL ORDER BY updated_at DESC LIMIT 100",
                (rs, i) -> toEntry(rs), userId);

        // 计算余弦相似度，取 topK
        List<MemoryWithScore> scored = new ArrayList<>();
        for (MemoryEntry entry : all) {
            float[] memEmbedding = parseEmbedding(entry.embeddingJson());
            if (memEmbedding == null) continue;
            double score = cosineSimilarity(queryEmbedding, memEmbedding);
            scored.add(new MemoryWithScore(entry, score));
        }
        scored.sort(Comparator.comparingDouble(MemoryWithScore::score).reversed());
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    @Override
    public void delete(String userId, String memoryKey) {
        jdbc.update("DELETE FROM ai_memory WHERE user_id=? AND memory_key=?", userId, memoryKey);
    }

    @Override
    public int count(String userId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_memory WHERE user_id=?", Integer.class, userId);
        return c == null ? 0 : c;
    }

    private static MemoryEntry toEntry(ResultSet rs) throws SQLException {
        return new MemoryEntry(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("memory_key"),
                rs.getString("value_json"),
                rs.getString("embedding_json"),
                rs.getString("category"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    /** 解析 embedding JSON 数组为 float[] */
    private float[] parseEmbedding(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank()) return null;
        try {
            List<Double> list = om.readValue(embeddingJson, new TypeReference<List<Double>>() {});
            float[] arr = new float[list.size()];
            for (int i = 0; i < list.size(); i++) arr[i] = list.get(i).floatValue();
            return arr;
        } catch (Exception e) {
            return null;
        }
    }

    /** 余弦相似度 */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }
}
