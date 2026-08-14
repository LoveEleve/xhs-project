package com.myxhs.ai.app.service.run;

import com.myxhs.ai.app.service.agent.harness.AgentBudget;
import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.agent.harness.HarnessEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Run 管理器（M5-2 异步化）：提交即返回 runId，后台执行，事件缓冲供 SSE 订阅。
 * 状态来源：内存 RunEntry（查询/订阅）+ RunStore（M5-1 持久化，重启恢复待 M5-4）。
 * 并发：fixed 20（设计 §8 中等并发上限）。
 */
@Component
public class RunManager {

    private static final Logger log = LoggerFactory.getLogger(RunManager.class);

    public record RunEntry(String runId, String userId, String query,
                           LinkedBlockingQueue<HarnessEvent> events,
                           CompletableFuture<AgentRun> future) {
    }

    private final AgentHarness harness;
    private final AgentBudget budget;
    private final ExecutorService executor = Executors.newFixedThreadPool(20);
    private final Map<String, RunEntry> runs = new ConcurrentHashMap<>();

    public RunManager(AgentHarness harness) {
        this.harness = harness;
        this.budget = harness.defaultBudget();
    }

    /** 提交诊断任务，立即返回；后台执行 */
    public RunEntry submit(String query, String userId) {
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        LinkedBlockingQueue<HarnessEvent> queue = new LinkedBlockingQueue<>();
        CompletableFuture<AgentRun> future = CompletableFuture.supplyAsync(() -> {
            AgentRun run = harness.run(query, budget, queue::offer);
            return run;
        }, executor).exceptionally(ex -> {
            log.warn("[runmgr] run={} 执行异常: {}", runId, ex.getMessage());
            return null;
        });
        RunEntry entry = new RunEntry(runId, userId, query, queue, future);
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

    /** SSE 订阅：先补发已缓冲事件，再实时转发直到终态；消费线程每轮超时 5s */
    public void streamTo(String runId, java.util.function.Consumer<HarnessEvent> sink,
                         java.lang.Runnable onComplete) {
        RunEntry e = runs.get(runId);
        if (e == null) {
            throw new IllegalArgumentException("run 不存在: " + runId);
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
                onComplete.run();
            }
        });
    }

    /** 内存态清理（run 已完成且无人订阅后）——store 仍保留记录 */
    public void remove(String runId) {
        runs.remove(runId);
    }
}
