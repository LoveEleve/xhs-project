package com.myxhs.ai.app.service.trace;

import java.util.List;

public record TraceDiagnosisReviewerInput(
        String traceId,
        String source,
        List<String> hitServices,
        String entryService,
        String lastService,
        List<String> suspiciousEvents,
        List<String> hypotheses,
        List<String> nextActions,
        List<String> timelinePreview) {
}
