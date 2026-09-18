package com.myxhs.common.zone.admin;

import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.common.zone.ZoneContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Zone 管理端点测试（D3-10）
 */
class ZoneAdminControllerTest {

    private ZoneAdminController controller;

    @BeforeEach
    void setUp() {
        ZoneContext.get().reset();
        AccessTokenGuard guard = mock(AccessTokenGuard.class);
        when(guard.isInternalCall("valid-token")).thenReturn(true);
        when(guard.isInternalCall("bad-token")).thenReturn(false);
        controller = new ZoneAdminController(guard);
    }

    @AfterEach
    void tearDown() {
        ZoneContext.get().reset();
    }

    @Test
    void testSwitchRejectsInvalidToken() {
        assertEquals(401, controller.switchZone("zone-b", "bad-token").getCode());
        assertEquals("defaultZone", ZoneContext.get().getZone());
    }

    @Test
    void testSwitchUpdatesZone() {
        var response = controller.switchZone("zone-b", "valid-token");
        assertEquals(200, response.getCode());
        assertEquals("zone-b", ZoneContext.get().getZone());
        assertEquals("defaultZone", ((java.util.Map<?, ?>) response.getData()).get("from"));
    }

    @Test
    void testSwitchRejectsBlankZone() {
        assertEquals(40002, controller.switchZone("  ", "valid-token").getCode());
    }
}
