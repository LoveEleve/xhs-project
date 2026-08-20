package com.myxhs.inventory.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "my-xhs-product")
public interface ProductFeignClient {

    @GetMapping("/api/product/sku/{skuId}")
    R<SkuDTO> getSkuDetail(@PathVariable("skuId") Long skuId);

    @lombok.Data
    class SkuDTO {
        private Long id;
        private Long spuId;
        private String name;
        private Integer status;
        private Integer spuStatus;
    }
}
