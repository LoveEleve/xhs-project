package com.myxhs.user.controller;

import com.myxhs.common.response.R;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.user.dto.request.AddressCreateRequest;
import com.myxhs.user.dto.request.AddressUpdateRequest;
import com.myxhs.user.dto.response.AddressVO;
import com.myxhs.user.service.UserAddressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 收货地址接口
 * <p>
 * 提供收货地址的 CRUD、设置默认地址功能。
 * 所有接口需要登录后访问（通过 Header 中的 X-User-Id 传递用户 ID）。
 * </p>
 */
@RestController
@RequestMapping("/api/user/address")
@RequiredArgsConstructor
public class UserAddressController {

    private final UserAddressService userAddressService;
    private final AccessTokenGuard accessTokenGuard;

    /**
     * 获取地址列表
     */
    @GetMapping("/list")
    public R<List<AddressVO>> listAddresses(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(userAddressService.listAddresses(userId));
    }

    /**
     * 获取默认地址
     */
    @GetMapping("/default")
    public R<AddressVO> getDefaultAddress(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(userAddressService.getDefaultAddress(userId));
    }

    /**
     * 获取地址详情
     */
    @GetMapping("/{id}")
    public R<AddressVO> getAddress(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long id) {
        return R.ok(userAddressService.getAddress(userId, id));
    }

    /**
     * 新增地址
     */
    /**
     * 内部调用：地址详情（明文手机号，X-Internal-Call 保护）——供订单快照等内部用途
     */
    @GetMapping("/internal/{id}")
    public R<AddressVO> getAddressInternal(@RequestHeader("X-User-Id") Long userId,
                                           @PathVariable("id") Long id,
                                           @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        return R.ok(userAddressService.getAddressForInternal(userId, id));
    }

    @PostMapping
    public R<AddressVO> createAddress(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody AddressCreateRequest request) {
        return R.ok(userAddressService.createAddress(userId, request));
    }

    /**
     * 更新地址
     */
    @PutMapping("/{id}")
    public R<AddressVO> updateAddress(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long id,
            @Valid @RequestBody AddressUpdateRequest request) {
        return R.ok(userAddressService.updateAddress(userId, id, request));
    }

    /**
     * 删除地址
     */
    @DeleteMapping("/{id}")
    public R<Void> deleteAddress(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long id) {
        userAddressService.deleteAddress(userId, id);
        return R.ok();
    }

    /**
     * 设置默认地址
     */
    @PutMapping("/{id}/default")
    public R<Void> setDefaultAddress(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long id) {
        userAddressService.setDefaultAddress(userId, id);
        return R.ok();
    }
}
