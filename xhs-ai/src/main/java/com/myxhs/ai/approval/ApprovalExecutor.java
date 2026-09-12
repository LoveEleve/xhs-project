package com.myxhs.ai.approval;

import com.myxhs.ai.audit.AuditService;
import com.myxhs.ai.mq.DlqAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审批通过后的变更执行器（M2.0；RV10：非阻塞，核验交给 RedeliverVerifier）
 * <p>intent→effect→settlement：本类只做 effect 与"待核验"登记，不在请求线程 sleep。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalExecutor {

    private final DlqAdminService dlqAdminService;
    private final AuditService auditService;

    public Map<String, Object> execute(String tool, Map<String, Object> rawInput, Long actor) {
        return execute(tool, rawInput, actor, null);
    }

    public Map<String, Object> execute(String tool, Map<String, Object> rawInput, Long actor, String traceId) {
        if (!"dlq.redeliver".equals(tool)) {
            throw new IllegalArgumentException("不支持的审批执行工具: " + tool);
        }
        String group = rawInput.get("group") == null ? null : String.valueOf(rawInput.get("group"));
        String msgId = rawInput.get("msgId") == null ? null : String.valueOf(rawInput.get("msgId"));
        String originalTopic = rawInput.get("originalTopic") == null ? null : String.valueOf(rawInput.get("originalTopic"));
        if (group == null || msgId == null) {
            throw new IllegalArgumentException("审批参数缺失 group/msgId");
        }

        auditService.record(actor, "dlq.redeliver.effect.start", "group=" + group + ",msgId=" + msgId,
                rawInput, "running", traceId);
        try {
            long backlogBefore = dlqAdminService.dlqBacklog(group);
            Map<String, Object> effect = dlqAdminService.redeliver(group, msgId, originalTopic);

            Map<String, Object> settlement = new LinkedHashMap<>();
            settlement.put("status", "pending_verification");
            settlement.put("backlogBefore", backlogBefore);
            settlement.put("newMsgId", effect.get("newMsgId"));
            settlement.put("group", group);
            settlement.put("sentAt", System.currentTimeMillis());
            settlement.put("note", "已发送；异步核验器在 10 分钟窗口内检查是否再次进入 DLQ（消费位点核验为 M2.x）");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("effect", effect);
            result.put("settlement", settlement);
            auditService.record(actor, "dlq.redeliver.effect.sent", "group=" + group + ",msgId=" + msgId,
                    rawInput, "sendStatus=" + effect.get("sendStatus"), traceId);
            return result;
        } catch (Exception e) {
            log.error("[审批执行] dlq.redeliver 失败 group={} msgId={}", group, msgId, e);
            auditService.record(actor, "dlq.redeliver.failed", "group=" + group + ",msgId=" + msgId, rawInput,
                    "error=" + e.getMessage(), traceId);
            throw new IllegalStateException("重投执行失败: " + e.getMessage(), e);
        }
    }
}
