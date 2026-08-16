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
    private static final long SSE_TIMEOUT_MS = 31 * 60_000L; // ≥31min（gateway response-timeout 对齐，2026-08-16）

    private final RunManager runManager;
    private final ObjectMapper om;
    /** 历史追溯（M8-4）：内存 TTL/重启后从 Run Store 回退读取 */
    private final com.myxhs.ai.app.service.store.RunStore runStore;

    public RunController(RunManager runManager, ObjectMapper om,
                         @org.springframework.beans.factory.annotation.Autowired(required = false)
                         com.myxhs.ai.app.service.store.RunStore runStore) {
        this.runManager = runManager;
        this.om = om;
        this.runStore = runStore;
    }

    @PostMapping
    public Map<String, String> submit(@RequestBody Map<String, String> body,
                                      @org.springframework.web.bind.annotation.RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        String message = body.getOrDefault("message", "");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        // Gateway 集成（2026-08-16）：X-User-Id header 优先（统一鉴权注入）、body 兜底（直连/开发兼容）
        String userId = firstNonBlank(headerUserId, body.get("userId"), "anonymous");
        // M10：显式 conversationId 优先；无则新建会话（响应带 convId，前端缓存用于后续多轮）
        String convId = body.get("conversationId");
        boolean freshConv = convId == null || convId.isBlank();
        if (freshConv) {
            convId = com.myxhs.ai.app.service.conversation.ConversationService.newConvId();
        }
        try {
            RunManager.RunEntry entry = runManager.submit(message, userId, convId);
            Map<String, String> resp = new java.util.LinkedHashMap<>();
            resp.put("runId", entry.runId());
            resp.put("status", "RECEIVED");
            resp.put("conversationId", convId);
            return resp;
        } catch (RunManager.ConversationBusyException e) {
            // 同会话并发（M10）：第二个活跃 run 拒绝（409 语义）
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 取消诊断任务（协作式：当前步完成后生效） */
    @DeleteMapping("/{runId}")
    public Map<String, String> cancel(@PathVariable String runId) {
        if (!runManager.cancel(runId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run 不存在或已完成: " + runId);
        }
        return Map.of("runId", runId, "status", "CANCELLING");
    }

    /** M11 HITL 审批：approve → 恢复执行被审批工具；reject → CANCELLED + 审计 */
    @PostMapping("/{runId}/approve")
    public Map<String, String> approve(@PathVariable String runId,
                                       @RequestBody(required = false) Map<String, String> body) {
        String decision = body == null ? null : body.get("decision");
        if (decision == null || !decision.equals("approve") && !decision.equals("reject")) {
            throw new IllegalArgumentException("decision 必须为 approve 或 reject");
        }
        String reason = body.get("reason");
        String approver = body.get("approver");
        if (!runManager.approve(runId, decision, reason, approver)) {
            // 无待审批/状态已变/重复审批
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "run " + runId + " 无待审批项或已被处理（仅 WAITING_APPROVAL 可审批）");
        }
        return Map.of("runId", runId, "decision", decision, "status",
                "approve".equals(decision) ? "APPROVED_RUNNING" : "REJECTED_CANCELLED");
    }

    @GetMapping("/{runId}")
    public Map<String, Object> get(@PathVariable String runId) {
        RunManager.RunEntry entry = runManager.get(runId);
        if (entry == null) {
            // 内存 miss（TTL 清理/重启）：从 Run Store 回退读取历史 run（M8-4 可追溯闭环）
            return viewFromStore(runId);
        }
        if (!entry.future().isDone()) {
            return Map.of("runId", runId, "status", "RUNNING", "query", entry.query());
        }
        AgentRun run = entry.future().join();
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "run 执行异常: " + runId);
        }
        // M11 HITL：挂起待审批（run 对象未终态，但状态=WAITING_APPROVAL + pendingApproval）
        if (run.status() == com.myxhs.ai.app.service.agent.harness.RunStatus.WAITING_APPROVAL) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("runId", runId);
            m.put("status", "WAITING_APPROVAL");
            m.put("query", run.query());
            m.put("pendingTool", run.pendingTool());
            m.put("pendingApproval", run.pendingApproval());
            return m;
        }
        return view(run);
    }

    /** 历史 run 视图（store 回退）：RunRecord + StepRecord 重建（finalAnswer/steps/evidence 全量可查） */
    private Map<String, Object> viewFromStore(String runId) {
        if (runStore == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run 不存在: " + runId);
        }
        var rec = runStore.loadRun(runId);
        if (rec.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run 不存在: " + runId);
        }
        var r = rec.get();
        var codec = new com.myxhs.ai.app.service.agent.harness.AgentDecisionCodec(om);
        List<Map<String, Object>> steps = new java.util.ArrayList<>();
        List<String> evidence = new java.util.ArrayList<>();
        for (var s : runStore.loadSteps(runId)) {
            var d = s.decisionJson() == null ? null : codec.parse(s.decisionJson());
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("stepNumber", s.stepNo());
            m.put("state", s.state());
            m.put("action", d == null ? null : d.action());
            m.put("tool", d == null ? null : d.tool());
            m.put("reasoning", d == null ? null : d.reasoning());
            m.put("toolResult", s.toolResult());
            m.put("evidenceRefs", s.evidenceIds() == null ? List.of()
                    : java.util.Arrays.asList(s.evidenceIds().split(",")));
            steps.add(m);
            if (s.evidenceIds() != null) {
                for (String ev : s.evidenceIds().split(",")) {
                    if (!ev.isBlank() && !evidence.contains(ev)) {
                        evidence.add(ev);
                    }
                }
            }
        }
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("runId", r.runId());
        m.put("status", r.status());
        m.put("terminationReason", r.terminationReason());
        m.put("query", r.query());
        m.put("steps", steps);
        m.put("evidence", evidence);
        m.put("finalAnswer", r.finalAnswer());
        m.put("costMs", r.startedAt() == null || r.endedAt() == null ? 0
                : java.time.Duration.between(r.startedAt(), r.endedAt()).toMillis());
        m.put("fromStore", true);
        return m;
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
            emitter.send(SseEmitter.event().name(event.type().name()).data(om.writeValueAsString(event)));
        } catch (Exception e) {
            log.warn("[sse] 推送失败 type={} err={}", event.type(), e.getMessage());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
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
        // 非诊断任务直答（问候/超范围，零步骤 COMPLETED）：说明未调用工具/模型，避免前端"空执行"误解
        if (run.steps().isEmpty() && run.terminationReason() == TerminationReason.COMPLETED) {
            m.put("note", "输入被识别为非诊断任务（问候/闲聊/超范围话题），直接应答，未调用工具/模型");
        }
        return m;
    }

    private static Map<String, Object> stepView(AgentStep s) {
        var d = s.decision();
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("stepNumber", s.stepNumber());
        m.put("state", s.state().name());
        m.put("action", d == null ? null : d.action());
        m.put("tool", d == null ? null : d.tool());
        m.put("reasoning", d == null ? null : d.reasoning());
        m.put("toolResult", s.toolResult());
        m.put("evidenceRefs", s.evidenceRefs() == null ? List.of() : s.evidenceRefs());
        return m;
    }
}
