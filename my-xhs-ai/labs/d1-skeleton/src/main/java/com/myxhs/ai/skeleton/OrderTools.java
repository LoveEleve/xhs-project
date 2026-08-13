package com.myxhs.ai.skeleton;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * D1 骨架用工具（mock 数据，模拟真实"业务指标语义层"工具）。
 * 真实实现 = my-xhs-ai-mcp 的 business-mcp（L1，只读，带口径/时间窗/来源）。
 * 工具输出必须是确定性 JSON（带 metric/口径/source），供契约测试逐字段校验。
 */
public class OrderTools {

    @Tool("查询指定时间窗内的订单量")
    public String queryOrderVolume(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd，如 2026-08-01~2026-08-07") String window) {
        return "{\"status\":\"ok\",\"metric\":\"order.query_volume\",\"window\":\""
                + window
                + "\",\"value\":12850,\"unit\":\"单\",\"source\":\"mock/order_daily_summary\"}";
    }

    @Tool("查询指定时间窗内按支付渠道分组的支付成功率")
    public String paymentSuccessRateByChannel(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return "{\"status\":\"ok\",\"metric\":\"payment.success_rate_by_channel\",\"window\":\""
                + window
                + "\",\"channels\":[{\"channel\":\"ALIPAY\",\"rate\":0.971},{\"channel\":\"WECHAT\",\"rate\":0.903}],"
                + "\"source\":\"mock/payment_daily_summary\"}";
    }
}
