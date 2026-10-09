package com.myxhs.user.controller;

import com.myxhs.common.annotation.RateLimit;
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
    @RateLimit(prefix = "myxhs:user:captcha", maxRequests = 30, windowSeconds = 60, perUser = true,
            message = "验证码获取过于频繁，请稍后再试")
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
     * P2-9：取客户端 IP 用于 IP 维度登录失败锁定，防单源账号 DoS。
     * <b>可信来源是安全前提</b>——原实现直接读 X-Forwarded-For 第一段，客户端可伪造，
     * 攻击者用 5 个假 IP 即可锁任意账号（账号 DoS）或伪造受害者 IP 触发封禁。
     * 现口径：优先 X-Real-IP（gateway 单值注入、客户端不可伪造）；
     * 退化取 X-Forwarded-For 最后一段（gateway 追加的真实连接 IP 在末尾，伪造段只在前面）。
     * </p>
     */
    @PostMapping("/login")
    public R<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                  @RequestHeader(value = "X-Real-IP", required = false) String realIp,
                                  @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        return R.ok(userService.login(request,
                com.myxhs.common.web.ClientIpResolver.resolve(realIp, forwardedFor, null)));
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
