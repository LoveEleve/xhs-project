package com.myxhs.ai.api;

import com.myxhs.ai.common.R;
import com.myxhs.ai.session.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话与消息查询（M2.0）
 */
@RestController
@RequestMapping("/api/ai/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionRepository sessionRepository;

    @GetMapping
    public R<List<Map<String, Object>>> sessions(@RequestParam(defaultValue = "20") int limit,
                                                 @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return R.ok(sessionRepository.listSessions(userId == null ? 0L : userId, limit));
    }

    @GetMapping("/{sessionId}/messages")
    public R<List<Map<String, Object>>> messages(@PathVariable("sessionId") String sessionId,
                                                 @RequestParam(defaultValue = "50") int limit,
                                                 @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return R.ok(sessionRepository.listMessages(sessionId, userId == null ? 0L : userId, limit));
    }
}
