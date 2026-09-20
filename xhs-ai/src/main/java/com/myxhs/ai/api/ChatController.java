package com.myxhs.ai.api;

import com.myxhs.ai.common.R;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 对话入口（M1-2 最小闭环：模型直答 + SSE 流式）
 * <p>Agent 工具循环/会话状态/HITL 在 M2 接入</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class ChatController {

    private final Model chatModel;

    /** 单轮对话（同步） */
    @PostMapping("/chat")
    public Mono<R<String>> chat(@Valid @RequestBody ChatRequest request,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        List<Msg> messages = List.of(new UserMessage(request.message()));
        return chatModel.stream(messages, List.of(), defaultOptions())
                // 2026-09-20 修复：原实现未注入用户上下文 → ModelGateway 预算检查整段绕过（未计量、可滥用）
                .contextWrite(ctx -> userId == null ? ctx
                        : ctx.put(com.myxhs.ai.model.TokenBudget.USER_ID_KEY, userId))
                .timeout(Duration.ofSeconds(120))
                .map(this::extractText)
                .collect(Collectors.joining())
                .map(text -> text == null || text.isBlank()
                        // RV-fix：reasoning 模型可能把 maxTokens 全耗在思考上（finish=length），
                        // 此时 content 为空——显式报错，绝不静默返回空字符串
                        ? R.<String>fail(503, "模型未返回有效内容（推理可能被截断），请重试或换个问法")
                        : R.ok(text))
                .onErrorResume(e -> {
                    if (e instanceof com.myxhs.ai.model.TokenBudget.ExceededException) {
                        log.warn("[AI] 预算拒绝: {}", e.getMessage());
                        return Mono.just(R.fail(429, e.getMessage()));
                    }
                    log.error("[AI] 对话失败", e);
                    return Mono.just(R.fail(500, "模型调用失败，请稍后重试"));
                });
    }

    /** 流式对话（SSE：delta → done） */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest request,
                                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        List<Msg> messages = List.of(new UserMessage(request.message()));
        return chatModel.stream(messages, List.of(), defaultOptions())
                .contextWrite(ctx -> userId == null ? ctx
                        : ctx.put(com.myxhs.ai.model.TokenBudget.USER_ID_KEY, userId))
                .timeout(Duration.ofSeconds(120))
                .map(this::extractText)
                .filter(text -> !text.isEmpty())
                .map(text -> ServerSentEvent.<String>builder().event("delta").data(text).build())
                .concatWith(Mono.just(ServerSentEvent.<String>builder().event("done").data("{}").build()))
                .onErrorResume(e -> {
                    if (e instanceof com.myxhs.ai.model.TokenBudget.ExceededException) {
                        log.warn("[AI] 流式预算拒绝: {}", e.getMessage());
                        return Flux.just(ServerSentEvent.<String>builder()
                                .event("error").data("{\"code\":429,\"message\":\"今日 AI 用量已用完\"}").build());
                    }
                    log.error("[AI] 流式对话失败", e);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error").data("{\"message\":\"模型调用失败，请稍后重试\"}").build());
                });
    }

    private GenerateOptions defaultOptions() {
        return GenerateOptions.builder()
                .temperature(0.3)
                // RV-fix：1024 对 reasoning 模型不够（推理吃满后 content 为空），提升到 4096
                .maxTokens(4096)
                .stream(true)
                .build();
    }

    private String extractText(ChatResponse response) {
        if (response.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.getContent()) {
            if (block instanceof TextBlock text && text.getText() != null) {
                sb.append(text.getText());
            }
        }
        return sb.toString();
    }

    /** 请求体 */
    public record ChatRequest(
            @NotBlank(message = "不能为空")
            @Size(max = 4000, message = "长度不能超过 4000")
            String message) {
    }
}
