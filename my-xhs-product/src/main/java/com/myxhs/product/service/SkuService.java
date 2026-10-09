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
    private final com.myxhs.product.feign.InventoryFeignClient inventoryFeignClient;
    /** 库存初始化失败打点（失败仅告警，管理端可用 /api/inventory/init 重试） */
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /**
     * 创建 SKU
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createSku(SkuCreateRequest request) {
        // 校验 SPU 是否存在（不要求"已上架"：正常经营流程是 建 SPU(下架) → 加 SKU → 上架，
        // 原实现要求 SPU 已上架才能建 SKU，与"上架需至少一个上架 SKU"的校验互锁）
        Spu spu = spuMapper.selectById(request.getSpuId());
        if (spu == null) {
            throw new BizException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // P6：划线价不得低于售价（前端展示"原价 < 现价"是明显的数据错误）
        if (request.getOriginalPrice() != null && request.getPrice() != null
                && request.getOriginalPrice().compareTo(request.getPrice()) < 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "划线价不能低于售价");
        }

        // specs 列语义为 JSON（DDL 注释），脏 JSON 会沿购物车/订单快照链路放大 → 入库前校验
        if (request.getSpecs() != null && !request.getSpecs().isBlank()) {
            try {
                objectMapper.readTree(request.getSpecs());
            } catch (Exception e) {
                throw new BizException(ResultCode.PARAM_INVALID, "规格属性必须是合法 JSON");
            }
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
                initInventory(sku.getId(), sku.getStock());
            }
        });

        log.info("[商品] 创建 SKU 成功, skuId={}, spuId={}", sku.getId(), sku.getSpuId());
        return sku.getId();
    }

    /**
     * 新建 SKU 后初始化库存（远程调用放提交后，不进事务）：
     * 失败仅 ERROR + 打点（SKU 已建成，管理端可用 /api/inventory/init 重试），不伪成功
     */
    private void initInventory(Long skuId, Integer stock) {
        try {
            com.myxhs.product.feign.InventoryFeignClient.InitRequest req =
                    new com.myxhs.product.feign.InventoryFeignClient.InitRequest();
            req.setSkuId(skuId);
            req.setTotalStock(stock != null ? stock : 0);
            com.myxhs.common.response.R<Void> r = inventoryFeignClient.initStock(req);
            if (r != null && r.isSuccess()) {
                log.info("[商品] SKU 库存初始化成功, skuId={}, totalStock={}", skuId, req.getTotalStock());
            } else if (r != null && r.getMessage() != null && r.getMessage().contains("已初始化")) {
                log.info("[商品] SKU 库存已存在(跳过): skuId={}", skuId);
            } else {
                log.error("[商品] SKU 库存初始化失败(管理端 /api/inventory/init 可重试): skuId={}, resp={}",
                        skuId, r != null ? r.getMessage() : "null");
                io.micrometer.core.instrument.Counter
                        .builder("myxhs_product_inventory_init_fail_total")
                        .register(meterRegistry).increment();
            }
        } catch (Exception e) {
            log.error("[商品] SKU 库存初始化调用异常(管理端 /api/inventory/init 可重试): skuId={}", skuId, e);
            io.micrometer.core.instrument.Counter
                    .builder("myxhs_product_inventory_init_fail_total")
                    .register(meterRegistry).increment();
        }
    }

    /**
     * 获取 SKU 详情
     */
    public SkuVO getSkuDetail(Long skuId) {
        Sku sku = skuMapper.selectById(skuId);
        if (sku == null || sku.getStatus() == null || sku.getStatus() != ProductStatus.ON_SHELF.getCode()) {
            throw new BizException(ResultCode.SKU_NOT_FOUND);
        }
        Spu spu = spuMapper.selectById(sku.getSpuId());
        if (spu == null || spu.getStatus() == null || spu.getStatus() != ProductStatus.ON_SHELF.getCode()) {
            throw new BizException(ResultCode.PRODUCT_NOT_FOUND);
        }
        return toSkuVO(sku, resolveSpuImage(sku.getSpuId()), spu.getStatus());
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
        // P2-1/T-047 合并：原实现对同一批 spuIds 查了两次 selectBatchIds（首图 + 状态），改为一次查询构建两张 Map
        List<Spu> spuList = spuIds.isEmpty() ? List.of() : spuMapper.selectBatchIds(spuIds);
        Map<Long, String> spuImageMap = buildSpuImageMap(spuList);
        Map<Long, Integer> spuStatusMap = buildSpuStatusMap(spuList);
        return skuList.stream()
                .map(sku -> toSkuVO(sku, spuImageMap.get(sku.getSpuId()), spuStatusMap.get(sku.getSpuId())))
                .collect(Collectors.toList());
    }

    /**
     * 按 SPU 查询 SKU 列表
     */
    public List<SkuVO> listSkusBySpuId(Long spuId) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null || spu.getStatus() == null || spu.getStatus() != ProductStatus.ON_SHELF.getCode()) {
            return List.of();
        }

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
                spuMapper.selectBatchIds(skuList.stream().map(Sku::getSpuId).collect(Collectors.toSet())));
        final Integer spuStatus = spu.getStatus();
        return skuList.stream()
                .map(sku -> toSkuVO(sku, spuImageMap.get(sku.getSpuId()), spuStatus))
                .collect(Collectors.toList());
    }

    /**
     * 批量构建 SPU 首图 Map（P2-1：一次 IN 查询替代 N 次单查）
     */
    private Map<Long, String> buildSpuImageMap(List<Spu> spuList) {
        if (spuList == null || spuList.isEmpty()) {
            return Collections.emptyMap();
        }
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
    private Map<Long, Integer> buildSpuStatusMap(List<Spu> spuList) {
        if (spuList == null || spuList.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Integer> result = new HashMap<>();
        for (Spu spu : spuList) {
            result.put(spu.getId(), spu.getStatus());
        }
        return result;
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
     * <p>（getSkuDetail 单查路径使用；批量路径走 buildSpuImageMap 避免 N+1）</p>
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
