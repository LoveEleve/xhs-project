package com.myxhs.ai.app.service.agent.harness;

/**
 * Step 状态（契约类型化，替代散落字符串；ai_step.state 列持久化为 name()，兼容旧数据）。
 * 语义：一步（stepNumber）含多个状态——THINK(决策) + TOOL(执行) 同号，如 1,1,2,2。
 */
public enum StepState {
    THINK,
    POLICY_DENIED,
    TOOL,
    ANSWER
}
