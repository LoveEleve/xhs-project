package com.myxhs.ai.app.service.trace;

import com.myxhs.ai.app.service.knowledge.CodeChangeInsightLoader;
import com.myxhs.ai.app.service.knowledge.CodeSearchResult;
import com.myxhs.ai.app.service.knowledge.CodeSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TraceDiagnosisService {

    private final TraceLogSearchAccess traceLogSearchAccess;
    private final TraceServiceContextLoader traceServiceContextLoader;
    private final TraceCallChainLoader traceCallChainLoader;
    private final TraceNavigationLoader traceNavigationLoader;
    private final TraceDiagnosisReviewAccess reviewer;
    private final CodeChangeInsightLoader codeChangeInsightLoader;
    private final CodeSearchService codeSearchService;

    public TraceDiagnosisService(TraceLogSearchAccess traceLogSearchAccess,
                                 TraceServiceContextLoader traceServiceContextLoader,
                                 TraceCallChainLoader traceCallChainLoader,
                                 TraceNavigationLoader traceNavigationLoader,
                                 TraceDiagnosisReviewAccess reviewer) {
        this(traceLogSearchAccess, traceServiceContextLoader, traceCallChainLoader,
                traceNavigationLoader, reviewer, new CodeChangeInsightLoader(), new CodeSearchService());
    }

    @Autowired
    public TraceDiagnosisService(TraceLogSearchAccess traceLogSearchAccess,
                                 TraceServiceContextLoader traceServiceContextLoader,
                                 TraceCallChainLoader traceCallChainLoader,
                                 TraceNavigationLoader traceNavigationLoader,
                                 TraceDiagnosisReviewAccess reviewer,
                                 CodeChangeInsightLoader codeChangeInsightLoader,
                                 CodeSearchService codeSearchService) {
        this.traceLogSearchAccess = traceLogSearchAccess;
        this.traceServiceContextLoader = traceServiceContextLoader;
        this.traceCallChainLoader = traceCallChainLoader;
        this.traceNavigationLoader = traceNavigationLoader;
        this.reviewer = reviewer;
        this.codeChangeInsightLoader = codeChangeInsightLoader;
        this.codeSearchService = codeSearchService;
    }

    public String diagnose(String traceId) {
        return diagnoseStructured(traceId).renderedAnswer();
    }

    public TraceDiagnosisResult diagnoseStructured(String traceId) {
        TraceSearchResult result = traceLogSearchAccess.searchByTraceId(traceId);
        if (result.hits().isEmpty()) {
            StringBuilder answer = new StringBuilder("未在").append(result.source()).append("中检索到 `")
                    .append(traceId).append("`");
            if (!result.failedServices().isEmpty()) {
                answer.append("。失败：").append(String.join("、", result.failedServices()));
            }
            answer.append("。建议扩大时间窗口，或降级到其他日志来源复核。");
            TraceDiagnosisResult draft = new TraceDiagnosisResult(traceId, result.source(), "uncertain", "evidence_missing", "bounded-reviewer-v1", null,
                    List.of(), List.of("当前没有足够日志命中证明请求真实链路"),
                    List.of("扩大时间窗口", "补充 traceId", "检查远程日志采集状态"), List.of(), result,
                    TraceServiceContextLoader.ServiceContext.empty(""), answer.toString());
            TraceDiagnosisReviewer.ReviewResult review = reviewer.review(toReviewerInput(draft));
            return new TraceDiagnosisResult(traceId, result.source(), review.verdict(), review.verificationStatus(), "bounded-reviewer-v1", review.rationale(),
                    draft.suspiciousEvents(), draft.hypotheses(), draft.nextActions(), List.of(), result,
                    draft.serviceContext(), draft.renderedAnswer());
        }
        StringBuilder answer = new StringBuilder("已按 requestId/traceId=`")
                .append(traceId).append("` 检索日志，来源：").append(result.source()).append("。\n\n命中服务：");
        answer.append(String.join("、", result.hits().stream()
                .map(hit -> hit.service() + (hit.layer().isBlank() ? "" : "（" + hit.layer() + "）") + " 命中")
                .toList()));
        if (!result.missedServices().isEmpty()) {
            answer.append("\n未命中服务：").append(String.join("、", result.missedServices()));
        }
        if (!result.failedServices().isEmpty()) {
            answer.append("\n失败服务：").append(String.join("、", result.failedServices()));
        }
        if (result.entryService() != null) {
            answer.append("\n入口服务：").append(result.entryService());
        }
        if (result.lastService() != null) {
            answer.append("\n最后命中服务：").append(result.lastService());
        }
        if (!result.timeline().isEmpty()) {
            answer.append("\n时间线摘要：");
            answer.append(String.join("；", result.timeline().stream().limit(3)
                    .map(ev -> ev.timestamp() + " " + ev.service() + " " + summarize(ev.message()))
                    .toList()));
        }
        TraceServiceContextLoader.ServiceContext ctx = result.lastService() == null
                ? TraceServiceContextLoader.ServiceContext.empty("")
                : traceServiceContextLoader.load(result.lastService());
        if (ctx.role() != null && !ctx.role().isBlank()) {
            answer.append("\n类职责：").append(ctx.service()).append(" -> ").append(ctx.role());
        }
        if (!ctx.services().isEmpty()) {
            answer.append("\n关键服务类：").append(String.join("、", ctx.services()));
        }
        if (!ctx.controllers().isEmpty()) {
            answer.append("\n关键控制器：").append(String.join("、", ctx.controllers()));
        }
        if (!ctx.consumers().isEmpty()) {
            answer.append("\n关键消费者：").append(String.join("、", ctx.consumers()));
        }
        if (ctx.primarySource() != null) {
            answer.append("\n源码落点：").append(ctx.primarySource());
        }
        java.util.List<TraceDiagnosisResult.ServiceProfile> serviceProfiles = serviceProfiles(result);
        appendServiceProfiles(answer, serviceProfiles);
        java.util.List<String> suspiciousEvents = suspiciousEvents(result);
        java.util.List<String> hypotheses = hypotheses(result, ctx);
        java.util.List<String> nextActions = nextActions(result, ctx);
        appendRuleAnalysis(answer, result, ctx, suspiciousEvents, hypotheses);
        answer.append("\n建议下一步：").append(String.join("；", nextActions));
        answer.append("\n\n以上分析基于远程日志、时间线和 service-map 规则；没有证据的部分不会当作确定结论。\n");
        TraceDiagnosisResult draft = new TraceDiagnosisResult(traceId, result.source(), "continue", "needs_more_evidence", "bounded-reviewer-v1", null,
                suspiciousEvents, hypotheses, nextActions, serviceProfiles, result, ctx, answer.toString());
        TraceDiagnosisReviewer.ReviewResult review = reviewer.review(toReviewerInput(draft));
        return new TraceDiagnosisResult(traceId, result.source(), review.verdict(), review.verificationStatus(), "bounded-reviewer-v1", review.rationale(),
                suspiciousEvents, hypotheses, nextActions, serviceProfiles, result, ctx, answer.toString());
    }

    private java.util.List<TraceDiagnosisResult.ServiceProfile> serviceProfiles(TraceSearchResult result) {
        java.util.List<TraceDiagnosisResult.ServiceProfile> profiles = result.hits().stream().map(hit -> {
            TraceServiceContextLoader.ServiceContext c = traceServiceContextLoader.load(hit.service());
            TraceNavigationLoader.Navigation nav = traceNavigationLoader.load(hit.service());
            CodeChangeInsightLoader.Insight insight = codeChangeInsightLoader.load(
                    nav.primaryServicePath() == null ? c.primarySource() : nav.primaryServicePath(),
                    hit.service(), nav.primaryService(), nav.nextHops());
            CodeSearchResult.Hit topHit = alignTopHit(hit.service(), c, nav);
            String primaryService = topHit == null || topHit.primaryService() == null ? nav.primaryService() : topHit.primaryService();
            String primaryServicePath = topHit == null || topHit.primaryServicePath() == null ? nav.primaryServicePath() : topHit.primaryServicePath();
            String primaryServiceSnippet = topHit == null || topHit.primaryServiceSnippet() == null ? nav.primaryServiceSnippet() : topHit.primaryServiceSnippet();
            String methodHint = topHit == null || topHit.methodHint() == null ? insight.methodHint() : topHit.methodHint();
            String diagnosisTriplet = CodeChangeInsightLoader.renderDiagnosisTriplet(
                    primaryServicePath == null ? c.primarySource() : primaryServicePath,
                    methodHint,
                    topHit == null || topHit.recentCommits() == null || topHit.recentCommits().isEmpty() ? insight.recentCommits() : topHit.recentCommits());
            return new TraceDiagnosisResult.ServiceProfile(hit.service(), hit.layer(), c.role(), c.services(),
                    c.controllers(), c.consumers(), c.primarySource(), c.owner(), c.recentCommits(),
                    traceCallChainLoader.summariesForService(hit.service()),
                    nav.primaryController(), nav.primaryControllerPath(), nav.primaryControllerSnippet(),
                    primaryService, primaryServicePath, primaryServiceSnippet,
                    diagnosisTriplet,
                    topHit == null || topHit.methodBlameSummary() == null ? insight.methodBlameSummary() : topHit.methodBlameSummary(),
                    topHit == null || topHit.methodSnippet() == null ? insight.methodSnippet() : topHit.methodSnippet(),
                    buildFollowupCodeQuestion(hit.service(), primaryService, methodHint),
                    nav.nextHops(), nav.classSources(),
                    methodHint,
                    topHit == null || topHit.blameSummary() == null ? insight.blameSummary() : topHit.blameSummary(),
                    topHit == null || topHit.changeExplanation() == null ? insight.changeExplanation() : topHit.changeExplanation());
        }).toList();
        if (result.lastService() == null || result.lastService().isBlank()) {
            return profiles;
        }
        return profiles.stream()
                .sorted((a, b) -> Boolean.compare(!a.service().equals(result.lastService()), !b.service().equals(result.lastService())))
                .toList();
    }

    private CodeSearchResult.Hit alignTopHit(String service,
                                              TraceServiceContextLoader.ServiceContext ctx,
                                              TraceNavigationLoader.Navigation nav) {
        String question = buildCodeQuestion(service, ctx, nav);
        if (question == null) {
            return null;
        }
        CodeSearchResult result = codeSearchService.searchStructured(question);
        return result == null ? null : result.topHit();
    }

    private static String buildCodeQuestion(String service,
                                            TraceServiceContextLoader.ServiceContext ctx,
                                            TraceNavigationLoader.Navigation nav) {
        if (service == null || service.isBlank()) {
            return null;
        }
        StringBuilder q = new StringBuilder(service).append(" 哪个类负责这里的主逻辑");
        if (nav != null && nav.primaryService() != null && !nav.primaryService().isBlank()) {
            q.append("，优先看 ").append(nav.primaryService());
        } else if (ctx != null && !ctx.services().isEmpty()) {
            q.append("，优先看 ").append(ctx.services().get(0));
        }
        return q.toString();
    }

    private static String buildFollowupCodeQuestion(String service, String primaryService, String methodHint) {
        if (service == null || service.isBlank()) {
            return null;
        }
        StringBuilder q = new StringBuilder(service).append(" 这个主类/方法负责什么");
        if (primaryService != null && !primaryService.isBlank()) {
            q.append("，为什么优先看 ").append(primaryService);
            if (methodHint != null && !methodHint.isBlank()) {
                q.append(".").append(methodHint).append("()");
            }
        }
        q.append("？");
        return q.toString();
    }

    private static void appendServiceProfiles(StringBuilder answer,
                                              java.util.List<TraceDiagnosisResult.ServiceProfile> profiles) {
        if (profiles.isEmpty()) {
            return;
        }
        answer.append("\n链路剖面：");
        answer.append(String.join("；", profiles.stream().map(p -> p.service() + "="
                + (p.role() == null || p.role().isBlank() ? "职责未知" : p.role())
                + (p.keyServices().isEmpty() ? "" : ", 主类=" + p.keyServices().get(0))).toList()));
    }

    private static void appendRuleAnalysis(StringBuilder answer, TraceSearchResult result,
                                             TraceServiceContextLoader.ServiceContext ctx,
                                             java.util.List<String> suspicious,
                                             java.util.List<String> hypotheses) {

        answer.append("\n诊断分析：");
        if (!suspicious.isEmpty()) {
            answer.append("发现可疑事件：").append(String.join("；", suspicious)).append("。 ");
        } else {
            answer.append("当前样本未发现明确 ERROR/超时/失败信号。 ");
        }
        if (result.hits().size() == 1 && result.lastService() != null) {
            answer.append("请求在 ").append(result.lastService())
                    .append(" 服务留下可观测记录，但当前样本没有证明它继续到其他服务；优先检查该服务的 Controller/Service 边界。 ");
        } else if (result.lastService() != null) {
            answer.append("最后命中服务为 ").append(result.lastService())
                    .append("，它是当前证据链的末端；优先检查该服务到下游的调用或消息发布。 ");
        }
        if (ctx.role() != null && !ctx.role().isBlank()) {
            answer.append("结合其职责“").append(ctx.role()).append("”，最可疑代码层是 ");
            if (!ctx.controllers().isEmpty()) {
                answer.append("Controller -> ").append(ctx.controllers().get(0)).append("；");
            }
            if (!ctx.services().isEmpty()) {
                answer.append("Service -> ").append(ctx.services().get(0)).append("。");
            }
        }
        answer.append("\n分析结论：").append(String.join("；", hypotheses));
    }

    private static java.util.List<String> suspiciousEvents(TraceSearchResult result) {
        return result.timeline().stream()
                .filter(ev -> containsAny(ev.message(), "ERROR", "异常", "超时", "timeout", "失败", "exception", "5xx"))
                .map(ev -> ev.service() + "：" + summarize(ev.message()))
                .distinct()
                .limit(3)
                .toList();
    }

    private static java.util.List<String> hypotheses(TraceSearchResult result,
                                                      TraceServiceContextLoader.ServiceContext ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("日志命中服务是故障定位候选，不等于已证明根因");
        if (result.hits().size() == 1 && result.lastService() != null) {
            out.add("当前证据更像停留在 " + result.lastService() + " 本地边界，尚未证明请求继续流向下游服务");
        }
        if (ctx.role() != null && !ctx.role().isBlank()) {
            out.add("结合服务职责，优先排查 " + ctx.role() + " 对应的主控制器/主服务实现");
        }
        return out;
    }

    private static TraceDiagnosisReviewerInput toReviewerInput(TraceDiagnosisResult diagnosis) {
        java.util.List<String> hitServices = diagnosis.traceSearch() == null
                ? List.of()
                : diagnosis.traceSearch().hits().stream().map(TraceSearchResult.ServiceHit::service).toList();
        java.util.List<String> timelinePreview = diagnosis.traceSearch() == null
                ? List.of()
                : diagnosis.traceSearch().timeline().stream().limit(3)
                .map(ev -> ev.timestamp() + " " + ev.service() + " " + summarize(ev.message()))
                .toList();
        return new TraceDiagnosisReviewerInput(
                diagnosis.traceId(),
                diagnosis.source(),
                hitServices,
                diagnosis.traceSearch() == null ? null : diagnosis.traceSearch().entryService(),
                diagnosis.traceSearch() == null ? null : diagnosis.traceSearch().lastService(),
                diagnosis.suspiciousEvents(),
                diagnosis.hypotheses(),
                diagnosis.nextActions(),
                timelinePreview);
    }

    private static java.util.List<String> nextActions(TraceSearchResult result,
                                                      TraceServiceContextLoader.ServiceContext ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (ctx.controllers() != null && !ctx.controllers().isEmpty()) {
            out.add("检查 Controller：" + ctx.controllers().get(0));
        }
        if (ctx.services() != null && !ctx.services().isEmpty()) {
            out.add("检查 Service：" + ctx.services().get(0));
        }
        if (!result.missedServices().isEmpty()) {
            out.add("核对未命中服务是否本应出现在该请求链路中");
        }
        if (out.isEmpty()) {
            out.add("补充更多日志窗口后重新检索");
        }
        return out;
    }

    private static boolean containsAny(String value, String... needles) {
        String text = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String summarize(String text) {
        String clean = blank(text).replaceAll("\\s+", " ").trim();
        if (clean.length() <= 120) {
            return clean;
        }
        return clean.substring(0, 120) + "...";
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? "(无消息)" : text;
    }
}
