package com.myxhs.ai.app.service.trace;

import java.util.List;

public record TraceDiagnosisResult(
        String traceId,
        String source,
        String verdict,
        String verificationStatus,
        String reviewerMode,
        String reviewerRationale,
        List<String> suspiciousEvents,
        List<String> hypotheses,
        List<String> nextActions,
        List<ServiceProfile> serviceProfiles,
        TraceSearchResult traceSearch,
        TraceServiceContextLoader.ServiceContext serviceContext,
        String renderedAnswer) {

    public record ClassSource(String className, String filePath, String snippet) {
    }

    public record ServiceProfile(
            String service,
            String layer,
            String role,
            List<String> keyServices,
            List<String> keyControllers,
            List<String> keyConsumers,
            String source,
            Owner owner,
            List<String> recentCommits,
            List<String> callChainHints,
            String primaryController,
            String primaryControllerPath,
            String primaryControllerSnippet,
            String primaryService,
            String primaryServicePath,
            String primaryServiceSnippet,
            String diagnosisTriplet,
            String methodBlameSummary,
            String methodSnippet,
            String followupCodeQuestion,
            List<String> nextHops,
            List<ClassSource> classSources,
            String methodHint,
            String blameSummary,
            String changeExplanation) {
    }

    public record Owner(
            String name,
            String email,
            String commit,
            String summary) {
    }
}
