package com.myxhs.ai.app.service.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.QueryWindowExtractor;
import com.myxhs.ai.app.service.store.RunStore;
import com.myxhs.ai.tools.MetricToolAccess;
import com.myxhs.ai.tools.ObsToolAccess;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 受限诊断 Agent 引擎（D4，设计 §2 状态机落地）：
 *   THINK(LLM 决策 JSON) → VALIDATE(PolicyGuard) → TOOL(工具执行+登记证据) → OBSERVE → LOOPCHECK(预算+循环检测) → ANSWER
 * 红线（§6.1 教训的确定性兜底）：
 *  - 存在性校验：ANSWER 的 evidenceRefs 必须命中 ToolResultRegistry，缺失/编造 → 拒绝重想，N 次不收敛 → EVIDENCE_INVALID
 *  - 工具只经 allowlist（PolicyGuard deny-by-default），模型无法提交任意 SQL
 *  - partial 终止（预算/循环/策略耗尽）时返回已收集证据摘要，不假装全成
 *  - 模型不可用 → FAILED 明确降级，不瞎编
 */
public class AgentHarness {

    private static final Logger log = LoggerFactory.getLogger(AgentHarness.class);

    public static final String SYSTEM_PROMPT = """
            你是 my-xhs 运营诊断 Agent。目标是查清用户问题，通过多步工具调查归因。
            可用工具（只读；参数 window 格式 yyyy-MM-dd~yyyy-MM-dd，跨度≤31天）：
            - queryOrderVolume(window)：下单量（口径：排除已删、含取消/退款，按创建时间，Asia/Shanghai）
            - paymentSuccessRate(window)：支付成功率（口径：成功/(成功+失败)，排除待支付/退款；渠道为 Mock：1支付宝/2微信/99）
            - contentInteraction(window)：内容互动量（口径：点赞/收藏/评论/分享，曝光单列）
            - baselineWindow(window)：计算对比基线窗口（上一同长窗口，确定性）
            - httpErrors(service, hours)：服务 HTTP 5xx 错误统计（按 uri 聚合，最近 N 小时；service 如 my-xhs-gateway，空=全部）
            - httpLatency(service, hours)：服务 HTTP 慢端点 top（P95 延迟秒，最近 N 小时）
            - mqConsumerLag(group)：RocketMQ 消费积压（按消费组聚合 lag；空=全部）
            - mqDlqBacklog(consumerGroup)：RocketMQ 死信积压（空=全部；**-1 为应用侧哨兵值=无 DLQ 或查询失败，非真实积压**）
            - mysqlReplicationLag()：MySQL 主从复制延迟（Seconds_Behind_Master，全部从库）
            - mysqlDeadlocks()：MySQL 死锁事件（累计 total + 最新 new_events）
            - funnelConversion(window)：电商漏斗各环节量（商品浏览/加购/下单/支付，窗口内）
            - paymentFailures(window)：支付失败事件（PAY_FAIL 按失败码聚合，窗口内）
            - notePublishEvents(window)：内容发布事件数（PUBLISH 按天，窗口内）
            排障提示：httpErrors 的 uri=/** 已由工具单列为 noiseScanRoutes（扫描/探测噪音），归因时排除；
            /api/coupon/*、/api/cart/* 的 [Gateway-异常] WARN 日志非 5xx
            已知服务名（L2 观测可用）：my-xhs-gateway / my-xhs-order / my-xhs-payment / my-xhs-content /
            my-xhs-user / my-xhs-inventory / my-xhs-product / my-xhs-search / my-xhs-cart / my-xhs-coupon 等
            规则：
            1. 数字必须来自工具结果，禁止编造。
            2. 对比/升降分析：**当前窗口以系统注入的时间窗规则为准**（见消息中的"当前窗口已确定"）；
               基线窗口必须用 baselineWindow 工具计算（上一同长窗口），不得自行推算。
            3. 工具返回 error/partial 时如实说明，不猜测。
            4. 证据充分即 ANSWER：典型调查 5~10 步工具调用；不要为求全面反复查同一指标的不同窗口
              （有当前+基线对比即可）；业务/观测两面各覆盖关键指标后即收敛。
            5. 不把相关当因果；有反证须显式说明（counterEvidence）；结论的不确定性须声明。
            6. 每次输出必须是合法 JSON（不要 markdown 代码块），格式：
            {"action":"TOOL_CALL","tool":"queryOrderVolume","args":{"window":"2026-08-01~2026-08-07"},"reasoning":"为什么查"}
            {"action":"ANSWER","conclusion":"结论","evidenceRefs":["ev_xxx"],"counterEvidence":"反证或空","uncertainty":"不确定性或空"}
            {"action":"DECLINE","conclusion":"无法回答的说明","reasoning":"原因"}
            7. 如果用户消息不是诊断问题（问候/闲聊/超范围话题如天气/新闻等），必须输出 DECLINE（conclusion 说明能力范围并引导提问），
               严禁调用任何工具；DECLINE 是零证据路径，不需要 evidenceRefs，不要为凑证据而调用工具。
            """;

