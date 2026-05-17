package com.myxhs.home.feign;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.fallback.UserFeignFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 用户服务 Feign Client
 */
@FeignClient(name = "my-xhs-user",
        fallbackFactory = UserFeignFallbackFactory.class)
public interface UserFeignClient {

    /**
     * 获取用户公开信息
     */
    @GetMapping("/api/user/{userId}/info")
    R<Map<String, Object>> getUserPublicInfo(@PathVariable("userId") Long userId);
}
