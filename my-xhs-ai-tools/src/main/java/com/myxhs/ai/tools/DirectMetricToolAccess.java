package com.myxhs.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * 指标工具直连实现：直接调用真实工具（不经 MCP）。
 * 用于：单元测试（H2）、无 MCP 场景降级、对比验证。
 * ⚠️ 具体类方法必须带 @Tool（AiServices 扫描具体类，不继承接口注解）。
 */
public class DirectMetricToolAccess implements MetricToolAccess {

    private final OrderMetricsTool order;
    private final PaymentMetricsTool payment;
    private final ContentInteractionTool content;
    private final BaselineWindowTool baseline;
    private final EventAnalyticsTool events;

    public DirectMetricToolAccess(OrderMetricsTool order, PaymentMetricsTool payment, ContentInteractionTool content) {
        this(order, payment, content, new BaselineWindowTool(), null);
    }

    public DirectMetricToolAccess(OrderMetricsTool order, PaymentMetricsTool payment, ContentInteractionTool content,
                                  BaselineWindowTool baseline) {
        this(order, payment, content, baseline, null);
    }

    public DirectMetricToolAccess(OrderMetricsTool order, PaymentMetricsTool payment, ContentInteractionTool content,
                                  BaselineWindowTool baseline, EventAnalyticsTool events) {
        this.order = order;
        this.payment = payment;
        this.content = content;
        this.baseline = baseline;
        this.events = events;
    }

    private static final String EVENTS_NOT_ASSEMBLED = "{\"status\":\"error\",\"error\":\"事件分析工具未装配\"}";

    @Override
    @Tool("查询下单量（口径：排除已删、含取消/退款，按创建时间，Asia/Shanghai，窗口≤31天）")
    public String queryOrderVolume(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd，如 2026-08-01~2026-08-07") String window) {
        return order.queryOrderVolume(window);
    }

    @Override
    @Tool("查询支付成功率（口径：成功/(成功+失败)，排除待支付/退款；渠道为 Mock）")
    public String paymentSuccessRate(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return payment.paymentSuccessRate(window);
    }

    @Override
    @Tool("查询内容互动量（口径：点赞/收藏/评论/分享，曝光单列）")
    public String contentInteraction(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return content.contentInteraction(window);
    }

    @Override
    @Tool("计算对比基线窗口（上一同长窗口，确定性；模型不得自行推算基线）")
    public String baselineWindow(
            @P("当前时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return baseline.baselineWindow(window);
    }

    @Override
    @Tool("查询电商漏斗各环节量（浏览/加购/下单/支付，窗口内）")
    public String funnelConversion(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return events == null ? EVENTS_NOT_ASSEMBLED : events.funnelConversion(window);
    }

    @Override
    @Tool("查询支付失败事件（按失败码聚合，窗口内）")
    public String paymentFailures(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return events == null ? EVENTS_NOT_ASSEMBLED : events.paymentFailures(window);
    }

    @Override
    @Tool("查询内容发布事件数（按天，窗口内）")
    public String notePublishEvents(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return events == null ? EVENTS_NOT_ASSEMBLED : events.notePublishEvents(window);
    }
}
