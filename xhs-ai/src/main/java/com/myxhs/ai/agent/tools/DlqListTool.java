package com.myxhs.ai.agent.tools;

import com.myxhs.ai.audit.AuditService;
import com.myxhs.ai.mq.DlqAdminService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * DIAG-08：DLQ topic 清单（只读）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqListTool implements AgentTool {

    private final DlqAdminService dlqAdminService;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "dlq_topic_list";
    }

    @Override
    public String getDescription() {
        return "列出 RocketMQ 所有死信(DLQ) topic 及积压消息数。诊断消费失败、死信积压时首先调用本工具。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolSupport.schema(Map.of(), List.of());
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        try {
            List<Map<String, Object>> topics = dlqAdminService.listDlqTopics();
            auditService.record(ToolSupport.actor(param), "dlq.topic_list", "rocketmq",
                    Map.of("count", topics.size()), "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(Map.of("dlqTopics", topics)));
        } catch (Exception e) {
            log.warn("[工具] dlq_topic_list 失败: {}", e.getMessage());
            return ToolSupport.error(param, "DLQ 列表查询失败（内部错误）");
        }
    }
}
