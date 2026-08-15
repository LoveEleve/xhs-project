package com.myxhs.ai.app.service.agent.profile;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Agent 领域分派（M13 PoC，规则零成本）：AGENT 意图已定 → 业务归因 or 技术排障。
 * 错误分派代价低：工具子集外请求被 PolicyGuard deny → 模型重想/反馈，不产生错误执行。
 */
public class AgentDispatcher {

    /** Ops 强信号（服务/链路/日志/运维动作） */
    private static final List<Pattern> OPS_PATTERNS = List.of(
            compile("日志"), compile("检索"), compile("traceid"), compile("请求id"),
            compile("5xx"), compile("错误"), compile("报错"), compile("异常堆栈"),
            compile("延迟"), compile("慢"), compile("超时"), compile("积压"),
            compile("死信"), compile("死锁"), compile("主从"), compile("复制"),
            compile("服务"), compile("重启"), compile("重投"), compile("网关"),
            compile("nacos"), compile("注册中心"), compile("健康检查"), compile("actuator"));

    /** Business 强信号（业务指标归因） */
    private static final List<Pattern> BUSINESS_PATTERNS = List.of(
            compile("订单"), compile("支付"), compile("内容"), compile("互动"),
            compile("漏斗"), compile("转化"), compile("浏览"), compile("加购"),
            compile("退款"), compile("销售额"), compile("发布"), compile("下单"));

    public AgentProfile dispatch(String query) {
        if (query == null || query.isBlank()) {
            return AgentProfiles.BUSINESS;
        }
        String text = query.toLowerCase(Locale.ROOT);
        boolean ops = matches(OPS_PATTERNS, text);
        boolean biz = matches(BUSINESS_PATTERNS, text);
        // 冲突或无信号 → BUSINESS（默认；错误分派由工具集 deny 兜底）
        return ops && !biz ? AgentProfiles.OPS : AgentProfiles.BUSINESS;
    }

    private static boolean matches(List<Pattern> patterns, String text) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern compile(String keyword) {
        return Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
    }
}
