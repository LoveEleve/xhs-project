package com.myxhs.ai.approval;

import com.myxhs.ai.audit.AuditService;
import com.myxhs.ai.mq.DlqAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审批通过后的变更执行器（M2.0：intent→effect→settlement，DELTA-4）
 * <p>仅支持 dlq.redeliver；执行后必须核验（DLQ 是否有新增积压）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalExecutor {

    private static final long SETTLEMENT_WAIT_MS = 3000;

    private final DlqAdminService dlqAdminService;
    private final AuditService auditService;

    public Map<String, Object> execute(String tool, Map<String, Object> rawInput, Long actor) {
        if (!"dlq.redeliver".equals(tool)) {
            throw new IllegalArgumentException("不支持的审批执行工具: " + tool);
        }
        String group = String.valueOf(rawInput.get("group"));
        String msgId = String.valueOf(rawInput.get("msgId"));
        String originalTopic = rawInput.get("originalTopic") == null ? null : String.valueOf(rawInput.get("originalTopic"));

        auditService.record(actor, "dlq.redeliver.effect.start", "group=" + group + ",msgId=" + msgId, rawInput, "running");
        try {
            long backlogBefore = dlqAdminService.dlqBacklog(group);
            Map<String, Object> effect = dlqAdminService.redeliver(group, msgId, originalTopic);
            Thread.sleep(SETTLEMENT_WAIT_MS);
            long backlogAfter = dlqAdminService.dlqBacklog(group);

            Map<String, Object> settlement = new LinkedHashMap<>();
            settlement.put("backlogBefore", backlogBefore);
            settlement.put("backlogAfter", backlogAfter);
            settlement.put("noNewDlq", backlogAfter <= backlogBefore);
            settlement.put("verdict", backlogAfter <= backlogBefore
                    ? "重投已发送且未产生新死信（消息仍在 DLQ 属正常，DLQ 保留历史）"
                    : "警告：重投后 DLQ 出现新增消息，请检查消费端");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("effect", effect);
            result.put("settlement", settlement);
            auditService.record(actor, "dlq.redeliver.settled", "group=" + group + ",msgId=" + msgId, rawInput,
                    "sendStatus=" + effect.get("sendStatus") + ",noNewDlq=" + settlement.get("noNewDlq"));
            return result;
        } catch (Exception e) {
            log.error("[审批执行] dlq.redeliver 失败 group={} msgId={}", group, msgId, e);
            auditService.record(actor, "dlq.redeliver.failed", "group=" + group + ",msgId=" + msgId, rawInput,
                    "error=" + e.getMessage());
            throw new IllegalStateException("重投执行失败: " + e.getMessage(), e);
        }
    }
}
