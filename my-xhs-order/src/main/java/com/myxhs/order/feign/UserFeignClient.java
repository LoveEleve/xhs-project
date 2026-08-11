package com.myxhs.order.feign;

import com.myxhs.common.response.R;
import com.myxhs.order.dto.UserAddressDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 用户服务 Feign Client（订单创建时获取真实收货地址）
 * <p>
 * 通过 X-User-Id 头向 user 服务查询地址；X-Internal-Call 由公共拦截器
 * (FeignInternalCallInterceptor) 自动注入。
 * </p>
 */
@FeignClient(name = "my-xhs-user")
public interface UserFeignClient {

    /**
     * 获取用户收货地址详情（供订单地址快照）
     */
    @GetMapping("/api/user/address/{id}")
    R<UserAddressDTO> getAddress(@RequestHeader("X-User-Id") Long userId,
                                 @PathVariable("id") Long addressId);
}
