package com.myxhs.ai.app.controller;

import com.myxhs.ai.app.service.agent.MetricAssistant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent 工具循环端点（D1）：意图→工具→结果→带来源回答。
 * ⚠️ 需 my-xhs 真实 MySQL（myxhs 数据源）就绪；本地 H2 只验证了工具口径（OrderMetricsToolTest）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiAgentController {

    private final MetricAssistant metricAssistant;

    public AiAgentController(MetricAssistant metricAssistant) {
        this.metricAssistant = metricAssistant;
    }

    @PostMapping("/agent")
    public Map<String, String> agent(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        return Map.of("reply", metricAssistant.chat(message));
    }
}
