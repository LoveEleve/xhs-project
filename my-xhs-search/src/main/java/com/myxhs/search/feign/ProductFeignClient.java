package com.myxhs.search.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 商品服务 Feign Client（search 模块专用）
 * <p>
 * 用于 ES 索引同步时从 product 服务获取 SPU 完整信息。
 * 使用 Map 类型绕过跨模块 DTO 依赖。
 * </p>
 */
@FeignClient(name = "my-xhs-product")
public interface ProductFeignClient {

    /**
     * 获取 SPU 详情（含 categoryName + skuList）
     */
    @GetMapping("/api/product/spu/{spuId}")
    R<Map<String, Object>> getSpuDetail(@PathVariable Long spuId);
}
