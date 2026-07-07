package com.myxhs.product.provider;

import com.myxhs.product.api.dubbo.ProductDubboService;
import com.myxhs.product.dto.response.SkuVO;
import com.myxhs.product.dto.response.SpuDetailVO;
import com.myxhs.product.service.SkuService;
import com.myxhs.product.service.SpuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 商品 Dubbo Provider 实现（Triple 协议）
 * <p>
 * 替代 Feign 调用，提供高性能的商品查询 RPC 接口。
 * 主要用于首页聚合服务和购物车服务的商品信息查询。
 * </p>
 */
@Slf4j
@Component
@DubboService
@RequiredArgsConstructor
public class ProductDubboServiceImpl implements ProductDubboService {

    private final SpuService spuService;
    private final SkuService skuService;

    @Override
    public Map<String, Object> getSpuDetail(Long spuId) {
        SpuDetailVO vo = spuService.getSpuDetail(spuId);
        return voToMap(vo);
    }

    @Override
    public Map<String, Object> getSkuDetail(Long skuId) {
        SkuVO vo = skuService.getSkuDetail(skuId);
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
            log.warn("[ProductDubbo] VO 转 Map 异常", e);
        }
        return map;
    }
}
