package com.myxhs.user.api.dubbo;

import java.util.Map;

/**
 * 用户 Dubbo 服务接口（Triple 协议，替代 Feign 调用）
 * <p>
 * 用于首页聚合服务和分析服务高频调用用户服务：
 * - 用户公开信息（笔记作者信息、用户主页）
 * </p>
 */
public interface UserDubboService {

    /**
     * 获取用户公开信息
     */
    Map<String, Object> getUserPublicInfo(Long userId);
}