    private final ChatModel chatModel;
    private final MetricToolAccess metricToolAccess;
    private final ObsToolAccess obsToolAccess;
    private final PolicyGuard policyGuard = new PolicyGuard();
    private final AgentDecisionCodec codec;
    private final AgentBudget defaultBudget;
    private final double pricePer1kTokens;
    private final int maxInvalidAnswers;
    private final RunStore store;
    private final ObjectMapper om;
    private final String modelName;

    public AgentHarness(ChatModel chatModel, MetricToolAccess metricToolAccess, ObsToolAccess obsToolAccess,
                        ObjectMapper mapper, AgentBudget defaultBudget, double pricePer1kTokens, int maxInvalidAnswers) {
        this(chatModel, metricToolAccess, obsToolAccess, mapper, defaultBudget, pricePer1kTokens, maxInvalidAnswers,
                null, "unknown", 400);
    }

    /** 带 RunStore 的构造（M5 Durable：run/step 落库；store=null 不持久化，兼容测试） */
    public AgentHarness(ChatModel chatModel, MetricToolAccess metricToolAccess, ObsToolAccess obsToolAccess,
                        ObjectMapper mapper, AgentBudget defaultBudget, double pricePer1kTokens, int maxInvalidAnswers,
                        RunStore store, String modelName) {
        this(chatModel, metricToolAccess, obsToolAccess, mapper, defaultBudget, pricePer1kTokens, maxInvalidAnswers,
                store, modelName, 400);
    }

    /** 带截断长度配置的构造（M8-4 可配化：模型可见工具结果截断长度） */
    public AgentHarness(ChatModel chatModel, MetricToolAccess metricToolAccess, ObsToolAccess obsToolAccess,
                        ObjectMapper mapper, AgentBudget defaultBudget, double pricePer1kTokens, int maxInvalidAnswers,
                        RunStore store, String modelName, int toolResultMaxLen) {
        this.chatModel = chatModel;
        this.metricToolAccess = metricToolAccess;
        this.obsToolAccess = obsToolAccess;
        this.codec = new AgentDecisionCodec(mapper);
        this.defaultBudget = defaultBudget;
        this.pricePer1kTokens = pricePer1kTokens;
        this.maxInvalidAnswers = maxInvalidAnswers;
        this.store = store;
        this.om = mapper;
        this.modelName = modelName;
        this.toolResultMaxLen = Math.max(1, toolResultMaxLen);
    }

    public AgentBudget defaultBudget() {
        return defaultBudget;
    }

    public AgentRun run(String query) {
        return run(query, defaultBudget, null);
    }

    public AgentRun run(String query, AgentBudget budget) {
        return run(query, budget, null);
    }

    /** 带事件回调的 run（SSE 流式推送用，默认预算；listener 异常不影响执行，仅记录） */
    public AgentRun run(String query, java.util.function.Consumer<HarnessEvent> listener) {
        return run(query, defaultBudget, listener);
    }

    /** 带事件回调的 run（SSE 流式推送用；listener 异常不影响执行，仅记录） */
    public AgentRun run(String query, AgentBudget budget, java.util.function.Consumer<HarnessEvent> listener) {
        return run(query, budget, listener, "anonymous");
    }

    /** 带 userId 的 run（M5-2 异步化：用户级审计落库；其余同上） */
    public AgentRun run(String query, AgentBudget budget, java.util.function.Consumer<HarnessEvent> listener,
                        String userId) {
        return run(query, budget, listener, userId, null);
    }

