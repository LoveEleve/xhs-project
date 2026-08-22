package com.myxhs.ai.app.service.trace;

import java.util.List;

public record TraceSearchResult(
        String traceId,
        String source,
        List<ServiceHit> hits,
        List<String> missedServices,
        List<String> failedServices,
        List<TraceEvent> timeline,
        String entryService,
        String lastService) {

    public record ServiceHit(String service, String layer, int matches) {
    }

    public record TraceEvent(String timestamp, String service, String level, String message) {
    }
}
