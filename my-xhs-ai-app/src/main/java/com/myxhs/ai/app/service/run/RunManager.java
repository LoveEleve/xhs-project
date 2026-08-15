package com.myxhs.ai.app.service.run;

import com.myxhs.ai.app.service.agent.harness.AgentBudget;
import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.agent.harness.HarnessEvent;
import com.myxhs.ai.app.service.agent.harness.HarnessEventType;
import com.myxhs.ai.app.service.agent.harness.TerminationReason;
import com.myxhs.ai.app.service.conversation.ConversationService;
import com.myxhs.ai.app.service.router.Intent;
import com.myxhs.ai.app.service.router.IntentRouter;
import com.myxhs.ai.app.service.store.RunStore;
import dev.langchain4j.data.message.ChatMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    /** 会话并发锁：convId → 活跃 runId（M10：同会话同一时间仅一个活跃 run，V1 串行，409 语义） */
    private final Map<String, String> activeByConv = new ConcurrentHashMap<>();

    /** 同会话已有活跃 run（M10 并发限制） */
    public static class ConversationBusyException extends RuntimeException {
        public ConversationBusyException(String convId) {
            super("会话 " + convId + " 已有进行中的诊断任务（同会话串行）");
        }
    }

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
    /** 会话服务（M10；=null 时多轮功能关闭，兼容旧测试构造） */
    private final ConversationService conversation;
    /** M13：Agent 领域分派（AGENT 意图 → 业务/排障画像；规则零成本） */
    private final com.myxhs.ai.app.service.agent.profile.AgentDispatcher dispatcher =
            new com.myxhs.ai.app.service.agent.profile.AgentDispatcher();

    /** Spring 注入点：与 AiQueryController 共用配置好的路由（含 LLM 分类，行为一致） */
    @Autowired
    public RunManager(AgentHarness harness, RunStore store, RunMetrics metrics, IntentRouter intentRouter,
                      @org.springframework.beans.factory.annotation.Autowired(required = false)
                      ConversationService conversation) {
        this.harness = harness;
        this.store = store;
        this.metrics = metrics;
        this.intentRouter = intentRouter;
        this.conversation = conversation;
        this.budget = harness.defaultBudget();
    }

    /** 纯规则降级（测试/离线；无 LLM 分类时无信号输入默认引导） */
    public RunManager(AgentHarness harness, RunStore store, RunMetrics metrics, IntentRouter intentRouter) {
        this(harness, store, metrics, intentRouter, null);
    }

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
        String convId = null;
        try {
            var rec = store.loadRun(runId);
            if (rec.isPresent()) {
                userId = rec.get().userId();
                query = rec.get().query();
                convId = rec.get().sessionId();
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
        // M11 审批恢复（会话续接）：run 挂起时未释放会话锁/未写终态消息——
        // 恢复后重新登记锁 + 终态写消息/摘要/释放（与 submit 同语义）
        if (convId != null && conversation != null) {
            final String cid = convId;
            String prev = activeByConv.putIfAbsent(cid, runId);
            if (prev != null && !prev.equals(runId)) {
                // 边缘竞态：恢复期间该会话已有活跃 run（崩溃恢复 + 用户并发新提交）——记录不阻断
                log.warn("[runmgr] run={} 恢复时会话 {} 已被 run={} 占用", runId, cid, prev);
            }
            future.whenComplete((run, ex) -> {
                boolean terminal = run != null
                        && run.status() != com.myxhs.ai.app.service.agent.harness.RunStatus.WAITING_APPROVAL;
                try {
                    if (terminal) {
                        conversation.appendAssistantMessage(cid, runId, run.finalAnswer(),
                                run.evidenceChain().entries().stream()
                                        .map(com.myxhs.ai.app.service.agent.harness.EvidenceChain.Evidence::evidenceId)
                                        .toList());
                        conversation.updateSummary(cid, run.finalAnswer());
                    }
                } catch (Exception e) {
                    log.warn("[runmgr] 恢复 run 会话落库失败 conv={} run={} err={}", cid, runId, e.getMessage());
                } finally {
                    if (terminal) {
                        activeByConv.remove(cid, runId);
                    }
                }
            });
        }
        // 恢复是既有 run 的续跑：不碰 running/runs_total 指标（避免口径失真，P1-2）
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
        return submit(query, userId, null);
    }

    /** 多轮提交（M10）：convId 非空时挂接会话（历史注入/消息落库/同会话串行 409） */
    public RunEntry submit(String query, String userId, String convId) {
        purgeDone();
        Intent intent = intentRouter.classify(query);
        if (intent == Intent.GREETING) {
            return submitDirectAnswer(query, userId, convId, IntentRouter.GREETING_ANSWER,
                    "问候直答（非诊断任务，未调用工具/模型）");
        }
        if (intent == Intent.OUT_OF_SCOPE) {
            return submitDirectAnswer(query, userId, convId, IntentRouter.OUT_OF_SCOPE_ANSWER,
                    "超范围话题拒答（非诊断任务，未调用工具/模型）");
        }
        String runId = AgentHarness.newRunId();
        if (convId != null && conversation != null) {
            // 同会话并发限制（V1 串行：第二个活跃 run 直接 409）
            if (activeByConv.putIfAbsent(convId, runId) != null) {
                throw new ConversationBusyException(convId);
            }
            try {
                conversation.ensureConversation(convId, userId, query);
            } catch (Exception e) {
                // 会话持久化故障不阻断诊断（仅日志），但锁仍须释放
                log.warn("[runmgr] 会话准备失败 conv={} err={}", convId, e.getMessage());
            }
        }
        // M10 多轮上下文注入（摘要 + 历史结论，无工具原文）；失败降级为单轮。
        // 注意顺序：buildContext 必须先于 appendUserMessage——否则当前问题被写入历史后
        // 会被重复注入（历史一条 + harness 尾部"用户问题："一条）（P0-2 修复）
        List<ChatMessage> initial = null;
        if (convId != null && conversation != null) {
            try {
                initial = conversation.buildContext(convId);
            } catch (Exception e) {
                log.warn("[runmgr] 会话上下文注入失败 conv={} 降级单轮: {}", convId, e.getMessage());
            }
            try {
                conversation.appendUserMessage(convId, runId, query);
            } catch (Exception e) {
                log.warn("[runmgr] 用户消息落库失败 conv={} err={}", convId, e.getMessage());
            }
        }
        List<ChatMessage> initialMessages = initial;
        // M13：AGENT 领域分派（业务/排障画像——prompt 变体 + 工具子集）
        LinkedBlockingQueue<HarnessEvent> queue = new LinkedBlockingQueue<>();
        AtomicBoolean cancelToken = new AtomicBoolean(false);
        CompletableFuture<AgentRun> future = CompletableFuture.supplyAsync(() ->
                harness.run(runId, query, budget, queue::offer, userId, cancelToken, initialMessages,
                        dispatcher.dispatch(query)), executor)
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
        // M10/M11：终态后写 assistant 结论消息 + 更新会话摘要 + 释放会话锁。
        // 挂起（WAITING_APPROVAL）不是终态：不写消息、不释放锁（会话仍被挂起 run 占用，审批后 resume 继续）
        if (convId != null && conversation != null) {
            final String cid = convId;
            future.whenComplete((run, ex) -> {
                boolean terminal = run != null
                        && run.status() != com.myxhs.ai.app.service.agent.harness.RunStatus.WAITING_APPROVAL;
                try {
                    // run → 会话追溯（ai_run.session_id=convId）：无条件（createRun 已先执行；
                    // 挂起也写——审批恢复 resumeEntry 依赖它续接会话）
                    if (store != null) {
                        store.updateSessionId(runId, cid);
                    }
                    if (terminal) {
                        conversation.appendAssistantMessage(cid, runId, run.finalAnswer(),
                                run.evidenceChain().entries().stream()
                                        .map(com.myxhs.ai.app.service.agent.harness.EvidenceChain.Evidence::evidenceId)
                                        .toList());
                        conversation.updateSummary(cid, run.finalAnswer());
                    }
                } catch (Exception e) {
                    log.warn("[runmgr] 会话终态落库失败 conv={} run={} err={}", cid, runId, e.getMessage());
                } finally {
                    if (terminal) {
                        activeByConv.remove(cid, runId);
                    }
                }
            });
        }
        RunEntry entry = new RunEntry(runId, userId, query, queue, future, new AtomicBoolean(false), cancelToken);
        runs.put(runId, entry);
        log.info("[runmgr] submit run={} query={} user={} conv={}", runId, query, userId, convId);
        return entry;
    }

    /** 非诊断任务直答（问候/超范围）：立即完成（RUN_STARTED→COMPLETED 事件流完整，前端零改动）。
     *  落库（M8-4 可追溯闭环：所有 run 统一可追溯，重启/TTL 后历史直答也可查）；
     *  M10：挂接会话时同步写 user/assistant 消息 + 摘要（直答零耗时，无需锁） */
    private RunEntry submitDirectAnswer(String query, String userId, String convId, String answer, String note) {
        String runId = AgentHarness.newRunId();
        if (convId != null && conversation != null) {
            try {
                conversation.ensureConversation(convId, userId, query);
                conversation.appendUserMessage(convId, runId, query);
                conversation.appendAssistantMessage(convId, runId, answer, List.of());
                conversation.updateSummary(convId, answer);
            } catch (Exception e) {
                log.warn("[runmgr] direct-answer 会话落库失败 conv={} err={}", convId, e.getMessage());
            }
        }
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
                // 落库失败不阻断直答，但错误级别记录（P1-3：可追溯性损失需可见）
                log.error("[runmgr] direct-answer 落库失败 run={} query={} err={}",
                        runId, query, e.getMessage());
                note = note + "（注：历史追溯落库失败）";
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

    /**
     * M11 HITL 审批：approve → resume 恢复执行被审批工具；reject → CANCELLED 终态 + 审计。
     * 原子认领（仅 WAITING_APPROVAL 可处理）防并发/重复审批；内存 miss（重启后）走 resumeEntry 重建。
     * 返回 false = 无待审批/状态已变（调用方 409/404）。
     */
    public boolean approve(String runId, String decision, String reason, String approver) {
        if (store == null || !"approve".equals(decision) && !"reject".equals(decision)) {
            return false;
        }
        String approvalJson = null;
        try {
            approvalJson = store.loadApproval(runId).orElse(null);
        } catch (Exception e) {
            log.warn("[runmgr] run={} 审批加载失败: {}", runId, e.getMessage());
            return false;
        }
        if (approvalJson == null || !approvalJson.contains("\"status\":\"PENDING\"")) {
            return false; // 无待审批（未挂起/已处理）
        }
        int claimed;
        try {
            claimed = store.claimApproval(runId); // WAITING_APPROVAL → RUNNING（原子）
        } catch (Exception e) {
            log.warn("[runmgr] run={} 审批认领失败: {}", runId, e.getMessage());
            return false;
        }
        if (claimed == 0) {
            log.warn("[runmgr] run={} 审批认领失败（状态已变），拒绝重复审批", runId);
            return false;
        }
        String who = approver == null || approver.isBlank() ? "anonymous" : approver;
        String updated = approvalJson.replace("\"status\":\"PENDING\"",
                "\"status\":" + ("approve".equals(decision) ? "\"APPROVED\"" : "\"REJECTED\""));
        // 审计字段注入到 JSON 末尾闭合符前（lastIndexOf 定位最外层 }，args 内的 } 不受影响）
        int last = updated.lastIndexOf('}');
        if (last > 0) {
            updated = updated.substring(0, last)
                    + ",\"approver\":\"" + who.replace("\"", "'")
                    + "\",\"reason\":\"" + (reason == null ? "" : reason.replace("\"", "'"))
                    + "\",\"decidedAt\":\"" + java.time.Instant.now() + "\"}";
        }
        try {
            store.updateApproval(runId, updated);
        } catch (Exception e) {
            log.warn("[runmgr] run={} 审批审计落库失败: {}", runId, e.getMessage());
        }
        if ("reject".equals(decision)) {
            try {
                store.updateRunStatus(runId, "CANCELLED", "APPROVAL_REJECTED", 0, 0);
                store.updateFinalAnswer(runId, "审批拒绝：" + (reason == null ? "未提供理由" : reason));
            } catch (Exception e) {
                log.warn("[runmgr] run={} 拒绝终态落库失败: {}", runId, e.getMessage());
            }
            runs.remove(runId); // 内存挂起 entry 移除 → GET 走 store 回退（CANCELLED）
            log.info("[runmgr] run={} 审批拒绝 by={} reason={}", runId, who, reason);
            return true;
        }
        // approve：resume 恢复执行（内存 miss → resumeEntry 从 store 重建；与崩溃恢复同路径）
        if (runs.get(runId) != null) {
            runs.remove(runId);
        }
        RunEntry entry = resumeEntry(runId);
        log.info("[runmgr] run={} 审批通过 by={} 恢复执行", runId, who);
        return entry != null;
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
        // 取消 token 先于 streaming 标志登记：闭合 cancelStream 竞态窗口
        // （客户端在 compareAndSet 与 put 之间断开时，cancelStream 也能命中 token）
        AtomicBoolean cancel = new AtomicBoolean(false);
        streamTokens.put(runId, cancel);
        if (!e.streaming().compareAndSet(false, true)) {
            streamTokens.remove(runId, cancel);
            log.warn("[runmgr] run={} 已有活动订阅者，拒绝并发订阅", runId);
            return false;
        }
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
