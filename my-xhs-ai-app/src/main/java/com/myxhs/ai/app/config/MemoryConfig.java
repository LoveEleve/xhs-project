package com.myxhs.ai.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.embedding.EmbeddingClient;
import com.myxhs.ai.app.service.memory.JdbcMemoryStore;
import com.myxhs.ai.app.service.memory.MemoryService;
import com.myxhs.ai.app.service.memory.MemoryStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 研发用户级长期记忆装配（M10 Memory）。
 * 使用 AI 专用数据源（my_xhs_ai 库，写账号 myxhs_ai_rw）。
 * 向量化：复用 EmbeddingClient（豆包 doubao-embedding-vision-large，2048维）。
 */
@Configuration
public class MemoryConfig {

    @Bean
    public MemoryStore memoryStore(@Qualifier("aiJdbcTemplate") JdbcTemplate aiJdbc) {
        return new JdbcMemoryStore(aiJdbc);
    }

    @Bean
    public MemoryService memoryService(MemoryStore memoryStore,
                                       EmbeddingClient embeddingClient,
                                       ObjectMapper om) {
        return new MemoryService(memoryStore, embeddingClient, om);
    }
}
