package com.myxhs.ai.app.service.trace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceDiagnosisRegressionTest {

    private final BoundedTraceDiagnosisReviewer reviewer = new BoundedTraceDiagnosisReviewer();

    @Test
    void cleanMultiServiceTrace_isCompleteCandidate() {
        TraceDiagnosisReviewer.ReviewResult result = reviewer.review(new TraceDiagnosisReviewerInput(
                "5304dc5a8afb4741b8bc74cee49c3980", "remote-es",
                List.of("my-xhs-gateway", "my-xhs-order", "my-xhs-payment"),
                "my-xhs-gateway", "my-xhs-payment", List.of(), List.of(), List.of(),
                List.of("gateway", "order", "payment")));
        assertEquals("complete", result.verdict());
        assertEquals("evidence_sufficient", result.verificationStatus());
    }

    @Test
    void singleServiceError_isUncertain() {
        TraceDiagnosisReviewer.ReviewResult result = reviewer.review(new TraceDiagnosisReviewerInput(
                "f0d7831997f5442ebacebcb7d2aed1d3", "remote-es",
                List.of("my-xhs-order"), "my-xhs-order", "my-xhs-order",
                List.of("order business error"), List.of(), List.of(), List.of("order")));
        assertEquals("uncertain", result.verdict());
        assertEquals("hypothesis_only", result.verificationStatus());
    }

    @Test
    void multiServiceGatewayAnomaly_isUncertain() {
        TraceDiagnosisReviewer.ReviewResult result = reviewer.review(new TraceDiagnosisReviewerInput(
                "a429df1005fa43c48d156bd16564deff", "remote-es",
                List.of("my-xhs-gateway", "my-xhs-order", "my-xhs-payment"),
                "my-xhs-gateway", "my-xhs-payment", List.of("nonce anomaly"), List.of(), List.of(),
                List.of("gateway", "order", "payment")));
        assertEquals("uncertain", result.verdict());
        assertEquals("hypothesis_only", result.verificationStatus());
    }

    @Test
    void noEvidence_isUncertain() {
        TraceDiagnosisReviewer.ReviewResult result = reviewer.review(new TraceDiagnosisReviewerInput(
                "00000000000000000000000000000000", "remote-es", List.of(), null, null,
                List.of(), List.of(), List.of("retry"), List.of()));
        assertEquals("uncertain", result.verdict());
        assertEquals("missing_evidence", result.verificationStatus());
    }
}
