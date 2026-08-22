package com.myxhs.ai.app.service.knowledge;

import com.myxhs.ai.app.service.trace.TraceDiagnosisResult;

import java.util.List;

public record CodeSearchResult(
        String query,
        String summary,
        Hit topHit,
        List<Hit> hits) {

    public record RelatedTraceSample(String id, String traceId, String route, String note) {
    }

    public record Hit(
            String title,
            String service,
            String role,
            String semanticRole,
            String question,
            String clientClass,
            String callerService,
            String calleeService,
            String source,
            List<String> keyServices,
            List<String> keyControllers,
            List<String> keyConsumers,
            List<String> keyFeignClients,
            List<String> responsibilities,
            TraceDiagnosisResult.Owner owner,
            List<String> recentCommits,
            String blameSummary,
            String changeExplanation,
            String primaryService,
            String primaryServicePath,
            String primaryServiceSnippet,
            String methodHint,
            String diagnosisTriplet,
            String methodBlameSummary,
            String methodSnippet,
            List<RelatedTraceSample> relatedTraceSamples,
            List<String> nextHops,
            int score,
            String renderedText) {
    }
}
