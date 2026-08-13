package com.myxhs.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * 指标工具访问接口（D2 收尾）：Agent/路由通过该接口取 3 个真实只读指标。
 * 两种实现：DirectMetricToolAccess（直连 MySQL）/ McpToolBridge（经 MCP server）。
 * 切换：myxhs.ai.tools.mode=direct|mcp（默认 mcp）。
 */
public interface MetricToolAccess {

    @Tool("查询下单量（口径：排除已删、含取消/退款，按创建时间，Asia/Shanghai，窗口≤31天）")
    String queryOrderVolume(@P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd，如 2026-08-01~2026-08-07") String window);

    @Tool("查询支付成功率（口径：成功/(成功+失败)，排除待支付/退款；渠道为 Mock）")
    String paymentSuccessRate(@P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window);

    @Tool("查询内容互动量（口径：点赞/收藏/评论/分享，曝光单列）")
    String contentInteraction(@P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window);
}
