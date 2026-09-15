package com.myxhs.ai.api;

import com.myxhs.ai.agent.AgentService;
import com.myxhs.ai.web.IdempotencyService;
import com.myxhs.ai.common.R;
import com.myxhs.ai.web.TraceIdFilter;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 对话入口（M2.0）：支持工具编排与审批事件
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final IdempotencyService idempotencyService;

    @PostMapping("/chat")
    public Mono<R<Map<String, Object>>> chat(@Valid @RequestBody AgentChatRequest request,
                                             @RequestHeader(value = "X-User-Id", required = false) Long userId,
                                             @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long uid = userId == null ? 0L : userId;
        String sessionId = resolveSession(request.sessionId(), uid);
        if (requestId != null && !requestId.isBlank()) {
            IdempotencyService.Result idem = idempotencyService.begin(uid, requestId);
            if (idem.state() == IdempotencyService.State.IN_FLIGHT) {
                return Mono.just(R.fail(409, "同一请求正在处理中，请勿重复提交"));
            }
            if (idem.state() == IdempotencyService.State.DONE) {
                return Mono.just(R.ok(Map.of("sessionId", idem.sessionId(), "reply", idem.reply(), "idempotent", true)));
            }
            return withErrorMapping(uid, sessionId, doChat(uid, sessionId, request)
                    .doOnNext(reply -> idempotencyService.complete(uid, requestId, sessionId, reply))
                    .doOnError(e -> idempotencyService.fail(uid, requestId)));
        }
        return withErrorMapping(uid, sessionId, doChat(uid, sessionId, request));
    }

    private Mono<String> doChat(long uid, String sessionId, AgentChatRequest request) {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return agentService.chat(uid, sessionId, request.message(), traceId)
                .timeout(Duration.ofSeconds(300))
                .map(reply -> reply == null || reply.isBlank() ? "" : reply);
    }

    /** 兼容：非幂等路径的错误映射（与 doChat 分离） */
    private Mono<R<Map<String, Object>>> withErrorMapping(long uid, String sessionId, Mono<String> replyMono) {
        return replyMono
                .map(reply -> reply.isBlank()
                        ? R.<Map<String, Object>>fail(503, "模型网关未返回内容（可能不稳定），请稍后重试")
                        : R.ok(Map.<String, Object>of("sessionId", sessionId, "reply", reply)))
                .onErrorResume(e -> {
                    if (e instanceof com.myxhs.ai.model.TokenBudget.ExceededException) {
                        log.warn("[Agent] 预算拒绝: {}", e.getMessage());
                        return Mono.just(R.fail(429, e.getMessage()));
                    }
                    log.error("[Agent] chat 失败", e);
                    return Mono.just(R.fail(500, "Agent 执行失败，请稍后重试"));
                });
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody AgentChatRequest request,
                                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        long uid = userId == null ? 0L : userId;
        String sessionId = resolveSession(request.sessionId(), uid);
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        StringBuilder finalText = new StringBuilder();
        return agentService.stream(uid, sessionId, request.message(), traceId)
                .timeout(Duration.ofSeconds(300))
                .flatMap(event -> Flux.fromIterable(toSse(event, finalText)))
                .concatWith(Mono.fromSupplier(() -> finalText.length() == 0
                        ? sse("error", "{\"message\":\"模型网关未返回内容（可能不稳定），请稍后重试\"}")
                        : sse("done", "{\"sessionId\":\"" + sessionId + "\"}")))
                .doFinally(signal -> agentService.recordAssistant(sessionId, uid, finalText.toString(), traceId))
                .onErrorResume(e -> {
                    log.error("[Agent] stream 失败", e);
                    return Flux.just(sse("error", "{\"message\":\"Agent 执行失败，请稍后重试\"}"));
                });
    }

    // ---------- internal ----------

    private List<ServerSentEvent<String>> toSse(Event event, StringBuilder finalText) {
        List<ServerSentEvent<String>> events = new ArrayList<>();
        if (event.getType() == EventType.REASONING) {
            String text = extractText(event.getMessage());
            if (!text.isEmpty()) {
                events.add(sse("delta", jsonData(text)));
            }
        } else if (event.getType() == EventType.TOOL_RESULT) {
            String name = toolName(event.getMessage());
            String output = extractToolOutput(event.getMessage());
            events.add(sse("tool", jsonData(Map.of("name", name, "output", truncate(output, 800)))));
            if (isPendingApproval(output)) {
                events.add(sse("approval_required", output));
            }
        } else if (event.getType() == EventType.AGENT_RESULT && event.isLast()) {
            String text = extractText(event.getMessage());
            finalText.setLength(0);
            finalText.append(text);
            events.add(sse("final", jsonData(text)));
        } else if (event.getType() == EventType.HINT) {
            String text = extractText(event.getMessage());
            if (!text.isEmpty()) {
                events.add(sse("hint", jsonData(text)));
            }
        }
        return events;
    }

    private boolean isPendingApproval(String output) {
        if (output == null || !output.contains("pending_approval")) {
            return false;
        }
        try {
            return "pending_approval".equals(
                    new ObjectMapper().readTree(output).path("status").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private String toolName(io.agentscope.core.message.Msg msg) {
        if (msg != null && msg.getContent() != null) {
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolResultBlock result && result.getName() != null) {
                    return result.getName();
                }
            }
        }
        return msg == null || msg.getName() == null ? "tool" : String.valueOf(msg.getName());
    }

    private String extractToolOutput(io.agentscope.core.message.Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ToolResultBlock result) {
                for (ContentBlock out : result.getOutput()) {
                    if (out instanceof TextBlock text && text.getText() != null) {
                        sb.append(text.getText());
                    }
                }
            } else if (block instanceof TextBlock text && text.getText() != null) {
                sb.append(text.getText());
            }
        }
        return sb.toString();
    }

    private String extractText(io.agentscope.core.message.Msg msg) {
        return AgentService.extractText(msg);
    }

    private String resolveSession(String sessionId, long userId) {
        return sessionId == null || sessionId.isBlank() ? agentService.newSessionId(userId) : sessionId;
    }

    private ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    private String jsonData(Object value) {
        return com.myxhs.ai.agent.tools.ToolSupport.json(value);
    }

    private String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "...";
    }

    public record AgentChatRequest(
            @Size(max = 128, message = "长度不能超过 128")
            @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "格式非法")
            String sessionId,
            @NotBlank(message = "不能为空")
            @Size(max = 4000, message = "长度不能超过 4000")
            String message) {
    }
}
