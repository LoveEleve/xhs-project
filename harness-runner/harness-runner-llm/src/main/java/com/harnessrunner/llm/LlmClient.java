package com.harnessrunner.llm;

public interface LlmClient {

    LlmResponse complete(String systemPrompt, String userPrompt);
}
