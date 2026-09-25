package com.harnessrunner.domain.change;

public enum ChangeEventType {
    CHANGE_CREATED("变更创建"),
    STAGE_STARTED("阶段开始"),
    STAGE_PASSED("阶段通过"),
    STAGE_FAILED("阶段失败"),
    APPROVAL_REQUESTED("等待人工确认"),
    APPROVED("人工确认通过"),
    CHANGE_FAILED("变更失败"),
    CHANGE_DONE("变更完成");

    private final String label;

    ChangeEventType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
