package com.myxhs.home.feign.fallback;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.InventoryFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Slf4j
@Component
public class InventoryFeignFallbackFactory implements FallbackFactory<InventoryFeignClient> {
    @Override
    public InventoryFeignClient create(Throwable cause) {
        log.warn("[降级] InventoryFeignClient 不可用: {}", cause.getMessage());
        return skuId -> R.ok(Collections.emptyMap());
    }
}
