package com.myxhs.content.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * O-Comment-2 修复（2026-08-13）：用户服务内部客户端——通知 senderName 填充
 * X-Internal-Call 由 FeignInternalCallInterceptor 全局注入
 */
@FeignClient(name = "my-xhs-user", configuration = InternalCallFeignConfig.class)
public interface UserFeignClient {

    /**
     * 用户公开信息（昵称/头像）
     */
    @GetMapping("/api/user/internal/info/{userId}")
    R<Map<String, Object>> internalUserInfo(@PathVariable("userId") Long userId);
}
