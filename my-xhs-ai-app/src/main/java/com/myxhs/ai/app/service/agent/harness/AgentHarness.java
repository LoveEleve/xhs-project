package com.myxhs.ai.app.service.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.tools.MetricToolAccess;
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
            规则：
            1. 数字必须来自工具结果，禁止编造。
            2. 对比/升降分析：**基线窗口必须用 baselineWindow 计算**（上一同长窗口），不得自行推算；
               若用户指定了当前窗口就用它，否则取最近 7 天。
            3. 工具返回 error/partial 时如实说明，不猜测。
            4. 证据充分即 ANSWER；证据不足继续 TOOL_CALL。
            5. 不把相关当因果；有反证须显式说明（counterEvidence）；结论的不确定性须声明。
            6. 每次输出必须是合法 JSON（不要 markdown 代码块），格式：
            {"action":"TOOL_CALL","tool":"queryOrderVolume","args":{"window":"2026-08-01~2026-08-07"},"reasoning":"为什么查"}
            {"action":"ANSWER","conclusion":"结论","evidenceRefs":["ev_xxx"],"counterEvidence":"反证或空","uncertainty":"不确定性或空"}
            """;

    private final ChatModel chatModel;
    private final MetricToolAccess metricToolAccess;
    private final PolicyGuard policyGuard = new PolicyGuard();
    private final AgentDecisionCodec codec;
    private final AgentBudget defaultBudget;
    private final double pricePer1kTokens;
    private final int maxInvalidAnswers;

    public AgentHarness(ChatModel chatModel, MetricToolAccess metricToolAccess, ObjectMapper mapper,
                        AgentBudget defaultBudget, double pricePer1kTokens, int maxInvalidAnswers) {
        this.chatModel = chatModel;
        this.metricToolAccess = metricToolAccess;
        this.codec = new AgentDecisionCodec(mapper);
        this.defaultBudget = defaultBudget;
        this.pricePer1kTokens = pricePer1kTokens;
        this.maxInvalidAnswers = maxInvalidAnswers;
    }

    public AgentRun run(String query) {
        return run(query, defaultBudget);
    }

    public AgentRun run(String query, AgentBudget budget) {
        AgentRun run = new AgentRun("run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                query, budget);
        LoopCtrl ctrl = new LoopCtrl(budget);
        LoopDetector loop = new LoopDetector();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.add(UserMessage.from("用户问题：" + query));
        int invalidOutputs = 0;

        log.info("[harness] run={} start query={} budget={}", run.runId(), query, budget);

        while (true) {
            TerminationReason pre = ctrl.checkBeforeStep();
            if (pre != null) {
                return terminatePartial(run, pre, ctrl);
            }

            // THINK：模型决策（JSON 结构化输出；失败重试一次，仍失败 → 明确降级 FAILED，不瞎编）
            ChatResponse response = callModel(run.runId(), messages);
            if (response == null) {
                run.terminate(TerminationReason.MODEL_UNAVAILABLE, "模型暂不可用，请稍后重试（调查未完成）");
                return run;
            }
            int tokens = tokensOf(response);
            ctrl.recordStep(tokens);
            ctrl.recordCost(tokens / 1000.0 * pricePer1kTokens);

            AgentDecision decision = codec.parse(response.aiMessage().text());
            if (decision == null) {
                run.recordStep(AgentStep.think(ctrl.steps(), null, tokens));
                messages.add(UserMessage.from(AgentDecisionCodec.malformedOutputMessage()));
                log.warn("[harness] run={} 模型输出非 JSON，反馈重想", run.runId());
                invalidOutputs++;
                if (invalidOutputs >= maxInvalidAnswers) {
                    return terminatePartial(run, TerminationReason.EVIDENCE_INVALID, ctrl);
                }
                continue;
            }
            run.recordStep(AgentStep.think(ctrl.steps(), decision, tokens));
            messages.add(AiMessage.from(response.aiMessage().text()));

            if (decision.isToolCall()) {
                // VALIDATE：deny-by-default
                // 预算语义：policy 拒绝不计步骤数（惩罚探索会扭曲调查），由 LoopCtrl.policyDeniedCount
                // 单独计数，连续 N 次拒绝 → POLICY_EXHAUSTED（设计 §2"反馈模型重想"的专用机制）
                PolicyDecision pd = policyGuard.evaluate(decision.tool(), decision.args());
                if (!pd.allowed()) {
                    ctrl.recordPolicyDenied();
                    run.recordStep(AgentStep.policyDenied(ctrl.steps(), decision, pd.reason()));
                    String note = pd.requiresApproval()
                            ? pd.reason() + "（L3 需人工审批，V1 不可执行）" : pd.reason();
                    messages.add(UserMessage.from(AgentDecisionCodec.feedback(decision, note)));
                    log.info("[harness] run={} policy_denied tool={} reason={}", run.runId(), decision.tool(), pd.reason());
                    TerminationReason loopReason = loop.recordStep(run.evidenceChain().hash());
                    if (loopReason != null) {
                        return terminatePartial(run, loopReason, ctrl);
                    }
                    continue;
                }

                // TOOL：执行 + 登记证据（存在性校验数据源）
                String result = callTool(decision.tool(), decision.args());
                String evidenceId = run.registry().register(decision.tool(), decision.args(), result);
                String window = decision.args() == null ? null : decision.args().get("window");
                run.evidenceChain().add(evidenceId, decision.tool(), window, result);
                run.recordStep(AgentStep.tool(ctrl.steps(), decision, result, List.of(evidenceId)));
                messages.add(UserMessage.from("工具 " + decision.tool() + " 结果（证据 id=" + evidenceId + "）：" + result));
                log.info("[harness] run={} tool={} window={} ev={} result={}", run.runId(),
                        decision.tool(), window, evidenceId, result);

                // LOOPCHECK
                TerminationReason loopReason = loop.recordToolCall(decision.tool(), decision.args(),
                        run.evidenceChain().hash());
                if (loopReason != null) {
                    return terminatePartial(run, loopReason, ctrl);
                }
            } else if (decision.isAnswer()) {
                // 存在性校验（确定性兜底，不靠模型自觉）
                String invalid = validateAnswer(run, decision);
                if (invalid == null) {
                    run.recordStep(AgentStep.answer(ctrl.steps(), decision));
                    String answer = composeAnswer(decision, run);
                    run.terminate(TerminationReason.COMPLETED, answer);
                    log.info("[harness] run={} COMPLETED ev={} answer={}", run.runId(),
                            run.evidenceChain().size(), answer);
                    return run;
                }
                invalidOutputs++;
                run.recordStep(AgentStep.answer(ctrl.steps(), decision));
                messages.add(UserMessage.from(AgentDecisionCodec.feedback(decision, invalid)));
                log.warn("[harness] run={} 证据校验失败: {}", run.runId(), invalid);
                if (invalidOutputs >= maxInvalidAnswers) {
                    return terminatePartial(run, TerminationReason.EVIDENCE_INVALID, ctrl);
                }
                TerminationReason loopReason = loop.recordStep(run.evidenceChain().hash());
                if (loopReason != null) {
                    return terminatePartial(run, loopReason, ctrl);
                }
            } else {
                invalidOutputs++;
                messages.add(UserMessage.from(AgentDecisionCodec.feedback(decision, "action 必须是 TOOL_CALL 或 ANSWER")));
                if (invalidOutputs >= maxInvalidAnswers) {
                    return terminatePartial(run, TerminationReason.EVIDENCE_INVALID, ctrl);
                }
                TerminationReason loopReason = loop.recordStep(run.evidenceChain().hash());
                if (loopReason != null) {
                    return terminatePartial(run, loopReason, ctrl);
                }
            }
        }
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
        try {
            return switch (tool) {
                case PolicyGuard.TOOL_ORDER_VOLUME -> metricToolAccess.queryOrderVolume(window);
                case PolicyGuard.TOOL_PAYMENT_RATE -> metricToolAccess.paymentSuccessRate(window);
                case PolicyGuard.TOOL_CONTENT_INTERACTION -> metricToolAccess.contentInteraction(window);
                case PolicyGuard.TOOL_BASELINE_WINDOW -> metricToolAccess.baselineWindow(window);
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

    /** partial 终止：确定性摘要（已收集证据 + 终止原因），不调模型、不假装全成 */
    private AgentRun terminatePartial(AgentRun run, TerminationReason reason, LoopCtrl ctrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("调查在 ").append(reason.name()).append(" 时终止（未完成归因）")
                .append("，已用步骤 ").append(ctrl.steps()).append("/").append(ctrl.budget().maxSteps())
                .append("。\n已收集证据：");
        if (run.evidenceChain().size() == 0) {
            sb.append("无");
        } else {
            for (var e : run.evidenceChain().entries()) {
                var rec = run.registry().get(e.evidenceId());
                sb.append("\n[").append(e.evidenceId()).append("] ").append(e.tool())
                        .append(" window=").append(e.window())
                        .append(" 结果=").append(rec.map(r -> r.result()).orElse("(无记录)"));
            }
        }
        String answer = sb.toString();
        run.terminate(reason, answer);
        log.info("[harness] run={} {} answer={}", run.runId(), reason, answer);
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
