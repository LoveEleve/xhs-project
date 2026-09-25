package com.harnessrunner.domain.change;

import java.util.Arrays;
import java.util.Optional;

public enum Stage {
    HARNESSING(1, "需求分析"),
    CODING(2, "编码实现"),
    TEST_WRITE(3, "单测编写"),
    REVIEW(4, "专家评审"),
    CI(5, "CI 门禁"),
    DEPLOY_VERIFY(6, "部署验证");

    private final int order;
    private final String label;

    Stage(int order, String label) {
        this.order = order;
        this.label = label;
    }

    public int order() {
        return order;
    }

    public String label() {
        return label;
    }

    public static Stage first() {
        return HARNESSING;
    }

    public Optional<Stage> next() {
        return Arrays.stream(values())
                .filter(stage -> stage.order == this.order + 1)
                .findFirst();
    }

    public boolean isLast() {
        return next().isEmpty();
    }
}
