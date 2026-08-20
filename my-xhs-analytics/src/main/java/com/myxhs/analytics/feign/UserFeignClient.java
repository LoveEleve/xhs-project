package com.myxhs.analytics.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * T-013: 用户服务内部客户端（关注链路校验 target 存在性）
 * X-Internal-Call 由 FeignInternalCallInterceptor 全局注入
 */
@FeignClient(name = "my-xhs-user", configuration = InternalCallFeignConfig.class)
public interface UserFeignClient {

    @GetMapping("/api/user/internal/exists/{userId}")
    R<Boolean> userExists(@PathVariable("userId") Long userId);
}
