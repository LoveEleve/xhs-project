package com.myxhs.ai.api;

import com.myxhs.ai.approval.ApprovalService;
import com.myxhs.ai.common.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * HITL 审批 API（M2.0，契约见 docs/design/02 §8）
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String sessionId,
                                             @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return R.ok(approvalService.list(userId == null ? 0L : userId, status, sessionId));
    }

    @PostMapping("/{id}/reply")
    public R<Map<String, Object>> reply(@PathVariable("id") Long id,
                                        @RequestBody ApprovalReply request,
                                        @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        try {
            return R.ok(approvalService.reply(userId == null ? 0L : userId, id, request.reply(), request.message()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.fail(400, e.getMessage());
        }
    }

    public record ApprovalReply(String reply, String message) {
    }
}
