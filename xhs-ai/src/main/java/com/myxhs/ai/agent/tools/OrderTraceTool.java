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
 * 业务工具：单笔订单全链路追踪（订单/事件/支付/退款/库存预扣/通知）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTraceTool implements AgentTool {

    private final BusinessQueryService businessQueryService;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "order_trace";
    }

    @Override
    public String getDescription() {
        return "按订单号追踪单笔订单的完整链路：订单状态与明细、状态流转事件、支付单、退款单、库存预扣、关联通知。"
                + "排查“订单为什么没发货/退款到哪一步了/卡在哪个状态”时使用；必须同时提供下单用户ID（userId，用于分片路由）与订单号（orderNo）；userId 必须取用户问题中明确给出的值，不得使用当前会话用户ID。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolSupport.schema(Map.of(
                "userId", Map.of("type", "integer", "description", "下单用户ID（订单分片路由必需）"),
                "orderNo", Map.of("type", "string", "description", "订单号 order_no")), List.of("userId", "orderNo"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String orderNo = ToolSupport.arg(param, "orderNo");
        String userIdRaw = ToolSupport.arg(param, "userId");
        if (orderNo == null || orderNo.isBlank() || userIdRaw == null || userIdRaw.isBlank()) {
            return ToolSupport.error(param, "需要 userId 与 orderNo 两个参数（订单按用户ID分片路由）");
        }
        long userId;
        try {
            userId = Long.parseLong(userIdRaw.trim());
        } catch (NumberFormatException e) {
            return ToolSupport.error(param, "userId 必须为数字");
        }
        try {
            Map<String, Object> result = businessQueryService.orderTrace(userId, orderNo.trim());
            auditService.record(ToolSupport.actor(param), "business.order_trace", "order=" + orderNo,
                    Map.of("userId", userId, "found", result.get("found")), "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(result));
        } catch (Exception e) {
            log.warn("[工具] order_trace 失败: {}", e.getMessage());
            return ToolSupport.error(param, "订单链路查询失败: " + e.getMessage());
        }
    }
}
