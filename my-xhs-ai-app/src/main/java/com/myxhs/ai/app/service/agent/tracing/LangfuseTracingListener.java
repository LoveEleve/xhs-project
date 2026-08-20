package com.myxhs.ai.app.service.agent.tracing;

import com.myxhs.ai.app.service.agent.harness.HarnessEvent;
import com.myxhs.ai.app.service.agent.harness.HarnessEventType;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * D6 Langfuse Trace：监听 HarnessEvent，为每个事件创建 OTel span。
 *
 * 改进版：从 RunMetadataStore 读取真实 userId/sessionId/model/tokens/cost。
 */
@Component
public class LangfuseTracingListener implements Consumer<HarnessEvent> {

    private static final Logger log = LoggerFactory.getLogger(LangfuseTracingListener.class);

    private final Tracer tracer;
    private final RunMetadataStore metadataStore;

    /** runId → root Span（trace 根 span，用于关联子 span） */
    private final Map<String, Span> rootSpans = new ConcurrentHashMap<>();

    public LangfuseTracingListener(Tracer tracer, RunMetadataStore metadataStore) {
        this.tracer = tracer;
        this.metadataStore = metadataStore;
    }

    @Override
    public void accept(HarnessEvent event) {
        try {
            dispatch(event);
            log.debug("[langfuse] span created: type={} run={} step={} tool={}",
                    event.type(), event.runId(), event.stepNumber(), event.tool());
        } catch (Exception e) {
            log.warn("[langfuse] trace 事件处理失败: type={} run={} err={}",
                    event.type(), event.runId(), e.getMessage());
        }
    }

    private void dispatch(HarnessEvent event) {
        switch (event.type()) {
            case RUN_STARTED -> onStart(event);
            case THINK -> onThink(event);
            case TOOL -> onTool(event);
            case ANSWER -> onAnswer(event);
            case POLICY_DENIED -> onPolicyDenied(event);
            case WAITING_APPROVAL -> onWaitingApproval(event);
            case APPROVAL_RESULT -> onApprovalResult(event);
            case COMPLETED, PARTIAL, FAILED, CANCELLED -> onTerminal(event);
        }
    }

    /** Run 开始：创建 trace root span（Langfuse 需要 langfuse.* 属性来创建 trace） */
    private void onStart(HarnessEvent event) {
        RunMetadataStore.RunMetadata m = metadataStore.get(event.runId());
        String userId = m != null ? m.userId() : "unknown";
        String sessionId = m != null && m.sessionId() != null ? m.sessionId() : event.runId();

        Span root = tracer.spanBuilder("agent.run")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("langfuse.trace.name", "diagnosis-" + event.runId())
                .setAttribute("langfuse.user.id", userId)
                .setAttribute("langfuse.session.id", sessionId)
                .setAttribute("langfuse.observation.metadata.run_id", event.runId())
                .setAttribute("agent.step_count", event.stepNumber())
                .startSpan();
        rootSpans.put(event.runId(), root);
    }

    /** 模型决策：创建 generation span（Langfuse 识别为 LLM 调用） */
    private void onThink(HarnessEvent event) {
        Span root = rootSpans.get(event.runId());
        if (root == null) return;

        RunMetadataStore.RunMetadata m = metadataStore.get(event.runId());

        Span span = tracer.spanBuilder("agent.think")
                .setParent(Context.current().with(root))
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("langfuse.observation.type", "generation")
                .setAttribute("agent.step", event.stepNumber())
                .startSpan();

        if (m != null) {
            span.setAttribute("gen_ai.model.name", m.modelName() != null ? m.modelName() : "unknown");
            span.setAttribute("gen_ai.usage.input_tokens", m.inputTokens());
            span.setAttribute("gen_ai.usage.output_tokens", m.outputTokens());
            span.setAttribute("langfuse.observation.cost", m.cost());
        }
        if (event.tool() != null) {
            span.setAttribute("gen_ai.tool.name", event.tool());
        }
        if (event.message() != null) {
            String msg = event.message();
            span.setAttribute("langfuse.observation.input",
                    msg.length() > 1000 ? msg.substring(0, 1000) + "…" : msg);
        }
        span.end();
    }

