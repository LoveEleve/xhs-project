package com.myxhs.ai.app.service.agent.tracing;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D6 Langfuse Trace：run 级别 metadata 存储。
 * 
 * 由 RunManager 写入 userId/sessionId，AgentHarness 写入 model/tokens/cost，
 * LangfuseTracingListener 读取，避免频繁改 HarnessEvent 契约。
 */
@Component
public class RunMetadataStore {

    public record RunMetadata(
            String runId,
            String userId,
            String sessionId,
            String modelName,
            int inputTokens,
            int outputTokens,
            double cost
    ) {
        public RunMetadata withTokens(int in, int out, double cost) {
            return new RunMetadata(runId, userId, sessionId, modelName, in, out, cost);
        }
    }

    private final Map<String, RunMetadata> byRunId = new ConcurrentHashMap<>();

    public void put(RunMetadata metadata) {
        byRunId.put(metadata.runId(), metadata);
    }

    public RunMetadata get(String runId) {
        return byRunId.get(runId);
    }

    public void updateTokens(String runId, int input, int output, double cost) {
        RunMetadata m = byRunId.get(runId);
        if (m != null) {
            byRunId.put(runId, m.withTokens(input, output, cost));
        }
    }

    public void remove(String runId) {
        byRunId.remove(runId);
    }
}
