package com.myxhs.home.feign.fallback;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.CounterFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
public class CounterFeignFallbackFactory implements FallbackFactory<CounterFeignClient> {
    @Override
    public CounterFeignClient create(Throwable cause) {
        log.warn("[降级] CounterFeignClient 不可用: {}", cause.getMessage());
        return request -> R.ok(Collections.emptyMap());
    }
}