    /** 工具执行完成：创建 tool span */
    private void onTool(HarnessEvent event) {
        Span root = rootSpans.get(event.runId());
        if (root == null) return;

        Span span = tracer.spanBuilder("agent.tool." + (event.tool() != null ? event.tool() : "unknown"))
                .setParent(Context.current().with(root))
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("langfuse.observation.type", "tool")
                .setAttribute("agent.step", event.stepNumber())
                .startSpan();

        if (event.tool() != null) {
            span.setAttribute("gen_ai.tool.name", event.tool());
        }
        if (event.window() != null) {
            span.setAttribute("agent.tool.window", event.window());
        }
        if (event.evidenceRefs() != null && !event.evidenceRefs().isEmpty()) {
            span.setAttribute("agent.evidence_refs", String.join(",", event.evidenceRefs()));
        }
        if (event.message() != null) {
            String msg = event.message();
            span.setAttribute("langfuse.observation.output",
                    msg.length() > 2000 ? msg.substring(0, 2000) + "…" : msg);
        }
        span.end();
    }

    /** 模型结论：创建 answer span */
    private void onAnswer(HarnessEvent event) {
        Span root = rootSpans.get(event.runId());
        if (root == null) return;

        Span span = tracer.spanBuilder("agent.answer")
                .setParent(Context.current().with(root))
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("langfuse.observation.type", "span")
                .setAttribute("agent.step", event.stepNumber())
                .startSpan();

        if (event.message() != null) {
            String msg = event.message();
            span.setAttribute("langfuse.observation.output",
                    msg.length() > 3000 ? msg.substring(0, 3000) + "…" : msg);
        }
        span.end();
    }

    /** 策略拒绝 */
    private void onPolicyDenied(HarnessEvent event) {
        Span root = rootSpans.get(event.runId());
        if (root == null) return;

        Span span = tracer.spanBuilder("agent.policy_denied")
                .setParent(Context.current().with(root))
                .setAttribute("langfuse.observation.level", "WARNING")
                .setAttribute("agent.step", event.stepNumber())
                .startSpan();

        if (event.tool() != null) {
            span.setAttribute("gen_ai.tool.name", event.tool());
        }
        if (event.message() != null) {
            span.setAttribute("langfuse.observation.status_message", event.message());
        }
        span.end();
    }

    /** HITL 审批挂起 */
    private void onWaitingApproval(HarnessEvent event) {
        Span root = rootSpans.get(event.runId());
        if (root == null) return;

        Span span = tracer.spanBuilder("agent.waiting_approval")
                .setParent(Context.current().with(root))
                .setAttribute("langfuse.observation.level", "DEFAULT")
                .setAttribute("agent.step", event.stepNumber())
                .startSpan();

        if (event.tool() != null) {
            span.setAttribute("gen_ai.tool.name", event.tool());
        }
        if (event.message() != null) {
            span.setAttribute("langfuse.observation.metadata.approval_info", event.message());
        }
        span.end();
    }

    /** HITL 审批结果 */
    private void onApprovalResult(HarnessEvent event) {
        Span root = rootSpans.get(event.runId());
        if (root == null) return;

        Span span = tracer.spanBuilder("agent.approval_result")
                .setParent(Context.current().with(root))
                .setAttribute("agent.step", event.stepNumber())
                .startSpan();

        if (event.message() != null) {
            span.setAttribute("langfuse.observation.metadata.approval_result", event.message());
        }
        span.end();
    }

    /** 终态（COMPLETED/PARTIAL/FAILED/CANCELLED）：关闭 root span */
    private void onTerminal(HarnessEvent event) {
        Span root = rootSpans.remove(event.runId());
        if (root == null) return;

        root.setAttribute("agent.termination", event.type().name());
        if (event.terminationReason() != null) {
            root.setAttribute("agent.termination_reason", event.terminationReason());
        }
        if (event.message() != null) {
            String msg = event.message();
            root.setAttribute("langfuse.observation.output",
                    msg.length() > 5000 ? msg.substring(0, 5000) + "…" : msg);
        }

        if (event.type() == HarnessEventType.FAILED) {
            root.setStatus(StatusCode.ERROR, event.terminationReason() != null
                    ? event.terminationReason() : "failed");
        } else {
            root.setStatus(StatusCode.OK);
        }

        root.end();
        metadataStore.remove(event.runId());
    }
}
