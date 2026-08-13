package com.myxhs.ai.app.service.agent.harness;

import java.util.Map;
import java.util.Objects;

/**
 * 循环检测（设计 §4，防死循环），两种模式任一命中即终止：
 *  1. 重复工具+相同参数：连续 N 次（默认 3）且期间**无新证据** → LOOP_REPEATED_CALL
 *     （证据前进=数据/结果变化，视为新探索，重置计数，防误杀）
 *  2. 状态不前进：过去 M 步（默认 6）证据链 hash 无新增 → LOOP_NO_PROGRESS
 * 调用协议（Harness 每步遵守，防双计）：
 *  - 每完成一步（THINK 或 TOOL）→ recordStep(evidenceHash)
 *  - 工具调用完成时用 recordToolCall(tool, args, evidenceHash) 代替本步的 recordStep
 * 无状态可复用：每 Run 新建实例。
 */
public class LoopDetector {

    private final int maxRepeatedCalls;
    private final int noProgressWindow;

    private String lastCallFingerprint;
    private int consecutiveCalls;
    private String lastEvidenceHash;
    private int noProgressSteps;

    public LoopDetector(int maxRepeatedCalls, int noProgressWindow) {
        if (maxRepeatedCalls <= 0 || noProgressWindow <= 0) {
            throw new IllegalArgumentException("循环检测阈值必须 > 0");
        }
        this.maxRepeatedCalls = maxRepeatedCalls;
        this.noProgressWindow = noProgressWindow;
    }

    public LoopDetector() {
        this(3, 6);
    }

    /** 每完成一步调用（含 THINK 空转步）；命中返回终止原因，否则 null */
    public TerminationReason recordStep(String evidenceHash) {
        if (Objects.equals(evidenceHash, lastEvidenceHash)) {
            noProgressSteps++;
        } else {
            noProgressSteps = 1;
            lastEvidenceHash = evidenceHash;
        }
        if (noProgressSteps >= noProgressWindow) {
            return TerminationReason.LOOP_NO_PROGRESS;
        }
        return null;
    }

    /** 工具调用完成后调用（本步不再调 recordStep，防双计）；命中返回终止原因，否则 null */
    public TerminationReason recordToolCall(String toolName, Map<String, String> args, String evidenceHash) {
        String fingerprint = toolName + "|" + Objects.toString(args);
        boolean newEvidence = !Objects.equals(evidenceHash, lastEvidenceHash);
        if (newEvidence) {
            consecutiveCalls = 1;
        } else if (fingerprint.equals(lastCallFingerprint)) {
            consecutiveCalls++;
        } else {
            consecutiveCalls = 1;
        }
        lastCallFingerprint = fingerprint;
        if (consecutiveCalls >= maxRepeatedCalls) {
            return TerminationReason.LOOP_REPEATED_CALL;
        }
        return recordStep(evidenceHash);
    }
}
