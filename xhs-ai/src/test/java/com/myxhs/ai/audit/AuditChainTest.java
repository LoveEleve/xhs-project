package com.myxhs.ai.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditChainTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void canonicalSortsKeysRecursively() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("b", 2);
        nested.put("a", 1);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("z", nested);
        input.put("m", "x");
        assertThat(AuditChain.canonical(mapper, input)).isEqualTo("{\"m\":\"x\",\"z\":{\"a\":1,\"b\":2}}");
    }

    @Test
    void hashIsDeterministicAndTamperSensitive() {
        String h1 = AuditChain.hash("GENESIS", "t1", 99L, "dlq.redeliver", "rocketmq", "{\"a\":1}", "ok");
        String h2 = AuditChain.hash("GENESIS", "t1", 99L, "dlq.redeliver", "rocketmq", "{\"a\":1}", "ok");
        assertThat(h1).isEqualTo(h2).hasSize(64);
        String tampered = AuditChain.hash("GENESIS", "t1", 99L, "dlq.redeliver", "rocketmq", "{\"a\":2}", "ok");
        assertThat(tampered).isNotEqualTo(h1);
    }

    @Test
    void resultTruncatedToColumnLimit() {
        assertThat(AuditChain.truncateResult("x".repeat(2000))).hasSize(1024);
        assertThat(AuditChain.truncateResult(null)).isNull();
    }
}
