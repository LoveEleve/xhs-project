package com.myxhs.ai.model;

/**
 * 按用户 token 预算（F13）：软限切轻量模型、硬限拒绝（fail-closed）。
 * 计量来自模型网关的真实 usage，不采信模型自报。
 */
public final class TokenBudget {

    /** Reactor 上下文键：AgentService 写入，ModelGateway 读取 */
    public static final String USER_ID_KEY = "xhsai.budget.userId";

    public enum Decision { OK, SOFT, HARD }

    private TokenBudget() {
    }

    public static Decision decide(long used, long soft, long hard) {
        if (used >= hard) {
            return Decision.HARD;
        }
        if (used >= soft) {
            return Decision.SOFT;
        }
        return Decision.OK;
    }

    public static class ExceededException extends RuntimeException {
        public ExceededException(String message) {
            super(message);
        }
    }
}
