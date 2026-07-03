package com.myxhs.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.response.ResultCode;
import com.myxhs.product.dto.request.SkuCreateRequest;
import com.myxhs.product.dto.response.SkuVO;
import com.myxhs.product.entity.Sku;
import com.myxhs.product.entity.Spu;
import com.myxhs.product.enums.ProductStatus;
import com.myxhs.product.mapper.SkuMapper;
import com.myxhs.product.mapper.SpuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SKU 服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkuService {

    private final SkuMapper skuMapper;
    private final SpuMapper spuMapper;
    private final SpuService spuService;
    private final IdGeneratorUtil idGeneratorUtil;

    /**
     * 创建 SKU
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createSku(SkuCreateRequest request) {
        // 校验 SPU 是否存在
        Spu spu = spuMapper.selectById(request.getSpuId());
        if (spu == null) {
            throw new BizException(ResultCode.PRODUCT_NOT_FOUND);
        }

        Sku sku = new Sku();
        sku.setId(idGeneratorUtil.nextId());
        sku.setSpuId(request.getSpuId());
        sku.setName(request.getName());
        sku.setPrice(request.getPrice());
        sku.setOriginalPrice(request.getOriginalPrice());
        sku.setStock(request.getStock() != null ? request.getStock() : 0);
        sku.setSpecs(request.getSpecs());
        sku.setStatus(ProductStatus.ON_SHELF.getCode());

        skuMapper.insert(sku);

        // 清除 SPU 缓存（SKU 列表变了）
        spuService.evictSpuCache(request.getSpuId());

        log.info("[商品] 创建 SKU 成功, skuId={}, spuId={}", sku.getId(), sku.getSpuId());
        return sku.getId();
    }

    /**
     * 获取 SKU 详情
     */
    public SkuVO getSkuDetail(Long skuId) {
        Sku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BizException(ResultCode.SKU_NOT_FOUND);
        }
        return toSkuVO(sku);
    }

    /**
     * 批量获取 SKU 详情（内部接口，供购物车等服务调用）
     * <p>
     * 使用 WHERE id IN (...) 一次查询替代 N 次循环单查。
     * </p>
     */
    public List<SkuVO> batchGetSkuDetails(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        List<Sku> skuList = skuMapper.selectBatchIds(skuIds);
        return skuList.stream()
                .map(this::toSkuVO)
                .collect(Collectors.toList());
    }

    /**
     * 按 SPU 查询 SKU 列表
     */
    public List<SkuVO> listSkusBySpuId(Long spuId) {
        List<Sku> skuList = skuMapper.selectList(
                new LambdaQueryWrapper<Sku>()
                        .eq(Sku::getSpuId, spuId)
                        .eq(Sku::getStatus, ProductStatus.ON_SHELF.getCode())
                        .orderByAsc(Sku::getId));

        return skuList.stream()
                .map(this::toSkuVO)
                .collect(Collectors.toList());
    }

    private SkuVO toSkuVO(Sku sku) {
        SkuVO vo = new SkuVO();
        vo.setId(sku.getId());
        vo.setSpuId(sku.getSpuId());
        vo.setName(sku.getName());
        vo.setPrice(sku.getPrice());
        vo.setOriginalPrice(sku.getOriginalPrice());
        vo.setStock(sku.getStock());
        vo.setSpecs(sku.getSpecs());
        vo.setStatus(sku.getStatus());
        return vo;
    }
}
