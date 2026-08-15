package com.myxhs.ai.app.service.run;

import com.myxhs.ai.app.service.agent.harness.AgentBudget;
import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.agent.harness.HarnessEvent;
import com.myxhs.ai.app.service.agent.harness.HarnessEventType;
import com.myxhs.ai.app.service.agent.harness.TerminationReason;
import com.myxhs.ai.app.service.router.Intent;
import com.myxhs.ai.app.service.router.IntentRouter;
import com.myxhs.ai.app.service.store.RunStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
                           AtomicBoolean streaming,
                           AtomicBoolean cancelToken) {
    }

    /** 心跳超时阈值：超过视为崩溃（正常单步最长约 1min，留余量） */
    private static final Duration CRASH_STALE = Duration.ofMinutes(10);

    private final AgentHarness harness;
    private final RunStore store;
    private final RunMetrics metrics;
    private final AgentBudget budget;
    private final ExecutorService executor = Executors.newFixedThreadPool(20);
    /** 订阅泵线程登记 + 每订阅取消 token（M8-4：客户端断开时按 runId 中断/标记） */
    private final java.util.concurrent.ConcurrentHashMap<String, Thread> streamThreads =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean> streamTokens =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, RunEntry> runs = new ConcurrentHashMap<>();
    /** 意图预检（三阶路由：规则 → LLM → 语义 → 默认引导）：问候/超范围直答，不进 Agent */
    private final IntentRouter intentRouter;

    /** Spring 注入点：与 AiQueryController 共用配置好的路由（含 LLM 分类，行为一致） */
    @Autowired
    public RunManager(AgentHarness harness, RunStore store, RunMetrics metrics, IntentRouter intentRouter) {
        this.harness = harness;
        this.store = store;
        this.metrics = metrics;
        this.intentRouter = intentRouter;
        this.budget = harness.defaultBudget();
    }

    /** 纯规则降级（测试/离线；无 LLM 分类时无信号输入默认引导） */
    public RunManager(AgentHarness harness, RunStore store, RunMetrics metrics) {
        this(harness, store, metrics, new IntentRouter());
    }

    public RunManager(AgentHarness harness, RunStore store) {
        this(harness, store, null);
    }

    /** 启动自动恢复（M5-4）：扫描 RUNNING 且心跳超时的 run，从 checkpoint 续跑 */
    @PostConstruct
    public void recoverCrashedOnStartup() {
        if (store == null) {
            return;
        }
        try {
            var stale = store.findRunningStale(Instant.now().minus(CRASH_STALE));
            if (!stale.isEmpty()) {
                log.warn("[runmgr] 启动发现 {} 个崩溃 run，开始恢复: {}", stale.size(), stale);
            }
                for (String runId : stale) {
                resumeEntry(runId);
            }
        } catch (Exception e) {
            log.warn("[runmgr] 启动恢复扫描失败: {}", e.getMessage());
        }
    }

    /** 将崩溃 run 装入内存并异步续跑（供启动恢复/手动 resume 复用）。
     *  原子认领：UPDATE 影响行数为 0 = 已被其他实例认领/状态已变（双实例防双份执行）。 */
    public RunEntry resumeEntry(String runId) {
        int claimed = store.claimRunning(runId);
        if (claimed == 0) {
            log.warn("[runmgr] run={} 认领失败（已被认领或非 RUNNING），跳过恢复", runId);
            return null;
        }
        String userId = "recovered";
        String query = runId;
        try {
            var rec = store.loadRun(runId);
            if (rec.isPresent()) {
                userId = rec.get().userId();
                query = rec.get().query();
            }
        } catch (Exception ignored) {
        }
        LinkedBlockingQueue<HarnessEvent> queue = new LinkedBlockingQueue<>();
        AtomicBoolean cancelToken = new AtomicBoolean(false);
        CompletableFuture<AgentRun> future = CompletableFuture.supplyAsync(() ->
                harness.resume(runId, queue::offer, cancelToken), executor)
                .exceptionally(ex -> {
                    log.warn("[runmgr] run={} 恢复执行异常: {}", runId, ex.getMessage());
                    queue.offer(new HarnessEvent(runId, HarnessEventType.FAILED, 0, null, null, null,
                            "EXECUTION_ERROR", "恢复执行异常: " + ex.getMessage()));
                    return null;
                });
        if (metrics != null) {
            metrics.onRunSubmitted();
            future.whenComplete((run, ex) -> metrics.onRunFinished(run));
        }
        RunEntry entry = new RunEntry(runId, userId, query, queue, future,
                new AtomicBoolean(false), cancelToken);
        runs.put(runId, entry);
        log.info("[runmgr] resume run={} user={}", runId, userId);
        return entry;
    }

    /** 提交诊断任务，立即返回；后台执行（userId 落库实现用户级审计）。
     *  意图预检：问候/超范围话题（无诊断目标）不进 Agent，直接完成（零模型/工具成本，
     *  防"你好→调 baselineWindow"“天气→查主从延迟"类蠢回答）。 */
    public RunEntry submit(String query, String userId) {
        purgeDone();
        Intent intent = intentRouter.classify(query);
        if (intent == Intent.GREETING) {
            return submitDirectAnswer(query, userId, IntentRouter.GREETING_ANSWER,
                    "问候直答（非诊断任务，未调用工具/模型）");
        }
        if (intent == Intent.OUT_OF_SCOPE) {
            return submitDirectAnswer(query, userId, IntentRouter.OUT_OF_SCOPE_ANSWER,
                    "超范围话题拒答（非诊断任务，未调用工具/模型）");
        }
        String runId = AgentHarness.newRunId();
        LinkedBlockingQueue<HarnessEvent> queue = new LinkedBlockingQueue<>();
        AtomicBoolean cancelToken = new AtomicBoolean(false);
        CompletableFuture<AgentRun> future = CompletableFuture.supplyAsync(() ->
                harness.run(runId, query, budget, queue::offer, userId, cancelToken), executor)
                .exceptionally(ex -> {
                    // 异常兜底：补发 FAILED 终态事件（订阅者不会拿到无终态空流）
                    log.warn("[runmgr] run={} 执行异常: {}", runId, ex.getMessage());
                    queue.offer(new HarnessEvent(runId, HarnessEventType.FAILED, 0, null, null, null,
                            "EXECUTION_ERROR", "执行异常: " + ex.getMessage()));
                    return null;
                });
        if (metrics != null) {
            metrics.onRunSubmitted();
            future.whenComplete((run, ex) -> metrics.onRunFinished(run));
        }
        RunEntry entry = new RunEntry(runId, userId, query, queue, future, new AtomicBoolean(false), cancelToken);
        runs.put(runId, entry);
        log.info("[runmgr] submit run={} query={} user={}", runId, query, userId);
        return entry;
    }

    /** 非诊断任务直答（问候/超范围）：立即完成（RUN_STARTED→COMPLETED 事件流完整，前端零改动）。
     *  落库（M8-4 可追溯闭环：所有 run 统一可追溯，重启/TTL 后历史直答也可查） */
    private RunEntry submitDirectAnswer(String query, String userId, String answer, String note) {
        String runId = AgentHarness.newRunId();
        LinkedBlockingQueue<HarnessEvent> queue = new LinkedBlockingQueue<>();
        AgentRun run = new AgentRun(runId, query, budget);
        run.terminate(TerminationReason.COMPLETED, answer);
        if (store != null) {
            try {
                store.createRun(runId, userId == null || userId.isBlank() ? "anonymous" : userId,
                        null, query, "{}", "{}");
                store.updateRunStatus(runId, "SUCCEEDED", "COMPLETED", 0, 0);
                store.updateFinalAnswer(runId, answer);
            } catch (Exception e) {
                log.warn("[runmgr] direct-answer 落库失败 run={} err={}", runId, e.getMessage());
            }
        }
        queue.offer(new HarnessEvent(runId, HarnessEventType.RUN_STARTED, 0, null, null, null, null, note));
        queue.offer(new HarnessEvent(runId, HarnessEventType.COMPLETED, 0, null, null, null,
                TerminationReason.COMPLETED.name(), answer));
        CompletableFuture<AgentRun> future = CompletableFuture.completedFuture(run);
        if (metrics != null) {
            metrics.onRunSubmitted();
            future.whenComplete((r, ex) -> metrics.onRunFinished(r));
        }
        RunEntry entry = new RunEntry(runId, userId, query, queue, future,
                new AtomicBoolean(false), new AtomicBoolean(false));
        runs.put(runId, entry);
        log.info("[runmgr] direct-answer run={} query={} user={} note={}", runId, query, userId, note);
        return entry;
    }

    public RunEntry get(String runId) {
        return runs.get(runId);
    }

    public boolean isDone(String runId) {
        RunEntry e = runs.get(runId);
        return e != null && e.future().isDone();
    }

    /** 协作式取消（M5-3）：置位取消令牌；Harness 当前步完成后终止为 CANCELLED */
    public boolean cancel(String runId) {
        RunEntry e = runs.get(runId);
        if (e == null || e.future().isDone()) {
            return false;
        }
        e.cancelToken().set(true);
        log.info("[runmgr] cancel run={} user={}", runId, e.userId());
        return true;
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
        AtomicBoolean cancel = new AtomicBoolean(false);
        streamTokens.put(runId, cancel);
        executor.submit(() -> {
            Thread self = Thread.currentThread();
            streamThreads.put(runId, self);
            try {
                HarnessEvent ev;
                while (true) {
                    if (cancel.get()) {
                        break;
                    }
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
                streamThreads.remove(runId, self);
                streamTokens.remove(runId, cancel);
                e.streaming().set(false);
                onComplete.run();
            }
        });
        return true;
    }

    /** 客户端断开/超时：标记取消并中断订阅泵，释放单消费者标志（M8-4 修复：刷新/断网后新订阅不再 409） */
    public void cancelStream(String runId) {
        AtomicBoolean cancel = streamTokens.get(runId);
        if (cancel != null) {
            cancel.set(true);
        }
        Thread t = streamThreads.get(runId);
        if (t != null) {
            t.interrupt();
        }
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
