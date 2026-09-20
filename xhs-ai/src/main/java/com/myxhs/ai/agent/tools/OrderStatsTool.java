package com.myxhs.ai.agent.tools;

import com.myxhs.ai.audit.AuditService;
import com.myxhs.ai.business.BusinessQueryService;
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
 * 业务工具：经营指标（订单量/状态分布/GMV/支付分布/退款金额）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatsTool implements AgentTool {

    private final BusinessQueryService businessQueryService;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "order_stats";
    }

    @Override
    public String getDescription() {
        return "查经营指标（全量聚合）：窗口内订单总量、按状态分布、已付款GMV、支付单状态分布、退款成功金额。"
                + "回答“今天/最近N小时有多少订单、成交额多少、支付成功率、退款多少”这类经营问题时使用；参数 hours 默认24（最大720）。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolSupport.schema(Map.of(
                "hours", Map.of("type", "integer", "description", "统计窗口小时数，默认24，最大720")), List.of());
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        int hours = ToolSupport.intArg(param, "hours", 24);
        try {
            Map<String, Object> result = businessQueryService.orderStats(hours);
            auditService.record(ToolSupport.actor(param), "business.order_stats", "order",
                    Map.of("hours", hours, "totalOrders", result.get("totalOrders")), "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(result));
        } catch (Exception e) {
            log.warn("[工具] order_stats 失败: {}", e.getMessage());
            return ToolSupport.error(param, "经营指标查询失败: " + e.getMessage());
        }
    }
}
