package com.myxhs.ai.app.eval;

import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.agent.harness.AgentStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具使用分析（M6-2：ACI 优化数据源，参考 Anthropic"花更多时间优化工具而非 prompt"）。
 * 从 run 的 TOOL 步骤提取模式：
 *  - 发散嫌疑：同一工具不同参数调用 > 阈值（模型反复换窗口查同一指标=不收敛）
 *  - 重复调用：相同工具+相同参数（LoopDetector 已拦截，此处记录供分析）
 *  - 覆盖度：工具调用分布（业务 vs 观测）
 */
public class ToolUsageAnalyzer {

    /** 发散阈值：同一工具不同窗口调用数超过即标记（实测校准，M6 默认 4） */
    public static final int DIVERGENT_THRESHOLD = 4;

    public record ToolStat(String tool, int callCount, int uniqueWindows, boolean divergent) {
    }

    /** 分析一次 run 的工具使用；返回含 callCount/divergent/分布 的 Map */
    public static Map<String, Object> analyze(AgentRun run) {
        Map<String, List<String>> toolWindows = new LinkedHashMap<>();
        List<String> toolSequence = new ArrayList<>();
        for (AgentStep s : run.steps()) {
            var d = s.decision();
            if (d != null && d.isToolCall() && d.tool() != null) {
                String window = d.args() == null ? null : d.args().get("window");
                toolWindows.computeIfAbsent(d.tool(), k -> new ArrayList<>()).add(window);
                toolSequence.add(d.tool());
            }
        }

        List<ToolStat> stats = new ArrayList<>();
        boolean divergent = false;
        for (var e : toolWindows.entrySet()) {
            Set<String> uniqueWindows = new LinkedHashSet<>(e.getValue());
            boolean d = e.getValue().size() >= DIVERGENT_THRESHOLD && uniqueWindows.size() > 1;
            if (d) {
                divergent = true;
            }
            stats.add(new ToolStat(e.getKey(), e.getValue().size(), uniqueWindows.size(), d));
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalCalls", toolSequence.size());
        m.put("uniqueTools", toolWindows.size());
        m.put("divergent", divergent);
        m.put("tools", stats);
        return m;
    }
}
