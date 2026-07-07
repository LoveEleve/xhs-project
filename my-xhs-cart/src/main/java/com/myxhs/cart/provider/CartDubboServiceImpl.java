package com.myxhs.cart.provider;

import com.myxhs.cart.dubbo.CartDubboService;
import com.myxhs.cart.dto.response.CartListVO;
import com.myxhs.cart.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 购物车 Dubbo Provider 实现（Triple 协议）
 * <p>
 * 替代 Feign 调用，提供高性能的购物车查询 RPC 接口。
 * 主要用于首页聚合服务的购物车列表和角标数量查询。
 * </p>
 */
@Slf4j
@Component
@DubboService
@RequiredArgsConstructor
public class CartDubboServiceImpl implements CartDubboService {

    private final CartService cartService;

    @Override
    public Map<String, Object> getCartList(Long userId) {
        CartListVO vo = cartService.getCartList(userId);
        return voToMap(vo);
    }

    @Override
    public Map<String, Integer> getCartCount(Long userId) {
        int count = cartService.getCartCount(userId);
        Map<String, Integer> result = new HashMap<>();
        result.put("count", count);
        return result;
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
            log.warn("[CartDubbo] VO 转 Map 异常", e);
        }
        return map;
    }
}
