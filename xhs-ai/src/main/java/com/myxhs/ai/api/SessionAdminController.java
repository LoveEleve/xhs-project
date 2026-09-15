package com.myxhs.ai.api;

import com.myxhs.ai.security.AiRoleResolver;
import com.myxhs.ai.session.SessionSummaryService;
import com.myxhs.ai.common.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话运维端点（ADMIN）：手动触发会话摘要（长会话压缩）
 */
@RestController
@RequestMapping("/api/ai/sessions")
@RequiredArgsConstructor
public class SessionAdminController {

    private final SessionSummaryService sessionSummaryService;
    private final AiRoleResolver roleResolver;

    @PostMapping("/{sessionId}/summarize")
    public ResponseEntity<R<Map<String, Object>>> summarize(@PathVariable("sessionId") String sessionId,
                                                            @RequestParam("userId") String userId,
                                                            @RequestParam(value = "force", defaultValue = "false") boolean force,
                                                            HttpServletRequest request) {
        if (!roleResolver.isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(403, "需要管理权限"));
        }
        return ResponseEntity.ok(R.ok(sessionSummaryService.summarize(sessionId, userId, force)));
    }
}
