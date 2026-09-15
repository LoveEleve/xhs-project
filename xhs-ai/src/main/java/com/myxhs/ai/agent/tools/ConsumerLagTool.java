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
 * OBS-03：消费积压 TopN（诊断"消费者跟不上/服务离线"）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumerLagTool implements AgentTool {

    private final DlqAdminService dlqAdminService;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "consumer_lag_top";
    }

    @Override
    public String getDescription() {
        return "查消费组积压 TopN（broker 位点 - 消费位点）。排查消费者跟不上/消费延迟/服务离线导致的积压时使用；参数 topN(默认10，最大50)。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolSupport.schema(Map.of("topN",
                Map.of("type", "integer", "description", "返回消费组数，默认 10，最大 50")), List.of());
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        int topN = Math.max(1, Math.min(50, ToolSupport.intArg(param, "topN", 10)));
        try {
            List<Map<String, Object>> items = dlqAdminService.consumerLagTop(topN);
            long total = items.stream().mapToLong(i -> ((Number) i.get("lag")).longValue()).sum();
            auditService.record(ToolSupport.actor(param), "consumer.lag_top", "rocketmq",
                    Map.of("topN", topN, "totalLag", total), "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(Map.of("totalLag", total, "items", items)));
        } catch (Exception e) {
            log.warn("[工具] consumer_lag_top 失败: {}", e.getMessage());
            return ToolSupport.error(param, "消费积压查询失败（RocketMQ Admin 不可用）");
        }
    }
}
