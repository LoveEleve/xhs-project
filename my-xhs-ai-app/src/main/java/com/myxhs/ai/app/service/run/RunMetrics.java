package com.myxhs.ai.app.service.run;

import com.myxhs.ai.app.service.agent.harness.AgentRun;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行指标（M6-3 可观测）：Prometheus 暴露 run 级质量/成本/延迟指标。
 *  - myxhs_ai_runs_total{status}      run 完成计数（按状态）
 *  - myxhs_ai_runs_running            当前运行中 run 数（gauge）
 *  - myxhs_ai_tokens_total            累计 token（输入+输出）
 *  - myxhs_ai_cost_total              累计成本估算
 *  - myxhs_ai_run_duration_seconds    单 run 耗时（histogram，延迟分位）
 * 质量指标（完成率/幻觉率/发散率）在评测层（EvalReport），此处为运行时成本/延迟/状态。
 */
@Component
public class RunMetrics {

    private static final Logger log = LoggerFactory.getLogger(RunMetrics.class);

    private final MeterRegistry registry;
    private final double pricePer1kTokens;
    private final Counter tokensTotal;
    private final Counter costTotal;
    private final Timer runDuration;
    private final AtomicLong running = new AtomicLong(0);

    public RunMetrics(MeterRegistry registry,
                      @Value("${myxhs.ai.agent.price-per-1k-tokens:0.002}") double pricePer1kTokens) {
        this.registry = registry;
        this.pricePer1kTokens = pricePer1kTokens;
        this.tokensTotal = Counter.builder("myxhs_ai_tokens_total")
                .description("AI 诊断累计 token（输入+输出）")
                .register(registry);
        this.costTotal = Counter.builder("myxhs_ai_cost_total")
                .description("AI 诊断累计成本估算")
                .register(registry);
        this.runDuration = Timer.builder("myxhs_ai_run_duration")
                .description("AI 诊断单 run 耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        // 运行中 gauge：绑定 AtomicLong 引用，实时读
        registry.gauge("myxhs_ai_runs_running", running, AtomicLong::get);
    }

    private Counter runsTotal(String status) {
        return Counter.builder("myxhs_ai_runs_total")
                .description("AI 诊断 run 完成计数（按状态）")
                .tag("status", status)
                .register(registry);
    }

    /** run 提交时调用（运行中 +1） */
    public void onRunSubmitted() {
        running.incrementAndGet();
    }

    /** run 结束时调用（运行中 -1 + 各计数；token/成本从 steps 汇总） */
    public void onRunFinished(AgentRun run) {
        running.decrementAndGet();
        if (run == null) {
            runsTotal("FAILED").increment();
            return;
        }
        String status = run.status() == null ? "UNKNOWN" : run.status().name();
        runsTotal(status).increment();
        long tokens = run.steps().stream().mapToLong(s -> s.tokensUsed()).sum();
        double cost = tokens / 1000.0 * pricePer1kTokens;
        long durationMs = run.endedAt() != null && run.startedAt() != null
                ? Duration.between(run.startedAt(), run.endedAt()).toMillis() : 0;
        tokensTotal.increment(tokens);
        costTotal.increment(cost);
        runDuration.record(Duration.ofMillis(Math.max(1, durationMs)));
        log.info("[metrics] run={} status={} tokens={} cost={} durationMs={}",
                run.runId(), status, tokens, cost, durationMs);
    }
}
