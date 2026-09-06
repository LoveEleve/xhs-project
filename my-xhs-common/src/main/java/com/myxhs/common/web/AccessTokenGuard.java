package com.myxhs.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一的管理/内部调用令牌校验辅助组件。
 * <p>
 * 目标：收口各服务 controller 中重复的 X-Admin-Call / X-Internal-Call equals 判断，
 * 但不改变当前权限语义。
 * </p>
 */
@Component
public class AccessTokenGuard {

    @Value("${myxhs.admin.token:}")
    private String adminToken;

    @Value("${myxhs.internal.token:}")
    private String internalToken;

    public boolean isAdminCall(String value) {
        return adminToken != null && !adminToken.isBlank() && adminToken.equals(value);
    }

    public boolean isInternalCall(String value) {
        return internalToken != null && !internalToken.isBlank() && internalToken.equals(value);
    }

    public void requireAdminTokenConfigured(String serviceName) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new IllegalStateException("myxhs.admin.token 未配置，拒绝启动 " + serviceName + " 服务");
        }
    }

    public void requireInternalTokenConfigured(String serviceName) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("myxhs.internal.token 未配置，拒绝启动 " + serviceName + " 服务");
        }
    }
}
