package com.myxhs.ai.app.controller;

import com.myxhs.ai.app.service.conversation.ConversationService;
import com.myxhs.ai.app.service.conversation.ConversationStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * M10 会话端点：
 *  - GET /api/conversations/{convId}：会话详情（消息列表 + summary + 关联 runId）
 *  - GET /api/conversations?userId=：会话列表（last_activity 倒序）
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final int MAX_MESSAGES = 100;

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping("/{convId}")
    public Map<String, Object> get(@PathVariable String convId) {
        ConversationStore.Conversation conv = conversationService.get(convId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + convId));
        List<Map<String, Object>> messages = conversationService.messages(convId, MAX_MESSAGES).stream()
                .map(m -> Map.<String, Object>of(
                        "role", m.role(), "content", m.content(),
                        "runId", m.runId() == null ? "" : m.runId(),
                        "createdAt", String.valueOf(m.createdAt())))
                .toList();
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("convId", conv.convId());
        r.put("userId", conv.userId());
        r.put("title", conv.title());
        r.put("summary", conv.summary());
        r.put("messageCount", conv.messageCount());
        r.put("messages", messages);
        return r;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
                                    @RequestParam(value = "limit", defaultValue = "20") int limit) {
        int capped = Math.min(Math.max(1, limit), 100);
        List<Map<String, Object>> items = conversationService.list(userId, capped).stream()
                .map(c -> Map.<String, Object>of(
                        "convId", c.convId(), "title", c.title() == null ? "" : c.title(),
                        "summary", c.summary() == null ? "" : c.summary(),
                        "messageCount", c.messageCount(),
                        "lastActivityAt", String.valueOf(c.lastActivityAt())))
                .toList();
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("userId", userId);
        r.put("conversations", items);
        return r;
    }
}
