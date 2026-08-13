package com.myxhs.ai.app.service.agent.harness;

/**
 * 终止原因（Run 结束时的定性，D4）。partial 结果=已收集证据原样返回，不假装全成。
 */
public enum TerminationReason {
    /** 正常完成（模型给出 ANSWER，证据通过存在性校验） */
    COMPLETED,
    /** 步骤数超预算 */
    BUDGET_STEPS,
    /** Token 超预算 */
    BUDGET_TOKENS,
    /** 成本超预算 */
    BUDGET_COST,
    /** 循环检测：连续同工具+同参数 */
    LOOP_REPEATED_CALL,
    /** 循环检测：M 步无新证据/新结论 */
    LOOP_NO_PROGRESS,
    /** 策略拒绝次数过多（模型反复请求非授权工具） */
    POLICY_EXHAUSTED,
    /** 存在性校验失败（答案引用未注册的工具结果，拒绝后模型仍不收敛） */
    EVIDENCE_INVALID,
    /** 模型不可用/超时（明确降级，不瞎编） */
    MODEL_UNAVAILABLE
}
