package com.myxhs.ai.mq;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DlqAdminServiceTest {

    @Test
    void retryTopicsMapToDistinctGroups() {
        List<String> groups = DlqAdminService.retryGroups(List.of(
                "%RETRY%cart-sync-consumer-group",
                "%RETRY%counter-consumer-group",
                "%RETRY%cart-sync-consumer-group",
                "CART_TOPIC", "%DLQ%cart-sync-consumer-group", "%RETRY%"));
        assertThat(groups).containsExactly("cart-sync-consumer-group", "counter-consumer-group");
    }
}