    /** 带取消令牌的 run（M5-3 协作式取消：token 置位后，当前步完成即终止，不打断进行中的模型调用） */
    public AgentRun run(String query, AgentBudget budget, java.util.function.Consumer<HarnessEvent> listener,
                        String userId, java.util.concurrent.atomic.AtomicBoolean cancelToken) {
        return run(newRunId(), query, budget, listener, userId, cancelToken);
    }

    /** 指定 runId 的 run（M8-4 契约修复：对外 runId 与事件/落库/视图一致） */
    public AgentRun run(String runId, String query, AgentBudget budget,
                        java.util.function.Consumer<HarnessEvent> listener,
                        String userId, java.util.concurrent.atomic.AtomicBoolean cancelToken) {
        AgentRun run = new AgentRun(runId, query, budget);
        if (store != null) {
            try {
                store.createRun(run.runId(), userId == null || userId.isBlank() ? "anonymous" : userId, null, query,
                        "{\"maxSteps\":" + budget.maxSteps() + ",\"maxTokens\":" + budget.maxTokens()
                                + ",\"maxCost\":" + budget.maxCost() + "}",
                        versionsJson());
            } catch (Exception e) {
                log.warn("[harness] run={} 创建 run 落库失败: {}", run.runId(), e.getMessage());
            }
        }
        LoopCtrl ctrl = new LoopCtrl(budget);
        LoopDetector loop = new LoopDetector();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        // 确定性当前窗口注入（QueryWindowExtractor 单一事实源）：消除"最近 7 天"由模型自选的软约束
        String currentWindow = QueryWindowExtractor.extract(query);
        messages.add(SystemMessage.from("时间窗规则（确定性，Asia/Shanghai）：用户显式指定优先，否则取最近 7 天。"
                + "当前窗口已确定 = " + currentWindow
                + "；对比基线必须用 baselineWindow 工具计算（上一同长窗口），不得自行推算。"));
        messages.add(UserMessage.from("用户问题：" + query));
        int invalidOutputs = 0;

        emit(listener, new HarnessEvent(run.runId(), "RUN_STARTED", 0, null, null, null, null,
                "开始调查：当前窗口已确定 = " + currentWindow));
        log.info("[harness] run={} start query={} budget={} currentWindow={}",
                run.runId(), query, budget, currentWindow);

        return executeLoop(run, messages, ctrl, loop, listener, cancelToken, invalidOutputs);
    }

