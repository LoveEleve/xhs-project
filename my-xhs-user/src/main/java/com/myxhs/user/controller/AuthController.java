package com.myxhs.user.controller;

import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.user.dto.request.LoginRequest;
import com.myxhs.user.dto.request.RefreshTokenRequest;
import com.myxhs.common.exception.BizException;
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
     * <p>
     * P2-9：取客户端 IP（gateway TrafficColoringFilter 已把 X-Forwarded-For 覆盖为真实连接 IP）
     * 用于 IP 维度登录失败锁定，防单源账号 DoS。
     * </p>
     */
    @PostMapping("/login")
    public R<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                  @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        return R.ok(userService.login(request, clientIp));
    }

    /**
     * 刷新 Token
     */
    @PostMapping("/refresh")
    public R<TokenResponse> refreshToken(@RequestBody(required = false) RefreshTokenRequest request) {
        // T-008: refreshToken 改 body 传递（原 query string 会进访问日志泄露凭证）
        if (request == null || request.getRefreshToken() == null || request.getRefreshToken().isEmpty()) {
            throw new BizException(ResultCode.TOKEN_INVALID, "refreshToken 不能为空");
        }
        return R.ok(userService.refreshToken(request.getRefreshToken()));
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public R<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @org.springframework.web.bind.annotation.RequestBody(required = false) RefreshTokenRequest refreshBody) {
        // T-008 一致性: refreshToken 走 body（原 query 会进访问日志）
        String refreshToken = refreshBody != null ? refreshBody.getRefreshToken() : null;
        String accessToken = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            accessToken = authorization.substring(7);
        }
        // RV30：access 缺失但 refresh 存在时也允许注销（原实现静默返回成功但会话未撤销）
        if (accessToken != null || (refreshToken != null && !refreshToken.isBlank())) {
            userService.logout(accessToken, refreshToken);
        }
        return R.ok();
    }
}
