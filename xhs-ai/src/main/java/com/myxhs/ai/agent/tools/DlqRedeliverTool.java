package com.myxhs.ai.agent.tools;

import com.myxhs.ai.approval.ApprovalExecutor;
import com.myxhs.ai.approval.ApprovalService;
import com.myxhs.ai.audit.AuditService;
import com.myxhs.ai.mq.DlqAdminService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OPS-01：DLQ 单条重投（变更操作，默认 ask）
 * <p>未审批不执行：首次调用仅生成审批单；审批通过由 ApprovalExecutor 执行 effect+settlement；
 * "always" 授权后同会话直接执行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqRedeliverTool implements AgentTool {

    private final DlqAdminService dlqAdminService;
    private final ApprovalService approvalService;
    private final ApprovalExecutor approvalExecutor;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "dlq_redeliver";
    }

    @Override
    public String getDescription() {
        return "【变更操作·需人工审批】把 DLQ 中的一条消息重投回原始 topic。首次调用只生成审批单，未审批不会执行；"
                + "审批通过后系统自动执行重投并核验。参数：group、msgId 必填；originalTopic 可选（默认取消息属性）；reason 可选。";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("group", Map.of("type", "string", "description", "消费组名（不带 %DLQ% 前缀）"));
        props.put("msgId", Map.of("type", "string", "description", "要重投的消息 ID"));
        props.put("originalTopic", Map.of("type", "string", "description", "原始 topic（可选，默认取消息属性）"));
        props.put("reason", Map.of("type", "string", "description", "重投原因（可选，审计用）"));
        return ToolSupport.schema(props, List.of("group", "msgId"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String group = ToolSupport.arg(param, "group");
        String msgId = ToolSupport.arg(param, "msgId");
        if (group == null || group.isBlank() || msgId == null || msgId.isBlank()) {
            return ToolSupport.error(param, "缺少必填参数 group/msgId");
        }
        Long actor = ToolSupport.actor(param);
        String sessionId = ToolSupport.sessionId(param);
        try {
            Map<String, Object> detail = dlqAdminService.messageDetail(group, msgId);
            String originalTopic = ToolSupport.arg(param, "originalTopic");
            if (originalTopic == null || originalTopic.isBlank()) {
                originalTopic = detail.get("originTopic") == null ? null : String.valueOf(detail.get("originTopic"));
            }
            Map<String, Object> rawInput = new LinkedHashMap<>();
            rawInput.put("group", group);
            rawInput.put("msgId", msgId);
            rawInput.put("originalTopic", originalTopic);
            rawInput.put("reason", ToolSupport.arg(param, "reason"));

            if (approvalService.hasGrant(actor, sessionId, "dlq.redeliver")) {
                Map<String, Object> execution = approvalExecutor.execute("dlq.redeliver", rawInput, actor);
                return ToolSupport.result(param, ToolSupport.json(Map.of(
                        "status", "executed_by_grant",
                        "message", "已按会话授权(always)直接执行重投并核验",
                        "execution", execution)));
            }

            Map<String, Object> pending = approvalService.createPending(actor, sessionId,
                    "dlq.redeliver", "mq.redeliver", rawInput, "ask", List.of("dlq:" + group));
            auditService.record(actor, "dlq.redeliver.intent", "group=" + group + ",msgId=" + msgId,
                    rawInput, "pending_approval");
            return ToolSupport.result(param, ToolSupport.json(Map.of(
                    "status", "pending_approval",
                    "approvalId", pending.get("approvalId"),
                    "risk", "ask",
                    "message", "已生成审批单（未审批不执行）。请告知用户：调用 POST /api/ai/approvals/"
                            + pending.get("approvalId") + "/reply，reply=once/always/reject。审批通过后将自动执行重投并核验。")));
        } catch (Exception e) {
            log.warn("[工具] dlq_redeliver 失败 group={} msgId={}: {}", group, msgId, e.getMessage());
            return ToolSupport.error(param, "提交重投审批失败: " + e.getMessage());
        }
    }
}