    /** 执行循环（run 与 resume 共用；M5-4 恢复=重建上下文后从这里继续） */
    private AgentRun executeLoop(AgentRun run, List<ChatMessage> messages, LoopCtrl ctrl, LoopDetector loop,
                                 java.util.function.Consumer<HarnessEvent> listener,
                                 java.util.concurrent.atomic.AtomicBoolean cancelToken, int invalidOutputs) {
        while (true) {
            if (cancelToken != null && cancelToken.get()) {
                return terminatePartial(run, TerminationReason.CANCELLED, ctrl, listener);
            }
            TerminationReason pre = ctrl.checkBeforeStep();
            if (pre != null) {
                return terminatePartial(run, pre, ctrl, listener);
            }

            // THINK：模型决策（JSON 结构化输出；失败重试一次，仍失败 → 明确降级 FAILED，不瞎编）
            ChatResponse response = callModel(run.runId(), messages);
            if (response == null) {
                run.terminate(TerminationReason.MODEL_UNAVAILABLE, "模型暂不可用，请稍后重试（调查未完成）");
                emit(listener, new HarnessEvent(run.runId(), "FAILED", ctrl.steps(), null, null, null,
                        TerminationReason.MODEL_UNAVAILABLE.name(), run.finalAnswer()));
                storeRunFinish(run, ctrl);
                return run;
            }
            int tokens = tokensOf(response);
            ctrl.recordStep(tokens);
            ctrl.recordCost(tokens / 1000.0 * pricePer1kTokens);

            AgentDecision decision = codec.parse(response.aiMessage().text());
            if (decision == null) {
                recordAndStore(run, AgentStep.think(ctrl.steps(), null, tokens), messages);
                messages.add(UserMessage.from(AgentDecisionCodec.malformedOutputMessage()));
                log.warn("[harness] run={} 模型输出非 JSON，反馈重想", run.runId());
                emit(listener, new HarnessEvent(run.runId(), "THINK", ctrl.steps(), null, null, null, null,
                        "模型输出非合法 JSON，已反馈重想"));
                invalidOutputs++;
                if (invalidOutputs >= maxInvalidAnswers) {
                    return terminatePartial(run, TerminationReason.EVIDENCE_INVALID, ctrl, listener);
                }
                continue;
            }
            recordAndStore(run, AgentStep.think(ctrl.steps(), decision, tokens), messages);
            messages.add(AiMessage.from(response.aiMessage().text()));
            emit(listener, new HarnessEvent(run.runId(), "THINK", ctrl.steps(), decision.tool(),
                    decision.args() == null ? null : decision.args().get("window"), null, null,
                    decision.isAnswer() ? decision.conclusion() : decision.reasoning()));

            if (decision.isToolCall()) {
                // VALIDATE：deny-by-default
                // 预算语义：policy 拒绝不计步骤数（惩罚探索会扭曲调查），由 LoopCtrl.policyDeniedCount
                // 单独计数，连续 N 次拒绝 → POLICY_EXHAUSTED（设计 §2"反馈模型重想"的专用机制）
                PolicyDecision pd = policyGuard.evaluate(decision.tool(), decision.args());
                if (!pd.allowed()) {
                    ctrl.recordPolicyDenied();
                    recordAndStore(run, AgentStep.policyDenied(ctrl.steps(), decision, pd.reason()), messages);
                    String note = pd.requiresApproval()
                            ? pd.reason() + "（L3 需人工审批，V1 不可执行）" : pd.reason();
                    messages.add(UserMessage.from(AgentDecisionCodec.feedback(decision, note)));
                    log.info("[harness] run={} policy_denied tool={} reason={}", run.runId(), decision.tool(), pd.reason());
                    emit(listener, new HarnessEvent(run.runId(), "POLICY_DENIED", ctrl.steps(),
                            decision.tool(), null, null, null, note));
                    TerminationReason loopReason = loop.recordStep(run.evidenceChain().hash());
                    if (loopReason != null) {
                        return terminatePartial(run, loopReason, ctrl, listener);
                    }
                    continue;
                }

                // TOOL：执行 + 登记证据（存在性校验数据源）
                String result = callTool(decision.tool(), decision.args());
                String evidenceId = run.registry().register(decision.tool(), decision.args(), result);
                String window = decision.args() == null ? null : decision.args().get("window");
                run.evidenceChain().add(evidenceId, decision.tool(), window, result);
                recordAndStore(run, AgentStep.tool(ctrl.steps(), decision, result, List.of(evidenceId)), messages);
                messages.add(UserMessage.from("工具 " + decision.tool() + " 结果（证据 id=" + evidenceId + "）："
                        + truncateToolResult(result)));
                log.info("[harness] run={} tool={} window={} ev={} result={}", run.runId(),
                        decision.tool(), window, evidenceId, result);
                emit(listener, new HarnessEvent(run.runId(), "TOOL", ctrl.steps(), decision.tool(),
                        window, List.of(evidenceId), null, result));

                // LOOPCHECK
                TerminationReason loopReason = loop.recordToolCall(decision.tool(), decision.args(),
                        run.evidenceChain().hash());
                if (loopReason != null) {
                    return terminatePartial(run, loopReason, ctrl, listener);
                }
            } else if (decision.isDecline()) {
                // 拒答（DECLINE）：明确无法回答/超范围，零证据豁免（M8-4 机制修复：
                // 防模型为满足存在性校验而调用无关工具"凑证据"，如"天气→查主从延迟"）
                String answer = composeDecline(decision);
                recordAndStore(run, AgentStep.answer(ctrl.steps(), decision), messages);
                run.terminate(TerminationReason.COMPLETED, answer);
                log.info("[harness] run={} DECLINED answer={}", run.runId(), answer);
                emit(listener, new HarnessEvent(run.runId(), "ANSWER", ctrl.steps(), null, null,
                        null, null, answer));
                emit(listener, new HarnessEvent(run.runId(), "COMPLETED", ctrl.steps(), null, null,
                        null, TerminationReason.COMPLETED.name(), answer));
                storeRunFinish(run, ctrl);
                return run;
            } else if (decision.isAnswer()) {
                // 存在性校验（确定性兜底，不靠模型自觉）
                String invalid = validateAnswer(run, decision);
                if (invalid == null) {
                    recordAndStore(run, AgentStep.answer(ctrl.steps(), decision), messages);
                    String answer = composeAnswer(decision, run);
                    run.terminate(TerminationReason.COMPLETED, answer);
                    log.info("[harness] run={} COMPLETED ev={} answer={}", run.runId(),
                            run.evidenceChain().size(), answer);
                    emit(listener, new HarnessEvent(run.runId(), "ANSWER", ctrl.steps(), null, null,
                            decision.evidenceRefs(), null, decision.conclusion()));
                    emit(listener, new HarnessEvent(run.runId(), "COMPLETED", ctrl.steps(), null, null,
                            decision.evidenceRefs(), TerminationReason.COMPLETED.name(), answer));
                    storeRunFinish(run, ctrl);
                    return run;
                }
                invalidOutputs++;
                recordAndStore(run, AgentStep.answer(ctrl.steps(), decision), messages);
                messages.add(UserMessage.from(AgentDecisionCodec.feedback(decision, invalid)));
                log.warn("[harness] run={} 证据校验失败: {}", run.runId(), invalid);
                emit(listener, new HarnessEvent(run.runId(), "ANSWER", ctrl.steps(), null, null,
                        decision.evidenceRefs(), null, "证据校验失败: " + invalid));
                if (invalidOutputs >= maxInvalidAnswers) {
                    return terminatePartial(run, TerminationReason.EVIDENCE_INVALID, ctrl, listener);
                }
                TerminationReason loopReason = loop.recordStep(run.evidenceChain().hash());
                if (loopReason != null) {
                    return terminatePartial(run, loopReason, ctrl, listener);
                }
            } else {
                invalidOutputs++;
                messages.add(UserMessage.from(AgentDecisionCodec.feedback(decision, "action 必须是 TOOL_CALL 或 ANSWER")));
                if (invalidOutputs >= maxInvalidAnswers) {
                    return terminatePartial(run, TerminationReason.EVIDENCE_INVALID, ctrl, listener);
                }
                TerminationReason loopReason = loop.recordStep(run.evidenceChain().hash());
                if (loopReason != null) {
                    return terminatePartial(run, loopReason, ctrl, listener);
                }
            }
        }
    }

