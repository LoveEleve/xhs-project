package com.myxhs.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search After 游标编解码（类型标签）测试
 */
class SearchAfterCodecTest {

    static class TestService extends AbstractSearchService {
        String encode(List<FieldValue> values) {
            return serializeSearchAfter(values);
        }

        List<FieldValue> decode(String cursor) {
            return parseSearchAfter(cursor);
        }
    }

    private final TestService service = new TestService();

    @Test
    @DisplayName("整数值 double（1.0/100.0）往返仍是 double")
    void wholeDoubleRoundTrip() {
        String cursor = service.encode(List.of(FieldValue.of(1.0), FieldValue.of(100.0)));
        List<FieldValue> values = service.decode(cursor);

        assertThat(values).hasSize(2);
        assertThat(values.get(0).isDouble()).isTrue();
        assertThat(values.get(0).doubleValue()).isEqualTo(1.0);
        assertThat(values.get(1).isDouble()).isTrue();
        assertThat(values.get(1).doubleValue()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("long/string/boolean/null 往返类型不丢")
    void mixedTypesRoundTrip() {
        String cursor = service.encode(List.of(
                FieldValue.of(10086L), FieldValue.of("note-1"), FieldValue.of(true), FieldValue.NULL));
        List<FieldValue> values = service.decode(cursor);

        assertThat(values.get(0).isLong()).isTrue();
        assertThat(values.get(0).longValue()).isEqualTo(10086L);
        assertThat(values.get(1).isString()).isTrue();
        assertThat(values.get(1).stringValue()).isEqualTo("note-1");
        assertThat(values.get(2).isBoolean()).isTrue();
        assertThat(values.get(2).booleanValue()).isTrue();
        assertThat(values.get(3).isNull()).isTrue();
    }

    @Test
    @DisplayName("旧格式裸数字仍可解析（1.5→double / 10086→long）")
    void legacyCursorStillWorks() {
        List<FieldValue> values = service.decode("[1.5,10086,\"x\"]");

        assertThat(values.get(0).isDouble()).isTrue();
        assertThat(values.get(0).doubleValue()).isEqualTo(1.5);
        assertThat(values.get(1).isLong()).isTrue();
        assertThat(values.get(1).longValue()).isEqualTo(10086L);
        assertThat(values.get(2).isString()).isTrue();
    }
}
