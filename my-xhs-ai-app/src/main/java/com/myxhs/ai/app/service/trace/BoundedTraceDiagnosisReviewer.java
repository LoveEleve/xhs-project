package com.myxhs.ai.app.service.trace;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class BoundedTraceDiagnosisReviewer implements TraceDiagnosisReviewAccess {

    @Override
    public TraceDiagnosisReviewer.ReviewResult review(TraceDiagnosisReviewerInput input) {
        if (input == null) {
            return new TraceDiagnosisReviewer.ReviewResult("blocked", "missing_diagnosis", "审查器没有拿到诊断输入，按 fail-closed 阻塞");
        }
        if (input.hitServices() == null || input.hitServices().isEmpty()) {
            return new TraceDiagnosisReviewer.ReviewResult("uncertain", "missing_evidence", "没有命中服务证据，不能形成完成判定");
        }
        if (input.entryService() == null || input.lastService() == null) {
            return new TraceDiagnosisReviewer.ReviewResult("blocked", "incomplete_timeline", "缺少入口或末端服务信息，不能完成判定");
        }
        if (input.suspiciousEvents() != null && !input.suspiciousEvents().isEmpty()) {
            return new TraceDiagnosisReviewer.ReviewResult("uncertain", "hypothesis_only", "存在异常信号，但当前仍是候选定位，不足以证明根因");
        }
        if (input.hitServices().size() == 1) {
            return new TraceDiagnosisReviewer.ReviewResult("continue", "needs_more_evidence", "只有单服务命中，建议继续检查下游服务或扩大时间窗口");
        }
        if (input.timelinePreview() == null || input.timelinePreview().isEmpty()) {
            return new TraceDiagnosisReviewer.ReviewResult("blocked", "missing_timeline", "缺少时间线摘要，不能完成判定");
        }
        return new TraceDiagnosisReviewer.ReviewResult("complete", "evidence_sufficient", "命中多服务且时间线完整，可视为完成态候选");
    }
}
