package com.myxhs.common.zone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZoneResolver 测试
 */
class ZoneResolverTest {

    @Test
    void testResolve() {
        ZoneResolver<String> resolver = s -> "zone-" + s;

        assertEquals("zone-test", resolver.resolve("test"));
        assertEquals("zone-shanghai", resolver.resolve("shanghai"));
    }

    @Test
    void testResolveNull() {
        ZoneResolver<String> resolver = s -> null;

        assertNull(resolver.resolve("anything"));
    }

    @Test
    void testApplyDelegatesToResolve() {
        ZoneResolver<Integer> resolver = i -> i > 0 ? "positive" : "negative";

        assertEquals("positive", resolver.apply(5));
        assertEquals("negative", resolver.apply(-1));
    }
}
