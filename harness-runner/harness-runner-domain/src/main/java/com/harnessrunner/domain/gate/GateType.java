package com.harnessrunner.domain.gate;

public enum GateType {
    AC_TESTABLE("验收条件可测"),
    COMPILE("编译"),
    TEST("单元测试"),
    COVERAGE("测试覆盖率"),
    DIFF_COVERAGE("增量覆盖率"),
    MUTATION("变异测试"),
    LINT("风格检查"),
    STATIC("静态分析"),
    ARCH("架构约束"),
    SMOKE("冒烟测试"),
    HEALTH("健康检查");

    private final String label;

    GateType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
