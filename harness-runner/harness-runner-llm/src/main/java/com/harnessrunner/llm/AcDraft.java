package com.harnessrunner.llm;

import java.util.List;
import java.util.Objects;

public record AcDraft(String summary, List<AcceptanceCriterion> criteria) {

    public AcDraft {
        summary = summary == null ? "" : summary;
        criteria = criteria == null ? List.of() : List.copyOf(criteria);
    }
}
