package com.myxhs.ai.app.service.trace;

import com.myxhs.ai.tools.LogSearchAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LocalTraceLogSearchAccess implements TraceLogSearchAccess {

    private static final Logger log = LoggerFactory.getLogger(LocalTraceLogSearchAccess.class);
    private static final Pattern MATCHES_PATTERN = Pattern.compile("\\\"matches\\\"\\s*:\\s*([0-9]+)");

    private final LogSearchAccess logSearchAccess;
    private final Executor executor = Executors.newFixedThreadPool(6);

    public LocalTraceLogSearchAccess(LogSearchAccess logSearchAccess) {
        this.logSearchAccess = logSearchAccess;
    }

    @Override
    public TraceSearchResult searchByTraceId(String traceId) {
        List<String> services = defaultServices();
        if (services.isEmpty()) {
            return new TraceSearchResult(traceId, "local-log-fallback", List.of(), List.of(), List.of(), List.of(), null, null);
        }
        List<CompletableFuture<ServiceScan>> scans = services.stream()
                .map(service -> CompletableFuture.supplyAsync(() -> scan(service, traceId), executor)
                        .completeOnTimeout(ServiceScan.failed(service), 3, TimeUnit.SECONDS)
                        .exceptionally(ex -> ServiceScan.failed(service)))
                .toList();
        List<TraceSearchResult.ServiceHit> hits = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (ServiceScan scan : scans.stream().map(CompletableFuture::join).toList()) {
            if (scan.failed()) {
                failed.add(scan.service());
            } else if (scan.matches() > 0) {
                hits.add(new TraceSearchResult.ServiceHit(scan.service(), TraceServiceLayerMapper.layerOf(scan.service()), scan.matches()));
            } else {
                missed.add(scan.service());
            }
        }
        String entry = hits.isEmpty() ? null : hits.get(0).service();
        String last = hits.isEmpty() ? null : hits.get(hits.size() - 1).service();
        return new TraceSearchResult(traceId, "local-log-fallback", hits, missed, failed, List.of(), entry, last);
    }

    private List<String> defaultServices() {
        if (logSearchAccess == null) {
            return List.of();
        }
        return List.of("my-xhs-order", "my-xhs-inventory", "my-xhs-payment",
                "my-xhs-gateway", "my-xhs-content", "my-xhs-user");
    }

    private ServiceScan scan(String service, String traceId) {
        try {
            String result = logSearchAccess.searchLog(service, traceId, "2000");
            return new ServiceScan(service, parseMatches(result), false);
        } catch (Exception e) {
            log.warn("[trace-local] service={} traceId={} err={}", service, traceId, e.getMessage());
            return ServiceScan.failed(service);
        }
    }

    private static int parseMatches(String result) {
        if (result == null || !result.contains("\"status\":\"ok\"")) {
            return 0;
        }
        Matcher m = MATCHES_PATTERN.matcher(result);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private record ServiceScan(String service, int matches, boolean failed) {
        private static ServiceScan failed(String service) {
            return new ServiceScan(service, 0, true);
        }
    }
}
