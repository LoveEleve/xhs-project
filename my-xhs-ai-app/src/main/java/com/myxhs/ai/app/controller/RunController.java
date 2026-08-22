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
    public Map<String, String> submit(@RequestBody Map<String, Object> body,
                                      @org.springframework.web.bind.annotation.RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        String message = String.valueOf(body.getOrDefault("message", ""));
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        // Gateway 集成（2026-08-16）：X-User-Id header 优先（统一鉴权注入）、body 兜底（直连/开发兼容）
        String userId = firstNonBlank(headerUserId,
                body.get("userId") == null ? null : String.valueOf(body.get("userId")), "anonymous");
        // M10：显式 conversationId 优先；无则新建会话（响应带 convId，前端缓存用于后续多轮）
        String convId = body.get("conversationId") == null ? null : String.valueOf(body.get("conversationId"));
        boolean freshConv = convId == null || convId.isBlank();
        if (freshConv) {
            convId = com.myxhs.ai.app.service.conversation.ConversationService.newConvId();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> followupMeta = body.get("followupMeta") instanceof Map<?, ?> m
                    ? m.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue())))
                    : null;
            RunManager.RunEntry entry = runManager.submit(message, userId, convId,
                    followupMeta == null ? null : followupMeta.get("sourceKind"),
                    followupMeta == null ? null : followupMeta.get("sourceText"),
                    followupMeta == null ? null : followupMeta.get("sourceRunId"),
                    followupMeta == null ? null : followupMeta.get("sourceService"));
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
        return view(run, entry.traceDiagnosis(), entry.codeSearchResult(),
                entry.followupSourceKind(), entry.followupSourceText(),
                entry.followupSourceRunId(), entry.followupSourceService());
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

    private static Map<String, Object> view(AgentRun run,
                                            com.myxhs.ai.app.service.trace.TraceDiagnosisResult traceDiagnosis,
                                            com.myxhs.ai.app.service.knowledge.CodeSearchResult codeSearchResult,
                                            String followupSourceKind,
                                            String followupSourceText,
                                            String followupSourceRunId,
                                            String followupSourceService) {
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
        if (followupSourceKind != null && !followupSourceKind.isBlank()) {
            java.util.Map<String, Object> followup = new java.util.LinkedHashMap<>();
            followup.put("sourceKind", followupSourceKind);
            followup.put("sourceText", followupSourceText == null ? "" : followupSourceText);
            followup.put("sourceRunId", followupSourceRunId == null ? "" : followupSourceRunId);
            followup.put("sourceService", followupSourceService == null ? "" : followupSourceService);
            m.put("followupMeta", followup);
        }
        if (codeSearchResult != null) {
            Map<String, Object> code = new java.util.LinkedHashMap<>();
            code.put("query", codeSearchResult.query());
            code.put("summary", codeSearchResult.summary());
            code.put("topHit", hitView(codeSearchResult.topHit()));
            code.put("hits", codeSearchResult.hits().stream().map(RunController::hitView).toList());
            code.put("recommendedFollowups", buildCodeFollowups(codeSearchResult));
            m.put("codeSearch", code);
        }
        if (traceDiagnosis != null) {
            Map<String, Object> diagnosis = new java.util.LinkedHashMap<>();
            diagnosis.put("traceId", traceDiagnosis.traceId());
            diagnosis.put("source", traceDiagnosis.source());
            diagnosis.put("verdict", traceDiagnosis.verdict());
            diagnosis.put("verificationStatus", traceDiagnosis.verificationStatus());
            diagnosis.put("reviewerMode", traceDiagnosis.reviewerMode());
            diagnosis.put("reviewerRationale", traceDiagnosis.reviewerRationale());
            diagnosis.put("suspiciousEvents", traceDiagnosis.suspiciousEvents());
            diagnosis.put("hypotheses", traceDiagnosis.hypotheses());
            diagnosis.put("nextActions", traceDiagnosis.nextActions());
            diagnosis.put("serviceProfiles", traceDiagnosis.serviceProfiles().stream().map(p -> {
                Map<String, Object> profile = new java.util.LinkedHashMap<>();
                profile.put("service", p.service());
                profile.put("layer", p.layer());
                profile.put("role", p.role() == null ? "" : p.role());
                profile.put("keyServices", p.keyServices());
                profile.put("keyControllers", p.keyControllers());
                profile.put("keyConsumers", p.keyConsumers());
                profile.put("source", p.source() == null ? "" : p.source());
                profile.put("primaryController", p.primaryController() == null ? "" : p.primaryController());
                profile.put("primaryControllerPath", p.primaryControllerPath() == null ? "" : p.primaryControllerPath());
                profile.put("primaryControllerSnippet", p.primaryControllerSnippet() == null ? "" : p.primaryControllerSnippet());
                profile.put("primaryService", p.primaryService() == null ? "" : p.primaryService());
                profile.put("primaryServicePath", p.primaryServicePath() == null ? "" : p.primaryServicePath());
                profile.put("primaryServiceSnippet", p.primaryServiceSnippet() == null ? "" : p.primaryServiceSnippet());
                profile.put("diagnosisTriplet", p.diagnosisTriplet() == null ? "" : p.diagnosisTriplet());
                profile.put("methodBlameSummary", p.methodBlameSummary() == null ? "" : p.methodBlameSummary());
                profile.put("methodSnippet", p.methodSnippet() == null ? "" : p.methodSnippet());
                profile.put("followupCodeQuestion", p.followupCodeQuestion() == null ? "" : p.followupCodeQuestion());
                profile.put("methodHint", p.methodHint() == null ? "" : p.methodHint());
                profile.put("blameSummary", p.blameSummary() == null ? "" : p.blameSummary());
                profile.put("changeExplanation", p.changeExplanation() == null ? "" : p.changeExplanation());
                profile.put("nextHops", p.nextHops());
                profile.put("classSources", p.classSources().stream().map(cs -> {
                    Map<String, Object> m2 = new java.util.LinkedHashMap<>();
                    m2.put("className", cs.className());
                    m2.put("filePath", cs.filePath());
                    m2.put("snippet", cs.snippet() == null ? "" : cs.snippet());
                    return m2;
                }).toList());
                profile.put("owner", p.owner() == null ? Map.of() : Map.of(
                        "name", p.owner().name(),
                        "email", p.owner().email(),
                        "commit", p.owner().commit(),
                        "summary", p.owner().summary()));
                profile.put("recentCommits", p.recentCommits());
                profile.put("callChainHints", p.callChainHints());
                return profile;
            }).toList());
            if (traceDiagnosis.traceSearch() != null) {
                diagnosis.put("entryService", traceDiagnosis.traceSearch().entryService());
                diagnosis.put("lastService", traceDiagnosis.traceSearch().lastService());
                diagnosis.put("hitServices", traceDiagnosis.traceSearch().hits().stream().map(h -> h.service()).toList());
                diagnosis.put("hitServiceDetails", traceDiagnosis.traceSearch().hits().stream().map(h -> Map.of(
                        "service", h.service(),
                        "layer", h.layer(),
                        "matches", h.matches())).toList());
            }
            diagnosis.put("renderedAnswer", traceDiagnosis.renderedAnswer());
            diagnosis.put("recommendedFollowups", buildTraceFollowups(traceDiagnosis));
            m.put("traceDiagnosis", diagnosis);
        }
        if (run.steps().isEmpty() && "SUCCEEDED".equals(run.status().name())) {
            String answer = run.finalAnswer() == null ? "" : run.finalAnswer();
            if (answer.contains("来源：remote-es") || answer.contains("来源：local-log-fallback")) {
                m.put("note", "输入被识别为 requestId/traceId 请求流转诊断，直接返回结构化检索结果，未调用模型");
            } else {
                m.put("note", "输入被识别为非诊断任务（问候/闲聊/超范围话题），直接应答，未调用工具/模型");
            }
        }

        return m;
    }

    private static java.util.List<Map<String, Object>> buildCodeFollowups(com.myxhs.ai.app.service.knowledge.CodeSearchResult result) {
        if (result == null || result.topHit() == null) {
            return List.of();
        }
        var hit = result.topHit();
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (hit.primaryService() != null && hit.methodHint() != null) {
            out.add(followup("为什么优先看 " + hit.primaryService() + "." + hit.methodHint() + "()？", "code", 100));
        }
        if (hit.relatedTraceSamples() != null && !hit.relatedTraceSamples().isEmpty()) {
            out.add(followup("用真实 trace 看看 " + hit.primaryService() + "." + hit.methodHint() + "() 在哪条请求里最可疑：" + hit.relatedTraceSamples().get(0).traceId(), "trace", 90));
        }
        if (hit.methodHint() != null) {
            out.add(followup(hit.service() + " 这个方法 " + hit.methodHint() + "() 负责什么？", "change", 80));
        }
        return out;
    }

    private static java.util.List<Map<String, Object>> buildTraceFollowups(com.myxhs.ai.app.service.trace.TraceDiagnosisResult diagnosis) {
        if (diagnosis == null || diagnosis.serviceProfiles() == null || diagnosis.serviceProfiles().isEmpty()) {
            return List.of();
        }
        var first = diagnosis.serviceProfiles().get(0);
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (first.followupCodeQuestion() != null && !first.followupCodeQuestion().isBlank()) {
            out.add(followup(first.followupCodeQuestion(), "code", 100));
        }
        if (first.primaryService() != null && first.methodHint() != null) {
            out.add(followup("帮我继续看 " + diagnosis.traceId() + " 里 " + first.primaryService() + "." + first.methodHint() + "() 最近谁改过？", "change", 90));
        }
        if (first.diagnosisTriplet() != null && !first.diagnosisTriplet().isBlank()) {
            out.add(followup(first.diagnosisTriplet(), "trace", 80));
        }
        return out;
    }

    private static Map<String, Object> followup(String text, String kind, int priority) {
        return Map.of("text", text, "kind", kind, "priority", priority, "suggestedConversationInput", text);
    }

    private static Map<String, Object> hitView(com.myxhs.ai.app.service.knowledge.CodeSearchResult.Hit hit) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("title", hit.title());
        m.put("service", hit.service());
        m.put("role", hit.role());
        m.put("semanticRole", hit.semanticRole());
        m.put("question", hit.question());
        m.put("clientClass", hit.clientClass());
        m.put("callerService", hit.callerService());
        m.put("calleeService", hit.calleeService());
        m.put("source", hit.source());
        m.put("keyServices", hit.keyServices());
        m.put("keyControllers", hit.keyControllers());
        m.put("keyConsumers", hit.keyConsumers());
        m.put("keyFeignClients", hit.keyFeignClients());
        m.put("responsibilities", hit.responsibilities());
        m.put("owner", hit.owner() == null ? Map.of() : Map.of(
                "name", hit.owner().name(),
                "email", hit.owner().email(),
                "commit", hit.owner().commit(),
                "summary", hit.owner().summary()));
        m.put("recentCommits", hit.recentCommits());
        m.put("blameSummary", hit.blameSummary() == null ? "" : hit.blameSummary());
        m.put("changeExplanation", hit.changeExplanation() == null ? "" : hit.changeExplanation());
        m.put("primaryService", hit.primaryService());
        m.put("primaryServicePath", hit.primaryServicePath());
        m.put("primaryServiceSnippet", hit.primaryServiceSnippet() == null ? "" : hit.primaryServiceSnippet());
        m.put("diagnosisTriplet", hit.diagnosisTriplet() == null ? "" : hit.diagnosisTriplet());
        m.put("methodBlameSummary", hit.methodBlameSummary() == null ? "" : hit.methodBlameSummary());
        m.put("methodSnippet", hit.methodSnippet() == null ? "" : hit.methodSnippet());
        m.put("relatedTraceSamples", hit.relatedTraceSamples().stream().map(s -> Map.of(
                "id", s.id(),
                "traceId", s.traceId(),
                "route", s.route(),
                "note", s.note())).toList());
        m.put("methodHint", hit.methodHint() == null ? "" : hit.methodHint());
        m.put("nextHops", hit.nextHops());
        m.put("score", hit.score());
        m.put("renderedText", hit.renderedText());
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
