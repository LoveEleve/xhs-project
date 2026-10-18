package com.myxhs.search.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 订单服务 Feign Client（search 域，C-004）
 * <p>
 * 用途：商家子单索引的<b>存量回填</b>——游标分页扫描全部物理子单表
 * （订单数据在分片库中，search 域无直连，必须经 order 域）。
 * 使用 Map 绕过跨模块 DTO 依赖（与 ProductFeignClient 同风格）。
 * </p>
 */
@FeignClient(name = "my-xhs-order", configuration = InternalCallFeignConfig.class)
public interface OrderFeignClient {

    /**
     * 子单全量扫描（游标分页；snake_case 字段）。
     *
     * @param cursor 上一批最后一条的 id（首次传 0）
     * @param limit  批次大小（order 域上限 1000）
     */
    @GetMapping("/api/order/internal/sub-order-scan")
    R<List<Map<String, Object>>> subOrderScan(@RequestParam("cursor") long cursor, @RequestParam("limit") int limit);
}
