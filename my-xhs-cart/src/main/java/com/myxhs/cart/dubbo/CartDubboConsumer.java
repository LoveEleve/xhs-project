package com.myxhs.cart.dubbo;

import com.myxhs.product.api.dubbo.ProductDubboService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 购物车服务 Dubbo Consumer
 * <p>
 * 管理购物车服务对商品服务的 Dubbo 调用引用：
 * - 商品服务：SKU 详情查询（购物车商品信息填充）
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartDubboConsumer {

    @DubboReference(version = "1.0.0", timeout = 3000, retries = 0)
    private ProductDubboService productDubboService;

    public Map<String, Object> getSkuDetail(Long skuId) {
        return productDubboService.getSkuDetail(skuId);
    }
}
