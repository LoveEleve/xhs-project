package com.myxhs.ai.api;

import com.myxhs.ai.approval.ApprovalService;
import com.myxhs.ai.mq.DlqAdminService;
import com.myxhs.ai.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final DlqAdminService dlqAdminService;

    @Value("${myxhs.admin.token:}")
    private String adminToken;

    @Value("${myxhs.internal.token:}")
    private String internalToken;

    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String sessionId,
                                             @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return R.ok(approvalService.list(userId == null ? 0L : userId, status, sessionId));
    }

    @PostMapping("/{id}/reply")
    public ResponseEntity<R<Map<String, Object>>> reply(@PathVariable("id") Long id,
                                                        @RequestBody ApprovalReply request,
                                                        @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        try {
            return ResponseEntity.ok(
                    R.ok(approvalService.reply(userId == null ? 0L : userId, id, request.reply(), request.message())));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(400, e.getMessage()));
        }
    }

    /** 执行失败重试（仅 approved 且上次执行 failed/缺失） */
    @PostMapping("/{id}/execute")
    public ResponseEntity<R<Map<String, Object>>> retryExecution(@PathVariable("id") Long id,
                                                                 @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        try {
            return ResponseEntity.ok(R.ok(approvalService.retryExecution(userId == null ? 0L : userId, id)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(400, e.getMessage()));
        }
    }

    /** 消费组位点诊断（仅管理/内部令牌） */
    @GetMapping("/diagnostics/consumer-progress")
    public ResponseEntity<R<Map<String, Object>>> consumerProgress(
            @RequestParam("group") String group,
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        boolean privileged = (adminToken != null && !adminToken.isEmpty() && adminToken.equals(adminCall))
                || (internalToken != null && !internalToken.isEmpty() && internalToken.equals(internalCall));
        if (!privileged) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(403, "需要管理令牌"));
        }
        try {
            return ResponseEntity.ok(R.ok(dlqAdminService.consumerProgress(group)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(R.fail(500, "消费位点读取失败"));
        }
    }

    public record ApprovalReply(String reply, String message) {
    }
}
