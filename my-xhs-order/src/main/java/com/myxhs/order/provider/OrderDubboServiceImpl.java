package com.myxhs.order.provider;

import com.myxhs.order.api.dubbo.OrderDubboService;
import com.myxhs.order.dto.response.OrderVO;
import com.myxhs.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单 Dubbo Provider 实现（Triple 协议）
 * <p>
 * 替代 Feign 调用，提供高性能的订单查询 RPC 接口。
 * 主要用于首页聚合服务和支付服务的订单查询。
 * </p>
 */
@Slf4j
@Component
@DubboService
@RequiredArgsConstructor
public class OrderDubboServiceImpl implements OrderDubboService {

    private final OrderService orderService;

    @Override
    public Map<String, Object> getOrderDetail(Long userId, Long orderId) {
        OrderVO vo = orderService.getOrderDetail(userId, orderId);
        return voToMap(vo);
    }

    @Override
    public List<Map<String, Object>> getUserOrders(Long userId, Integer status) {
        List<OrderVO> orders = orderService.getUserOrders(userId, status);
        return orders.stream().map(this::voToMap).collect(Collectors.toList());
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
            log.warn("[OrderDubbo] VO 转 Map 异常", e);
        }
        return map;
    }
}
