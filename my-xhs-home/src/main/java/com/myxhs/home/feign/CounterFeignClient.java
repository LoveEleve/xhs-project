package com.myxhs.home.feign;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.fallback.CounterFeignFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 计数服务 Feign Client
 */
@FeignClient(name = "my-xhs-counter",
        fallbackFactory = CounterFeignFallbackFactory.class)
public interface CounterFeignClient {

    /**
     * 批量查询计数
     */
    @PostMapping("/api/counter/batch-get")
    R<Map<String, Map<String, Long>>> batchGetCounts(@RequestBody Map<String, Object> request);
}
