package com.myxhs.ai.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型网关（D01 最小落地）：主/备通道 + 传输重试 + 熔断 + 指标
 * <ul>
 *   <li>重试：仅对**传输类错误**重试（连接失败/reset/超时）；已出流后不重试（防重复增量），改由备用通道接管</li>
 *   <li>熔断：连续失败达阈值 → 冷却期内直接走备用通道并快速失败</li>
 *   <li>降级：主通道不可用 → 备用模型；两者皆失败 → 错误向上传递（不返回静默空答复）</li>
 * </ul>
 */
@Slf4j
public class ModelGateway implements Model {

    private final Model primary;
    private final Model fallback;
    private final MeterRegistry meterRegistry;
    private final int maxAttempts;
    private final long backoffMs;
    private final int breakerThreshold;
    private final long breakerCooldownMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong breakerOpenUntil = new AtomicLong(0);

    public ModelGateway(Model primary, Model fallback, MeterRegistry meterRegistry,
                        int maxAttempts, long backoffMs, int breakerThreshold, long breakerCooldownMs) {
        this.primary = primary;
        this.fallback = fallback;
        this.meterRegistry = meterRegistry;
        this.maxAttempts = Math.max(maxAttempts, 1);
        this.backoffMs = backoffMs;
        this.breakerThreshold = breakerThreshold;
        this.breakerCooldownMs = breakerCooldownMs;
    }

    @Override
    public String getModelName() {
        return primary.getModelName();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        if (System.currentTimeMillis() < breakerOpenUntil.get()) {
            log.warn("[模型网关] 熔断开启，直接使用备用通道: primary={}", primary.getModelName());
            counter("fallback", "breaker_open");
            return callWithRetry(fallback, "fallback", messages, tools, options, 1);
        }
        return callWithRetry(primary, "primary", messages, tools, options, maxAttempts)
                .onErrorResume(primaryError -> {
                    recordFailure();
                    log.warn("[模型网关] 主通道失败（{}），切换备用通道: {}",
                            primary.getModelName(), primaryError.getMessage());
                    counter("fallback", "failover");
                    return callWithRetry(fallback, "fallback", messages, tools, options, 2)
                            .onErrorResume(fallbackError -> {
                                log.error("[模型网关] 主/备通道均失败: primary={}, fallback={}",
                                        primaryError.getMessage(), fallbackError.getMessage());
                                return Flux.error(new ModelUnavailableException(
                                        "模型网关不可用（主备均失败）: " + primaryError.getMessage(), fallbackError));
                            });
                });
    }

    private Flux<ChatResponse> callWithRetry(Model model, String channel,
                                             List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options, int attempts) {
        String modelName = model.getModelName();
        long start = System.currentTimeMillis();
        return Flux.defer(() -> {
                    AtomicBoolean emitted = new AtomicBoolean(false);
                    return model.stream(messages, tools, options)
                            .doOnNext(r -> emitted.set(true))
                            .onErrorMap(e -> emitted.get() ? new PartialStreamException(e) : e);
                })
                .retryWhen(Retry.backoff(Math.max(attempts - 1, 0), Duration.ofMillis(backoffMs))
                        .filter(this::isRetryable)
                        .doBeforeRetry(signal -> {
                            log.warn("[模型网关] {} 传输错误重试（{}/{}）: {}",
                                    channel, signal.totalRetries() + 1, maxAttempts, signal.failure().getMessage());
                            counter(channel, "retry");
                        })
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .doOnComplete(() -> {
                    counter(channel, "ok");
                    timer(channel, modelName, System.currentTimeMillis() - start);
                })
                .doOnError(e -> {
                    counter(channel, "error");
                    timer(channel, modelName, System.currentTimeMillis() - start);
                });
    }

    /** 传输类错误可重试；已出流的局部失败不可重试（防重复增量） */
    private boolean isRetryable(Throwable e) {
        if (e instanceof PartialStreamException) {
            return false;
        }
        Throwable t = e;
        while (t != null) {
            if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException
                    || t instanceof SocketException) {
                return true;
            }
            String message = String.valueOf(t.getMessage());
            if (message.contains("Connection reset") || message.contains("HTTP connect timed out")
                    || message.contains("HTTP transport error") || message.contains("SSE/NDJSON stream failed")
                    || message.contains("connection closed") || message.contains("IOException: closed")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= breakerThreshold) {
            long until = System.currentTimeMillis() + breakerCooldownMs;
            if (until > breakerOpenUntil.get()) {
                breakerOpenUntil.set(until);
                counter("primary", "breaker_open");
                log.error("[模型网关] 熔断开启 {}ms（连续失败 {} 次）", breakerCooldownMs, failures);
            }
        }
    }

    private void counter(String channel, String result) {
        if (meterRegistry != null) {
            meterRegistry.counter("ai_model_calls_total",
                    "channel", channel, "result", result, "model", getModelName()).increment();
        }
    }

    private void timer(String channel, String modelName, long millis) {
        if (meterRegistry != null) {
            meterRegistry.timer("ai_model_latency", "channel", channel, "model", modelName)
                    .record(Duration.ofMillis(millis));
        }
    }

    /** 已发出部分增量后的失败（不可重试） */
    static class PartialStreamException extends RuntimeException {
        PartialStreamException(Throwable cause) {
            super(cause == null ? "partial stream failed" : cause.getMessage(), cause);
        }
    }

    /** 主备均不可用 */
    public static class ModelUnavailableException extends RuntimeException {
        public ModelUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
