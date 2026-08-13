package com.myxhs.ai.app.service.agent.harness;

/**
 * 策略判定结果（设计 §2 VALIDATE）：
 *  - ALLOW：进入 TOOL 执行
 *  - DENY：记录 policy_denied，反馈模型重想（计入预算）
 *  - REQUIRES_APPROVAL：HITL 门（V1 无 L3 工具，恒不通过执行）
 */
public record PolicyDecision(boolean allowed, boolean requiresApproval, String reason) {

    public static PolicyDecision allow() {
        return new PolicyDecision(true, false, null);
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(false, false, reason);
    }

    public static PolicyDecision requiresApproval(String reason) {
        return new PolicyDecision(false, true, reason);
    }
}
