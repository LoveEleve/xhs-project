package com.myxhs.product.rpc;

import com.myxhs.common.rpc.ProductSkuRpc;
import com.myxhs.common.rpc.SkuRpcDTO;
import com.myxhs.product.service.SkuService;
import com.myxhs.product.dto.response.SkuVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.Collections;
import java.util.List;

/**
 * 商品 SKU RPC 提供者（B2 试点）：直接复用 SkuService，与 HTTP 接口同一逻辑。
 */
@Slf4j
@DubboService
@RequiredArgsConstructor
public class ProductSkuRpcImpl implements ProductSkuRpc {

    private final SkuService skuService;

    @Override
    public SkuRpcDTO getSku(Long skuId) {
        return toDto(skuService.getSkuDetail(skuId));
    }

    @Override
    public List<SkuRpcDTO> batchGetSkus(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Collections.emptyList();
        }
        return skuService.batchGetSkuDetails(skuIds).stream().map(this::toDto).toList();
    }

    private SkuRpcDTO toDto(SkuVO vo) {
        if (vo == null) {
            return null;
        }
        SkuRpcDTO dto = new SkuRpcDTO();
        dto.setId(vo.getId());
        dto.setSpuId(vo.getSpuId());
        dto.setName(vo.getName());
        dto.setPrice(vo.getPrice());
        dto.setOriginalPrice(vo.getOriginalPrice());
        dto.setSpecs(vo.getSpecs());
        dto.setStatus(vo.getStatus());
        dto.setSpuStatus(vo.getSpuStatus());
        return dto;
    }
}
