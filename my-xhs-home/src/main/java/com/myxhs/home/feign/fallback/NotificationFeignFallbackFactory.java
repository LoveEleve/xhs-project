package com.myxhs.home.feign.fallback;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.NotificationFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class NotificationFeignFallbackFactory implements FallbackFactory<NotificationFeignClient> {
    @Override
    public NotificationFeignClient create(Throwable cause) {
        log.warn("[降级] NotificationFeignClient 不可用: {}", cause.getMessage());
        return userId -> R.ok(Map.of("total", 0, "details", Map.of()));
    }
}
