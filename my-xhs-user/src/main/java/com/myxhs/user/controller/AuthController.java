package com.myxhs.user.controller;

import com.myxhs.common.response.R;
import com.myxhs.user.dto.request.LoginRequest;
import com.myxhs.user.dto.request.RegisterRequest;
import com.myxhs.user.dto.response.CaptchaResponse;
import com.myxhs.user.dto.response.TokenResponse;
import com.myxhs.user.service.CaptchaService;
import com.myxhs.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口（注册/登录/注销/刷新Token/验证码）
 */
@RestController
@RequestMapping("/api/user/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final CaptchaService captchaService;

    /**
     * 获取图形验证码
     */
    @GetMapping("/captcha")
    public R<CaptchaResponse> getCaptcha() {
        return R.ok(captchaService.generateCaptcha());
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public R<Void> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request);
        return R.ok();
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public R<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(userService.login(request));
    }

    /**
     * 刷新 Token
     */
    @PostMapping("/refresh")
    public R<TokenResponse> refreshToken(@RequestParam("refreshToken") String refreshToken) {
        return R.ok(userService.refreshToken(refreshToken));
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public R<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "refreshToken", required = false) String refreshToken) {
        String accessToken = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            accessToken = authorization.substring(7);
        }
        if (accessToken != null) {
            userService.logout(accessToken, refreshToken);
        }
        return R.ok();
    }
}
