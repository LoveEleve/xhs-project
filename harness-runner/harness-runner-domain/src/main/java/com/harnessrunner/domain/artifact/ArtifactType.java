package com.harnessrunner.domain.artifact;

public enum ArtifactType {
    PRD("需求规格"),
    SOLUTION("方案设计"),
    TEST_DESIGN("测试设计"),
    TEST_CASES("测试用例"),
    TEST_REPORT("测试报告"),
    DIFF("代码变更"),
    REVIEW("专家评审"),
    VERIFY("验证记录");

    private final String label;

    ArtifactType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
