package com.myxhs.home.feign.fallback;

import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.home.feign.UserFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UserFeignFallbackFactory implements FallbackFactory<UserFeignClient> {
    @Override
    public UserFeignClient create(Throwable cause) {
        log.warn("[降级] UserFeignClient 不可用: {}", cause.getMessage());
        return userId -> R.fail(ResultCode.SERVICE_UNAVAILABLE, "用户服务不可用");
    }
}
