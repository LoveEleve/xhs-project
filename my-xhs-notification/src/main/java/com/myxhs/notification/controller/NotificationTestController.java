package com.myxhs.notification.controller;

import com.alibaba.fastjson2.JSON;
import com.myxhs.common.response.R;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.notification.dto.NotificationEventDTO;
import com.myxhs.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/**
 * 通知测试接口（模拟 MQ 事件，方便调试）
 * <p>
 * 生产环境应删除此 Controller，通知事件由 MQ 消费者处理。
 * </p>
 */
@Slf4j
@Profile("dev")
@RestController
@RequestMapping("/api/notification/test")
@RequiredArgsConstructor
public class NotificationTestController {

    private final NotificationService notificationService;
    private final AccessTokenGuard accessTokenGuard;

    /**
     * 模拟发送通知事件（绕过 MQ，直接调用 Service）
     * <p>
     * 2026-09-19 review：原实现任何已登录用户可给任意用户发通知（仅 @Profile("dev") 挡生产）；
     * 补 X-Internal-Call 守卫（测试脚本本就携带内部令牌，回归不受影响）。
     * </p>
     */
    @PostMapping("/send")
    public R<Void> sendTestNotification(@Valid @RequestBody NotificationEventDTO event,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            log.warn("[测试通知] 非内部调用被拒绝");
            return R.fail(403, "仅限内部服务调用");
        }
        log.info("[测试] 模拟通知事件: {}", JSON.toJSONString(event));
        notificationService.processEvent(event);
        return R.ok();
    }
}
