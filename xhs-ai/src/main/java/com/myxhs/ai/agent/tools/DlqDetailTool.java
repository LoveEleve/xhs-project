package com.myxhs.ai.agent.tools;

import com.myxhs.ai.audit.AuditService;
import com.myxhs.ai.log.EsLogSearchService;
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
 * DIAG-08：DLQ 消息详情 + 按 msgId 关联首错日志（只读）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqDetailTool implements AgentTool {

    private final DlqAdminService dlqAdminService;
    private final EsLogSearchService esLogSearchService;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "dlq_message_detail";
    }

    @Override
    public String getDescription() {
        return "查看指定消费组 DLQ 中的消息详情（msgId/keys/tags/重试次数/消息体/原始topic），"
                + "并自动按 msgId 关联 Elasticsearch 中的首次失败日志。group 必填；msgId 可选（缺省取最新一条）。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("group", Map.of("type", "string", "description", "消费组名（不带 %DLQ% 前缀），如 cart-event-sink-group"));
        props.put("msgId", Map.of("type", "string", "description", "消息 ID（可选，缺省取最新一条）"));
        return ToolSupport.schema(props, List.of("group"));
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String group = ToolSupport.arg(param, "group");
        String msgId = ToolSupport.arg(param, "msgId");
        if (group == null || group.isBlank()) {
            return ToolSupport.error(param, "缺少必填参数 group");
        }
        if (!group.matches("[A-Za-z0-9._-]{1,64}")) {
            return ToolSupport.error(param, "group 参数格式非法");
        }
        if (msgId != null && !msgId.isBlank() && !msgId.matches("[A-Za-z0-9]{4,64}")) {
            return ToolSupport.error(param, "msgId 参数格式非法");
        }
        try {
            Map<String, Object> detail = dlqAdminService.messageDetail(group, msgId);
            Map<String, Object> firstFailure = esLogSearchService.firstFailure(
                    str(detail.get("originMsgId")), str(detail.get("msgId")), str(detail.get("keys")));
            detail.put("firstFailureLog", firstFailure);
            auditService.record(ToolSupport.actor(param), "dlq.message_detail", "group=" + group,
                    Map.of("msgId", String.valueOf(detail.get("msgId"))), "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(detail));
        } catch (Exception e) {
            log.warn("[工具] dlq_message_detail 失败 group={}: {}", group, e.getMessage());
            return ToolSupport.error(param, "DLQ 消息查询失败（内部错误）");
        }
    }
}
