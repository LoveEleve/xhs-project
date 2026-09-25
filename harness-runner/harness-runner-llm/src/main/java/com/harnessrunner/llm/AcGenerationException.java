package com.harnessrunner.llm;

public class AcGenerationException extends RuntimeException {

    private final String prompt;
    private final String rawResponse;

    public AcGenerationException(String message, String prompt, String rawResponse, Throwable cause) {
        super(message, cause);
        this.prompt = prompt == null ? "" : prompt;
        this.rawResponse = rawResponse == null ? "" : rawResponse;
    }

    public String prompt() {
        return prompt;
    }

    public String rawResponse() {
        return rawResponse;
    }
}