    /** 崩溃恢复（M5-4）：从 store 加载 run/步骤/最后 checkpoint，重建上下文后续跑 */
    public AgentRun resume(String runId, java.util.function.Consumer<HarnessEvent> listener,
                           java.util.concurrent.atomic.AtomicBoolean cancelToken) {
        if (store == null) {
            throw new IllegalStateException("Run Store 未装配，无法恢复");
        }
        var rec = store.loadRun(runId).orElseThrow(() -> new IllegalStateException("run 不存在: " + runId));
        if (!"RUNNING".equals(rec.status())) {
            throw new IllegalStateException("仅 RUNNING 状态的 run 可恢复，当前: " + rec.status());
        }
        AgentBudget budget = parseBudget(rec.budgetJson());
        AgentRun run = new AgentRun(runId, rec.query(), budget);
        List<RunStore.StepRecord> steps = store.loadSteps(runId);
        List<ChatMessage> messages = new ArrayList<>();
        LoopCtrl ctrl = new LoopCtrl(budget);
        int invalidOutputs = 0;
        for (RunStore.StepRecord sr : steps) {
            AgentStep step = fromStepRecord(sr);
            run.recordStep(step);
            // 计步语义与原执行一致：仅 THINK 步计入步骤预算（TOOL/POLICY_DENIED/ANSWER 不计）
            if ("THINK".equals(sr.state())) {
                ctrl.recordStep((int) sr.tokensUsed());
            }
            if ("TOOL".equals(step.state()) && sr.evidenceIds() != null && !sr.evidenceIds().isBlank()
                    && step.decision() != null) {
                for (String evId : sr.evidenceIds().split(",")) {
                    var args = step.decision().args();
                    run.registry().restore(evId, step.decision().tool(), args, sr.toolResult());
                    run.evidenceChain().add(evId, step.decision().tool(),
                            args == null ? null : args.get("window"), sr.toolResult());
                }
            }
        }
        var cp = store.lastCheckpoint(runId).orElseThrow(
                () -> new IllegalStateException("无 checkpoint 可恢复: " + runId));
        messages.addAll(restoreMessages(cp.messagesSnapshot()));
        LoopDetector loop = new LoopDetector();
        emit(listener, new HarnessEvent(runId, "RUN_STARTED", 0, null, null, null, null,
                "恢复执行（已完成 " + steps.size() + " 步）"));
        log.info("[harness] run={} RESUME from checkpoint steps={}", runId, steps.size());
        return executeLoop(run, messages, ctrl, loop, listener, cancelToken, invalidOutputs);
    }

