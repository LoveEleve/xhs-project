package com.myxhs.common.rpc;

import java.util.List;

/**
 * 商品 SKU 查询 RPC 契约（B2 试点：cart → product）。
 * <p>双协议并存：Dubbo 提供者实现本接口；调用方保留 Feign 路径作为回退（开关控制）。</p>
 */
public interface ProductSkuRpc {

    /** 单条 SKU（不存在时返回 null 或抛业务异常，由调用方按既有语义处理） */
    SkuRpcDTO getSku(Long skuId);

    /** 批量 SKU（建议 ≤100） */
    List<SkuRpcDTO> batchGetSkus(List<Long> skuIds);
}
