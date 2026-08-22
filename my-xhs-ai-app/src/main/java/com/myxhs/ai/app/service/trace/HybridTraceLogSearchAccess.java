package com.myxhs.ai.app.service.trace;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class HybridTraceLogSearchAccess implements TraceLogSearchAccess {

    private final EsTraceLogSearchAccess esTraceLogSearchAccess;
    private final LocalTraceLogSearchAccess localTraceLogSearchAccess;

    public HybridTraceLogSearchAccess(EsTraceLogSearchAccess esTraceLogSearchAccess,
                                      LocalTraceLogSearchAccess localTraceLogSearchAccess) {
        this.esTraceLogSearchAccess = esTraceLogSearchAccess;
        this.localTraceLogSearchAccess = localTraceLogSearchAccess;
    }

    @Override
    public TraceSearchResult searchByTraceId(String traceId) {
        TraceSearchResult remote = esTraceLogSearchAccess.searchByTraceId(traceId);
        if (!remote.hits().isEmpty()) {
            return remote;
        }
        return localTraceLogSearchAccess.searchByTraceId(traceId);
    }
}
