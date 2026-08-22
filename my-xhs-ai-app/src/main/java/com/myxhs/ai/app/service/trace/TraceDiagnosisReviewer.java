package com.myxhs.ai.app.service.trace;

import org.springframework.stereotype.Component;

@Component
public class TraceDiagnosisReviewer implements TraceDiagnosisReviewAccess {

    @Override
    public ReviewResult review(TraceDiagnosisReviewerInput input) {
        if (input == null) {
            return new ReviewResult("blocked", "missing_diagnosis", "没有可审查的诊断输入");
        }
        if (input.hitServices() == null || input.hitServices().isEmpty()) {
            return new ReviewResult("uncertain", "missing_evidence", "没有命中服务证据，不能形成完成判定");
        }
        if (input.suspiciousEvents() != null && !input.suspiciousEvents().isEmpty()) {
            return new ReviewResult("uncertain", "hypothesis_only", "存在异常信号，但当前仍缺少下游调用或额外验证证据");
        }
        if (input.hitServices().size() == 1) {
            return new ReviewResult("continue", "needs_more_evidence", "只有单服务命中，建议继续检查下游服务或扩大日志窗口");
        }
        if (input.entryService() == null || input.lastService() == null) {
            return new ReviewResult("blocked", "incomplete_timeline", "缺少入口或末端服务信息，不能完成判定");
        }
        return new ReviewResult("complete", "evidence_sufficient", "已形成跨服务证据链，可视为完成态候选");
    }

    public record ReviewResult(String verdict, String verificationStatus, String rationale) {
    }
}