    private AgentBudget parseBudget(String budgetJson) {
        try {
            var n = om.readTree(budgetJson);
            return new AgentBudget(n.path("maxSteps").asInt(AgentBudget.DEFAULT_MAX_STEPS),
                    n.path("maxTokens").asLong(AgentBudget.DEFAULT_MAX_TOKENS),
                    n.path("maxCost").asDouble(AgentBudget.DEFAULT_MAX_COST));
        } catch (Exception e) {
            return AgentBudget.defaults();
        }
    }

    private AgentStep fromStepRecord(RunStore.StepRecord sr) {
        AgentDecision decision = null;
        if (sr.decisionJson() != null) {
            try {
                decision = om.readValue(sr.decisionJson(), AgentDecision.class);
            } catch (Exception e) {
                log.warn("[harness] 恢复决策解析失败: {}", e.getMessage());
            }
        }
        List<String> refs = sr.evidenceIds() == null ? null : List.of(sr.evidenceIds().split(","));
        return new AgentStep(sr.stepNo(), sr.state(), decision, sr.toolResult(), refs,
                (int) sr.tokensUsed(), sr.createdAt());
    }

    private List<ChatMessage> restoreMessages(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, String>> list = om.readValue(snapshot, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            List<ChatMessage> msgs = new ArrayList<>();
            for (Map<String, String> m : list) {
                String type = m.getOrDefault("type", "");
                String text = m.getOrDefault("text", "");
                if ("SYSTEM".equals(type)) {
                    msgs.add(SystemMessage.from(text));
                } else if ("AI".equals(type)) {
                    msgs.add(AiMessage.from(text));
                } else {
                    msgs.add(UserMessage.from(text));
                }
            }
            return msgs;
        } catch (Exception e) {
            throw new IllegalStateException("消息快照恢复失败（checkpoint 损坏）: " + e.getMessage(), e);
        }
    }

    /** 落 step 记录 + 可选持久化（messages 快照 checkpoint）；持久化失败不影响执行 */
    private void recordAndStore(AgentRun run, AgentStep step, List<ChatMessage> messages) {
        run.recordStep(step);
        if (store != null) {
            try {
                store.saveStep(run.runId(), step, snapshot(messages));
            } catch (Exception e) {
                log.warn("[harness] run={} step 落库失败: {}", run.runId(), e.getMessage());
            }
        }
    }

    /** 记录最终状态（COMPLETED/PARTIAL/FAILED 共用）；store 为空或失败不阻塞 */
    private void storeRunFinish(AgentRun run, LoopCtrl ctrl) {
        if (store == null) {
            return;
        }
        try {
            store.updateRunStatus(run.runId(), run.status().name(),
                    run.terminationReason() == null ? null : run.terminationReason().name(),
                    ctrl.tokens(), ctrl.cost());
        } catch (Exception e) {
            log.warn("[harness] run={} 终态落库失败: {}", run.runId(), e.getMessage());
        }
    }

