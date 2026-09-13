package com.myxhs.ai.agent;

/**
 * Agent 工具预算护栏（M4）：工具数超软阈值告警、超硬阈值拒绝启动。
 * 硬预算要求"先实现 tool_search 渐进加载，再新增工具"，防止工具集无界膨胀。
 */
public final class ToolBudget {

    public enum Level { OK, WARN, FAIL }

    private ToolBudget() {
    }

    public static Level evaluate(int total, int soft, int hard) {
        if (total > hard) {
            return Level.FAIL;
        }
        if (total > soft) {
            return Level.WARN;
        }
        return Level.OK;
    }

    public static String failMessage(int total, int soft, int hard) {
        return "Agent 工具数 " + total + " 超过硬预算 " + hard
                + "（软阈值 " + soft + "）：请先实现 tool_search 渐进加载或裁剪白名单，再新增工具";
    }
}
