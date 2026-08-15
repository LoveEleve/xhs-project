package com.myxhs.ai.app.config;

import com.myxhs.ai.app.service.conversation.ConversationStore;
import com.myxhs.ai.app.service.conversation.JdbcConversationStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M10 会话装配：ai_conversation/ai_message 复用 AI 自有库（aiJdbcTemplate，写账号 myxhs_ai_rw）。
 */
@Configuration
public class ConversationConfig {

    @Bean
    public ConversationStore conversationStore(JdbcTemplate aiJdbcTemplate) {
        return new JdbcConversationStore(aiJdbcTemplate);
    }
}
