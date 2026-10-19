package com.myxhs.home.controller;

import com.myxhs.common.annotation.RateLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * home BFF 限流注解核验（C-014 §7-5：原 5 端点零限流——首页/详情是最高频公开面，
 * 一次 feed 请求扇出 9 个下游 Feign）。
 */
class HomeControllerRateLimitTest {

    @Test
    @DisplayName("5 端点 @RateLimit 落位")
    void rateLimitsPresent() {
        for (String methodName : Arrays.asList("getFollowFeed", "getNoteDetail", "getProductDetail",
                "getUserProfile", "getCartAgg")) {
            Method m = Arrays.stream(HomeController.class.getMethods())
                    .filter(mm -> mm.getName().equals(methodName)).findFirst().orElseThrow();
            assertThat(m.getAnnotation(RateLimit.class)).as(methodName + " 应有 @RateLimit").isNotNull();
        }
    }
}
