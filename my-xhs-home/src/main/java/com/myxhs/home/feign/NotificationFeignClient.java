package com.myxhs.home.feign;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.fallback.NotificationFeignFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * 通知服务 Feign Client
 */
@FeignClient(name = "my-xhs-notification",
        fallbackFactory = NotificationFeignFallbackFactory.class)
public interface NotificationFeignClient {

    /**
     * 获取未读通知数
     */
    @GetMapping("/api/notification/unread-count")
    R<Map<String, Object>> getUnreadCount(@RequestHeader("X-User-Id") Long userId);
}
