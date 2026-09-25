package com.harnessrunner.llm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AcValidator {

    private AcValidator() {
    }

    public static List<String> problems(List<AcceptanceCriterion> criteria) {
        List<String> problems = new ArrayList<>();
        if (criteria == null || criteria.isEmpty()) {
            problems.add("AC 列表为空");
            return problems;
        }
        Set<String> ids = new HashSet<>();
        for (AcceptanceCriterion criterion : criteria) {
            if (criterion.id().isBlank()) {
                problems.add("存在缺少 id 的 AC");
            } else if (!ids.add(criterion.id())) {
                problems.add("AC id 重复: " + criterion.id());
            }
            if (criterion.statement().isBlank()) {
                problems.add("AC " + criterion.id() + " 缺少可测描述");
            }
        }
        return problems;
    }
}
