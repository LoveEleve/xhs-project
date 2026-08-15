package com.myxhs.ai.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.agent.harness.AgentBudget;
import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.app.service.store.RunStore;
import com.myxhs.ai.tools.LogSearchAccess;
import com.myxhs.ai.tools.MetricToolAccess;
import com.myxhs.ai.tools.ObsToolAccess;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D4 Agent Harness 装配：预算默认值/成本单价/非法输出容忍次数均可配置。
 */
@Configuration
public class HarnessConfig {

    @Bean
    public AgentHarness agentHarness(ChatModel chatModel, MetricToolAccess metricToolAccess,
                                     ObsToolAccess obsToolAccess, LogSearchAccess logSearchAccess,
                                     ObjectMapper mapper,
                                     @Value("${myxhs.ai.agent.max-steps:15}") int maxSteps,
                                     @Value("${myxhs.ai.agent.max-tokens:100000}") long maxTokens,
                                     @Value("${myxhs.ai.agent.max-cost:1.0}") double maxCost,
                                     @Value("${myxhs.ai.agent.price-per-1k-tokens:0.002}") double pricePer1k,
                                     @Value("${myxhs.ai.agent.max-invalid-answers:2}") int maxInvalidAnswers,
                                     @Value("${myxhs.ai.agent.tool-result-max-len:400}") int toolResultMaxLen,
                                     @Value("${myxhs.ai.llm.model:deepseek-v4-flash}") String modelName,
                                     RunStore runStore) {
        return new AgentHarness(chatModel, metricToolAccess, obsToolAccess, logSearchAccess, mapper,
                new AgentBudget(maxSteps, maxTokens, maxCost), pricePer1k, maxInvalidAnswers, runStore, modelName,
                toolResultMaxLen);
    }
}
