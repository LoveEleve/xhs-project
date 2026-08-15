package com.myxhs.ai.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.agent.harness.AgentStep;
import com.myxhs.ai.app.service.agent.harness.EvidenceChain;
import com.myxhs.ai.app.service.agent.harness.HarnessEvent;
import com.myxhs.ai.app.service.agent.harness.TerminationReason;
import com.myxhs.ai.app.service.run.RunManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * M5-2 异步 Run 端点：
 *  - POST /api/runs：提交立即返回 runId（后台执行）
 *  - GET  /api/runs/{id}：状态/步骤/答案
 *  - GET  /api/runs/{id}/stream：SSE 订阅事件流（已缓冲事件补发 + 实时转发）
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private static final Logger log = LoggerFactory.getLogger(RunController.class);
    private static final long SSE_TIMEOUT_MS = 30 * 60_000L; // 覆盖最坏 run（15 步 × 单步最坏 120s）

    private final RunManager runManager;
    private final ObjectMapper om;

    public RunController(RunManager runManager, ObjectMapper om) {
        this.runManager = runManager;
        this.om = om;
    }

    @PostMapping
    public Map<String, String> submit(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        String userId = body.getOrDefault("userId", "anonymous");
        RunManager.RunEntry entry = runManager.submit(message, userId);
        return Map.of("runId", entry.runId(), "status", "RECEIVED");
    }

    /** 取消诊断任务（协作式：当前步完成后生效） */
    @DeleteMapping("/{runId}")
    public Map<String, String> cancel(@PathVariable String runId) {
        if (!runManager.cancel(runId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run 不存在或已完成: " + runId);
        }
        return Map.of("runId", runId, "status", "CANCELLING");
    }

    @GetMapping("/{runId}")
    public Map<String, Object> get(@PathVariable String runId) {
        RunManager.RunEntry entry = runManager.get(runId);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run 不存在: " + runId);
        }
        if (!entry.future().isDone()) {
            return Map.of("runId", runId, "status", "RUNNING", "query", entry.query());
        }
        AgentRun run = entry.future().join();
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "run 执行异常: " + runId);
        }
        return view(run);
    }

    @GetMapping("/{runId}/stream")
    public SseEmitter stream(@PathVariable String runId) {
        RunManager.RunEntry entry = runManager.get(runId);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run 不存在: " + runId);
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String traceId = java.util.UUID.randomUUID().toString().replace("-", "");
        // M8-4 修复：客户端断开/超时立即释放单消费者标志（否则 run 结束前新订阅全部 409）
        emitter.onTimeout(() -> {
            log.warn("[sse] run={} 流超时", runId);
            runManager.cancelStream(runId);
        });
        emitter.onCompletion(() -> {
            log.info("[sse] run={} 流完成", runId);
            runManager.cancelStream(runId);
        });
        MDC.put("traceId", traceId);
        try {
            boolean accepted = runManager.streamTo(runId, event -> send(emitter, event), emitter::complete);
            if (!accepted) {
                // 同一 run 已有活动订阅者（单消费者防事件竞争）
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "run " + runId + " 已有活动订阅者");
            }
        } finally {
            MDC.remove("traceId");
        }
        return emitter;
    }

    private void send(SseEmitter emitter, HarnessEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(om.writeValueAsString(event)));
        } catch (Exception e) {
            log.warn("[sse] 推送失败 type={} err={}", event.type(), e.getMessage());
        }
    }

    private static Map<String, Object> view(AgentRun run) {
        List<Map<String, Object>> steps = run.steps().stream().map(RunController::stepView).toList();
        List<String> evidence = run.evidenceChain().entries().stream()
                .map(EvidenceChain.Evidence::evidenceId).toList();
        long costMs = run.endedAt() == null ? 0
                : Duration.between(run.startedAt(), run.endedAt()).toMillis();
        // HashMap：Map.of 禁止 null 值（terminationReason 等可能为 null）
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("runId", run.runId());
        m.put("status", run.status().name());
        m.put("terminationReason", run.terminationReason() == null ? null : run.terminationReason().name());
        m.put("query", run.query());
        m.put("steps", steps);
        m.put("evidence", evidence);
        m.put("finalAnswer", run.finalAnswer());
        m.put("costMs", costMs);
        // 问候/闲聊直答（零步骤 COMPLETED）：说明未调用工具/模型，避免前端"空执行"误解
        if (run.steps().isEmpty() && run.terminationReason() == TerminationReason.COMPLETED) {
            m.put("note", "输入被识别为问候/闲聊（非诊断任务），直接应答，未调用工具/模型");
        }
        return m;
    }

    private static Map<String, Object> stepView(AgentStep s) {
        var d = s.decision();
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("stepNumber", s.stepNumber());
        m.put("state", s.state());
        m.put("action", d == null ? null : d.action());
        m.put("tool", d == null ? null : d.tool());
        m.put("reasoning", d == null ? null : d.reasoning());
        m.put("toolResult", s.toolResult());
        m.put("evidenceRefs", s.evidenceRefs() == null ? List.of() : s.evidenceRefs());
        return m;
    }
}
