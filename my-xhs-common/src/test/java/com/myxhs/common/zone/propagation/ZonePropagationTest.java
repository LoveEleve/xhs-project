package com.myxhs.common.zone.propagation;

import com.myxhs.common.zone.ZoneContext;
import feign.RequestTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Zone 传播测试（入站过滤器 + 出站拦截器）
 */
class ZonePropagationTest {

    @AfterEach
    void tearDown() {
        ZoneContextHolder.clear();
        ZoneContext.get().reset();
    }

    @Test
    void testInboundFilterSetsAndClearsHolder() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ZonePropagationFilter filter = new ZonePropagationFilter(registry);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/demo");
        request.addHeader(ZoneContextHolder.ZONE_HEADER, "zone-a");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(ZoneContextHolder.get(), "请求结束必须清理 ThreadLocal");
        assertEquals(1.0, registry.find("myxhs_zone_propagation_total")
                .tags("direction", "in", "zone", "zone-a").counter().count());
    }

    @Test
    void testOutboundInterceptorAddsHeader() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ZoneContextHolder.set("zone-a");
        ZonePropagationInterceptor interceptor = new ZonePropagationInterceptor(registry);

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        assertEquals("zone-a", template.headers().get(ZoneContextHolder.ZONE_HEADER).iterator().next());
        assertEquals(1.0, registry.find("myxhs_zone_propagation_total")
                .tags("direction", "out", "zone", "zone-a").counter().count());
    }

    @Test
    void testOutboundSkipsDefaultZone() {
        ZoneContext.get().setZone("defaultZone");
        ZonePropagationInterceptor interceptor = new ZonePropagationInterceptor(null);
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertEquals(null, template.headers().get(ZoneContextHolder.ZONE_HEADER));
    }
}
