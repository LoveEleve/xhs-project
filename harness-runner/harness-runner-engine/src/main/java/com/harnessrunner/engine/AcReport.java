package com.harnessrunner.engine;

import com.harnessrunner.domain.change.Change;
import com.harnessrunner.engine.store.LlmCall;
import com.harnessrunner.llm.AcDraft;
import com.harnessrunner.llm.AcceptanceCriterion;

public final class AcReport {

    private AcReport() {
    }

    public static String markdown(Change change, AcDraft draft, LlmCall call) {
        StringBuilder report = new StringBuilder();
        report.append("# 需求规格 · AC 草案\n\n");
        report.append("- 变更: ").append(change.id()).append("\n");
        report.append("- 项目: ").append(change.projectId()).append("\n");
        report.append("- 需求: ").append(change.requirement()).append("\n");
        report.append("- 概述: ").append(draft.summary().isBlank() ? "-" : draft.summary()).append("\n");
        report.append("- 生成: ").append(call.model())
                .append("（latency=").append(call.latencyMs()).append(" ms, promptChars=")
                .append(call.promptChars()).append(", outputChars=").append(call.outputChars())
                .append("）\n\n");
        report.append("## 验收条件（AC）\n\n");
        report.append("| 编号 | 验收条件 |\n");
        report.append("|---|---|\n");
        for (AcceptanceCriterion criterion : draft.criteria()) {
            report.append("| ").append(criterion.id())
                    .append(" | ").append(escape(criterion.statement()))
                    .append(" |\n");
        }
        return report.toString();
    }

    public static String failure(Change change, String message, String rawResponse, LlmCall call) {
        StringBuilder report = new StringBuilder();
        report.append("# 需求规格 · 生成失败\n\n");
        report.append("- 变更: ").append(change.id()).append("\n");
        report.append("- 需求: ").append(change.requirement()).append("\n");
        report.append("- 失败原因: ").append(message).append("\n");
        report.append("- 调用: ").append(call.id())
                .append("（promptChars=").append(call.promptChars())
                .append(", outputChars=").append(call.outputChars())
                .append("）\n\n");
        if (!rawResponse.isBlank()) {
            report.append("## 原始输出（截断）\n\n```\n")
                    .append(excerpt(rawResponse))
                    .append("\n```\n");
        }
        return report.toString();
    }

    private static String escape(String text) {
        return text.replace("|", "\\|");
    }

    private static String excerpt(String text) {
        return text.length() <= 2000 ? text : text.substring(0, 2000) + "\n...(截断)";
    }
}
