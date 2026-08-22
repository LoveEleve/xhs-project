package com.myxhs.ai.app.service.trace;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TraceServiceLayerMapperTest {

    @Test
    void gateway响应日志不应覆盖业务末端服务() throws Exception {
        Method method = EsTraceLogSearchAccess.class.getDeclaredMethod("logicalLastService", List.class);
        method.setAccessible(true);
        String last = (String) method.invoke(null, List.of(
                new TraceSearchResult.TraceEvent("2026-01-01T00:00:00Z", "my-xhs-gateway", "INFO", "in"),
                new TraceSearchResult.TraceEvent("2026-01-01T00:00:01Z", "my-xhs-order", "INFO", "order"),
                new TraceSearchResult.TraceEvent("2026-01-01T00:00:02Z", "my-xhs-payment", "INFO", "payment"),
                new TraceSearchResult.TraceEvent("2026-01-01T00:00:03Z", "my-xhs-gateway", "INFO", "out")));
        assertEquals("my-xhs-payment", last);
    }
}
