package com.myxhs.ai.app.config;

import com.myxhs.ai.app.service.agent.MetricAssistant;
import com.myxhs.ai.tools.MetricToolAccess;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 装配：AiServices + MetricToolAccess（D2 收尾：默认经 MCP，direct 模式可切换）。
 */
@Configuration
public class AgentConfig {

    @Bean
    public MetricAssistant metricAssistant(ChatModel chatModel, MetricToolAccess metricToolAccess) {
        return AiServices.builder(MetricAssistant.class)
                .chatModel(chatModel)
                .tools(metricToolAccess)
                .build();
    }
}
