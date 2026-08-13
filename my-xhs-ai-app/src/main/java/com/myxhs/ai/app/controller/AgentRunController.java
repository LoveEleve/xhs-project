package com.myxhs.ai.app.controller;

import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.agent.harness.AgentStep;
import com.myxhs.ai.app.service.agent.harness.EvidenceChain;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * D4 受限诊断 Agent 端点（同步执行，V1；SSE 流式推送待 D5 异步化）。
 * 返回：runId + 状态 + 终止原因 + 全步骤（THINK/TOOL/证据）+ 证据链 + 最终答案。
 * 横切：每请求 traceId 入 MDC（响应==日志，logback %X{traceId}），与 AiQueryController 一致。
 */
@RestController
@RequestMapping("/api/ai/agent")
public class AgentRunController {

    private final AgentHarness agentHarness;

    public AgentRunController(AgentHarness agentHarness) {
        this.agentHarness = agentHarness;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        String traceId = java.util.UUID.randomUUID().toString().replace("-", "");
        MDC.put("traceId", traceId);
        try {
            AgentRun run = agentHarness.run(message);
            Map<String, Object> resp = new java.util.HashMap<>(toResponse(run));
            resp.put("traceId", traceId);
            return resp;
        } finally {
            MDC.remove("traceId");
        }
    }

    private static Map<String, Object> toResponse(AgentRun run) {
        List<StepView> steps = run.steps().stream().map(AgentRunController::toStepView).toList();
        List<String> evidence = run.evidenceChain().entries().stream()
                .map(EvidenceChain.Evidence::evidenceId).toList();
        long costMs = run.endedAt() == null ? 0
                : Duration.between(run.startedAt(), run.endedAt()).toMillis();
        return Map.of(
                "runId", run.runId(),
                "status", run.status().name(),
                "terminationReason", run.terminationReason() == null ? null : run.terminationReason().name(),
                "query", run.query(),
                "steps", steps,
                "evidence", evidence,
                "finalAnswer", run.finalAnswer(),
                "costMs", costMs);
    }

    private static StepView toStepView(AgentStep s) {
        var d = s.decision();
        return new StepView(s.stepNumber(), s.state(),
                d == null ? null : d.action(),
                d == null ? null : d.tool(),
                d == null ? null : d.reasoning(),
                s.toolResult(),
                s.evidenceRefs() == null ? List.of() : s.evidenceRefs());
    }

    public record StepView(int stepNumber, String state, String action, String tool,
                           String reasoning, String toolResult, List<String> evidenceRefs) {
    }
}
