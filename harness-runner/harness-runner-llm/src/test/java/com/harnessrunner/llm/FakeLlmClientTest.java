package com.harnessrunner.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeLlmClientTest {

    @Test
    void fakeClientProducesValidJsonWithRequirement() {
        String prompt = "项目: p\n需求：\n给 Calc 增加除法\n";

        LlmResponse response = new FakeLlmClient().complete("sys", prompt);

        assertTrue(response.content().contains("给 Calc 增加除法"), response.content());
        assertTrue(response.content().contains("AC-1"));
        assertEquals("fake-deterministic", response.model());
    }

    @Test
    void fakeClientParsesThroughGenerator() {
        AcGeneration generation = new AcGenerator(new FakeLlmClient()).generate("p", "给 Calc 增加除法");

        assertEquals(3, generation.draft().criteria().size());
        assertTrue(generation.draft().summary().contains("给 Calc 增加除法"));
    }
}
