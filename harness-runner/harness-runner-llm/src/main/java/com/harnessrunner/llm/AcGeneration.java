package com.harnessrunner.llm;

import java.util.Objects;

public record AcGeneration(AcDraft draft, String prompt, LlmResponse response) {

    public AcGeneration {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(response, "response");
    }
}
