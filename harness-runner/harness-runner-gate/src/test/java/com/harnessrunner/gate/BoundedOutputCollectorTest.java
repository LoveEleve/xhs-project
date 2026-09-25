package com.harnessrunner.gate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedOutputCollectorTest {

    @Test
    void keepsEverythingBelowLimit() {
        BoundedOutputCollector collector = new BoundedOutputCollector(100);
        collector.append("hello ".toCharArray(), 6);
        assertEquals("hello ", collector.text());
        assertFalse(collector.truncated());
        assertEquals(6, collector.totalChars());
    }

    @Test
    void keepsTailAndCountsOmittedChars() {
        BoundedOutputCollector collector = new BoundedOutputCollector(10);
        collector.append("0123456789ABCDEF".toCharArray(), 16);
        assertTrue(collector.truncated());
        assertEquals(16, collector.totalChars());
        String text = collector.text();
        assertTrue(text.contains("省略前 6 字符"));
        assertTrue(text.endsWith("0123456789ABCDEF".substring(6)));
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedOutputCollector(0));
    }
}
