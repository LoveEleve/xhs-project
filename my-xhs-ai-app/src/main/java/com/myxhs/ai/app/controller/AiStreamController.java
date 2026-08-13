package com.myxhs.ai.app.controller;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * SSE 流式端点（D1：/api/runs/{id}/events 的前身）。
 * 事件：token → [DONE]，与 UI 差分渲染对接。
 */
@RestController
@RequestMapping("/api/ai")
public class AiStreamController {

    private final StreamingChatModel streamingChatModel;

    public AiStreamController(StreamingChatModel streamingChatModel) {
        this.streamingChatModel = streamingChatModel;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        SseEmitter emitter = new SseEmitter(120_000L);

        streamingChatModel.chat(message, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                send("token", Map.of("delta", partialResponse));
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                send("done", Map.of(
                        "inputTokens", completeResponse.tokenUsage().inputTokenCount(),
                        "outputTokens", completeResponse.tokenUsage().outputTokenCount()));
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("message", String.valueOf(error.getMessage()))));
                } catch (IOException ignored) {
                    // 连接已断
                }
                emitter.completeWithError(error);
            }

            private void send(String name, Object data) {
                try {
                    emitter.send(SseEmitter.event().name(name).data(data));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }
}
