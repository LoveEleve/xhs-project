package com.myxhs.user.provider;

import com.myxhs.user.api.dubbo.UserDubboService;
import com.myxhs.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户 Dubbo Provider 实现（Triple 协议）
 * <p>
 * 替代 Feign 调用，提供高性能的用户信息查询 RPC 接口。
 * 主要用于首页聚合服务和分析服务的用户公开信息查询。
 * </p>
 */
@Slf4j
@Component
@DubboService
@RequiredArgsConstructor
public class UserDubboServiceImpl implements UserDubboService {

    private final UserService userService;

    @Override
    public Map<String, Object> getUserPublicInfo(Long userId) {
        Object vo = userService.getUserPublicInfo(userId);
        return voToMap(vo);
    }

    private Map<String, Object> voToMap(Object vo) {
        Map<String, Object> map = new HashMap<>();
        if (vo == null) return map;
        try {
            for (var field : vo.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                map.put(field.getName(), field.get(vo));
            }
        } catch (Exception e) {
            log.warn("[UserDubbo] VO 转 Map 异常", e);
        }
        return map;
    }
}
