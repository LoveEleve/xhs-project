package com.myxhs.ai.app.controller;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * D1 最小闭环：REST 接模型。
 * 后续：IntentRouter / Agent Harness / Run API / SSE（D1-D4）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private final ChatModel chatModel;

    public AiChatController(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "my-xhs-ai-app");
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        String reply = chatModel.chat(message);
        return Map.of("reply", reply);
    }
}
