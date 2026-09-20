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
 * 业务工具：用户优惠券（状态/门槛/有效期/使用订单）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponQueryTool implements AgentTool {

    private final BusinessQueryService businessQueryService;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "coupon_query";
    }

    @Override
    public String getDescription() {
        return "查某个用户最近 20 张优惠券：券名/类型/面额/使用门槛/有效期/状态（未使用/已使用/已过期）/使用订单。"
                + "排查“用户说券不能用/券没退回/券哪来的”时使用；参数 userId 必填，且必须取用户问题中明确给出的值，不得使用当前会话用户ID。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolSupport.schema(Map.of(
                "userId", Map.of("type", "integer", "description", "用户ID")), List.of("userId"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String userIdRaw = ToolSupport.arg(param, "userId");
        if (userIdRaw == null || userIdRaw.isBlank()) {
            return ToolSupport.error(param, "需要 userId 参数");
        }
        long userId;
        try {
            userId = Long.parseLong(userIdRaw.trim());
        } catch (NumberFormatException e) {
            return ToolSupport.error(param, "userId 必须为数字");
        }
        try {
            Map<String, Object> result = businessQueryService.couponQuery(userId);
            auditService.record(ToolSupport.actor(param), "business.coupon_query", "user=" + userId,
                    Map.of("userId", userId, "count", result.get("count")), "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(result));
        } catch (Exception e) {
            log.warn("[工具] coupon_query 失败: {}", e.getMessage());
            return ToolSupport.error(param, "优惠券查询失败: " + e.getMessage());
        }
    }
}
