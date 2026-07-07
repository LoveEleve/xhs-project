package com.myxhs.analytics.feign;

import com.myxhs.common.response.R;
import com.myxhs.user.dto.response.UserPublicInfoResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户服务 Feign Client（analytics 模块专用）
 */
@FeignClient(name = "my-xhs-user",
        fallbackFactory = UserFeignFallbackFactory.class)
public interface UserFeignClient {

    /**
     * 批量获取用户公开信息
     */
    @GetMapping("/api/user/batch")
    R<List<UserPublicInfoResponse>> getUserPublicInfoBatch(@RequestParam("userIds") List<Long> userIds);
}
