package com.myxhs.ai.app.service.memory;

import java.time.LocalDateTime;

/**
 * 研发用户级长期记忆实体。
 * 存储研发/运维的诊断结论、排查路径、常见问题——帮助 Agent 记住"这个研发上次查了什么"。
 * embedding_json：2048维浮点向量（豆包 doubao-embedding-vision-large），用于语义检索。
 */
public record MemoryEntry(
        Long id,
        String userId,
        String memoryKey,
        String valueJson,
        String embeddingJson,
        String category,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
