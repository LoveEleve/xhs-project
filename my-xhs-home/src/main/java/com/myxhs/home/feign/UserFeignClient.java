package com.myxhs.home.feign;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.fallback.UserFeignFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;
import java.util.Set;

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

    @PostMapping("/api/user/batch/info")
    R<Map<Long, Map<String, Object>>> batchGetUserPublicInfo(@RequestBody Set<Long> userIds);
}
