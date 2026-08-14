package com.myxhs.ai.app.service.run;

import com.myxhs.ai.app.service.agent.harness.AgentBudget;
import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.agent.harness.HarnessEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Run 管理器（M5-2 异步化）：提交即返回 runId，后台执行，事件缓冲供 SSE 订阅。
 * 状态来源：内存 RunEntry（查询/订阅）+ RunStore（M5-1 持久化，重启恢复待 M5-4）。
 * 并发：fixed 20（设计 §8）；同一 run 仅允许一个活动订阅者（事件流单消费者，防竞争丢失）。
 */
@Component
public class RunManager {

    private static final Logger log = LoggerFactory.getLogger(RunManager.class);

    /** 完成后内存保留时长（TTL 清理；store 仍保留记录供追溯） */
    private static final Duration DONE_TTL = Duration.ofHours(1);

    public record RunEntry(String runId, String userId, String query,
                           LinkedBlockingQueue<HarnessEvent> events,
                           CompletableFuture<AgentRun> future,
                           AtomicBoolean streaming) {
    }

    private final AgentHarness harness;
    private final AgentBudget budget;
    private final ExecutorService executor = Executors.newFixedThreadPool(20);
    private final Map<String, RunEntry> runs = new ConcurrentHashMap<>();

    public RunManager(AgentHarness harness) {
        this.harness = harness;
        this.budget = harness.defaultBudget();
    }

    /** 提交诊断任务，立即返回；后台执行（userId 落库实现用户级审计） */
    public RunEntry submit(String query, String userId) {
        purgeDone();
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        LinkedBlockingQueue<HarnessEvent> queue = new LinkedBlockingQueue<>();
        CompletableFuture<AgentRun> future = CompletableFuture.supplyAsync(() ->
                harness.run(query, budget, queue::offer, userId), executor)
                .exceptionally(ex -> {
                    // 异常兜底：补发 FAILED 终态事件（订阅者不会拿到无终态空流）
                    log.warn("[runmgr] run={} 执行异常: {}", runId, ex.getMessage());
                    queue.offer(new HarnessEvent(runId, "FAILED", 0, null, null, null,
                            "EXECUTION_ERROR", "执行异常: " + ex.getMessage()));
                    return null;
                });
        RunEntry entry = new RunEntry(runId, userId, query, queue, future, new AtomicBoolean(false));
        runs.put(runId, entry);
        log.info("[runmgr] submit run={} query={} user={}", runId, query, userId);
        return entry;
    }

    public RunEntry get(String runId) {
        return runs.get(runId);
    }

    public boolean isDone(String runId) {
        RunEntry e = runs.get(runId);
        return e != null && e.future().isDone();
    }

    /** SSE 订阅：同一 run 单活动订阅者（并发订阅返回 false 拒绝）；缓冲补发 + 实时转发直到终态 */
    public boolean streamTo(String runId, java.util.function.Consumer<HarnessEvent> sink,
                            java.lang.Runnable onComplete) {
        RunEntry e = runs.get(runId);
        if (e == null) {
            return false;
        }
        if (!e.streaming().compareAndSet(false, true)) {
            log.warn("[runmgr] run={} 已有活动订阅者，拒绝并发订阅", runId);
            return false;
        }
        executor.submit(() -> {
            try {
                HarnessEvent ev;
                while (true) {
                    ev = e.events().poll(5, TimeUnit.SECONDS);
                    if (ev != null) {
                        sink.accept(ev);
                        continue;
                    }
                    if (e.future().isDone()) {
                        while ((ev = e.events().poll()) != null) {
                            sink.accept(ev);
                        }
                        break;
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                e.streaming().set(false);
                onComplete.run();
            }
        });
        return true;
    }

    /** TTL 清理：已完成（含异常 null）超过 DONE_TTL 的 run 从内存移除（store 记录仍在） */
    private void purgeDone() {
        runs.entrySet().removeIf(en -> {
            if (!en.getValue().future().isDone()) {
                return false;
            }
            try {
                AgentRun r = en.getValue().future().join();
                return r == null || isOld(r);
            } catch (Exception ex) {
                return true;
            }
        });
    }

    private static boolean isOld(AgentRun run) {
        return run != null && run.endedAt() != null
                && Duration.between(run.endedAt(), Instant.now()).compareTo(DONE_TTL) > 0;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
