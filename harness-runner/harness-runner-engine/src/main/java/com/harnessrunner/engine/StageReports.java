package com.harnessrunner.engine;

import com.harnessrunner.domain.change.Change;
import com.harnessrunner.domain.gate.GateResult;
import com.harnessrunner.engine.store.StageRunRecord;

public final class StageReports {

    private StageReports() {
    }

    public static String markdown(Change change, StageRunRecord record) {
        StringBuilder report = new StringBuilder();
        report.append("# 阶段报告 · ").append(record.stage().order())
                .append(" ").append(record.stage().label()).append("\n\n");
        report.append("- 变更: ").append(change.id()).append("\n");
        report.append("- 项目: ").append(change.projectId()).append("\n");
        report.append("- 需求: ").append(change.requirement()).append("\n");
        report.append("- 尝试: 第 ").append(record.attempt()).append(" 次\n");
        report.append("- 结论: ").append(record.passed() ? "通过" : "失败").append("\n");
        report.append("- 开始: ").append(record.startedAt()).append("\n");
        report.append("- 结束: ").append(record.finishedAt()).append("\n\n");

        if (record.gates().isEmpty()) {
            report.append("> 本阶段无可执行门禁（人工/LLM 阶段）。\n");
            return report.toString();
        }

        report.append("## 门禁结果\n\n");
        report.append("| 门禁 | 结论 | 原因 | 退出码 | 耗时(ms) | 证据 |\n");
        report.append("|---|---|---|---|---|---|\n");
        for (GateResult gate : record.gates()) {
            report.append("| ").append(gate.type().label())
                    .append(" | ").append(gate.passed() ? "PASS" : "FAIL")
                    .append(" | ").append(escape(gate.reason()))
                    .append(" | ").append(gate.exitCode())
                    .append(" | ").append(gate.durationMs())
                    .append(" | ").append(gate.outputRef() == null ? "-" : gate.outputRef())
                    .append(" |\n");
        }
        return report.toString();
    }

    private static String escape(String text) {
        return text.replace("|", "\\|");
    }
}