    /** LLM 对话上下文快照（{type,text} 列表，恢复时按 type 重建） */
    private String snapshot(List<ChatMessage> messages) {
        try {
            List<Map<String, String>> list = messages.stream().map(m -> {
                String text = m instanceof AiMessage a ? a.text()
                        : m instanceof UserMessage u ? u.singleText()
                        : m instanceof SystemMessage s ? s.text() : "";
                return Map.of("type", m.type().name(), "text", text == null ? "" : text);
            }).toList();
            return om.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    /** model/prompt/tool 版本（M5 版本追溯；model 名来自配置，工具数自动计数；prompt 版本化抽文件待 M6） */
    private String versionsJson() {
        return "{\"model\":\"" + modelName + "\",\"prompt\":\"SYSTEM_PROMPT.v1\",\"tools\":"
                + PolicyGuard.allowedToolCount()
                + "}";
    }

    public static String newRunId() {
        return "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static void emit(java.util.function.Consumer<HarnessEvent> listener, HarnessEvent event) {
        if (listener == null) {
            return;
        }
        try {
            listener.accept(event);
        } catch (Exception e) {
            // 推送失败（客户端断开等）不影响执行；终态由 run 结果兜底
            log.warn("[harness] 事件推送失败 type={} err={}", event.type(), e.getMessage());
        }
    }

    /**
     * 工具结果进上下文的截断（M7 性能优化）：registry/证据链保留完整结果（存在性校验/幻觉检测
     * 不受影响），仅模型可见文本截断——大幅降上下文体积（15 步 × 400+ 字符 JSON 的冗余）。
     * 截断保留头部（value/window/status 等核心字段在 JSON 前端）。
     * 长度可配置（myxhs.ai.agent.tool-result-max-len，默认 400）。
     */
    private final int toolResultMaxLen;

    private String truncateToolResult(String result) {
        if (result == null || result.length() <= toolResultMaxLen) {
            return result;
        }
        // 字段边界截断：避免把 JSON 截在字段中间（模型读到不完整字段值会误读）
        String cut = result.substring(0, toolResultMaxLen);
        int boundary = Math.max(cut.lastIndexOf(','), cut.lastIndexOf('}'));
        if (boundary > toolResultMaxLen / 2) {
            cut = cut.substring(0, boundary + 1);
        }
        return cut + "...(结果已截断，仅保留核心字段)";
    }

    /** 模型调用：失败重试一次（设计 §6.1#3 retryable），仍失败返回 null（调用方降级） */
    private ChatResponse callModel(String runId, List<ChatMessage> messages) {
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .responseFormat(ResponseFormat.JSON)
                .build();
        try {
            return chatModel.chat(request);
        } catch (Exception e) {
            log.warn("[harness] run={} 模型调用失败(重试1次): {}", runId, e.getMessage());
            try {
                return chatModel.chat(request);
            } catch (Exception e2) {
                log.warn("[harness] run={} 模型调用重试仍失败: {}", runId, e2.getMessage());
                return null;
            }
        }
    }

    /** 存在性校验：结论非空 + 证据 refs 全部命中登记簿 */
    private String validateAnswer(AgentRun run, AgentDecision decision) {
        if (decision.conclusion() == null || decision.conclusion().isBlank()) {
            return "结论（conclusion）不能为空";
        }
        List<String> refs = decision.evidenceRefs();
        if (refs == null || refs.isEmpty()) {
            return "答案必须引用至少一条工具证据（evidenceRefs 非空）——数字必须来自工具结果，禁止编造";
        }
        for (String ref : refs) {
            if (!run.registry().contains(ref)) {
                return "引用的证据 " + ref + " 不存在（未发生该工具调用）——禁止编造证据";
            }
        }
        return null;
    }

    /** 工具执行（只经 allowlist；异常→ERROR 结果如实回填，不抛出） */
    private String callTool(String tool, Map<String, String> args) {
        String window = args == null ? null : args.get("window");
        String service = args == null ? null : args.get("service");
        String hours = args == null ? null : args.get("hours");
        try {
            return switch (tool) {
                case PolicyGuard.TOOL_ORDER_VOLUME -> metricToolAccess.queryOrderVolume(window);
                case PolicyGuard.TOOL_PAYMENT_RATE -> metricToolAccess.paymentSuccessRate(window);
                case PolicyGuard.TOOL_CONTENT_INTERACTION -> metricToolAccess.contentInteraction(window);
                case PolicyGuard.TOOL_BASELINE_WINDOW -> metricToolAccess.baselineWindow(window);
                case PolicyGuard.TOOL_HTTP_ERRORS -> obsToolAccess.httpErrors(service, hours);
                case PolicyGuard.TOOL_HTTP_LATENCY -> obsToolAccess.httpLatency(service, hours);
                case PolicyGuard.TOOL_MQ_LAG -> obsToolAccess.mqConsumerLag(args == null ? null : args.get("group"));
                case PolicyGuard.TOOL_MQ_DLQ -> obsToolAccess.mqDlqBacklog(args == null ? null : args.get("consumerGroup"));
                case PolicyGuard.TOOL_MYSQL_REPLICA_LAG -> obsToolAccess.mysqlReplicationLag();
                case PolicyGuard.TOOL_MYSQL_DEADLOCKS -> obsToolAccess.mysqlDeadlocks();
                case PolicyGuard.TOOL_FUNNEL -> metricToolAccess.funnelConversion(window);
                case PolicyGuard.TOOL_PAY_FAILURES -> metricToolAccess.paymentFailures(window);
                case PolicyGuard.TOOL_NOTE_PUBLISH -> metricToolAccess.notePublishEvents(window);
                default -> "ERROR: 未注册工具 " + tool;
            };
        } catch (Exception e) {
            log.warn("[harness] 工具调用异常 tool={} err={}", tool, e.getMessage());
            return "ERROR: 工具调用异常: " + e.getMessage();
        }
    }

    /** 成功答案 = 结论 + 证据链 + 反证 + 不确定性 */
    private String composeAnswer(AgentDecision decision, AgentRun run) {
        StringBuilder sb = new StringBuilder(decision.conclusion());
        sb.append("\n\n证据链：");
        for (String ref : decision.evidenceRefs()) {
            var rec = run.registry().get(ref);
            sb.append("\n[").append(ref).append("] ")
                    .append(rec.map(r -> r.tool() + " window="
                            + (r.args() == null ? "?" : r.args().get("window"))).orElse("?"));
        }
        sb.append("\n反证：").append(blank(decision.counterEvidence(), "无"));
        sb.append("\n不确定性：").append(blank(decision.uncertainty(), "无"));
        return sb.toString();
    }

    /** 拒答答案：明确说明无法回答 + 能力范围（DECLINE 零证据，天然不含证据链） */
    private String composeDecline(AgentDecision decision) {
        return decision.conclusion();
    }

    /** partial 终止：确定性摘要（已收集证据 + 终止原因），不调模型、不假装全成 */
    private AgentRun terminatePartial(AgentRun run, TerminationReason reason, LoopCtrl ctrl,
                                      java.util.function.Consumer<HarnessEvent> listener) {
        StringBuilder sb = new StringBuilder();
        sb.append("调查").append(reason == TerminationReason.CANCELLED ? "已被用户取消" : "在 " + reason.name() + " 时终止（未完成归因）")
                .append("，已用步骤 ").append(ctrl.steps()).append("/").append(ctrl.budget().maxSteps())
                .append("。\n已收集证据：");
        if (run.evidenceChain().size() == 0) {
            sb.append("无");
        } else {
            for (var e : run.evidenceChain().entries()) {
                var rec = run.registry().get(e.evidenceId());
                sb.append("\n[").append(e.evidenceId()).append("] ").append(e.tool())
                        .append(" window=").append(e.window())
                        .append(" 结果=").append(rec.map(r -> truncateToolResult(r.result())).orElse("(无记录)"));
            }
        }
        String answer = sb.toString();
        run.terminate(reason, answer);
        log.info("[harness] run={} {} answer={}", run.runId(), reason, answer);
        // 终态事件 type 与 run 状态一致（取消=CANCELLED，其余=PARTIAL）
        String eventType = run.status() == RunStatus.CANCELLED ? "CANCELLED" : "PARTIAL";
        emit(listener, new HarnessEvent(run.runId(), eventType, ctrl.steps(), null, null, null,
                reason.name(), answer));
        storeRunFinish(run, ctrl);
        return run;
    }

    private static int tokensOf(ChatResponse response) {
        var usage = response.tokenUsage();
        return usage == null ? 0
                : Math.max(0, usage.inputTokenCount()) + Math.max(0, usage.outputTokenCount());
    }

    private static String blank(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
