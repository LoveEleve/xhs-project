package com.myxhs.ai.api;

import com.myxhs.ai.common.R;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final OpenAIChatModel chatModel;

    /** 单轮对话（同步） */
    @PostMapping("/chat")
    public Mono<R<String>> chat(@Valid @RequestBody ChatRequest request) {
        List<Msg> messages = List.of(new UserMessage(request.message()));
        return chatModel.stream(messages, List.of(), defaultOptions())
                .timeout(Duration.ofSeconds(120))
                .map(this::extractText)
                .collect(Collectors.joining())
                .map(R::ok)
                .onErrorResume(e -> {
                    log.error("[AI] 对话失败", e);
                    return Mono.just(R.fail(500, "模型调用失败，请稍后重试"));
                });
    }

    /** 流式对话（SSE：delta → done） */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest request) {
        List<Msg> messages = List.of(new UserMessage(request.message()));
        return chatModel.stream(messages, List.of(), defaultOptions())
                .timeout(Duration.ofSeconds(120))
                .map(this::extractText)
                .filter(text -> !text.isEmpty())
                .map(text -> ServerSentEvent.<String>builder().event("delta").data(text).build())
                .concatWith(Mono.just(ServerSentEvent.<String>builder().event("done").data("{}").build()))
                .onErrorResume(e -> {
                    log.error("[AI] 流式对话失败", e);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error").data("{\"message\":\"模型调用失败，请稍后重试\"}").build());
                });
    }

    private GenerateOptions defaultOptions() {
        return GenerateOptions.builder()
                .temperature(0.3)
                .maxTokens(1024)
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
