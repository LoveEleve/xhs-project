package com.myxhs.ai.app.service.agent.profile;

import com.myxhs.ai.app.service.agent.harness.AgentBudget;

import java.util.Set;

/**
 * Agent 画像（M13 PoC）：领域化 prompt + 工具子集 + 预算。
 * 共享 AgentHarness 引擎——差异只在配置；FULL = 单 Agent 现状（对比基线）。
 */
public record AgentProfile(
        String id,
        String systemPrompt,
        Set<String> toolNames,
        AgentBudget budget) {

    public boolean allows(String tool) {
        return toolNames.contains(tool);
    }
}
