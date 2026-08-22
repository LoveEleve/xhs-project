package com.myxhs.ai.app.service.trace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TraceDiagnosisReviewerTest {

    private final BoundedTraceDiagnosisReviewer reviewer = new BoundedTraceDiagnosisReviewer();

    @Test
    void 单服务异常保持不确定() {
        TraceDiagnosisReviewer.ReviewResult result = reviewer.review(new TraceDiagnosisReviewerInput(
                "trace", "remote-es", List.of("my-xhs-order"), "my-xhs-order", "my-xhs-order",
                List.of("order error"), List.of("candidate"), List.of("check service"), List.of("event")));
        assertEquals("uncertain", result.verdict());
        assertEquals("hypothesis_only", result.verificationStatus());
    }

    @Test
    void 无命中证据保持不确定() {
        TraceDiagnosisReviewer.ReviewResult result = reviewer.review(new TraceDiagnosisReviewerInput(
                "trace", "remote-es", List.of(), null, null, List.of(), List.of(), List.of(), List.of()));
        assertEquals("uncertain", result.verdict());
        assertEquals("missing_evidence", result.verificationStatus());
    }

    @Test
    void 多服务缺时间线阻塞完成() {
        TraceDiagnosisReviewer.ReviewResult result = reviewer.review(new TraceDiagnosisReviewerInput(
                "trace", "remote-es", List.of("my-xhs-gateway", "my-xhs-payment"),
                "my-xhs-gateway", "my-xhs-payment", List.of(), List.of(), List.of(), List.of()));
        assertEquals("blocked", result.verdict());
        assertEquals("missing_timeline", result.verificationStatus());
    }

    @Test
    void 多服务存在异常保持不确定() {
        TraceDiagnosisReviewer.ReviewResult result = reviewer.review(new TraceDiagnosisReviewerInput(
                "trace", "remote-es", List.of("my-xhs-gateway", "my-xhs-order", "my-xhs-payment"),
                "my-xhs-gateway", "my-xhs-payment", List.of("nonce anomaly"), List.of(), List.of(), List.of("gateway", "order")));
        assertEquals("uncertain", result.verdict());
        assertEquals("hypothesis_only", result.verificationStatus());
    }

    @Test
    void 多服务无异常且时间线完整才允许完成候选() {
        TraceDiagnosisReviewer.ReviewResult result = reviewer.review(new TraceDiagnosisReviewerInput(
                "trace", "remote-es", List.of("my-xhs-gateway", "my-xhs-order", "my-xhs-payment"),
                "my-xhs-gateway", "my-xhs-payment", List.of(), List.of(), List.of(), List.of("gateway", "order", "payment")));
        assertEquals("complete", result.verdict());
        assertEquals("evidence_sufficient", result.verificationStatus());
    }
}
