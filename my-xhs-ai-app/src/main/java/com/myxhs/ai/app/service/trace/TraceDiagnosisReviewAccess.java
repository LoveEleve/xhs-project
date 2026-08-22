package com.myxhs.ai.app.service.trace;

public interface TraceDiagnosisReviewAccess {
    TraceDiagnosisReviewer.ReviewResult review(TraceDiagnosisReviewerInput input);
}
