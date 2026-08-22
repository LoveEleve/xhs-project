package com.myxhs.ai.app.service.trace;

public interface TraceLogSearchAccess {
    TraceSearchResult searchByTraceId(String traceId);
}
