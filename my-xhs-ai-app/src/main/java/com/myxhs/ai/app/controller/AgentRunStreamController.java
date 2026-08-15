package com.myxhs.ai.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.app.service.agent.harness.HarnessEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * D4 SSE 流式端点（设计 §8：同步执行 + 流式推送，客户端实时看调查过程）。
 * POST /api/ai/agent/run/stream → SSE 事件流：
 *   RUN_STARTED → THINK* → (TOOL | POLICY_DENIED)* → ANSWER → COMPLETED | PARTIAL | FAILED
 * 每个事件 data 为 HarnessEvent JSON；终态事件含 terminationReason + finalAnswer。
 * 说明：V1 仍同步执行（客户端断开不取消，事件推送失败仅记录）；异步化/取消属 D5。
 */
@RestController
@RequestMapping("/api/ai/agent")
public class AgentRunStreamController {

    private static final Logger log = LoggerFactory.getLogger(AgentRunStreamController.class);
    private static final long SSE_TIMEOUT_MS = 10 * 60_000L;

    private final AgentHarness agentHarness;
    private final ObjectMapper om;
    /** 设计 §8：中等并发上限（20），超出拒绝（防无限线程+长 run 耗尽资源） */
    private final ExecutorService executor = Executors.newFixedThreadPool(20);

    public AgentRunStreamController(AgentHarness agentHarness, ObjectMapper om) {
        this.agentHarness = agentHarness;
        this.om = om;
    }

    @PostMapping("/run/stream")
    public SseEmitter runStream(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String traceId = java.util.UUID.randomUUID().toString().replace("-", "");
        emitter.onCompletion(() -> log.info("[sse] run 流完成 traceId={}", traceId));
        emitter.onTimeout(() -> log.warn("[sse] run 流超时 traceId={}", traceId));
        try {
            executor.submit(() -> {
                MDC.put("traceId", traceId);
                try {
                    agentHarness.run(message, event -> send(emitter, event, traceId));
                    emitter.complete();
                } catch (Exception e) {
                    log.warn("[sse] run 执行异常 traceId={} err={}", traceId, e.getMessage());
                    try {
                        emitter.completeWithError(e);
                    } catch (Exception ignored) {
                        // 客户端已断开
                    }
                } finally {
                    MDC.remove("traceId");
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("[sse] 并发超限拒绝 traceId={}", traceId);
            throw new IllegalStateException("并发已满（上限 20），请稍后重试");
        }
        return emitter;
    }

    private void send(SseEmitter emitter, HarnessEvent event, String traceId) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type().name())
                    .data(om.writeValueAsString(event)));
        } catch (Exception e) {
            // 客户端断开/序列化异常：Harness 侧只记日志（emit 已兜底）
            log.warn("[sse] 事件推送失败 traceId={} type={} err={}", traceId, event.type(), e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
