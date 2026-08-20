package com.myxhs.user.controller;

import lombok.extern.slf4j.Slf4j;

import com.myxhs.common.response.R;
import com.myxhs.user.dto.request.ChangePasswordRequest;
import com.myxhs.user.dto.request.UpdateUserRequest;
import com.myxhs.user.dto.response.UserInfoResponse;
import com.myxhs.user.dto.response.UserPublicInfoResponse;
import com.myxhs.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * 用户信息接口
 * <p>
 * 注意：这些接口需要登录后才能访问。
 * 当前阶段通过 Header 中的 X-User-Id 传递用户 ID（后续由 Gateway 统一注入）。
 * </p>
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;

    /** T-013: 内部端点令牌（X-Internal-Call，fail-closed） */
    @org.springframework.beans.factory.annotation.Value("${myxhs.internal.token:}")
    private String internalToken;

    /** T-122: 管理端点令牌（X-Admin-Call，对照 product/coupon 管理端点模式） */
    @org.springframework.beans.factory.annotation.Value("${myxhs.admin.token:}")
    private String adminToken;

    private boolean isAdminCall(String v) {
        return adminToken != null && !adminToken.isEmpty() && adminToken.equals(v);
    }

    /**
     * T-122（2026-08-16）：管理员删除用户
     * <p>
     * 逻辑删除 + 吊销该用户全部 token（黑名单+清 Redis+删 hmac），防删号后旧凭证残留可用。
     * 经 gateway 需 JWT + X-Admin-Call（hmac-white-list 放行免签，对照 coupon/product 管理端点）。
     * </p>
     */
    @DeleteMapping("/internal/delete/{userId}")
    public R<Void> deleteUser(@PathVariable Long userId,
                              @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!isAdminCall(adminCall)) {
            return R.fail(403, "无权访问管理接口");
        }
        userService.deleteUser(userId);
        return R.ok();
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/me")
    public R<UserInfoResponse> getCurrentUser(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(userService.getUserInfo(userId));
    }

    /**
     * 获取指定用户信息（公开接口）
     * <p>
     * 只返回非敏感字段（昵称、头像、性别、签名），不暴露手机号、邮箱等隐私信息。
     * 路径设计为 /{userId}/info，与 Gateway 白名单 /api/user/&#42;/info 匹配。
     * </p>
     */


    /**
     * T-013: 内部端点——校验用户是否存在（供 analytics 关注链路校验，X-Internal-Call 保护）
     */
    @GetMapping("/internal/exists/{userId}")
    public R<Boolean> internalUserExists(@PathVariable Long userId,
                                         @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (internalToken == null || internalToken.isEmpty() || !internalToken.equals(internalCall)) {
            return R.fail(401, "内部调用令牌无效");
        }
        return R.ok(userService.userExists(userId));
    }

    /**
     * O-Comment-2 修复（2026-08-13）：内部端点——用户昵称/头像（通知 senderName 填充，X-Internal-Call 保护）
     */
    @GetMapping("/internal/info/{userId}")
    public R<UserPublicInfoResponse> internalUserInfo(@PathVariable Long userId,
                                                      @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (internalToken == null || internalToken.isEmpty() || !internalToken.equals(internalCall)) {
            return R.fail(401, "内部调用令牌无效");
        }
        return R.ok(userService.getUserPublicInfo(userId));
    }

    @GetMapping("/{userId}/info")
    public R<UserPublicInfoResponse> getUserPublicInfo(@PathVariable Long userId) {
        return R.ok(userService.getUserPublicInfo(userId));
    }

    /**
     * 更新当前用户信息
     */
    @PutMapping("/me")
    public R<UserInfoResponse> updateCurrentUser(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody UpdateUserRequest request) {
        return R.ok(userService.updateUserInfo(userId, request));
    }

    /**
     * 修改密码
     */
    @PutMapping("/me/password")
    public R<Void> changePassword(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(userId, request);
        return R.ok();
    }

    // ==================== 屏蔽管理 ====================

    /**
     * 屏蔽用户
     */
    @PostMapping("/block/{targetUserId}")
    public R<Void> blockUser(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long targetUserId) {
        userService.blockUser(userId, targetUserId);
        return R.ok();
    }

    /**
     * 取消屏蔽
     */
    @DeleteMapping("/block/{targetUserId}")
    public R<Void> unblockUser(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long targetUserId) {
        userService.unblockUser(userId, targetUserId);
        return R.ok();
    }

    /**
     * 获取屏蔽用户列表
     */
    @GetMapping("/block/list")
    public R<Set<Object>> getBlockList(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(userService.getBlockList(userId));
    }
}
