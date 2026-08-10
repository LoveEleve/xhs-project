package com.myxhs.notification.controller;

import com.alibaba.fastjson2.JSON;
import com.myxhs.common.response.R;
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

    /**
     * 模拟发送通知事件（绕过 MQ，直接调用 Service）
     */
    @PostMapping("/send")
    public R<Void> sendTestNotification(@Valid @RequestBody NotificationEventDTO event) {
        log.info("[测试] 模拟通知事件: {}", JSON.toJSONString(event));
        notificationService.processEvent(event);
        return R.ok();
    }
}
