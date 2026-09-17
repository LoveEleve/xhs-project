package com.myxhs.cart.rpc;

import com.myxhs.cart.feign.ProductFeignClient;
import com.myxhs.common.response.R;
import com.myxhs.common.rpc.ProductSkuRpc;
import com.myxhs.common.rpc.SkuRpcDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SKU 查询双协议客户端（B2 试点）。
 * <p>开关 myxhs.rpc.dubbo-enabled=true 时走 Dubbo，异常自动回退 Feign；
 * 默认 false，行为与现状完全一致（可回滚）。</p>
 */
@Slf4j
@Component
public class ProductSkuRpcClient {

    private final ProductFeignClient productFeignClient;

    @DubboReference(check = false, timeout = 3000, retries = 0)
    private ProductSkuRpc productSkuRpc;

    @Value("${myxhs.rpc.dubbo-enabled:false}")
    private boolean dubboEnabled;

    public ProductSkuRpcClient(ProductFeignClient productFeignClient) {
        this.productFeignClient = productFeignClient;
    }

    public R<ProductFeignClient.SkuDTO> getSkuDetail(Long skuId) {
        if (dubboEnabled && productSkuRpc != null) {
            try {
                return R.ok(toFeignDto(productSkuRpc.getSku(skuId)));
            } catch (Exception e) {
                log.warn("[RPC-试点] Dubbo getSku 失败，回退 Feign: skuId={}, err={}", skuId, e.getMessage());
            }
        }
        return productFeignClient.getSkuDetail(skuId);
    }

    public R<List<ProductFeignClient.SkuDTO>> batchGetSkuDetails(List<Long> skuIds) {
        if (dubboEnabled && productSkuRpc != null) {
            try {
                List<ProductFeignClient.SkuDTO> list = productSkuRpc.batchGetSkus(skuIds).stream()
                        .map(this::toFeignDto).toList();
                return R.ok(list);
            } catch (Exception e) {
                log.warn("[RPC-试点] Dubbo batchGetSkus 失败，回退 Feign: size={}, err={}", skuIds == null ? 0 : skuIds.size(), e.getMessage());
            }
        }
        return productFeignClient.batchGetSkuDetails(skuIds);
    }

    private ProductFeignClient.SkuDTO toFeignDto(SkuRpcDTO dto) {
        if (dto == null) {
            return null;
        }
        ProductFeignClient.SkuDTO out = new ProductFeignClient.SkuDTO();
        out.setId(dto.getId());
        out.setSpuId(dto.getSpuId());
        out.setName(dto.getName());
        out.setPrice(dto.getPrice());
        out.setOriginalPrice(dto.getOriginalPrice());
        out.setStock(dto.getStock());
        out.setSpecs(dto.getSpecs());
        out.setStatus(dto.getStatus());
        out.setSpuStatus(dto.getSpuStatus());
        return out;
    }
}
