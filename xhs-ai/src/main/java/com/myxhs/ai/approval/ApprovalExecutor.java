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
 * <p>intent→effect→settlement：本类只做 effect 与"待核验"登记（队列坐标+位点基线），核验由 RedeliverVerifier 完成。</p>
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
            Long consumerOffsetBefore = null;
            try {
                consumerOffsetBefore = dlqAdminService.consumerOffsetSum(group);
            } catch (Exception e) {
                log.warn("[审批执行] 消费位点基线获取失败（不影响重投）: {}", e.getMessage());
            }
            Map<String, Object> effect = dlqAdminService.redeliver(group, msgId, originalTopic);

            Map<String, Object> settlement = new LinkedHashMap<>();
            settlement.put("status", "pending_verification");
            settlement.put("backlogBefore", backlogBefore);
            settlement.put("newMsgId", effect.get("newMsgId"));
            settlement.put("offsetMsgId", effect.get("offsetMsgId"));
            settlement.put("originTopic", effect.get("originTopic"));
            settlement.put("brokerName", effect.get("brokerName"));
            settlement.put("queueId", effect.get("queueId"));
            settlement.put("queueOffset", effect.get("queueOffset"));
            settlement.put("group", group);
            settlement.put("sentAt", System.currentTimeMillis());
            settlement.put("note", "已发送；核验器在 10 分钟窗口内检查是否再次进入 DLQ，并按消息所落队列的消费位点判断是否已消费");

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
