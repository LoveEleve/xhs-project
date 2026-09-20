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
 * 业务工具：SKU 库存（可用/锁定/冻结 + TCC 冻结明细 + 补偿记录）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryQueryTool implements AgentTool {

    private final BusinessQueryService businessQueryService;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "inventory_query";
    }

    @Override
    public String getDescription() {
        return "查某个 SKU 的库存状况：可用/锁定/冻结库存、最近 TCC 冻结明细、最近库存补偿记录，并带商品名与价格。"
                + "排查“库存为什么少了/为什么下不了单/预扣与释放是否一致”时使用；参数 skuId 必填。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolSupport.schema(Map.of(
                "skuId", Map.of("type", "integer", "description", "SKU ID")), List.of("skuId"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String skuRaw = ToolSupport.arg(param, "skuId");
        if (skuRaw == null || skuRaw.isBlank()) {
            return ToolSupport.error(param, "需要 skuId 参数");
        }
        long skuId;
        try {
            skuId = Long.parseLong(skuRaw.trim());
        } catch (NumberFormatException e) {
            return ToolSupport.error(param, "skuId 必须为数字");
        }
        try {
            Map<String, Object> result = businessQueryService.inventoryQuery(skuId);
            auditService.record(ToolSupport.actor(param), "business.inventory_query", "sku=" + skuId,
                    Map.of("skuId", skuId), "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(result));
        } catch (Exception e) {
            log.warn("[工具] inventory_query 失败: {}", e.getMessage());
            return ToolSupport.error(param, "库存查询失败: " + e.getMessage());
        }
    }
}
