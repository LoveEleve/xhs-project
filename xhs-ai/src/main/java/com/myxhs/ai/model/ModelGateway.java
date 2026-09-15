package com.myxhs.ai.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.util.context.ContextView;
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
    private final TokenBudgetService tokenBudgetService;
    private final MeterRegistry meterRegistry;
    private final int maxAttempts;
    private final long backoffMs;
    private final int breakerThreshold;
    private final long breakerCooldownMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong breakerOpenUntil = new AtomicLong(0);

    public ModelGateway(Model primary, Model fallback, TokenBudgetService tokenBudgetService,
                        MeterRegistry meterRegistry,
                        int maxAttempts, long backoffMs, int breakerThreshold, long breakerCooldownMs) {
        this.primary = primary;
        this.fallback = fallback;
        this.tokenBudgetService = tokenBudgetService;
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
        return Flux.deferContextual(ctx -> streamWithBudget(ctx, messages, tools, options));
    }

    /** 预算判定（F13）：软限切轻量通道、硬限拒绝；用量按真实 usage 计入 Redis */
    private Flux<ChatResponse> streamWithBudget(ContextView ctx, List<Msg> messages,
                                                List<ToolSchema> tools, GenerateOptions options) {
        Long userId = ctx.getOrEmpty(TokenBudget.USER_ID_KEY)
                .map(v -> ((Number) v).longValue()).orElse(null);
        if (userId == null) {
            return doStream(messages, tools, options);
        }
        long used = tokenBudgetService.usedToday(userId);
        TokenBudget.Decision decision = TokenBudget.decide(used, tokenBudgetService.softLimit(),
                tokenBudgetService.hardLimit());
        if (decision == TokenBudget.Decision.HARD) {
            meterRegistry.counter("ai_model_budget_total", "result", "hard_reject").increment();
            log.warn("[Token预算] 用户 {} 今日用量 {}/{}，拒绝请求", userId, used, tokenBudgetService.hardLimit());
            return Flux.error(new TokenBudget.ExceededException(
                    "今日 AI 用量已用完（" + used + "/" + tokenBudgetService.hardLimit() + " tokens），请明日再试"));
        }
        Flux<ChatResponse> flux = decision == TokenBudget.Decision.SOFT
                ? callWithRetry(fallback, "fallback", messages, tools, options, 1)
                        .doOnSubscribe(sub -> {
                            meterRegistry.counter("ai_model_budget_total", "result", "soft_switch").increment();
                            log.info("[Token预算] 用户 {} 今日用量 {}/{}，切换轻量模型", userId, used,
                                    tokenBudgetService.hardLimit());
                        })
                : doStream(messages, tools, options);
        return flux.doOnNext(r -> {
            if (r.getUsage() != null) {
                tokenBudgetService.add(userId, r.getUsage().getInputTokens() + r.getUsage().getOutputTokens());
            }
        });
    }

    private Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        long openUntil = breakerOpenUntil.get();
        if (openUntil > 0 && System.currentTimeMillis() >= openUntil) {
            // 冷却结束：半开——清空计数放行本请求作为探测
            breakerOpenUntil.set(0);
            consecutiveFailures.set(0);
            log.info("[模型网关] 熔断冷却结束，半开放行探测请求");
        }
        if (System.currentTimeMillis() < breakerOpenUntil.get()) {
            log.warn("[模型网关] 熔断开启，直接使用备用通道: primary={}", primary.getModelName());
            counter("fallback", primary.getModelName(), "breaker_open");
            return callWithRetry(fallback, "fallback", messages, tools, options, 1);
        }
        return callWithRetry(primary, "primary", messages, tools, options, maxAttempts)
                .doOnComplete(() -> consecutiveFailures.set(0))
                .onErrorResume(primaryError -> {
                    recordFailure();
                    if (primaryError instanceof PartialStreamException) {
                        log.warn("[模型网关] 主通道流中断（已输出部分内容），不降级重放: {}", primaryError.getMessage());
                        return Flux.error(new ModelUnavailableException(
                                "模型流中断（已输出部分内容，请重试）: " + primaryError.getMessage(), primaryError));
                    }
                    log.warn("[模型网关] 主通道失败（{}），切换备用通道: {}",
                            primary.getModelName(), primaryError.getMessage());
                    counter("fallback", primary.getModelName(), "failover");
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
                            .doOnNext(r -> {
                                emitted.set(true);
                                if (r.getUsage() != null) {
                                    tokens(channel, modelName, "input", r.getUsage().getInputTokens());
                                    tokens(channel, modelName, "output", r.getUsage().getOutputTokens());
                                }
                            })
                            .onErrorMap(e -> emitted.get() ? new PartialStreamException(e) : e);
                })
                .retryWhen(Retry.backoff(Math.max(attempts - 1, 0), Duration.ofMillis(backoffMs))
                        .filter(this::isRetryable)
                        .doBeforeRetry(signal -> {
                            log.warn("[模型网关] {} 传输错误重试（{}/{}）: {}",
                                    channel, signal.totalRetries() + 1, attempts, signal.failure().getMessage());
                            counter(channel, modelName, "retry");
                        })
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .doOnComplete(() -> {
                    counter(channel, modelName, "ok");
                    timer(channel, modelName, System.currentTimeMillis() - start);
                })
                .doOnError(e -> {
                    counter(channel, modelName, "error");
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
                counter("primary", primary.getModelName(), "breaker_open");
                if (meterRegistry != null) {
                    meterRegistry.counter("ai_model_breaker_open_total", "model", primary.getModelName()).increment();
                }
                log.error("[模型网关] 熔断开启 {}ms（连续失败 {} 次）", breakerCooldownMs, failures);
            }
        }
    }

    private void counter(String channel, String modelName, String result) {
        if (meterRegistry != null) {
            meterRegistry.counter("ai_model_calls_total",
                    "channel", channel, "result", result, "model", modelName).increment();
        }
    }

    private void tokens(String channel, String modelName, String type, int count) {
        if (meterRegistry != null && count > 0) {
            meterRegistry.counter("ai_model_tokens_total",
                    "channel", channel, "model", modelName, "type", type).increment(count);
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
