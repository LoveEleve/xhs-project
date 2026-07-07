package com.myxhs.notification.provider;

import com.myxhs.notification.dto.UnreadCountVO;
import com.myxhs.notification.dubbo.NotificationDubboService;
import com.myxhs.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 通知 Dubbo Provider 实现（Triple 协议）
 * <p>
 * 替代 Feign 调用，提供高性能的通知查询 RPC 接口。
 * 主要用于首页聚合服务的未读通知数查询（导航栏角标）。
 * </p>
 */
@Slf4j
@Component
@DubboService
@RequiredArgsConstructor
public class NotificationDubboServiceImpl implements NotificationDubboService {

    private final NotificationService notificationService;

    @Override
    public Map<String, Object> getUnreadCount(Long userId) {
        UnreadCountVO vo = notificationService.getUnreadCount(userId);
        Map<String, Object> result = new HashMap<>();
        if (vo != null) {
            result.put("total", vo.getTotal());
            if (vo.getDetails() != null) {
                result.put("details", vo.getDetails());
            }
        }
        return result;
    }
}
