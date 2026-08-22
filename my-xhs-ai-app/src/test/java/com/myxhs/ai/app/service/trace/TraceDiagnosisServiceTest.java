package com.myxhs.ai.app.service.trace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceDiagnosisServiceTest {

    @Test
    void es命中时返回remoteEs来源() {
        TraceDiagnosisService service = new TraceDiagnosisService(traceId -> new TraceSearchResult(
                traceId,
                "remote-es",
                List.of(new TraceSearchResult.ServiceHit("my-xhs-order", "交易链路", 3)),
                List.of("my-xhs-payment"),
                List.of(),
                List.of(new TraceSearchResult.TraceEvent("2026-08-21T10:00:00", "my-xhs-order", "INFO", "pay amount ok")),
                "my-xhs-order",
                "my-xhs-order"), new TraceServiceContextLoader(), new TraceCallChainLoader(), new TraceNavigationLoader(), new TraceDiagnosisReviewer());
        TraceDiagnosisResult result = service.diagnoseStructured("abc123");
        String answer = result.renderedAnswer();
        assertTrue(answer.contains("来源：remote-es"), answer);
        assertTrue(answer.contains("my-xhs-order（交易链路） 命中"), answer);
        assertTrue(answer.contains("入口服务：my-xhs-order"), answer);
        assertTrue(answer.contains("时间线摘要："), answer);
        assertTrue(answer.contains("类职责：my-xhs-order -> 交易主链同步编排中心"), answer);
        assertTrue(answer.contains("关键服务类：OrderService、OrderTransactionService"), answer);
        assertTrue(answer.contains("源码落点：my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java"), answer);
        assertTrue(answer.contains("诊断分析："), answer);
        assertTrue(answer.contains("Controller -> OrderController"), answer);
        assertTrue(answer.contains("建议下一步："), answer);
        assertTrue(result.serviceProfiles().get(0).methodHint() != null && !result.serviceProfiles().get(0).methodHint().isBlank(), result.toString());
        assertTrue(result.serviceProfiles().get(0).blameSummary() != null && !result.serviceProfiles().get(0).blameSummary().isBlank(), result.toString());
        assertTrue(result.serviceProfiles().get(0).changeExplanation() != null && !result.serviceProfiles().get(0).changeExplanation().isBlank(), result.toString());
        assertTrue(result.serviceProfiles().get(0).diagnosisTriplet() != null && !result.serviceProfiles().get(0).diagnosisTriplet().isBlank(), result.toString());
        assertTrue(result.serviceProfiles().get(0).methodBlameSummary() != null && !result.serviceProfiles().get(0).methodBlameSummary().isBlank(), result.toString());
        assertTrue(result.serviceProfiles().get(0).methodSnippet() != null && !result.serviceProfiles().get(0).methodSnippet().isBlank(), result.toString());
        assertTrue(result.serviceProfiles().get(0).methodHint() == null || result.serviceProfiles().get(0).methodSnippet().contains(result.serviceProfiles().get(0).methodHint() + "("), result.toString());
        assertTrue(result.serviceProfiles().get(0).followupCodeQuestion() != null && !result.serviceProfiles().get(0).followupCodeQuestion().isBlank(), result.toString());
        assertTrue(result.hypotheses().size() >= 1, result.toString());
        assertTrue(result.nextActions().size() >= 1, result.toString());
        assertTrue(result.reviewerRationale() != null && !result.reviewerRationale().isBlank(), result.toString());
        assertTrue("continue".equals(result.verdict()) || "uncertain".equals(result.verdict()), result.toString());
    }

    @Test
    void 多服务命中时最后命中服务排第一() {
        TraceDiagnosisService service = new TraceDiagnosisService(traceId -> new TraceSearchResult(
                traceId,
                "remote-es",
                List.of(
                        new TraceSearchResult.ServiceHit("my-xhs-gateway", "入口/聚合层", 2),
                        new TraceSearchResult.ServiceHit("my-xhs-order", "交易链路", 3)),
                List.of(),
                List.of(),
                List.of(),
                "my-xhs-gateway",
                "my-xhs-order"), new TraceServiceContextLoader(), new TraceCallChainLoader(), new TraceNavigationLoader(), new TraceDiagnosisReviewer());
        TraceDiagnosisResult result = service.diagnoseStructured("abc123");
        assertTrue(!result.serviceProfiles().isEmpty(), result.toString());
        assertTrue("my-xhs-order".equals(result.serviceProfiles().get(0).service()), result.toString());
        assertTrue("OrderService".equals(result.serviceProfiles().get(0).primaryService()), result.toString());
    }

    @Test
    void 无命中时返回未检索到() {
        TraceDiagnosisService service = new TraceDiagnosisService(traceId -> new TraceSearchResult(
                traceId,
                "local-log-fallback",
                List.of(),
                List.of(),
                List.of("my-xhs-order"),
                List.of(),
                null,
                null), new TraceServiceContextLoader(), new TraceCallChainLoader(), new TraceNavigationLoader(), new TraceDiagnosisReviewer());
        TraceDiagnosisResult result = service.diagnoseStructured("abc123");
        String answer = result.renderedAnswer();
        assertTrue(answer.contains("未在local-log-fallback中检索到`abc123`") || answer.contains("未在local-log-fallback中检索到 `abc123`"), answer);
        assertTrue(answer.contains("my-xhs-order"), answer);
        assertTrue("uncertain".equals(result.verdict()), result.toString());
    }
}
