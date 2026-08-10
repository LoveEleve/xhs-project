package com.myxhs.order.feign;

import com.myxhs.common.response.R;
import com.myxhs.order.dto.SkuInfoDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品服务 Feign Client
 * <p>
 * order 创建订单时查询 SKU 真实信息（名称/价格/所属 SPU），
 * 不再使用硬编码 mock 数据。
 * </p>
 */
@FeignClient(name = "my-xhs-product",
        fallbackFactory = ProductFeignFallbackFactory.class,
        configuration = InternalCallFeignConfig.class)
public interface ProductFeignClient {

    /**
     * 批量获取 SKU 详情（内部接口）
     */
    @GetMapping("/api/product/sku/batch")
    R<List<SkuInfoDTO>> batchGetSkuDetails(@RequestParam("skuIds") List<Long> skuIds);
}
