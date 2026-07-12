package com.myxhs.user.controller;

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
public class UserController {

    private final UserService userService;

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
