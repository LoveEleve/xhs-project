package com.myxhs.ai.app.service.agent.tracing;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D6 Langfuse Trace：run 级别的额外 metadata 存储。
 * 
 * 用途：
 * - HarnessEvent 不宜频繁扩字段（影响 SSE 契约），因此把 trace 额外信息（userId、sessionId、modelName、tokens、cost）
 *   单独存到这里，由 AgentHarness 写入、LangfuseTracingListener 读取。
 */
@Component
public class TraceContextStore {

    public record TraceContext(
            String runId,
            String userId,
            String sessionId,
            String modelName,
            int inputTokens,
            int outputTokens,
            double cost
    ) {
    }

    private final Map<String, TraceContext> byRunId = new ConcurrentHashMap<>();

    public void put(TraceContext ctx) {
        byRunId.put(ctx.runId(), ctx);
    }

    public TraceContext get(String runId) {
        return byRunId.get(runId);
    }

    public void remove(String runId) {
        byRunId.remove(runId);
    }
}
