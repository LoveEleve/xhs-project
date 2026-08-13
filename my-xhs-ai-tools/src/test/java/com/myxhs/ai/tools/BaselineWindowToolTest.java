package com.myxhs.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BaselineWindowTool 纯逻辑测试（无 DB）：同长窗口计算 + 参数校验。
 */
class BaselineWindowToolTest {

    private final ObjectMapper om = new ObjectMapper();
    private final BaselineWindowTool tool = new BaselineWindowTool(om);

    private JsonNode call(String window) throws Exception {
        return om.readTree(tool.baselineWindow(window));
    }

    @Test
    void 七天窗口_返回上一同长七天() throws Exception {
        JsonNode r = call("2026-08-01~2026-08-07");
        assertEquals("ok", r.path("status").asText());
        assertEquals("2026-07-25~2026-07-31", r.path("baseline").asText());
        assertEquals(7, r.path("spanDays").asInt());
    }

    @Test
    void 单日窗口_返回前一天() throws Exception {
        JsonNode r = call("2026-08-07~2026-08-07");
        assertEquals("2026-08-06~2026-08-06", r.path("baseline").asText());
        assertEquals(1, r.path("spanDays").asInt());
    }

    @Test
    void 跨月窗口_正确回退() throws Exception {
        JsonNode r = call("2026-08-01~2026-08-31");
        assertEquals("2026-07-01~2026-07-31", r.path("baseline").asText());
        assertEquals(31, r.path("spanDays").asInt());
    }

    @Test
    void 非法参数_返回error不抛异常() throws Exception {
        assertEquals("error", call("2026-08-07").path("status").asText());
        assertEquals("error", call("20260801~20260807").path("status").asText());
        assertEquals("error", call("2026-08-07~2026-08-01").path("status").asText());
        assertEquals("error", call("2026-08-01~2026-09-05").path("status").asText());
        assertEquals("error", call("").path("status").asText());
        assertEquals("error", call(null).path("status").asText());
    }

    @Test
    void 结果格式_含口径与asOf() throws Exception {
        JsonNode r = call("2026-08-01~2026-08-07");
        assertTrue(r.has("asOf"));
        assertEquals("baseline.window", r.path("metric").asText());
        assertTrue(r.path("rule").asText().contains("上一同长窗口"));
    }
}
