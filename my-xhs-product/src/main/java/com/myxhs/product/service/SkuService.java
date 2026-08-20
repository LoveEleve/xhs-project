package com.myxhs.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SKU 服务
 * <p>
 * SKU 无独立缓存，随所属 SPU 的缓存一起管理。
 * 创建 SKU 时会清除 SPU 缓存以触发重建。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkuService {

    private final SkuMapper skuMapper;
    private final SpuMapper spuMapper;
    private final SpuService spuService;
    private final IdGeneratorUtil idGeneratorUtil;
    private final ObjectMapper objectMapper;

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

        // 事务提交后清除 SPU 缓存（SKU 列表变了）
        final Long spuId = request.getSpuId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                spuService.evictSpuCache(spuId);
            }
        });

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
        LambdaQueryWrapper<Sku> wrapper = new LambdaQueryWrapper<Sku>()
                .in(Sku::getId, skuIds)
                .eq(Sku::getStatus, ProductStatus.ON_SHELF.getCode());
        List<Sku> skuList = skuMapper.selectList(wrapper);
        if (skuList.isEmpty()) {
            return List.of();
        }
        Set<Long> spuIds = skuList.stream().map(Sku::getSpuId).collect(Collectors.toSet());
        // P2-1：批量预取 SPU 首图（一次 IN 查询），消除每 SKU 单独查 SPU 的 N+1
        Map<Long, String> spuImageMap = buildSpuImageMap(spuIds);
        // T-047：批量预取 SPU 状态，供 cart 判断 SPU 维度有效性
        Map<Long, Integer> spuStatusMap = buildSpuStatusMap(spuIds);
        return skuList.stream()
                .map(sku -> toSkuVO(sku, spuImageMap.get(sku.getSpuId()), spuStatusMap.get(sku.getSpuId())))
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

        if (skuList.isEmpty()) {
            return List.of();
        }
        // P2-1：批量预取 SPU 首图，消除 N+1
        Map<Long, String> spuImageMap = buildSpuImageMap(
                skuList.stream().map(Sku::getSpuId).collect(Collectors.toSet()));
        final Integer spuStatus;
        Spu spu = spuMapper.selectById(spuId);
        if (spu != null) {
            spuStatus = spu.getStatus();
        } else {
            spuStatus = null;
        }
        return skuList.stream()
                .map(sku -> toSkuVO(sku, spuImageMap.get(sku.getSpuId()), spuStatus))
                .collect(Collectors.toList());
    }

    /**
     * 批量构建 SPU 首图 Map（P2-1：一次 IN 查询替代 N 次单查）
     */
    private Map<Long, String> buildSpuImageMap(Set<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Spu> spuList = spuMapper.selectBatchIds(spuIds);
        Map<Long, String> result = new HashMap<>();
        for (Spu spu : spuList) {
            if (spu.getImages() == null) {
                continue;
            }
            try {
                List<String> images = objectMapper.readValue(spu.getImages(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                if (images != null && !images.isEmpty()) {
                    result.put(spu.getId(), images.get(0));
                }
            } catch (Exception e) {
                log.warn("[商品] SPU images 解析失败, spuId={}", spu.getId(), e);
            }
        }
        return result;
    }

    /**
     * 构建 SPU 状态 Map（T-047：一次 IN 查询，供 SkuVO.spuStatus 填充，
     * cart 侧据此判断 SPU 维度有效性——SPU 下架后购物车条目应标记无效）
     */
    private Map<Long, Integer> buildSpuStatusMap(Set<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Spu> spuList = spuMapper.selectBatchIds(spuIds);
        Map<Long, Integer> result = new HashMap<>();
        for (Spu spu : spuList) {
            result.put(spu.getId(), spu.getStatus());
        }
        return result;
    }

    private SkuVO toSkuVO(Sku sku) {
        return toSkuVO(sku, resolveSpuImage(sku.getSpuId()), null);
    }

    private SkuVO toSkuVO(Sku sku, String firstImage) {
        return toSkuVO(sku, firstImage, null);
    }

    private SkuVO toSkuVO(Sku sku, String firstImage, Integer spuStatus) {
        SkuVO vo = new SkuVO();
        vo.setId(sku.getId());
        vo.setSpuId(sku.getSpuId());
        vo.setName(sku.getName());
        vo.setPrice(sku.getPrice());
        vo.setOriginalPrice(sku.getOriginalPrice());
        // stock 字段已从 SkuVO 剔除：SKU 表 stock 是冗余占位值，真实库存以 inventory 服务为准
        vo.setSpecs(sku.getSpecs());
        vo.setImage(firstImage);
        vo.setStatus(sku.getStatus());
        // T-047：填充所属 SPU 状态（cart 判断 SPU 维度有效性）
        vo.setSpuStatus(spuStatus);
        return vo;
    }

    /**
     * 解析 SKU 主图：SKU 表无 image 字段，图片存储在所属 SPU 的 images(JSON数组)，取第一张作主图。
     */
    private String resolveSpuImage(Long spuId) {
        if (spuId == null) {
            return null;
        }
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null || spu.getImages() == null) {
            return null;
        }
        try {
            List<String> images = objectMapper.readValue(spu.getImages(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return (images == null || images.isEmpty()) ? null : images.get(0);
        } catch (Exception e) {
            log.warn("[商品] SPU images 解析失败, spuId={}", spuId, e);
            return null;
        }
    }
}
