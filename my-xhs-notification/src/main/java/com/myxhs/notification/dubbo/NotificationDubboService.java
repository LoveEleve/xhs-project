package com.myxhs.notification.dubbo;

import java.util.Map;

/**
 * 通知 Dubbo 服务接口（Triple 协议，替代 Feign 调用）
 * <p>
 * 用于首页聚合服务高频调用通知服务：
 * - 未读通知数（导航栏角标）
 * </p>
 */
public interface NotificationDubboService {

    /**
     * 获取未读通知数
     */
    Map<String, Object> getUnreadCount(Long userId);
}
